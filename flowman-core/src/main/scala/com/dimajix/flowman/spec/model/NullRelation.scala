/*
 * Copyright 2018 Kaya Kupferschmidt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dimajix.flowman.spec.model

import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.StructType

import com.dimajix.flowman.execution.Context
import com.dimajix.flowman.execution.Executor
import com.dimajix.flowman.spec.schema.PartitionField
import com.dimajix.flowman.types.FieldValue
import com.dimajix.flowman.types.SingleValue


class NullRelation extends SchemaRelation {
    @JsonProperty(value="partitions", required=false) private var _partitions: Seq[PartitionField] = Seq()

    def partitions(implicit context: Context) : Seq[PartitionField] = _partitions

    /**
      * Reads data from the relation, possibly from specific partitions
      *
      * @param executor
      * @param schema
      * @param partitions
      * @return
      */
    override def read(executor:Executor, schema:StructType, partitions:Map[String,FieldValue] = Map()) : DataFrame = {
        assert(partitions != null)

        implicit val context = executor.context
        val rdd = executor.spark.sparkContext.emptyRDD[Row]
        val readSchema = Option(schema).getOrElse(inputSchema)
        executor.spark.createDataFrame(rdd, readSchema)
    }

    /**
      * Writes data into the relation, possibly into a specific partition
      *
      * @param executor
      * @param df
      * @param partition
      */
    override def write(executor:Executor, df:DataFrame, partition:Map[String,SingleValue], mode:String) : Unit = {
    }

    override def create(executor: Executor): Unit = {
    }
    override def destroy(executor: Executor): Unit = {
    }
    override def migrate(executor: Executor): Unit = {
    }
}
