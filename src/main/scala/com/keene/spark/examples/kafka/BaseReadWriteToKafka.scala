package com.keene.spark.examples.kafka

import com.keene.core.ExampleRunner
import com.keene.core.implicits._
import com.keene.core.parsers.{Arguments, ArgumentsParser}
import com.keene.kafka.KafkaParam
import com.keene.spark.utils.SimpleSpark

/**
  * 最基本的功能:
  * Streming从Kafka读
  * 转换一下写回Kafka
  */
class BaseReadWriteToKafka extends SimpleSpark with ExampleRunner{

  override def run(args: Array[String]): Unit = {

    info(args mkString "\t")
    val arg = ArgumentsParser[Lv1Args](args)
    val readParam = KafkaParam( arg.brokers , arg.subscribe )

    spark fromKafka readParam createOrReplaceTempView "t"

    implicit val writeParam = KafkaParam( arg.brokers , arg.topic , as = "writer")

    "select * from t".go.toKafka start

    "select base64(CAST(value as STRING)) value from t".go.toKafka start

    spark.streams.awaitAnyTermination
  }

  override def sparkConfOpts: Map[String, String] = super.sparkConfOpts ++ Map(
    "spark.sql.streaming.checkpointLocation" -> "e:/tmp/spark"
  )
}

class Lv1Args(
  var brokers: String = "",
  var subscribe : String = "" ,
  var topic: String = ""
) extends Arguments {
  override def usage =
    """
      |Options:
      |--brokers 逗号分隔的broker列表
      |--subscribe 订阅的topic
      |--topic 发布的topic
    """.stripMargin
}