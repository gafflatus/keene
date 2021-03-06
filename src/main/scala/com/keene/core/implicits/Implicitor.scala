/**
  * 这里封装了一些自定义的隐式转换
  * 可以方便的进行链式函数调用
  * 相比于`fun`(obj)的代码风格我更喜欢`str`.`fun`
  *
  */
package com.keene.core.implicits

import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}

import scala.reflect.ClassTag

trait Implicitor {
  implicit def to(str : String) = StringImplicitor(str)
  implicit def to(spark : SparkSession)  = SparkSessionImplicitor(spark)
  implicit def to[T](ds : Dataset[T]) = DataSetImplicitor(ds)
  implicit def to[T, U](map : Map[T, U]) = MapImplicitor(map)
  implicit def to[T](array : Array[T]) = ArrayImplicitor(array)
  implicit def to[T](t : Traversable[T] ) = TraversableImlicitor(t)
  implicit def to[T](t: T)(implicit tag : ClassTag[T]) = AnyImplicitor(t)
}