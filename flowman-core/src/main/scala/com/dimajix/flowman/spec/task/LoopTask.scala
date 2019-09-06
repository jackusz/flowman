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

package com.dimajix.flowman.spec.task

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory

import com.dimajix.flowman.execution.Context
import com.dimajix.flowman.execution.Executor
import com.dimajix.flowman.execution.Status
import com.dimajix.flowman.spec.JobIdentifier
import com.dimajix.flowman.types.ArrayValue
import com.dimajix.flowman.types.FieldValue
import com.dimajix.flowman.types.RangeValue
import com.dimajix.flowman.types.SingleValue


case class LoopTask(
    instanceProperties:Task.Properties,
    job:JobIdentifier,
    args:Map[String,FieldValue],
    force:Boolean = false
) extends BaseTask {
    private val logger = LoggerFactory.getLogger(classOf[LoopTask])

    override def execute(executor:Executor) : Boolean = {
        def interpolate(fn:Map[String,String] => Boolean, param:JobParameter, values:FieldValue) : Map[String,String] => Boolean = {
            val vals = param.ftype.interpolate(values, param.granularity).map(_.toString)
            args:Map[String,String] => vals.forall(v => fn(args + (param.name -> v)))
        }

        val instance = context.getBatch(job)
        val run = (args:Map[String,String]) => {
            logger.info(s"Calling sub-job '$job' with args ${args.map(kv => kv._1 + "=" + kv._2).mkString(", ")}")
            executor.runner.execute(executor, instance, args, force) match {
                case Status.SUCCESS => true
                case Status.SKIPPED => true
                case _ => false
            }
        }

        // Iterate by all parameters and create argument map
        val paramByName = instance.parameters.map(p => (p.name, p)).toMap
        val result = args.toSeq.foldRight(run)((p,a) => interpolate(a, paramByName(p._1), p._2))

        result(Map())
    }
}




class LoopTaskSpec extends TaskSpec {
    @JsonProperty(value = "job", required = true) private var job: String = ""
    @JsonProperty(value = "force") private var force: String = "$force"
    @JsonProperty(value = "args", required = true) private var args: Map[String, FieldValue] = Map()

    override def instantiate(context: Context): LoopTask = {
        LoopTask(
            instanceProperties(context),
            JobIdentifier(context.evaluate(job)),
            args.map {
                case (name,SingleValue(value)) => (name,SingleValue(context.evaluate(value)))
                case (name,ArrayValue(values)) => (name,ArrayValue(values.map(context.evaluate)))
                case (name,RangeValue(start,end,step)) => (name,RangeValue(context.evaluate(start), context.evaluate(end), step.map(context.evaluate)))
            },
            context.evaluate(force).toBoolean
        )
    }
}
