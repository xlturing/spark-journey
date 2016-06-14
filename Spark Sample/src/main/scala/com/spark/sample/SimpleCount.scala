package com.spark.sample

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext

object SimpleCount {
	def main(args: Array[String]) {
		val conf = new SparkConf().setAppName("TrySparkStreaming").setMaster("local[2]") // Create spark context
		val sc = new SparkContext(conf)
		//		val ssc = new StreamingContext(conf, Seconds(1)) // create streaming context

		val txtFile = "test"
		val txtData = sc.textFile(txtFile)
		txtData.cache()

		txtData.count()
		val wcData = txtData.flatMap { line => line.split(",") }.map { word => (word, 1) }.reduceByKey(_ + _)
		wcData.collect().foreach(println)
		
		sc.stop
	}
}