/*
 *    Copyright (C) 2023 The Chronon Authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package ai.chronon.spark.test

import org.slf4j.LoggerFactory
import ai.chronon.api.{Constants, StructType}
import ai.chronon.online.{AvroConversions, Mutation, SparkConversions}
import ai.chronon.online.Extensions.StructTypeOps
import ai.chronon.spark.{GenericRowHandler, TableUtils}
import com.esotericsoftware.kryo.Kryo
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.avro.io.{BinaryEncoder, EncoderFactory}
import org.apache.avro.specific.SpecificDatumWriter
import org.apache.commons.io.output.ByteArrayOutputStream
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.GenericRow
import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.execution.streaming.sources.ContinuousMemoryStream
import org.apache.spark.sql.{DataFrame, Dataset, Encoder, Encoders, Row, SparkSession, types}

import java.util.Base64

class InMemoryStream {
  private val logger = LoggerFactory.getLogger(getClass)

  private def encode(schema: org.apache.avro.Schema)(row: Row): Array[Byte] = {
    val gr: GenericRecord = new GenericData.Record(schema)
    row.schema.fieldNames.foreach(name => gr.put(name, row.getAs(name)))

    val writer = new SpecificDatumWriter[GenericRecord](schema)
    val out = new ByteArrayOutputStream()
    val encoder: BinaryEncoder = EncoderFactory.get().binaryEncoder(out, null)
    writer.write(gr, encoder)
    encoder.flush()
    out.close()
    out.toByteArray
  }

  private def encodeRecord(schema: org.apache.avro.Schema)(genericRecord: GenericData.Record): Array[Byte] = {
    val writer = new SpecificDatumWriter[GenericRecord](schema)
    val out = new ByteArrayOutputStream()
    val encoder: BinaryEncoder = EncoderFactory.get().binaryEncoder(out, null)
    writer.write(genericRecord, encoder)
    encoder.flush()
    out.close()
    out.toByteArray
  }

  // encode input as avro byte array and insert into memory stream.
  def getInMemoryStreamDF(spark: SparkSession, inputDf: Dataset[Row]): DataFrame = {
    val schema: StructType = StructType.from("input", SparkConversions.toChrononSchema(inputDf.schema))
    logger.info(s"Creating in-memory stream with schema: ${SparkConversions.fromChrononSchema(schema).catalogString}")
    val avroSchema = AvroConversions.fromChrononSchema(schema)
    import spark.implicits._
    val input: MemoryStream[Array[Byte]] =
      new MemoryStream[Array[Byte]](inputDf.schema.catalogString.hashCode % 1000, spark.sqlContext)
    input.addData(inputDf.collect.map { row: Row =>
      val bytes = encode(avroSchema)(row)
      bytes
    })
    input.toDF
  }

  def getContinuousStreamDF(spark: SparkSession, baseInput: Dataset[Row]): DataFrame = {
    // align schema
    val noDs = baseInput.drop(TableUtils(spark).partitionColumn)
    val mutationColumns = Constants.MutationFields.map(_.name)
    val fields = noDs.schema.fieldNames
    val baseFields = fields.filterNot(mutationColumns.contains)
    val mutationFields = mutationColumns.filter(fields.contains)
    val inputDf = noDs.selectExpr(baseFields ++ mutationFields: _*)

    // encode and write
    logger.info(s"encoding stream with schema: ${inputDf.schema.catalogString}")
    inputDf.show()
    val schema: StructType = StructType.from("input", SparkConversions.toChrononSchema(inputDf.schema))
    val avroSchema = AvroConversions.fromChrononSchema(schema)

    import spark.implicits._
    val input: MemoryStream[Array[Byte]] =
      new MemoryStream[Array[Byte]](inputDf.schema.catalogString.hashCode % 1000, spark.sqlContext)
    input.addData(inputDf.collect.map { row: Row =>
      val bytes =
        encodeRecord(avroSchema)(
          AvroConversions.fromChrononRow(row, schema, GenericRowHandler.func).asInstanceOf[GenericData.Record])
      bytes
    })
    input.toDF
  }
}
