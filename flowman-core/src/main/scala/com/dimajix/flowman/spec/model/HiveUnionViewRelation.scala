/*
 * Copyright 2018-2019 Kaya Kupferschmidt
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

import java.util.Locale

import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.spark.sql.DataFrame
import org.slf4j.LoggerFactory

import com.dimajix.flowman.execution.Context
import com.dimajix.flowman.execution.Executor
import com.dimajix.flowman.spec.RelationIdentifier
import com.dimajix.flowman.spec.schema.PartitionField
import com.dimajix.flowman.spec.schema.Schema
import com.dimajix.flowman.types.FieldType
import com.dimajix.flowman.types.FieldValue
import com.dimajix.flowman.types.SingleValue


case class HiveUnionViewRelation(
    instanceProperties:Relation.Properties,
    override val database: String,
    override val table: String,
    input: Seq[RelationIdentifier],
    customSchema:Option[Schema],
    override val partitions: Seq[PartitionField]
) extends HiveRelation {
    protected override val logger = LoggerFactory.getLogger(classOf[HiveUnionViewRelation])

    override def schema : Schema = customSchema.orNull

    override def write(executor:Executor, df:DataFrame, partition:Map[String,SingleValue], mode:String) : Unit = ???

    override def clean(executor: Executor, partitions: Map[String, FieldValue]): Unit = ???

    /**
      * This method will physically create the corresponding relation. This might be a Hive table or a directory. The
      * relation will not contain any data, but all metadata will be processed
      * @param executor
      */
    override def create(executor:Executor, ifNotExists:Boolean=false) : Unit = {
        logger.info(s"Creating Hive VIEW relation '$name' with table $tableIdentifier")

        executor.catalog.createView(tableIdentifier, sql, ifNotExists)
    }

    /**
      * This will delete any physical representation of the relation. Depending on the type only some meta data like
      * a Hive table might be dropped or also the physical files might be deleted
      * @param executor
      */
    override def destroy(executor:Executor, ifExists:Boolean=false) : Unit = {
        logger.info(s"Destroying Hive VIEW relation '$name' with table $tableIdentifier")

        val catalog = executor.catalog
        if (!ifExists || catalog.tableExists(tableIdentifier)) {
            catalog.dropView(tableIdentifier)
        }
    }

    /**
      * This will update any existing relation to the specified metadata.
      * @param executor
      */
    override def migrate(executor:Executor) : Unit = {
        logger.info(s"Migrating Hive VIEW relation '$name' with table $tableIdentifier")

        executor.catalog.alterView(tableIdentifier, sql)
    }

    private def sql = {
        def castField(name:String, src:FieldType, dst:FieldType) = {
            if (src == dst)
                name
            else
                s"CAST($name AS ${dst.sqlType}) AS ${name}"
        }
        def nullField(name:String, dst:FieldType) = {
            s"CAST(NULL AS ${dst.sqlType}) AS ${name}"
        }

        val relations = input.map(id => context.getRelation(id).asInstanceOf[HiveRelation])

        val selects = relations.map { rel =>
            val srcFields = rel.schema.fields.map(f => f.name.toLowerCase(Locale.ROOT) -> f).toMap
            val srcPartitions = rel.partitions.map(p => p.name.toLowerCase(Locale.ROOT) -> p).toMap
            val schemaFields = schema.fields.map { field =>
                srcFields.get(field.name.toLowerCase(Locale.ROOT))
                    .map(_.ftype)
                    .orElse(srcPartitions.get(field.name.toLowerCase(Locale.ROOT)).map(_.ftype))
                    .map(typ => castField(field.name, typ, field.ftype))
                    .getOrElse(nullField(field.name, field.ftype))
            }
            val partitionFields = partitions.map { part =>
                srcPartitions.get(part.name.toLowerCase(Locale.ROOT))
                    .map(_.ftype)
                    .orElse(srcFields.get(part.name.toLowerCase(Locale.ROOT)).map(_.ftype))
                    .map(typ => castField(part.name, typ, part.ftype))
                    .getOrElse(nullField(part.name, part.ftype))
            }
            val allFields = schemaFields ++ partitionFields

            "SELECT\n" +
                allFields.mkString("    ",",\n    ","\n") +
                "FROM " + rel.tableIdentifier
        }

        selects.mkString("\n\nUNION ALL\n\n")
    }
}



class HiveUnionViewRelationSpec extends RelationSpec with SchemaRelationSpec with PartitionedRelationSpec{
    @JsonProperty(value="database") private var database: String = _
    @JsonProperty(value="view") private var view: String = _
    @JsonProperty(value="input") private var input: Seq[String] = Seq()

    /**
      * Creates the instance of the specified Relation with all variable interpolation being performed
      * @param context
      * @return
      */
    override def instantiate(context: Context): HiveUnionViewRelation = {
        HiveUnionViewRelation(
            instanceProperties(context),
            context.evaluate(database),
            context.evaluate(view),
            input.map(i => RelationIdentifier(context.evaluate(i))),
            Option(schema).map(_.instantiate(context)),
            partitions.map(_.instantiate(context))
        )
    }
}