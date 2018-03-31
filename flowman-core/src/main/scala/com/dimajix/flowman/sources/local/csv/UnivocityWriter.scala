package com.dimajix.flowman.sources.local.csv

import java.io.Closeable
import java.io.Writer

import com.univocity.parsers.csv.CsvWriter
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types.BooleanType
import org.apache.spark.sql.types.ByteType
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.types.DateType
import org.apache.spark.sql.types.DecimalType
import org.apache.spark.sql.types.DoubleType
import org.apache.spark.sql.types.FloatType
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.types.LongType
import org.apache.spark.sql.types.ShortType
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.types.TimestampType


class UnivocityWriter(schema: StructType, writer:Writer, options:CsvOptions) extends Closeable {
    private type ValueConverter = (Row, Int) => String

    private val settings = CsvUtils.createWriterSettings(options)
    settings.setHeaders(schema.fieldNames: _*)
    private val gen = new CsvWriter(writer, settings)

    // `ValueConverter`s for all values in the fields of the schema
    private val valueConverters: Array[ValueConverter] =
        schema.map(_.dataType).map(makeConverter).toArray

    def writeHeader(): Unit = {
        gen.writeHeaders()
    }
    def writeRow(row:Row) : Unit = {
        gen.writeRow(convertRow(row): _*)
    }

    override def close(): Unit = gen.close()

    private def makeConverter(dataType: DataType): ValueConverter = dataType match {
        case DateType =>
            (row: Row, ordinal: Int) =>
                options.dateFormat.format(DateTimeUtils.toJavaDate(row.getInt(ordinal)))
        case TimestampType =>
            (row: Row, ordinal: Int) =>
                options.timestampFormat.format(DateTimeUtils.toJavaTimestamp(row.getLong(ordinal)))
        case BooleanType =>
            (row: Row, ordinal: Int) =>
                row.getBoolean(ordinal).toString
        case BooleanType =>
            (row: Row, ordinal: Int) =>
                row.getBoolean(ordinal).toString
        case ByteType =>
            (row: Row, ordinal: Int) =>
                row.getByte(ordinal).toString
        case ShortType =>
            (row: Row, ordinal: Int) =>
                row.getShort(ordinal).toString
        case IntegerType =>
            (row: Row, ordinal: Int) =>
                row.getInt(ordinal).toString
        case LongType =>
            (row: Row, ordinal: Int) =>
                row.getLong(ordinal).toString
        case FloatType =>
            (row: Row, ordinal: Int) =>
                row.getFloat(ordinal).toString
        case DoubleType =>
            (row: Row, ordinal: Int) =>
                row.getDouble(ordinal).toString
        case StringType =>
            (row: Row, ordinal: Int) =>
                row.getString(ordinal)
        case dt: DecimalType =>
            (row: Row, ordinal: Int) =>
                row.getDecimal(ordinal).toString
        case _ =>
            throw new UnsupportedOperationException(s"Cannot write type ${dataType.typeName} to csv")
    }

    private def convertRow(row: Row): Seq[String] = {
        (0 until row.length).map(i =>
            if (!row.isNullAt(i))
                valueConverters(i)(row,i)
            else
                options.nullValue
        )
    }
}