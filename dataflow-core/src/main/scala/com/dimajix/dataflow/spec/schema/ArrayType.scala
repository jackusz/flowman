package com.dimajix.dataflow.spec.schema

import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.spark.sql.types.DataType

import com.dimajix.dataflow.execution.Context


case class ArrayType(
    @JsonProperty(value="elementType") elementType:FieldType
                    ) extends ContainerType {
    override def sparkType(implicit context: Context) : DataType = {
        org.apache.spark.sql.types.ArrayType(elementType.sparkType)
    }

    override def parse(value:String) : Any = ???
    override def interpolate(value: FieldValue, granularity:String) : Iterable[Any] = ???
}
