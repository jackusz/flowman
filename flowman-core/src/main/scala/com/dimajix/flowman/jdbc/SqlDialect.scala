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

package com.dimajix.flowman.jdbc

import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.jdbc.JdbcType

import com.dimajix.flowman.types.FieldType


abstract class SqlDialect {
    /**
      * Check if this dialect instance can handle a certain jdbc url.
      * @param url the jdbc url.
      * @return True if the dialect can be applied on the given jdbc url.
      * @throws NullPointerException if the url is null.
      */
    def canHandle(url : String): Boolean

    /**
      * Retrieve the jdbc / sql type for a given datatype.
      * @param dt The datatype (e.g. [[org.apache.spark.sql.types.StringType]])
      * @return The new JdbcType if there is an override for this DataType
      */
    def getJdbcType(dt: FieldType): Option[JdbcType]

    /**
      * Quotes the identifier. This is used to put quotes around the identifier in case the column
      * name is a reserved keyword, or in case it contains characters that require quotes (e.g. space).
      */
    def quoteIdentifier(colName: String): String

    /**
      * Quotes a table name including the optional database prefix
      * @param table
      * @return
      */
    def quote(table:TableIdentifier) : String

    /**
      * Escapes a String literal to be used in SQL statements
      * @param value
      * @return
      */
    def escape(value: String): String

    /**
      * Creates an SQL literal from a given value
      * @param value
      * @return
      */
    def literal(value:Any) : String

    def statement : SqlStatements

    def expr : SqlExpressions
}
