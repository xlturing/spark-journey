package com.sparkstreaming.main;

import scala.reflect.ClassTag

import org.apache.log4j.LogManager
import org.apache.spark.SparkException
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.kafka.HasOffsetRanges
import org.apache.spark.streaming.kafka.KafkaUtils

import com.sparkstreaming.main.KafkaCluster
import com.sparkstreaming.main.KafkaCluster.LeaderOffset
import kafka.common.TopicAndPartition
import kafka.message.MessageAndMetadata
import kafka.serializer.Decoder
/**
 * @author litaoxiao
 * 千万注意包名称
 *
 */
class KafkaManager(val kafkaParams: Map[String, String]) extends Serializable {
  @transient lazy val log = LogManager.getLogger(this.getClass)
  private val kc = new KafkaCluster(kafkaParams)

  /**
   * 创建数据流
   * @param ssc
   * @param kafkaParams
   * @param topics
   * @tparam K
   * @tparam V
   * @tparam KD
   * @tparam VD
   * @return
   */
  def createDirectStream[K: ClassTag, V: ClassTag, KD <: Decoder[K]: ClassTag, VD <: Decoder[V]: ClassTag](
    ssc: StreamingContext, kafkaParams: Map[String, String], topics: Set[String]): InputDStream[(K, V)] = {
    val groupId = kafkaParams.get("group.id").get
    // 在zookeeper上读取offsets前先根据实际情况更新offsets
    setOrUpdateOffsets(topics, groupId)
    //从zookeeper上读取offset开始消费message
    val messages = {
      val partitionsE = kc.getPartitions(topics)
      if (partitionsE.isLeft) throw new SparkException("get kafka partition failed:")
      val partitions = partitionsE.right.get
      val consumerOffsetsE = kc.getConsumerOffsets(groupId, partitions)
      if (consumerOffsetsE.isLeft) throw new SparkException("get kafka consumer offsets failed:")
      val consumerOffsets = consumerOffsetsE.right.get
      KafkaUtils.createDirectStream[K, V, KD, VD, (K, V)](
        ssc, kafkaParams, consumerOffsets, (mmd: MessageAndMetadata[K, V]) => (mmd.key, mmd.message))
    }
    messages
  }

  /**
   * 创建数据流前，根据实际消费情况更新消费offsets
   * @param topics
   * @param groupId
   */
  private def setOrUpdateOffsets(topics: Set[String], groupId: String): Unit = {
    topics.foreach(topic => {
      var hasConsumed = true
      val partitionsE = kc.getPartitions(Set(topic))
      if (partitionsE.isLeft) throw new SparkException(s"get kafka partition failed: ${partitionsE.left.get.mkString("\n")}")
      val partitions: Set[TopicAndPartition] = partitionsE.right.get
      val consumerOffsetsE = kc.getConsumerOffsets(groupId, partitions)
      if (consumerOffsetsE.isLeft) hasConsumed = false
      log.info("consumerOffsetsE.isLeft: " + consumerOffsetsE.isLeft)
      if (hasConsumed) { // 消费过
        log.warn("消费过")
        /**
         * 如果zk上保存的offsets已经过时了，即kafka的定时清理策略已经将包含该offsets的文件删除。
         * 针对这种情况，只要判断一下zk上的consumerOffsets和earliestLeaderOffsets的大小，
         * 如果consumerOffsets比earliestLeaderOffsets还小的话，说明consumerOffsets已过时,
         * 这时把consumerOffsets更新为earliestLeaderOffsets
         */
        val earliestLeaderOffsetsE = kc.getEarliestLeaderOffsets(partitions)
        if (earliestLeaderOffsetsE.isLeft) throw new SparkException(s"get earliest offsets failed: ${earliestLeaderOffsetsE.left.get.mkString("\n")}")
        val earliestLeaderOffsets = earliestLeaderOffsetsE.right.get
        val consumerOffsets = consumerOffsetsE.right.get

        // 可能只是存在部分分区consumerOffsets过时，所以只更新过时分区的consumerOffsets为earliestLeaderOffsets
        var offsets: Map[TopicAndPartition, Long] = Map()
        consumerOffsets.foreach({
          case (tp, n) =>
            val earliestLeaderOffset = earliestLeaderOffsets(tp).offset
            if (n < earliestLeaderOffset) {
              log.warn("consumer group:" + groupId + ",topic:" + tp.topic + ",partition:" + tp.partition +
                " offsets已经过时，更新为" + earliestLeaderOffset)
              offsets += (tp -> earliestLeaderOffset)
            }
        })
        log.warn("offsets: " + consumerOffsets)
        if (!offsets.isEmpty) {
          kc.setConsumerOffsets(groupId, offsets)
        }
      } else { // 没有消费过
        log.warn("没消费过")
        val reset = kafkaParams.get("auto.offset.reset").map(_.toLowerCase)
        var leaderOffsets: Map[TopicAndPartition, LeaderOffset] = null
        if (reset == Some("smallest")) {
          leaderOffsets = kc.getEarliestLeaderOffsets(partitions).right.get
        } else {
          leaderOffsets = kc.getLatestLeaderOffsets(partitions).right.get
        }
        val offsets = leaderOffsets.map {
          case (tp, offset) => (tp, offset.offset)
        }
        log.warn("offsets: " + offsets)
        kc.setConsumerOffsets(groupId, offsets)
      }
    })
  }

  /**
   * 更新zookeeper上的消费offsets
   * @param rdd
   */
  def updateZKOffsets(rdd: RDD[(String, String)]): Unit = {
    val groupId = kafkaParams.get("group.id").get
    val offsetsList = rdd.asInstanceOf[HasOffsetRanges].offsetRanges

    for (offsets <- offsetsList) {
      val topicAndPartition = TopicAndPartition(offsets.topic, offsets.partition)
      val o = kc.setConsumerOffsets(groupId, Map((topicAndPartition, offsets.untilOffset)))
      if (o.isLeft) {
        log.error(s"Error updating the offset to Kafka cluster: ${o.left.get}")
      }
    }
  }
}