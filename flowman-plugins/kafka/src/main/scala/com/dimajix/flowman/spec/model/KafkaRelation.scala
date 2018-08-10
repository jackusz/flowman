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

import scala.collection.immutable.Nil

import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.streaming.StreamingQuery
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StructType
import org.slf4j.LoggerFactory

import com.dimajix.flowman.annotation.RelationType
import com.dimajix.flowman.execution.Context
import com.dimajix.flowman.execution.Executor
import com.dimajix.flowman.spec.schema.EmbeddedSchema
import com.dimajix.flowman.spec.schema.Schema
import com.dimajix.flowman.types.BinaryType
import com.dimajix.flowman.types.Field
import com.dimajix.flowman.types.FieldValue
import com.dimajix.flowman.types.IntegerType
import com.dimajix.flowman.types.LongType
import com.dimajix.flowman.types.SingleValue
import com.dimajix.flowman.types.StringType
import com.dimajix.flowman.types.TimestampType
import com.dimajix.flowman.util.SchemaUtils


object KafkaRelation {
    def apply(hosts:Seq[String], topics:Seq[String]): KafkaRelation = {
        val relation = new KafkaRelation()
        relation._hosts = hosts
        relation._topics = topics
        relation
    }
}


@RelationType(kind="kafka")
class KafkaRelation extends BaseRelation {
    private val logger = LoggerFactory.getLogger(classOf[KafkaRelation])

    @JsonProperty(value="hosts", required=false) private var _hosts:Seq[String] = Seq()
    @JsonProperty(value="topics", required=false) private var _topics:Seq[String] = Seq()
    @JsonProperty(value="startOffset", required=false) private var _startOffset:String = "earliest"
    @JsonProperty(value="endOffset", required=false) private var _endOffset:String = "latest"

    def hosts(implicit context:Context) : Seq[String] = _hosts.map(context.evaluate)
    def topics(implicit context:Context) : Seq[String] = _topics.map(context.evaluate)
    def startOffset(implicit context: Context) : String = context.evaluate(_startOffset)
    def endOffset(implicit context: Context) : String = context.evaluate(_endOffset)

    /**
      * Returns the schema of the relation
      * @param context
      * @return
      */
    override def schema(implicit context: Context) : Schema = {
        val fields =
            Field("key", BinaryType, nullable = true) ::
            Field("value", BinaryType, nullable = false) ::
            Field("topic", StringType, nullable = false) ::
            Field("partition", IntegerType, nullable = false) ::
            Field("offset", LongType, nullable = false) ::
            Field("timestamp", TimestampType, nullable = false) ::
            Field("timestampType", IntegerType, nullable = false) ::
            Nil
        EmbeddedSchema(fields)
    }

    /**
      * Reads data from the relation, possibly from specific partitions
      *
      * @param executor
      * @param schema     - the schema to read. If none is specified, all available columns will be read
      * @param partitions - List of partitions. If none are specified, all the data will be read
      * @return
      */
    override def read(executor: Executor, schema: StructType, partitions: Map[String, FieldValue]): DataFrame = {
        implicit val context = executor.context
        val hosts = this.hosts.mkString(",")
        val startOffset = this.startOffset
        val endOffset = this.endOffset
        val topics = this.topics.mkString(",")
        logger.info(s"Reading Kafka topics '$topics' at hosts '$hosts'")

        val reader = this.reader(executor)
            .format("kafka")
            .option("subscribe", topics)
            .option("kafka.bootstrap.servers", hosts)
            .option("startingOffsets", startOffset)
            .option("endingOffsets", endOffset)
        val df = reader.load()

        SchemaUtils.applySchema(df, schema)
    }

    /**
      * Writes data into the relation, possibly into a specific partition
      *
      * @param executor
      * @param df        - dataframe to write
      * @param partition - destination partition
      */
    override def write(executor: Executor, df: DataFrame, partition: Map[String, SingleValue], mode: String): Unit = {
        implicit val context = executor.context
        val hosts = this.hosts.mkString(",")
        val topic = this.topics.headOption.getOrElse(throw new IllegalArgumentException(s"Missing field 'topic' in relation '$name'"))
        logger.info(s"Writing to Kafka topic '$topic' at hosts '$hosts'")

        this.writer(executor, df)
            .format("kafka")
            .mode(mode)
            .option("topic", topic)
            .option("kafka.bootstrap.servers", hosts)
            .save()
    }

    /**
      * Reads data from a streaming source
      *
      * @param executor
      * @param schema
      * @return
      */
    override def readStream(executor: Executor, schema: StructType): DataFrame = {
        implicit val context = executor.context
        val hosts = this.hosts.mkString(",")
        val startOffset = this.startOffset
        val topics = this.topics.mkString(",")
        logger.info(s"Streaming from Kafka topics '$topics' at hosts '$hosts'")

        val reader = executor.spark.readStream.options(options)
            .format("kafka")
            .option("subscribe", topics)
            .option("kafka.bootstrap.servers", hosts)
            .option("startingOffsets", startOffset)
        val df = reader.load()

        SchemaUtils.applySchema(df, schema)
    }

    /**
      * Writes data to a streaming sink
      *
      * @param executor
      * @param df
      * @return
      */
    override def writeStream(executor: Executor, df: DataFrame, mode: OutputMode, checkpointLocation: Path): StreamingQuery = {
        implicit val context = executor.context
        val hosts = this.hosts.mkString(",")
        val topic = this.topics.headOption.getOrElse(throw new IllegalArgumentException(s"Missing field 'topic' in relation '$name'"))
        logger.info(s"Streaming to Kafka topic '$topic' at hosts '$hosts'")

        this.streamWriter(executor, df, mode, checkpointLocation)
           .format("kafka")
            .option("topic", topic)
            .option("kafka.bootstrap.servers", hosts)
            .start()
    }

    /**
      * This method will physically create the corresponding relation. This might be a Hive table or a directory. The
      * relation will not contain any data, but all metadata will be processed
      *
      * @param executor
      */
    override def create(executor: Executor): Unit = ???

    /**
      * This will delete any physical representation of the relation. Depending on the type only some meta data like
      * a Hive table might be dropped or also the physical files might be deleted
      *
      * @param executor
      */
    override def destroy(executor: Executor): Unit = ???

    /**
      * This will update any existing relation to the specified metadata.
      *
      * @param executor
      */
    override def migrate(executor: Executor): Unit = ???

    /**
      * Returns empty schema, so we read in all columns from Kafka
      *
      * @param context
      * @return
      */
    override protected def inputSchema(implicit context: Context): StructType = {
        StructType(Seq(
            StructField("key", org.apache.spark.sql.types.BinaryType),
            StructField("value", org.apache.spark.sql.types.BinaryType),
            StructField("topic", org.apache.spark.sql.types.StringType),
            StructField("partition", org.apache.spark.sql.types.IntegerType),
            StructField("offset", org.apache.spark.sql.types.LongType),
            StructField("timestamp", org.apache.spark.sql.types.TimestampType),
            StructField("timestampType", org.apache.spark.sql.types.IntegerType)
        ))
    }

    /**
      * Returns empty schema, so we write columns as they are given to Kafka
      *
      * @param context
      * @return
      */
    override protected def outputSchema(implicit context: Context): StructType = null
}
