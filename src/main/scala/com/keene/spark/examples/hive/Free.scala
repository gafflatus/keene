package com.keene.spark.examples.hive

import com.keene.core.Runner
import com.keene.core.parsers.{Arguments, ArgumentsParser}
import com.keene.spark.utils.SimpleSpark
import com.keene.core.implicits._

class Free extends Runner with SimpleSpark {
  import spark.implicits._
  override def run (args: Array[ String ]): Unit = {
    val arg = args.as[FreeArg]

    val click = "base_ads_click_sum_log".tab where($"dt" === arg.date)


  }

}

class FreeArg(
  var date : String = ""
) extends Arguments {
  override def usage =
    """Usage:
      |
      |--date
    """.stripMargin
}