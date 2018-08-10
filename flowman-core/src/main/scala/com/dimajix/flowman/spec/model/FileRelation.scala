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
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.execution.datasources.DataSource
import org.apache.spark.sql.execution.datasources.FileFormat
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources.RelationProvider
import org.apache.spark.sql.sources.SchemaRelationProvider
import org.apache.spark.sql.types.StructType
import org.slf4j.LoggerFactory

import com.dimajix.flowman.execution.Context
import com.dimajix.flowman.execution.Executor
import com.dimajix.flowman.spec.schema.PartitionField
import com.dimajix.flowman.spec.schema.PartitionSchema
import com.dimajix.flowman.types.FieldValue
import com.dimajix.flowman.types.SingleValue
import com.dimajix.flowman.util.FileCollector
import com.dimajix.flowman.util.SchemaUtils


class FileRelation extends SchemaRelation {
    private val logger = LoggerFactory.getLogger(classOf[FileRelation])

    @JsonProperty(value="location") private var _location: String = _
    @JsonProperty(value="format") private var _format: String = "csv"
    @JsonProperty(value="partitions") private var _partitions: Seq[PartitionField] = Seq()
    @JsonProperty(value="pattern") private var _pattern: String = _

    def pattern(implicit context:Context) : String = context.evaluate(_pattern)
    def location(implicit context:Context) : String = context.evaluate(_location)
    def format(implicit context:Context) : String = context.evaluate(_format)
    def partitions : Seq[PartitionField] = _partitions

    /**
      * Reads data from the relation, possibly from specific partitions
      *
      * @param executor
      * @param schema - the schema to read. If none is specified, all available columns will be read
      * @param partitions - List of partitions. If none are specified, all the data will be read
      * @return
      */
    override def read(executor:Executor, schema:StructType, partitions:Map[String,FieldValue] = Map()) : DataFrame = {
        implicit val context = executor.context
        val inputFiles = collectFiles(executor, partitions)

        //if (inputFiles.isEmpty)
        //    throw new IllegalArgumentException("No input files found")

        val reader = this.reader(executor)
            .format(format)

        // Use either load(files) or load(single_file) - this actually results in different code paths in Spark
        // load(single_file) will set the "path" option, while load(multiple_files) needs direct support from the
        // underlying format implementation
        val providingClass = lookupDataSource(format, executor.spark.sessionState.conf)
        val rawData = providingClass.newInstance() match {
            case _:RelationProvider => reader.load(inputFiles.map(_.toString).mkString(","))
            case _:SchemaRelationProvider => reader.load(inputFiles.map(_.toString).mkString(","))
            case _:FileFormat => reader.load(inputFiles.map(_.toString): _*)
            case _ => reader.load(inputFiles.map(_.toString).mkString(","))
        }

        SchemaUtils.applySchema(rawData, schema)
    }

    private def lookupDataSource(provider: String, conf: SQLConf): Class[_] = {
        // Check appropriate method, depending on Spark version
        try {
            // Spark 2.2.x
            val method = DataSource.getClass.getDeclaredMethod("lookupDataSource", classOf[String])
            method.invoke(DataSource, provider).asInstanceOf[Class[_]]
        }
        catch {
            case _:NoSuchMethodException => {
                // Spark 2.3.x
                val method = DataSource.getClass.getDeclaredMethod("lookupDataSource", classOf[String], classOf[SQLConf])
                method.invoke(DataSource, provider, conf).asInstanceOf[Class[_]]
            }
        }
    }

    /**
      * Writes data into the relation, possibly into a specific partition
      * @param executor
      * @param df - dataframe to write
      * @param partition - destination partition
      */
    override def write(executor:Executor, df:DataFrame, partition:Map[String,SingleValue], mode:String) : Unit = {
        implicit val context = executor.context

        val parsedPartition = PartitionSchema(partitions).parse(partition).map(kv => (kv._1.name, kv._2)).toMap
        val outputPath = collector(executor).resolve(parsedPartition)

        logger.info(s"Writing to output location '$outputPath' (partition=$partition) as '$format'")

        this.writer(executor, df)
            .format(format)
            .mode(mode)
            .save(outputPath.toString)
    }

    /**
      * This method will create the given directory as specified in "location"
      * @param executor
      */
    override def create(executor:Executor) : Unit = {
        implicit val context = executor.context
        logger.info(s"Creating directory '$location' for file relation")
        val path = new Path(location)
        val fs = path.getFileSystem(executor.spark.sparkContext.hadoopConfiguration)
        fs.mkdirs(path)
    }

    /**
      * This method will remove the given directory as specified in "location"
      * @param executor
      */
    override def destroy(executor:Executor) : Unit =  {
        implicit val context = executor.context
        logger.info(s"Deleting directory '$location' of file relation")
        val path = new Path(location)
        val fs = path.getFileSystem(executor.spark.sparkContext.hadoopConfiguration)
        fs.delete(path, true)
    }
    override def migrate(executor:Executor) : Unit = ???

    /**
      * Collects files for a given time period using the pattern inside the specification
      *
      * @param executor
      * @param partitions
      * @return
      */
    private def collectFiles(executor: Executor, partitions:Map[String,FieldValue]) = {
        implicit val context = executor.context
        if (location == null || location.isEmpty)
            throw new IllegalArgumentException("location needs to be defined for reading files")

        val inputFiles =
            if (this.partitions != null && this.partitions.nonEmpty)
                collectPartitionedFiles(executor, partitions)
            else
                collectUnpartitionedFiles(executor)

        // Print all files that we found
        inputFiles.foreach(f => logger.info("Reading input file {}", f.toString))
        inputFiles
    }

    private def collectPartitionedFiles(executor: Executor, partitions:Map[String,FieldValue]) = {
        implicit val context = executor.context
        if (partitions == null)
            throw new NullPointerException("Partitioned data source requires partition values to be defined")
        if (pattern == null || pattern.isEmpty)
            throw new IllegalArgumentException("pattern needs to be defined for reading partitioned files")

        val partitionColumnsByName = this.partitions.map(kv => (kv.name,kv)).toMap
        val resolvedPartitions = partitions.map(kv => (kv._1, partitionColumnsByName.getOrElse(kv._1, throw new IllegalArgumentException(s"Partition column '${kv._1}' not defined in relation $name")).interpolate(kv._2)))
        collector(executor).collect(resolvedPartitions)
    }

    private def collectUnpartitionedFiles(executor: Executor) = {
        collector(executor).collect()
    }

    private def collector(executor: Executor) = {
        implicit val context = executor.context
        new FileCollector(executor.spark)
            .path(new Path(location))
            .pattern(pattern)
    }
}
