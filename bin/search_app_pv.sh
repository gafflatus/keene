#!/usr/bin/env bash

source bin/ads_anti.sh
JAR=$(ls target/scala*/*.jar)
DATE='2018-07-28'

NUM_EXECUTORS=300
CORES=4
MEM=10G
REDUCERS=$((NUM_EXECUTORS * CORES * 3))

run \
--queue "root.bdp_jmart_ad.jd_ad_oozie" \
--num-executors ${NUM_EXECUTORS} \
--executor-cores ${CORES} \
--executor-memory ${MEM} \
--conf spark.sql.codegen=true \
--conf spark.sql.shuffle.partitions=$REDUCERS \
${JAR} \
--class sync.SearchAppPv \
--date ${DATE} \
--result-table antidb.keen_search_app_pv_log \
--num-repartition $REDUCERS \
--temp-path "/user/jd_ad/ads_anti/guanxinyu/metadata/hive/keen_search_app_pv_log"
