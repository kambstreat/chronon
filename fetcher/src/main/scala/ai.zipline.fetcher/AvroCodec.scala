package ai.zipline.fetcher

import java.io.ByteArrayOutputStream
import ai.zipline.aggregator.base.{
  BinaryType,
  BooleanType,
  DataType,
  DoubleType,
  FloatType,
  IntType,
  ListType,
  LongType,
  MapType,
  StringType,
  StructField,
  StructType
}
import ai.zipline.aggregator.row.Row
import ai.zipline.api.Constants
import org.apache.avro.Schema
import org.apache.avro.Schema.Field
import org.apache.avro.file.SeekableByteArrayInput
import org.apache.avro.generic.GenericData.Record
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{BinaryDecoder, BinaryEncoder, DecoderFactory, EncoderFactory}

import java.nio.ByteBuffer
import scala.collection.JavaConverters._
import scala.collection.mutable
import java.util

class AvroCodec(val schemaStr: String) extends Serializable {
  @transient private lazy val parser = new Schema.Parser()
  @transient private lazy val schema = parser.parse(schemaStr)

  // we reuse a lot of intermediate
  // lazy vals so that spark can serialize & ship the codec to executors
  @transient private lazy val datumWriter = new GenericDatumWriter[GenericRecord](schema)
  @transient private lazy val datumReader = new GenericDatumReader[GenericRecord](schema)

  @transient private lazy val outputStream = new ByteArrayOutputStream()
  val fieldNames: Array[String] = schema.getFields.asScala.map(_.name()).toArray
  private val tsIndex: Int = fieldNames.indexOf(Constants.TimeColumn)
  private val length: Int = fieldNames.length

  @transient private var encoder: BinaryEncoder = null
  @transient private var decoder: BinaryDecoder = null

  def encode(valueMap: Map[String, AnyRef]): Array[Byte] = {
    val record = new GenericData.Record(schema)
    schema.getFields.asScala.foreach { field =>
      record.put(field.name(), valueMap.get(field.name()).orNull)
    }
    encodeRecord(record)
  }

  def encode(row: Row): Array[Byte] = {
    val record = new GenericData.Record(schema)
    for (i <- 0 until row.length) {
      record.put(i, row.get(i))
    }
    encodeRecord(record)
  }

  def encodeArray(anyArray: Array[Any]): Array[Byte] = {
    val record = new GenericData.Record(schema)
    for (i <- anyArray.indices) {
      record.put(i, anyArray(i))
    }
    encodeRecord(record)
  }

  def encodeRecord(record: GenericRecord): Array[Byte] = {
    outputStream.reset()
    encoder = EncoderFactory.get.binaryEncoder(outputStream, encoder)
    datumWriter.write(record, encoder)
    encoder.flush()
    outputStream.flush()
    outputStream.toByteArray
  }

  def decode(bytes: Array[Byte]): GenericRecord = {
    val inputStream = new SeekableByteArrayInput(bytes)
    decoder = DecoderFactory.get.binaryDecoder(inputStream, decoder)
    if (!decoder.isEnd) {
      datumReader.read(null, decoder)
    } else {
      null
    }
  }

  def decodeRow(bytes: Array[Byte]): Row = new GenericRecordRow(decode(bytes), tsIndex, length)

  def decodeMap(bytes: Array[Byte]): Map[String, AnyRef] = {
    val record = decode(bytes)
    fieldNames.indices.map { i =>
      fieldNames(i) -> record.get(i)
    }.toMap
  }
}

// to be consumed by RowAggregator
class GenericRecordRow(record: GenericRecord, tsIndex: Int, fieldCount: Int) extends Row {
  override def get(index: Int): Any = record.get(index)

  override def ts: Long = tsIndex

  override val length: Int = fieldCount
}

object AvroCodec {
  // creating new codecs is expensive - so we want to do it once per process
  // but at the same-time we want to avoid contention across threads - hence threadlocal
  private val codecMap: ThreadLocal[mutable.HashMap[String, AvroCodec]] =
    new ThreadLocal[mutable.HashMap[String, AvroCodec]] {
      override def initialValue(): mutable.HashMap[String, AvroCodec] = new mutable.HashMap[String, AvroCodec]()
    }

  def of(schemaStr: String): AvroCodec = codecMap.get().getOrElseUpdate(schemaStr, new AvroCodec(schemaStr))
}

object AvroUtils {
  def toZiplineSchema(schema: Schema): DataType = {
    schema.getType match {
      case Schema.Type.RECORD =>
        StructType(schema.getName,
                   schema.getFields.asScala.toArray.map { field =>
                     StructField(field.name(), toZiplineSchema(field.schema()))
                   })
      case Schema.Type.ARRAY   => ListType(toZiplineSchema(schema.getElementType))
      case Schema.Type.MAP     => MapType(StringType, toZiplineSchema(schema.getValueType))
      case Schema.Type.STRING  => StringType
      case Schema.Type.INT     => IntType
      case Schema.Type.LONG    => LongType
      case Schema.Type.FLOAT   => FloatType
      case Schema.Type.DOUBLE  => DoubleType
      case Schema.Type.BYTES   => BinaryType
      case Schema.Type.BOOLEAN => BooleanType
      case Schema.Type.UNION   => toZiplineSchema(schema.getTypes.get(1)) // unions are only used to represent nullability
      case _                   => throw new UnsupportedOperationException(s"Cannot convert avro type ${schema.getType.toString}")
    }
  }

  def fromZiplineSchema(dataType: DataType): Schema = {
    dataType match {
      case StructType(name, fields) =>
        assert(name != null)
        Schema.createRecord(
          name,
          "", // doc
          "ai.zipline.data", // nameSpace
          false, // isError
          fields
            .map { ziplineField =>
              val defaultValue: AnyRef = null
              new Field(ziplineField.name,
                        Schema.createUnion(Schema.create(Schema.Type.NULL), fromZiplineSchema(ziplineField.fieldType)),
                        "",
                        defaultValue)
            }
            .toList
            .asJava
        )
      case ListType(elementType) => Schema.createArray(fromZiplineSchema(elementType))
      case MapType(keyType, valueType) => {
        assert(keyType == StringType, s"Avro only supports string keys for a map")
        Schema.createMap(fromZiplineSchema(valueType))
      }
      case StringType  => Schema.create(Schema.Type.STRING)
      case IntType     => Schema.create(Schema.Type.INT)
      case LongType    => Schema.create(Schema.Type.LONG)
      case FloatType   => Schema.create(Schema.Type.FLOAT)
      case DoubleType  => Schema.create(Schema.Type.DOUBLE)
      case BinaryType  => Schema.create(Schema.Type.BYTES)
      case BooleanType => Schema.create(Schema.Type.BOOLEAN)
      case _           => throw new UnsupportedOperationException(s"Cannot convert zipline type $dataType")
    }
  }
}
