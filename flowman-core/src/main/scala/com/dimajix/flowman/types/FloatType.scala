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

package com.dimajix.flowman.types

import org.apache.spark.sql.types.DataType


case object FloatType extends FieldType {
    override def sparkType : DataType = org.apache.spark.sql.types.FloatType

    override def parse(value:String, granularity: String) : Float = {
        if (granularity != null && granularity.nonEmpty) {
            val step = granularity.toFloat
            val v = value.toFloat
            v - (v % step)
        }
        else {
            value.toFloat
        }
    }
    override def interpolate(value: FieldValue, granularity:String) : Iterable[Float] = {
        value match {
            case SingleValue(v) => Seq(parse(v, granularity))
            case ArrayValue(values) => values.map(v => parse(v,granularity))
            case RangeValue(start,end,step) => {
                if (step != null && step.nonEmpty) {
                    val range = start.toFloat.until(end.toFloat).by(step.toFloat)
                    if (granularity != null && granularity.nonEmpty) {
                        val mod = granularity.toFloat
                        range.map(x => math.floor(x / mod) * mod).map(_.toFloat).distinct
                    }
                    else {
                        range
                    }
                }
                else if (granularity != null && granularity.nonEmpty) {
                    val mod = granularity.toFloat
                    start.toFloat.until(end.toFloat).by(mod).map(x => math.floor(x / mod) * mod).map(_.toFloat)
                }
                else {
                    start.toFloat.until(end.toFloat).by(1.0f)
                }
            }
        }
    }
}