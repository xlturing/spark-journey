#Spark streaming+Kafka demo
一个sparkstreaming连接kafka的demo，利用direct方式，同时将offset同步到zookeeper中进行保存。

##运行环境
1. 利用maven进行包管理
2. 利用maven-assembly-plugin进行打包，上传到spark上运行
3. 在spark1.6和1.3都进行过应用

##连接方式
本demo利用了kafka底层接口，直接操作kafka的offset，并将其同步到zookeeper中，构建了KafkaManager通用类。
在ConsumerMain中进行了简单的应用展示
