/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.collection

import java.io.ObjectOutputStream
import java.nio.ByteBuffer
import java.sql.DriverManager
import java.util.TimeZone

import scala.annotation.tailrec
import scala.collection.{mutable, Map => SMap}
import scala.language.existentials
import scala.reflect.ClassTag
import scala.util.Sorting
import scala.util.control.NonFatal

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, KryoSerializable}
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException
import io.snappydata.collection.ObjectObjectHashMap
import io.snappydata.{Constant, ToolsCallback}
import org.apache.commons.math3.distribution.NormalDistribution

import org.apache.spark._
import org.apache.spark.io.CompressionCodec
import org.apache.spark.memory.TaskMemoryManager
import org.apache.spark.rdd.RDD
import org.apache.spark.scheduler.TaskLocation
import org.apache.spark.scheduler.local.LocalSchedulerBackend
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenContext
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, GenericRow, UnsafeRow}
import org.apache.spark.sql.catalyst.json.{JSONOptions, JacksonGenerator, JacksonUtils}
import org.apache.spark.sql.catalyst.plans.logical.{LocalRelation, LogicalPlan}
import org.apache.spark.sql.catalyst.plans.physical.{Partitioning, PartitioningCollection}
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow, analysis}
import org.apache.spark.sql.execution.datasources.jdbc.{DriverRegistry, DriverWrapper}
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.hive.SnappyStoreHiveCatalog
import org.apache.spark.sql.sources.CastLongTime
import org.apache.spark.sql.types._
import org.apache.spark.storage.{BlockId, BlockManager, BlockManagerId}
import org.apache.spark.unsafe.Platform
import org.apache.spark.util.AccumulatorV2
import org.apache.spark.util.collection.BitSet
import org.apache.spark.util.io.ChunkedByteBuffer

object Utils {

  final val WEIGHTAGE_COLUMN_NAME = "SNAPPY_SAMPLER_WEIGHTAGE"
  final val SKIP_ANALYSIS_PREFIX = "SAMPLE_"
  private final val TASKCONTEXT_FUNCTION = "getTaskContextFromTSS"

  // 1 - (1 - 0.95) / 2 = 0.975
  final val Z95Percent: Double = new NormalDistribution().
      inverseCumulativeProbability(0.975)
  final val Z95Squared: Double = Z95Percent * Z95Percent

  def fillArray[T](a: Array[_ >: T], v: T, start: Int, endP1: Int): Unit = {
    var index = start
    while (index < endP1) {
      a(index) = v
      index += 1
    }
  }

  def analysisException(msg: String,
      cause: Option[Throwable] = None): AnalysisException =
    new AnalysisException(msg, None, None, None, cause)

  def columnIndex(col: String, cols: Array[String], module: String): Int = {
    val colT = toUpperCase(col.trim)
    cols.indices.collectFirst {
      case index if col == cols(index) => index
      case index if colT == toUpperCase(cols(index)) => index
    }.getOrElse {
      throw analysisException(
        s"""$module: Cannot resolve column name "$col" among
            (${cols.mkString(", ")})""")
    }
  }

  def fieldName(f: StructField): String = {
    if (f.metadata.contains("name")) f.metadata.getString("name") else f.name
  }

  def fieldIndex(relationOutput: Seq[Attribute], columnName: String,
      caseSensitive: Boolean): Int = {
    // lookup as per case-sensitivity (SNAP-1714)
    val resolver = if (caseSensitive) analysis.caseSensitiveResolution
    else analysis.caseInsensitiveResolution
    LocalRelation(relationOutput).resolveQuoted(columnName, resolver) match {
      case Some(a) => relationOutput.indexWhere(_.semanticEquals(a))
      case None => throw new IllegalArgumentException(
        s"""Field "$columnName" does not exist in "$relationOutput".""")
    }
  }

  def getAllExecutorsMemoryStatus(
      sc: SparkContext): Map[BlockManagerId, (Long, Long)] = {
    val memoryStatus = sc.env.blockManager.master.getMemoryStatus
    // no filtering for local backend
    if (isLoner(sc)) memoryStatus else memoryStatus.filter(!_._1.isDriver)
  }

  def getHostExecutorId(blockId: BlockManagerId): String =
    TaskLocation.executorLocationTag + blockId.host + '_' + blockId.executorId

  def classForName(className: String): Class[_] =
    org.apache.spark.util.Utils.classForName(className)

  def ERROR_NO_QCS(module: String): String = s"$module: QCS is empty"

  def qcsOf(qa: Array[String], cols: Array[String],
      module: String): (Array[Int], Array[String]) = {
    val colIndexes = qa.map(columnIndex(_, cols, module))
    Sorting.quickSort(colIndexes)
    (colIndexes, colIndexes.map(cols))
  }

  def resolveQCS(qcsV: Option[Any], fieldNames: Array[String],
      module: String): (Array[Int], Array[String]) = {
    qcsV.map {
      case qi: Array[Int] => (qi, qi.map(fieldNames))
      case qs: String =>
        if (qs.isEmpty) throw analysisException(ERROR_NO_QCS(module))
        else qcsOf(qs.split(","), fieldNames, module)
      case qa: Array[String] => qcsOf(qa, fieldNames, module)
      case q => throw analysisException(
        s"$module: Cannot parse 'qcs'='$q'")
    }.getOrElse(throw analysisException(ERROR_NO_QCS(module)))
  }

  def matchOption(optName: String,
      options: SMap[String, Any]): Option[(String, Any)] = {
    val optionName = toLowerCase(optName)
    options.get(optionName).map((optionName, _)).orElse {
      options.collectFirst { case (key, value)
        if toLowerCase(key) == optionName => (key, value)
      }
    }
  }

  def resolveQCS(options: SMap[String, Any], fieldNames: Array[String],
      module: String): (Array[Int], Array[String]) = {
    resolveQCS(matchOption("qcs", options).map(_._2), fieldNames, module)
  }

  def parseInteger(v: Any, module: String, option: String, min: Int = 1,
      max: Int = Int.MaxValue): Int = {
    val vl: Long = v match {
      case vi: Int => vi
      case vs: String =>
        try {
          vs.toLong
        } catch {
          case _: NumberFormatException => throw analysisException(
            s"$module: Cannot parse int '$option' from string '$vs'")
        }
      case vl: Long => vl
      case vs: Short => vs
      case vb: Byte => vb
      case _ => throw analysisException(
        s"$module: Cannot parse int '$option'=$v")
    }
    if (vl >= min && vl <= max) {
      vl.toInt
    } else {
      throw analysisException(
        s"$module: Integer value outside of bounds [$min-$max] '$option'=$vl")
    }
  }

  def parseDouble(v: Any, module: String, option: String, min: Double,
      max: Double, exclusive: Boolean = true): Double = {
    val vd: Double = v match {
      case vd: Double => vd
      case vs: String =>
        try {
          vs.toDouble
        } catch {
          case _: NumberFormatException => throw analysisException(
            s"$module: Cannot parse double '$option' from string '$vs'")
        }
      case vf: Float => vf.toDouble
      case vi: Int => vi.toDouble
      case vl: Long => vl.toDouble
      case vn: Number => vn.doubleValue()
      case _ => throw analysisException(
        s"$module: Cannot parse double '$option'=$v")
    }
    if (exclusive) {
      if (vd > min && vd < max) {
        vd
      } else {
        throw analysisException(
          s"$module: Double value outside of bounds ($min-$max) '$option'=$vd")
      }
    }
    else if (vd >= min && vd <= max) {
      vd
    } else {
      throw analysisException(
        s"$module: Double value outside of bounds [$min-$max] '$option'=$vd")
    }
  }

  def parseColumn(cv: Any, cols: Array[String], module: String,
      option: String): Int = {
    val cl: Long = cv match {
      case cs: String => columnIndex(cs, cols, module)
      case ci: Int => ci
      case cl: Long => cl
      case cs: Short => cs
      case cb: Byte => cb
      case _ => throw analysisException(
        s"$module: Cannot parse '$option'=$cv")
    }
    if (cl >= 0 && cl < cols.length) {
      cl.toInt
    } else {
      throw analysisException(s"$module: Column index out of bounds " +
          s"for '$option'=$cl among ${cols.mkString(", ")}")
    }
  }

  /** string specification for time intervals */
  private final val timeIntervalSpec = "([0-9]+)(ms|s|m|h)".r

  /**
    * Parse the given time interval value as long milliseconds.
    *
    * @see timeIntervalSpec for the allowed string specification
    */
  def parseTimeInterval(optV: Any, module: String): Long = {
    optV match {
      case tii: Int => tii.toLong
      case til: Long => til
      case tis: String => tis match {
        case timeIntervalSpec(interval, unit) =>
          unit match {
            case "ms" => interval.toLong
            case "s" => interval.toLong * 1000L
            case "m" => interval.toLong * 60000L
            case "h" => interval.toLong * 3600000L
            case _ => throw new AssertionError(
              s"unexpected regex match 'unit'=$unit")
          }
        case _ => throw analysisException(
          s"$module: Cannot parse 'timeInterval': $tis")
      }
      case _ => throw analysisException(
        s"$module: Cannot parse 'timeInterval': $optV")
    }
  }

  def parseTimestamp(ts: String, module: String, col: String): Long = {
    try {
      ts.toLong
    } catch {
      case _: NumberFormatException =>
        try {
          CastLongTime.getMillis(java.sql.Timestamp.valueOf(ts))
        } catch {
          case _: IllegalArgumentException =>
            throw analysisException(
              s"$module: Cannot parse timestamp '$col'=$ts")
        }
    }
  }

  def mapExecutors[T: ClassTag](sqlContext: SQLContext,
      f: () => Iterator[T]): RDD[T] = {
    val sc = sqlContext.sparkContext
    val cleanedF = sc.clean(f)
    new ExecutorLocalRDD[T](sc,
      (_: TaskContext, _: ExecutorLocalPartition) => cleanedF())
  }

  def mapExecutors[T: ClassTag](sc: SparkContext,
      f: (TaskContext, ExecutorLocalPartition) => Iterator[T]): RDD[T] = {
    val cleanedF = sc.clean(f)
    new ExecutorLocalRDD[T](sc, cleanedF)
  }

  def getFixedPartitionRDD[T: ClassTag](sc: SparkContext,
      f: (TaskContext, Partition) => Iterator[T], partitioner: Partitioner,
      numPartitions: Int): RDD[T] = {
    val cleanedF = sc.clean(f)
    new FixedPartitionRDD[T](sc, cleanedF, numPartitions, Some(partitioner))
  }

  def getPartitionData(blockId: BlockId, bm: BlockManager): ByteBuffer = {
    bm.getLocalBytes(blockId) match {
      case Some(block) => try {
        block.toByteBuffer
      } finally {
        bm.releaseLock(blockId)
      }
      case None => throw new GemFireXDRuntimeException(
        s"SparkSQLExecuteImpl: getPartitionData() block $blockId not found")
    }
  }

  def getInternalType(dataType: DataType): Class[_] = {
    dataType match {
      case ByteType => classOf[Byte]
      case IntegerType => classOf[Int]
      case LongType => classOf[Long]
      case FloatType => classOf[Float]
      case DoubleType => classOf[Double]
      case StringType => classOf[String]
      case DateType => classOf[Int]
      case TimestampType => classOf[Long]
      case _: DecimalType => classOf[Decimal]
      // case "binary" => org.apache.spark.sql.types.BinaryType
      // case "raw" => org.apache.spark.sql.types.BinaryType
      // case "logical" => org.apache.spark.sql.types.BooleanType
      // case "boolean" => org.apache.spark.sql.types.BooleanType
      case _ => throw new IllegalArgumentException(s"Invalid type $dataType")
    }
  }

  @tailrec
  def getSQLDataType(dataType: DataType): DataType = dataType match {
    case udt: UserDefinedType[_] => getSQLDataType(udt.sqlType)
    case _ => dataType
  }

  def getClientHostPort(netServer: String): String = {
    val addrIdx = netServer.indexOf('/')
    val portEndIndex = netServer.indexOf(']')
    if (addrIdx > 0) {
      val portIndex = netServer.indexOf('[')
      netServer.substring(0, addrIdx) +
          netServer.substring(portIndex, portEndIndex + 1)
    } else {
      netServer.substring(addrIdx + 1, portEndIndex + 1)
    }
  }

  final def isLoner(sc: SparkContext): Boolean =
    (sc ne null) && sc.schedulerBackend.isInstanceOf[LocalSchedulerBackend]

  def parseColumnsAsClob(s: String): (Boolean, Set[String]) = {
    if (s.trim.equals("*")) {
      (true, Set.empty[String])
    } else {
      (false, s.toUpperCase.split(',').toSet)
    }
  }

  def hasLowerCase(k: String): Boolean = {
    var index = 0
    val len = k.length
    while (index < len) {
      if (Character.isLowerCase(k.charAt(index))) {
        return true
      }
      index += 1
    }
    false
  }

  def toLowerCase(k: String): String = k.toLowerCase(java.util.Locale.ENGLISH)

  def toUpperCase(k: String): String = k.toUpperCase(java.util.Locale.ENGLISH)

  /**
   * Utility function to return a metadata for a StructField of StringType, to ensure that the
   * field is stored (and rendered) as VARCHAR by SnappyStore.
   *
   * @return the result Metadata object to use for StructField
   */
  def varcharMetadata(): Metadata = {
    varcharMetadata(Constant.MAX_VARCHAR_SIZE, Metadata.empty)
  }

  /**
   * Utility function to return a metadata for a StructField of StringType, to ensure that the
   * field is stored (and rendered) as VARCHAR by SnappyStore.
   *
   * @param size the size parameter of the VARCHAR() column type
   * @return the result Metadata object to use for StructField
   */
  def varcharMetadata(size: Int): Metadata = {
    varcharMetadata(size, Metadata.empty)
  }

  /**
   * Utility function to return a metadata for a StructField of StringType, to ensure that the
   * field is stored (and rendered) as VARCHAR by SnappyStore.
   *
   * @param size the size parameter of the VARCHAR() column type
   * @param md optional Metadata object to be merged into the result
   * @return the result Metadata object to use for StructField
   */
  def varcharMetadata(size: Int, md: Metadata): Metadata = {
    if (size < 1 || size > Constant.MAX_VARCHAR_SIZE) {
      throw new IllegalArgumentException(s"VARCHAR size should be between 1 " +
          s"and ${Constant.MAX_VARCHAR_SIZE}")
    }
    new MetadataBuilder().withMetadata(md).putString(Constant.CHAR_TYPE_BASE_PROP, "VARCHAR")
        .putLong(Constant.CHAR_TYPE_SIZE_PROP, size).build()
  }

  /**
   * Utility function to return a metadata for a StructField of StringType, to ensure that the
   * field is stored (and rendered) as CHAR by SnappyStore.
   *
   * @return the result Metadata object to use for StructField
   */
  def charMetadata(): Metadata = {
    charMetadata(Constant.MAX_CHAR_SIZE, Metadata.empty)
  }

  /**
   * Utility function to return a metadata for a StructField of StringType, to ensure that the
   * field is stored (and rendered) as CHAR by SnappyStore.
   *
   * @param size the size parameter of the CHAR() column type
   * @return the result Metadata object to use for StructField
   */
  def charMetadata(size: Int): Metadata = {
    charMetadata(size, Metadata.empty)
  }

  /**
   * Utility function to return a metadata for a StructField of StringType, to ensure that the
   * field is stored (and rendered) as CHAR by SnappyStore.
   *
   * @param size the size parameter of the CHAR() column type
   * @param md optional Metadata object to be merged into the result
   * @return the result Metadata object to use for StructField
   */
  def charMetadata(size: Int, md: Metadata): Metadata = {
    if (size < 1 || size > Constant.MAX_CHAR_SIZE) {
      throw new IllegalArgumentException(s"CHAR size should be between 1 " +
          s"and ${Constant.MAX_CHAR_SIZE}")
    }
    new MetadataBuilder().withMetadata(md).putString(Constant.CHAR_TYPE_BASE_PROP, "CHAR")
        .putLong(Constant.CHAR_TYPE_SIZE_PROP, size).build()
  }

  /**
   * Utility function to return a metadata for a StructField of StringType, to ensure that the
   * field is rendered as CLOB by SnappyStore.
   *
   * @param md optional Metadata object to be merged into the result
   * @return the result Metadata object to use for StructField
   */
  def stringMetadata(md: Metadata = Metadata.empty): Metadata = {
    // Put BASE as 'CLOB' so that SnappyStoreHiveCatalog.normalizeSchema() removes these
    // CHAR_TYPE* properties from the metadata. This enables SparkSQLExecuteImpl.getSQLType() to
    // render this field as CLOB.
    // If we don't add this property here, SnappyStoreHiveCatalog.normalizeSchema() will add one
    // on its own and this field would be rendered as VARCHAR.
    new MetadataBuilder().withMetadata(md).putString(Constant.CHAR_TYPE_BASE_PROP, "CLOB")
        .remove(Constant.CHAR_TYPE_SIZE_PROP).build()
  }

  def schemaFields(schema: StructType): Map[String, StructField] = {
    Map(schema.fields.flatMap { f =>
      val name = if (f.metadata.contains("name")) f.metadata.getString("name")
      else f.name
      Iterator((name, f))
    }: _*)
  }

  def getFields(o: Any): Map[String, Any] = {
    val fieldsAsPairs = for (field <- o.getClass.getDeclaredFields) yield {
      field.setAccessible(true)
      (field.getName, field.get(o))
    }
    Map(fieldsAsPairs: _*)
  }

  /**
    * Get the result schema given an optional explicit schema and base table.
    * In case both are specified, then check compatibility between the two.
    */
  def getSchemaAndPlanFromBase(schemaOpt: Option[StructType],
      baseTableOpt: Option[String], catalog: SnappyStoreHiveCatalog,
      asSelect: Boolean, table: String,
      tableType: String): (StructType, Option[LogicalPlan]) = {
    schemaOpt match {
      case Some(s) => baseTableOpt match {
        case Some(baseTableName) =>
          // if both baseTable and schema have been specified, then both
          // should have matching schema
          try {
            val tablePlan = catalog.lookupRelation(
              catalog.newQualifiedTableName(baseTableName))
            val tableSchema = tablePlan.schema
            if (catalog.compatibleSchema(tableSchema, s)) {
              (s, Some(tablePlan))
            } else if (asSelect) {
              throw analysisException(s"CREATE $tableType TABLE AS SELECT:" +
                  " mismatch of schema with that of base table." +
                  "\n  Base table schema: " + tableSchema +
                  "\n  AS SELECT schema: " + s)
            } else {
              throw analysisException(s"CREATE $tableType TABLE:" +
                  " mismatch of specified schema with that of base table." +
                  "\n  Base table schema: " + tableSchema +
                  "\n  Specified schema: " + s)
            }
          } catch {
            // continue with specified schema if base table fails to load
            case _: AnalysisException => (s, None)
          }
        case None => (s, None)
      }
      case None => baseTableOpt match {
        case Some(baseTable) =>
          try {
            // parquet and other such external tables may have different
            // schema representation so normalize the schema
            val tablePlan = catalog.lookupRelation(
              catalog.newQualifiedTableName(baseTable))
            (catalog.normalizeSchema(tablePlan.schema), Some(tablePlan))
          } catch {
            case ae: AnalysisException =>
              throw analysisException(s"Base table $baseTable " +
                  s"not found for $tableType TABLE $table", Some(ae))
          }
        case None =>
          throw analysisException(s"CREATE $tableType TABLE must provide " +
              "either column definition or baseTable option.")
      }
    }
  }

  def getDriverClassName(url: String): String = DriverManager.getDriver(url) match {
    case wrapper: DriverWrapper => wrapper.wrapped.getClass.getCanonicalName
    case driver => driver.getClass.getCanonicalName
  }

  /**
    * Register given driver class with Spark's loader.
    */
  def registerDriver(driver: String): Unit = {
    try {
      DriverRegistry.register(driver)
    } catch {
      case cnfe: ClassNotFoundException => throw new IllegalArgumentException(
        s"Couldn't find driver class $driver", cnfe)
    }
  }

  /**
    * Register driver for given JDBC URL and return the driver class name.
    */
  def registerDriverUrl(url: String): String = {
    val driver = getDriverClassName(url)
    registerDriver(driver)
    driver
  }

  /**
   * Wrap a DataFrame action to track all Spark jobs in the body so that
   * we can connect them with an execution.
   */
  def withNewExecutionId[T](df: DataFrame, body: => T): T = {
    df.withNewExecutionId(body)
  }

  def immutableMap[A, B](m: mutable.Map[A, B]): Map[A, B] = new Map[A, B] {

    private[this] val map = m

    override def size: Int = map.size

    override def -(elem: A): Map[A, B] = {
      if (map.contains(elem)) {
        val builder = Map.newBuilder[A, B]
        for (pair <- map) if (pair._1 != elem) {
          builder += pair
        }
        builder.result()
      } else this
    }

    override def +[B1 >: B](kv: (A, B1)): Map[A, B1] = {
      val builder = Map.newBuilder[A, B1]
      val newKey = kv._1
      for (pair <- map) if (pair._1 != newKey) {
        builder += pair
      }
      builder += kv
      builder.result()
    }

    override def iterator: Iterator[(A, B)] = map.iterator

    override def foreach[U](f: ((A, B)) => U): Unit = map.foreach(f)

    override def get(key: A): Option[B] = map.get(key)
  }

  def toOpenHashMap[K, V](map: scala.collection.Map[K, V]): ObjectObjectHashMap[K, V] = {
    val m = ObjectObjectHashMap.withExpectedSize[K, V](map.size)
    map.foreach(p => m.put(p._1, p._2))
    m
  }

  def createScalaConverter(dataType: DataType): Any => Any =
    CatalystTypeConverters.createToScalaConverter(dataType)

  def createCatalystConverter(dataType: DataType): Any => Any =
    CatalystTypeConverters.createToCatalystConverter(dataType)

  // we should use the exact day as Int, for example, (year, month, day) -> day
  def millisToDays(millisUtc: Long, tz: TimeZone): Int = {
    // SPARK-6785: use Math.floor so negative number of days (dates before 1970)
    // will correctly work as input for function toJavaDate(Int)
    val millisLocal = millisUtc + tz.getOffset(millisUtc)
    Math.floor(millisLocal.toDouble / DateTimeUtils.MILLIS_PER_DAY).toInt
  }

  def getGenericRowValues(row: GenericRow): Array[Any] = row.values

  def newChunkedByteBuffer(chunks: Array[ByteBuffer]): ChunkedByteBuffer =
    new ChunkedByteBuffer(chunks)

  def setDefaultConfProperty(conf: SparkConf, name: String,
      default: String): Unit = {
    conf.getOption(name) match {
      case None =>
        // set both in configuration and as System property for all
        // confs created on the fly
        conf.set(name, default)
        System.setProperty(name, default)
      case _ =>
    }
  }

  def setDefaultSerializerAndCodec(conf: SparkConf): Unit = {
    // enable optimized pooled Kryo serializer by default
    setDefaultConfProperty(conf, "spark.serializer",
      Constant.DEFAULT_SERIALIZER)
    setDefaultConfProperty(conf, "spark.closure.serializer",
      Constant.DEFAULT_SERIALIZER)
    if (Constant.DEFAULT_CODEC != CompressionCodec.DEFAULT_COMPRESSION_CODEC) {
      setDefaultConfProperty(conf, "spark.io.compression.codec",
        Constant.DEFAULT_CODEC)
    }
  }

  def clearDefaultSerializerAndCodec(): Unit = {
    System.clearProperty("spark.serializer")
    System.clearProperty("spark.closure.serializer")
    System.clearProperty("spark.io.compression.codec")
  }

  lazy val usingEnhancedSpark: Boolean = {
    try {
      classOf[SQLMetric].getMethod("longValue")
      true
    } catch {
      case NonFatal(_) => false
    }
  }

  def metricMethods: (String => String, String => String) = {
    if (usingEnhancedSpark) {
      (v => s"addLong($v)", v => s"$v.longValue()")
    } else {
      (v => s"add($v)",
          // explicit cast for value to Object is for janino bug
          v => s"(Long)((${classOf[AccumulatorV2[_, _]].getName})$v).value()")
    }
  }

  def getJsonGenerator(dataType: DataType, columnName: String,
      writer: java.io.Writer): AnyRef = {
    val schema = StructType(Seq(StructField(columnName, dataType)))
    JacksonUtils.verifySchema(schema)
    new JacksonGenerator(schema, writer, new JSONOptions(Map.empty[String, String]))
  }

  def generateJson(gen: AnyRef, row: InternalRow, columnIndex: Int,
      columnType: DataType): Unit = {
    val generator = gen.asInstanceOf[JacksonGenerator]
    generator.write(InternalRow(row.get(columnIndex, columnType)))
    generator.flush()
  }

  def closeJsonGenerator(gen: AnyRef): Unit = gen.asInstanceOf[JacksonGenerator].close()

  def getNumColumns(partitioning: Partitioning): Int = partitioning match {
    case c: PartitioningCollection =>
      math.max(1, c.partitionings.map(getNumColumns).min)
    case e: Expression => e.children.length
    case _ => 1
  }

  def taskMemoryManager(context: TaskContext): TaskMemoryManager =
    context.taskMemoryManager()

  def toUnsafeRow(buffer: ByteBuffer, numColumns: Int): UnsafeRow = {
    val row = new UnsafeRow(numColumns)
    if (buffer.isDirect) {
      row.pointTo(null, UnsafeHolder.getDirectBufferAddress(buffer) +
          buffer.position(), buffer.remaining())
    } else {
      row.pointTo(buffer.array(), Platform.BYTE_ARRAY_OFFSET +
          buffer.arrayOffset() + buffer.position(), buffer.remaining())
    }
    row
  }

  def genTaskContextFunction(ctx: CodegenContext): String = {
    // use common taskContext variable so it is obtained only once for a plan
    if (!ctx.addedFunctions.contains(TASKCONTEXT_FUNCTION)) {
      val taskContextVar = ctx.freshName("taskContext")
      val contextClass = classOf[TaskContext].getName
      ctx.addMutableState(contextClass, taskContextVar, "")
      ctx.addNewFunction(TASKCONTEXT_FUNCTION,
        s"""
           |private $contextClass $TASKCONTEXT_FUNCTION() {
           |  final $contextClass context = $taskContextVar;
           |  if (context != null) return context;
           |  return ($taskContextVar = $contextClass.get());
           |}
        """.stripMargin)
    }
    TASKCONTEXT_FUNCTION
  }
}

class ExecutorLocalRDD[T: ClassTag](_sc: SparkContext,
    f: (TaskContext, ExecutorLocalPartition) => Iterator[T])
    extends RDD[T](_sc, Nil) {

  override def getPartitions: Array[Partition] = {
    val numberedPeers = Utils.getAllExecutorsMemoryStatus(sparkContext).
        keySet.toList.zipWithIndex

    if (numberedPeers.nonEmpty) {
      numberedPeers.map {
        case (bid, idx) => createPartition(idx, bid)
      }.toArray[Partition]
    } else {
      Array.empty[Partition]
    }
  }

  def createPartition(index: Int,
      blockId: BlockManagerId): ExecutorLocalPartition =
    new ExecutorLocalPartition(index, blockId)

  override def getPreferredLocations(split: Partition): Seq[String] =
    Seq(split.asInstanceOf[ExecutorLocalPartition].hostExecutorId)

  override def compute(split: Partition, context: TaskContext): Iterator[T] = {
    val part = split.asInstanceOf[ExecutorLocalPartition]
    val thisBlockId = SparkEnv.get.blockManager.blockManagerId
    if (part.blockId.host != thisBlockId.host ||
        part.blockId.executorId != thisBlockId.executorId) {
      // kill the task and force a retry
      val msg = s"Unexpected execution of $part on $thisBlockId"
      logWarning(msg)
      if (context.attemptNumber() < 10) {
        throw new TaskKilledException
      } else {
        // fail after many retries (other executor is likely gone)
        throw new IllegalStateException(msg)
      }
    }

    f(context, part)
  }
}

class FixedPartitionRDD[T: ClassTag](_sc: SparkContext,
    f: (TaskContext, Partition) => Iterator[T], numPartitions: Int,
    override val partitioner: Option[Partitioner])
    extends RDD[T](_sc, Nil) {

  override def getPartitions: Array[Partition] = {
    var i = 0

    Array.fill[Partition](numPartitions)({
      val x = i
      i += 1
      new Partition() {
        override def index: Int = x
      }
    })
  }

  override def compute(split: Partition, context: TaskContext): Iterator[T] = {
    f(context, split)
  }
}

class ExecutorLocalPartition(override val index: Int,
    val blockId: BlockManagerId) extends Partition {

  def hostExecutorId: String = Utils.getHostExecutorId(blockId)

  override def toString: String = s"ExecutorLocalPartition($index, $blockId)"
}

final class MultiBucketExecutorPartition(private[this] var _index: Int,
    _buckets: mutable.ArrayBuffer[Int], numBuckets: Int,
    private[this] var _hostExecutorIds: Seq[String])
    extends Partition with KryoSerializable {

  private[this] var bucketSet = {
    if (_buckets ne null) {
      val set = new BitSet(numBuckets)
      for (b <- _buckets) {
        set.set(b)
      }
      set
    } else {
      // replicated region case
      new BitSet(0)
    }
  }

  override def index: Int = _index

  def buckets: java.util.Set[Integer] = new java.util.AbstractSet[Integer] {

    override def contains(o: Any): Boolean = o match {
      case b: Int => b >= 0 && bucketSet.get(b)
      case _ => false
    }

    override def iterator() = new java.util.Iterator[Integer] {
      private[this] var bucket = bucketSet.nextSetBit(0)

      override def hasNext: Boolean = bucket >= 0
      override def next(): Integer = {
        val b = Int.box(bucket)
        bucket = bucketSet.nextSetBit(bucket + 1)
        b
      }
      override def remove(): Unit = throw new UnsupportedOperationException
    }

    override def size(): Int = bucketSet.cardinality()
  }

  def bucketsString: String = {
    val sb = new StringBuilder
    val bucketSet = this.bucketSet
    var bucket = bucketSet.nextSetBit(0)
    while (bucket >= 0) {
      sb.append(bucket).append(',')
      bucket = bucketSet.nextSetBit(bucket + 1)
    }
    // trim trailing comma
    if (sb.nonEmpty) sb.setLength(sb.length - 1)
    sb.toString()
  }

  def hostExecutorIds: Seq[String] = _hostExecutorIds

  override def write(kryo: Kryo, output: Output): Unit = {
    output.writeVarInt(_index, true)
    kryo.writeClassAndObject(output, bucketSet)
    output.writeVarInt(_hostExecutorIds.length, true)
    for (executor <- _hostExecutorIds) {
      output.writeString(executor)
    }
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    _index = input.readVarInt(true)
    bucketSet = kryo.readClassAndObject(input).asInstanceOf[BitSet]
    var numExecutors = input.readVarInt(true)
    val executorBuilder = Seq.newBuilder[String]
    while (numExecutors > 0) {
      executorBuilder += input.readString()
      numExecutors -= 1
    }
    _hostExecutorIds = executorBuilder.result()
  }

  override def toString: String = s"MultiBucketExecutorPartition(" +
      s"$index, buckets=[$bucketsString], ${_hostExecutorIds.mkString(",")})"
}


private[spark] case class NarrowExecutorLocalSplitDep(
    @transient rdd: RDD[_],
    @transient splitIndex: Int,
    private var split: Partition) extends Serializable with KryoSerializable {

  // noinspection ScalaUnusedSymbol
  @throws[java.io.IOException]
  private def writeObject(oos: ObjectOutputStream): Unit =
    org.apache.spark.util.Utils.tryOrIOException {
      // Update the reference to parent split at the time of task serialization
      split = rdd.partitions(splitIndex)
      oos.defaultWriteObject()
    }

  override def write(kryo: Kryo, output: Output): Unit =
    org.apache.spark.util.Utils.tryOrIOException {
      // Update the reference to parent split at the time of task serialization
      split = rdd.partitions(splitIndex)
      kryo.writeClassAndObject(output, split)
    }

  override def read(kryo: Kryo, input: Input): Unit = {
    split = kryo.readClassAndObject(input).asInstanceOf[Partition]
  }
}

/**
  * Stores information about the narrow dependencies used by a StoreRDD.
  *
  * @param narrowDep maps to the dependencies variable in the parent RDD:
  *                  for each one to one dependency in dependencies,
  *                  narrowDeps has a NarrowExecutorLocalSplitDep (describing
  *                  the partition for that dependency) at the corresponding
  *                  index. The size of narrowDeps should always be equal to
  *                  the number of parents.
  */
private[spark] class CoGroupExecutorLocalPartition(
    idx: Int, val blockId: BlockManagerId,
    val narrowDep: Option[NarrowExecutorLocalSplitDep])
    extends Partition with Serializable {

  override val index: Int = idx

  def hostExecutorId: String = Utils.getHostExecutorId(blockId)

  override def toString: String =
    s"CoGroupExecutorLocalPartition($index, $blockId)"

  override def hashCode(): Int = idx
}

final class SmartExecutorBucketPartition(private var _index: Int,
    var hostList: mutable.ArrayBuffer[(String, String)])
    extends Partition with KryoSerializable {

  override def index: Int = _index

  override def write(kryo: Kryo, output: Output): Unit = {
    output.writeVarInt(_index, true)
    val numHosts = hostList.length
    output.writeVarInt(numHosts, true)
    for ((host, url) <- hostList) {
      output.writeString(host)
      output.writeString(url)
    }
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    _index = input.readVarInt(true)
    val numHosts = input.readVarInt(true)
    hostList = new mutable.ArrayBuffer[(String, String)](numHosts)
    for (_ <- 0 until numHosts) {
      val host = input.readString()
      val url = input.readString()
      hostList += host -> url
    }
  }

  override def toString: String =
    s"SmartExecutorBucketPartition($index, $hostList)"
}

object ToolsCallbackInit extends Logging {
  final val toolsCallback: ToolsCallback = {
    try {
      val c = org.apache.spark.util.Utils.classForName(
        "io.snappydata.ToolsCallbackImpl$")
      val tc = c.getField("MODULE$").get(null).asInstanceOf[ToolsCallback]
      logInfo("toolsCallback initialized")
      tc
    } catch {
      case _: ClassNotFoundException =>
        logWarning("toolsCallback couldn't be INITIALIZED." +
            "DriverURL won't get published to others.")
        null
    }
  }
}

object OrderlessHashPartitioningExtract {
  def unapply(partitioning: Partitioning): Option[(Seq[Expression],
      Seq[Seq[Attribute]], Int, Int, Int)] = {
    val callbacks = ToolsCallbackInit.toolsCallback
    if (callbacks ne null) {
      callbacks.checkOrderlessHashPartitioning(partitioning)
    } else None
  }
}
