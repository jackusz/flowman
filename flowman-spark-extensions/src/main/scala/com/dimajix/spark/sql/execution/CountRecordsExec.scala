package com.dimajix.spark.sql.execution

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.UnaryExecNode
import org.apache.spark.util.LongAccumulator


case class CountRecordsExec(child: SparkPlan, counter:LongAccumulator) extends UnaryExecNode {
    override def output: Seq[Attribute] = child.output

    override def outputPartitioning: Partitioning = child.outputPartitioning

    override protected def doExecute(): RDD[InternalRow] = {
        val c = counter
        child.execute().mapPartitions { iter =>
            iter.map { row =>
                c.add(1)
                row
            }
        }
    }
}
