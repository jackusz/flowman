package com.dimajix.dataflow.spec.schema

import scala.beans.BeanProperty

import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.types.StructField
import org.codehaus.jackson.annotate.JsonProperty

import com.dimajix.dataflow.execution.Context


case class StructType(
    @JsonProperty(value = "fields") private[schema] var fields:Seq[Field]
                     ) extends ContainerType {
    def this() = { this(Seq()) }
    override def sparkType(implicit context: Context) : DataType = {
        org.apache.spark.sql.types.StructType(
            fields.map(f => StructField(f.name, f.sparkType, f.nullable))
        )
    }
    override def parse(value:String) : Any = ???
    override def interpolate(value: FieldValue, granularity:String) : Iterable[Any] = ???
}