/*
 * Copyright 2018-2020 Kaya Kupferschmidt
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

package com.dimajix.flowman.spec.mapping

import java.io.StringWriter
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale

import scala.annotation.tailrec

import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.commons.io.IOUtils
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.plans.logical.UnaryNode
import org.apache.spark.sql.catalyst.plans.logical.Union

import com.dimajix.flowman.execution.Context
import com.dimajix.flowman.execution.Executor
import com.dimajix.flowman.model.BaseMapping
import com.dimajix.flowman.model.Mapping
import com.dimajix.flowman.model.MappingOutputIdentifier
import com.dimajix.flowman.types.StructType
import com.dimajix.spark.sql.DataFrameUtils
import com.dimajix.spark.sql.SqlParser


case class RecursiveSqlMapping(
    instanceProperties:Mapping.Properties,
    sql:Option[String],
    file:Option[Path],
    url:Option[URL]
)
extends BaseMapping {
    /**
      * Resolves all dependencies required to build the SQL
      *
      * @return
      */
    override def inputs : Seq[MappingOutputIdentifier] = {
        SqlParser.resolveDependencies(statement)
            .filter(_.toLowerCase(Locale.ROOT) != "__this__")
            .map(MappingOutputIdentifier.parse)
    }

    /**
      * Executes this MappingType and returns a corresponding DataFrame
      *
      * @param executor
      * @param input
      * @return
      */
    override def execute(executor:Executor, input:Map[MappingOutputIdentifier,DataFrame]) : Map[String,DataFrame] = {
        require(executor != null)
        require(input != null)

        val statement = this.statement

        @tailrec
        def fix(in:DataFrame, inCount:Long) : DataFrame = {
            val result = nextDf(statement, in)
            val resultCount = result.count()
            if (resultCount != inCount)
                fix(result, resultCount)
            else
                result
        }

        // Register all input DataFrames as temp views
        input.foreach(kv => kv._2.createOrReplaceTempView(kv._1.name))
        // Execute query
        val first = firstDf(executor.spark, statement)
        val result = fix(first, first.count())
        // Call SessionCatalog.dropTempView to avoid unpersisting the possibly cached dataset.
        input.foreach(kv => executor.spark.sessionState.catalog.dropTempView(kv._1.name))

        Map("main" -> result)
    }

    private def firstDf(spark:SparkSession, statement:String) : DataFrame = {
        @tailrec
        def findUnion(plan:LogicalPlan) : LogicalPlan = {
            plan match {
                case union:Union => union
                case node:UnaryNode =>findUnion(node.child)
            }
        }

        val plan = SqlParser.parsePlan(statement)
        val union = findUnion(plan)
        val firstChild = union.children.head
        val resolvedStart = spark.sessionState.analyzer.execute(firstChild)
        new Dataset[Row](spark, firstChild, RowEncoder(resolvedStart.schema))
    }
    private def nextDf(statement:String, prev:DataFrame) : DataFrame = {
        val spark = prev.sparkSession
        prev.createOrReplaceTempView("__this__")
        val result = spark.sql(statement).localCheckpoint(false)
        spark.sessionState.catalog.dropTempView("__this__")
        result
    }

    /**
      * Returns the schema as produced by this mapping, relative to the given input schema. The map might not contain
      * schema information for all outputs, if the schema cannot be inferred.
      * @param input
      * @return
      */
    override def describe(executor: Executor, input: Map[MappingOutputIdentifier, StructType]): Map[String, StructType] = {
        require(executor != null)
        require(input != null)

        val spark = executor.spark
        val statement = this.statement

        // Create dummy data frames
        val replacements = input.map { case (name,schema) =>
            name -> DataFrameUtils.singleRow(spark, schema.sparkType)
        }

        // Register all input DataFrames as temp views
        replacements.foreach(kv => kv._2.createOrReplaceTempView(kv._1.name))

        val result = firstDf(spark, statement)
        // Call SessionCatalog.dropTempView to avoid unpersisting the possibly cached dataset.
        replacements.foreach(kv => spark.catalog.dropTempView(kv._1.name))

        Map("main" -> StructType.of(result.schema))
    }

    private def statement : String = {
        if (sql.exists(_.nonEmpty)) {
            sql.get
        }
        else if (file.nonEmpty) {
            val fs = context.fs
            val input = fs.file(file.get).open()
            try {
                val writer = new StringWriter()
                IOUtils.copy(input, writer, Charset.forName("UTF-8"))
                writer.toString
            }
            finally {
                input.close()
            }
        }
        else if (url.nonEmpty) {
            IOUtils.toString(url.get)
        }
        else {
            throw new IllegalArgumentException("SQL mapping needs either 'sql', 'file' or 'url'")
        }
    }
}


class RecursiveSqlMappingSpec extends MappingSpec {
    @JsonProperty(value="sql", required=false) private var sql:Option[String] = None
    @JsonProperty(value="file", required=false) private var file:Option[String] = None
    @JsonProperty(value="url", required=false) private var url: Option[String] = None

    /**
      * Creates the instance of the specified Mapping with all variable interpolation being performed
      * @param context
      * @return
      */
    override def instantiate(context: Context): RecursiveSqlMapping = {
        RecursiveSqlMapping(
            instanceProperties(context),
            context.evaluate(sql),
            file.map(context.evaluate).filter(_.nonEmpty).map(p => new Path(p)),
            url.map(context.evaluate).filter(_.nonEmpty).map(u => new URL(u))
        )
    }
}
