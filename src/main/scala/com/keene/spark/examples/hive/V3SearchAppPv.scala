package com.keene.spark.examples.hive

import java.util.concurrent.CountDownLatch

import com.keene.core.Runner
import com.keene.core.implicits._
import com.keene.core.parsers.{Arguments, ArgumentsParser => Parser}
import com.keene.spark.utils.SimpleSpark
import org.apache.spark.sql.DataFrame
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.immutable.HashSet
import scala.concurrent.Future

class V3SearchAppPv extends Runner with SimpleSpark{

  import spark.implicits._
  override def run (args: Array[ String ]): Unit = {
    implicit val arg = args.as[Args]
    init
  }

  def init(implicit arg : Args) ={
    implicit val date : String = arg date

    var counter = 0

    def doOnSuccess: PartialFunction[Unit, _] =
    { case _ => counter += 1}
    //加载数据
    //hive表别名,注册临时表
    //log_mark大表,TB级数据,千万条
    //online_log小表,GB级数据,上亿条
    Future( fetchGdmOnlineLogMarkAs("log_mark") ) onSuccess doOnSuccess
    Future( fetchGdmM14WirelessOnlineLogAs("online_log") ) onSuccess doOnSuccess
    while( counter < 2 ) {
      s"counter:${counter},wait......".info
      Thread sleep 1000
    }
    continue
  }

  def continue(implicit arg : Args): Unit ={
    /**
      * 核心逻辑
      * 1.大表joinKey去重后与小表做差得到差集`E`
      * 2.大表与E做join与原join等价,只不过空值条件刚好相反
      * 3.发现E是MB级数据,直接做广播消除join
      * 4.注册spark自定义函数判断joinKey是否在E中
      *   (1)在,说明当前key不在online_mark中,等价于原join得到的null,则has_behavior=0
      *   (2)不在,同理,has_behavior=1
      * 5.通过存入临时目录,再执行load data读入hive表代替直接存hive表
      *
      * 最后发现保存文件仍有轻微的数据倾斜,
      * 之后可以进一步优化
      */
    val distinctedJoinKLogMark = "select distinct browser_uniq_id from log_mark".go cache

    val logMarkExceptOnlineLog = distinctedJoinKLogMark except fetchDF("online_log") cache

    val hasBehavior = chooseBehaviorFunction(
      logMarkExceptOnlineLog,
      distinctedJoinKLogMark
    )

    Array(distinctedJoinKLogMark, logMarkExceptOnlineLog) foreach( _ unpersist )

    spark.udf register("hasBehavior", hasBehavior )

    //result
    """
      |select
      |  log_mark.*,
      |  hasBehavior(browser_uniq_id) as has_behavior
      |from
      |  log_mark
    """.stripMargin.go.
      repartition(arg.numRepartition).
      write.
      mode("overwrite").
      orc(arg.tempPath)

    "uncache table log_mark" go

    s"""
       |load data inpath '${arg.tempPath}'
       |overwrite into table ${arg.resultTable}
       |partition (dt='${arg.date}')
    """.stripMargin go
  }

  def fetchDF(alias : String) : DataFrame = s"select * from ${alias}".go

  def fetchGdmOnlineLogMarkAs(alias : String)(implicit date : String) {
    val t = s"""
       |select
       |  error_cd,error_desc,error_original_data,request_tm,user_visit_ip,ct_url,session_id,web_site_id,use_java_flag,screen_colour_depth,screen_resolution,browser_code_mode,browser_lang_type,page_title,url_domain,flash_ver,os,browser_type,browser_ver,first_session_create_tm,prev_visit_create_tm,visit_create_tm,visit_times,sequence_num,utm_source,utm_campaign,utm_medium,utm_term,log_type1,log_type2,browser_uniq_id,user_log_acct,referer_url,request_par,referer_par,dry_url,item_first_cate_id,item_second_cate_id,item_third_cate_id,sku_id,sale_ord_id,referer_dry_url,referer_item_first_cate_id,referer_item_second_cate_id,referer_item_third_cate_id,referer_sku_id,referer_ord_id,referer_kwd,referer_page_num,request_time_sec,user_visit_ip_id,ord_content,marked_skus,jshop_app_case_id,item_qtty,real_view_flag,ext_columns,load_sec,kwd,page_num,from_position,from_content_id,impressions,first_request_flag,last_request_flag,session_rt,stm_rt,referer_sequence_num,mtm_rt,url_request_seq_num,src_url,src_dry_url,src_kwd,src_page_num,landing_url,stm_max_pv_qtty,mtm_max_pv_qtty,later_orders,from_position_seq_num,from_sys,parsed_url_par,ct_url_anchor,parsed_referer_par,shop_id,referer_shop_id,ct_utm_union_id,ct_utm_sub_union_id,ct_utm_src,ct_utm_medium,ct_utm_compaign,ct_utm_term,from_content_type,from_ad_par,list_page_item_filter,id_list,referer_id_list,src_first_domain,src_second_domain,common_list,chan_first_cate_cd,chan_second_cate_cd,chan_third_cate_cd,chan_fourth_cate_cd,pinid,page_extention,user_site_addr,user_site_cy_name,user_site_province_name,user_site_city_name,all_domain_uniq_id,all_domain_visit_times,all_domain_session_id,all_domain_sequence_num,cdt_flag
       |from xxxx
       |where dt='$date'
       |and log_type1 = 'mapp.000001'
       |and ext_columns['client'] != 'm'
    """.stripMargin.go.cache
    t createOrReplaceTempView alias
  }


  def fetchGdmM14WirelessOnlineLogAs(alias : String)(implicit date : String)=
    s"""
       |select
       |  distinct browser_uniq_id
       |from xxxxx
       |where dt = '$date'
       |and length(browser_uniq_id) > 0
    """.stripMargin.go createOrReplaceTempView alias

  def chooseBehaviorFunction(e : DataFrame, u : DataFrame) ={
    def broadcastSet(df : DataFrame) =
      df.as[String].collect.to[HashSet] bc

    var (ec, uc) = (0l, 0l)
    val latch = new CountDownLatch(2)
    new Thread(() => {
      ec = e.count
      latch.countDown
    }).start

    new Thread(() => {
      uc = u.count
      latch.countDown
    }).start

    latch.await
    if( ec < uc / 2 ){
      val exceptBc = broadcastSet(e)
      k: String => if( exceptBc contains k ) 0 else 1
    }
    else{
      val intersectBc = broadcastSet( u except e )
      k: String => if( intersectBc contains k ) 1 else 0
    }
  }

}