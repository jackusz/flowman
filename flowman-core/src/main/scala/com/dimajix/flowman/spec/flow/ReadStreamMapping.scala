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

package com.dimajix.flowman.spec.flow

import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.spark.sql.DataFrame
import org.slf4j.LoggerFactory

import com.dimajix.flowman.execution.Context
import com.dimajix.flowman.execution.Executor
import com.dimajix.flowman.spec.RelationIdentifier
import com.dimajix.flowman.spec.MappingIdentifier
import com.dimajix.flowman.types.ArrayValue
import com.dimajix.flowman.types.FieldValue
import com.dimajix.flowman.types.RangeValue
import com.dimajix.flowman.types.SingleValue
import com.dimajix.flowman.util.SchemaUtils


class ReadStreamMapping extends BaseMapping {
    private val logger = LoggerFactory.getLogger(classOf[ReadStreamMapping])

    @JsonProperty(value = "source", required = true) private var _source:String = _
    @JsonProperty(value = "columns", required=false) private var _columns:Map[String,String] = _

    def source(implicit context:Context) : RelationIdentifier = RelationIdentifier.parse(context.evaluate(_source))
    def columns(implicit context:Context) : Map[String,String] = if (_columns != null) _columns.mapValues(context.evaluate) else null

    /**
      * Executes this Transform by reading from the specified source and returns a corresponding DataFrame
      *
      * @param executor
      * @param input
      * @return
      */
    override def execute(executor:Executor, input:Map[MappingIdentifier,DataFrame]): DataFrame = {
        implicit val context = executor.context
        val source = this.source
        val fields = this.columns
        val relation = context.getRelation(source)
        val schema = if (fields != null && fields.nonEmpty) SchemaUtils.createSchema(fields.toSeq) else null
        logger.info(s"Reading from streaming relation '$source'")

        relation.readStream(executor, schema)
    }

    /**
      * Returns the dependencies of this mapping, which are empty for an InputMapping
      *
      * @param context
      * @return
      */
    override def dependencies(implicit context:Context) : Array[MappingIdentifier] = {
        Array()
    }
}
