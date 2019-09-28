/*
 * Copyright 2019 Kaya Kupferschmidt
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

package com.dimajix.flowman.execution

import scala.collection.mutable

import org.apache.spark.sql.DataFrame
import org.slf4j.LoggerFactory

import com.dimajix.flowman.spec.MappingIdentifier
import com.dimajix.flowman.spec.MappingOutputIdentifier
import com.dimajix.flowman.spec.ResourceIdentifier
import com.dimajix.flowman.spec.flow.Mapping
import com.dimajix.flowman.types.StructType


object MappingUtils {
    private val logger = LoggerFactory.getLogger(MappingUtils.getClass)

    /**
      * Returns the schema for a specific output created by a specific mapping. Note that not all mappings support
      * schema analysis beforehand. In such cases, None will be returned.
      * @param mapping
      * @param output
      * @return
      */
    def describe(mapping:Mapping, output:String) : Option[StructType] = {
        val schemaCache = mutable.Map[MappingOutputIdentifier, Option[StructType]]()

        def describe(mapping:Mapping, output:String) : Option[StructType] = {
            val oid = MappingOutputIdentifier(mapping.identifier, output)
            schemaCache.getOrElseUpdate(oid, {
                if (!mapping.outputs.contains(output))
                    throw new NoSuchMappingOutputException(oid)
                val context = mapping.context
                val deps = mapping.inputs
                    .flatMap(id => describe(context.getMapping(id.mapping), id.output).map(s => (id,s)))
                    .toMap

                // Only return a schema if all dependencies are present
                if (mapping.inputs.forall(d => deps.contains(d))) {
                    mapping.describe(deps, output)
                }
                else {
                    None
                }
            })
        }

        describe(mapping, output)
    }

    /**
      * Returns the schema for a specific output created by a specific mapping. Note that not all mappings support
      * schema analysis beforehand. In such cases, None will be returned.
      * @param context
      * @param output
      * @return
      */
    def describe(context:Context, output:MappingOutputIdentifier) : Option[StructType] = {
        val mapping = context.getMapping(output.mapping)
        describe(mapping, output.output)
    }


    /**
      * Returns a list of physical resources required for reading this dataset
      * @return
      */
    def requires(mapping: Mapping) : Seq[ResourceIdentifier] = {
        val resourceCache = mutable.Map[MappingIdentifier,Seq[ResourceIdentifier]]()

        def colllect(instance:Mapping) : Unit = {
            resourceCache.getOrElseUpdate(instance.identifier, instance.requires)
            instance.inputs
                .map(in => instance.context.getMapping(in.mapping))
                .foreach(colllect)
        }

        colllect(mapping)
        resourceCache.values.flatten.toSeq.distinct
    }

    /**
      * Returns a list of physical resources required for reading this dataset
      * @return
      */
    def requires(context:Context, mapping: MappingIdentifier) : Seq[ResourceIdentifier] = {
        val instance = context.getMapping(mapping)
        requires(instance)
    }
}