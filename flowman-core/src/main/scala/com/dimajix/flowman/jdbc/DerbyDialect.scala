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

import com.dimajix.flowman.types.BooleanType
import com.dimajix.flowman.types.ByteType
import com.dimajix.flowman.types.DecimalType
import com.dimajix.flowman.types.FieldType
import com.dimajix.flowman.types.ShortType
import com.dimajix.flowman.types.StringType


object DerbyDialect extends BaseDialect {
    private object Statements extends DerbyStatements(this)

    override def canHandle(url: String): Boolean = url.startsWith("jdbc:derby")

    /**
      * Quotes the identifier. This is used to put quotes around the identifier in case the column
      * name is a reserved keyword, or in case it contains characters that require quotes (e.g. space).
      */
    override def quoteIdentifier(colName: String): String = {
        s"""$colName"""
    }

    override def getJdbcType(dt: FieldType): Option[JdbcType] = dt match {
        case StringType => Option(JdbcType("CLOB", java.sql.Types.CLOB))
        case ByteType => Option(JdbcType("SMALLINT", java.sql.Types.SMALLINT))
        case ShortType => Option(JdbcType("SMALLINT", java.sql.Types.SMALLINT))
        case BooleanType => Option(JdbcType("BOOLEAN", java.sql.Types.BOOLEAN))
        // 31 is the maximum precision and 5 is the default scale for a Derby DECIMAL
        case t: DecimalType if t.precision > 31 =>
            Option(JdbcType("DECIMAL(31,5)", java.sql.Types.DECIMAL))
        case _ => super.getJdbcType(dt)
    }

    override def statement : SqlStatements = Statements
}


class DerbyStatements(dialect: BaseDialect) extends BaseStatements(dialect)  {
    override def firstRow(table: TableIdentifier, condition:String) : String = {
        if (condition.isEmpty)
            s"SELECT * FROM ${dialect.quote(table)} FETCH FIRST ROW ONLY"
        else
            s"SELECT * FROM ${dialect.quote(table)} WHERE $condition FETCH FIRST ROW ONLY"
    }
}
