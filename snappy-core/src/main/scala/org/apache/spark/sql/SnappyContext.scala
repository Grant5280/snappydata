package org.apache.spark.sql

import java.sql.Connection


import org.apache.spark.sql.aqp.{AQPDefault, AQPContext}
import org.apache.spark.sql.columnar.{ExternalStoreUtils, CachedBatch, InMemoryAppendableRelation, ExternalStoreRelation}
import org.apache.spark.sql.execution.{LogicalRDD, TopK, SparkPlan, ConnectionPool, ExtractPythonUDFs}

import org.apache.spark.sql.row.GemFireXDDialect
import org.apache.spark.sql.sources.{JdbcExtendedUtils, IndexableRelation, DestroyRelation, UpdatableRelation,
RowInsertableRelation, DeletableRelation}


import org.apache.spark.util.ShutdownHookManager

import scala.collection.mutable
import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag
import scala.reflect.runtime.{universe => u}

import io.snappydata.{Constant, Property}
import org.apache.spark.sql.{execution => sparkexecution}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.LockUtils.ReadWriteLock

import org.apache.spark.sql.catalyst.analysis.Analyzer
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression}
import org.apache.spark.sql.catalyst.planning.PhysicalOperation
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Subquery}
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow, ScalaReflection}
import org.apache.spark.sql.collection.{ToolsCallbackInit, UUIDRegionKey, Utils}


import org.apache.spark.sql.execution.datasources.{LogicalRelation, ResolvedDataSource}


import org.apache.spark.sql.hive.{ExternalTableType, QualifiedTableName, SnappyStoreHiveCatalog}

import org.apache.spark.sql.row.GemFireXDDialect
import org.apache.spark.sql.snappy.RDDExtensions
//import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.{LongType, StructField, StructType}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.{StreamingContext, Time}
import org.apache.spark.{Logging, SparkContext, SparkException, TaskContext}

import org.apache.spark.sql.{execution => sparkexecution}


import scala.util.{Failure, Success, Try}

/**
  * An instance of the Spark SQL execution engine that delegates to supplied
  * SQLContext offering additional capabilities.
  *
  * Created by Soubhik on 5/13/15.
  */
class SnappyContext private(sc: SparkContext)
    extends SQLContext(sc) with Serializable with Logging {

  self =>

  val aqpContext = Try {

    val mirror = u.runtimeMirror(getClass.getClassLoader)
    val cls = mirror.classSymbol(Class.forName(SnappyContext.aqpContextImplClass))
    val module = cls.companionSymbol.asModule
    mirror.reflectModule(module).instance.asInstanceOf[AQPContext]
  }  match {
    case Success(v) => v
    case Failure(_) => AQPDefault
  }

  // initialize GemFireXDDialect so that it gets registered

  GemFireXDDialect.init()
  GlobalSnappyInit.initGlobalSnappyContext(sc)

  @transient
  override protected[sql] val ddlParser = new SnappyDDLParser(sqlParser.parse)


  override protected[sql] def dialectClassName = if (conf.dialect == "sql") {
    this.aqpContext.getSQLDialectClassName
  } else {
    conf.dialect
  }
  override protected[sql] def executePlan(plan: LogicalPlan) =   aqpContext.executePlan(this, plan)




  @transient
  override lazy val catalog = this.aqpContext.getSnappyCatalogue(this)

  @transient
  override protected[sql] val cacheManager =  this.aqpContext.getSnappyCacheManager(self)




  /**
   * Append to an existing cache table.
   * Automatically uses #cacheQuery if not done already.
   */
  def appendToCache(df: DataFrame, table: String,
                    storageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK) = {
    val useCompression = conf.useCompression
    val columnBatchSize = conf.columnBatchSize

    val tableIdent = catalog.newQualifiedTableName(table)
    val plan = catalog.lookupRelation(tableIdent, None)
    val relation = cacheManager.lookupCachedData(plan).getOrElse {
      cacheManager.cacheQuery(DataFrame(self, plan),
        Some(tableIdent.table), storageLevel)

      cacheManager.lookupCachedData(plan).getOrElse {
        sys.error(s"couldn't cache table $tableIdent")
      }
    }

    val (schema, output) = (df.schema, df.logicalPlan.output)

    val cached = df.rdd.mapPartitionsPreserve { rowIterator =>

      val batches = ExternalStoreRelation(useCompression, columnBatchSize,
        tableIdent, schema, relation.cachedRepresentation, output)

      val converter = CatalystTypeConverters.createToCatalystConverter(schema)

      rowIterator.map(converter(_).asInstanceOf[InternalRow])
        .foreach(batches.appendRow((), _))
      batches.forceEndOfBatch().iterator
    }.persist(storageLevel)

    // trigger an Action to materialize 'cached' batch
    if (cached.count() > 0) {
      relation.cachedRepresentation match {
        case externalStore: ExternalStoreRelation =>
          externalStore.appendUUIDBatch(cached.asInstanceOf[RDD[UUIDRegionKey]])
        case appendable: InMemoryAppendableRelation =>
          appendable.appendBatch(cached.asInstanceOf[RDD[CachedBatch]])
      }
    }
  }



  def appendToCacheRDD(rdd: RDD[_], table: String, schema: StructType,
      storageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK) {
    val useCompression = conf.useCompression
    val columnBatchSize = conf.columnBatchSize

    val tableIdent = catalog.newQualifiedTableName(table)
    val plan = catalog.lookupRelation(tableIdent, None)
    val relation = cacheManager.lookupCachedData(plan).getOrElse {
      cacheManager.cacheQuery(DataFrame(this, plan),
        Some(tableIdent.table), storageLevel)

      cacheManager.lookupCachedData(plan).getOrElse {
        sys.error(s"couldn't cache table $tableIdent")
      }
    }

    val cached = rdd.mapPartitionsPreserve { rowIterator =>

      val batches = ExternalStoreRelation(useCompression, columnBatchSize,
        tableIdent, schema, relation.cachedRepresentation, schema.toAttributes)

      val converter = CatalystTypeConverters.createToCatalystConverter(schema)
      rowIterator.map(converter(_).asInstanceOf[InternalRow])
          .foreach(batches.appendRow((), _))
      batches.forceEndOfBatch().iterator
    }.persist(storageLevel)

    // trigger an Action to materialize 'cached' batch
    if (cached.count() > 0) {
      relation.cachedRepresentation match {
        case externalStore: ExternalStoreRelation =>
          externalStore.appendUUIDBatch(cached.asInstanceOf[RDD[UUIDRegionKey]])
        case appendable: InMemoryAppendableRelation =>
          appendable.appendBatch(cached.asInstanceOf[RDD[CachedBatch]])
      }
    }
  }

  def truncateTable(tableName: String): Unit = {
    cacheManager.lookupCachedData(catalog.lookupRelation(
      tableName)).foreach(_.cachedRepresentation.
        asInstanceOf[InMemoryAppendableRelation].truncate())
  }

  def truncateExternalTable(tableName: String): Unit = {
    val qualifiedTable = catalog.newQualifiedTableName(tableName)
    val plan = catalog.lookupRelation(qualifiedTable, None)
    snappy.unwrapSubquery(plan) match {
      case LogicalRelation(br) =>
        cacheManager.tryUncacheQuery(DataFrame(self, plan))
        br match {
          case d: DestroyRelation => d.truncate()
        }
      case _ => throw new AnalysisException(
        s"truncateExternalTable: Table $tableName not an external table")
    }
  }

  def registerTable[A <: Product : u.TypeTag](tableName: String): Unit = {
    if (u.typeOf[A] =:= u.typeOf[Nothing]) {
      sys.error("Type of case class object not mentioned. " +
          "Mention type information for e.g. registerSampleTableOn[<class>]")
    }

    SparkPlan.currentContext.set(self)
    val schema = ScalaReflection.schemaFor[A].dataType
        .asInstanceOf[StructType]

    val plan: LogicalRDD = LogicalRDD(schema.toAttributes,
      new DummyRDD(self))(self)

    catalog.registerTable(Seq(tableName), plan)
  }

  def registerSampleTable(tableName: String, schema: StructType,
      samplingOptions: Map[String, Any], streamTable: Option[String] = None,
      jdbcSource: Option[Map[String, String]] = None): SampleDataFrame =
       aqpContext.registerSampleTable(self,  tableName, schema,
         samplingOptions, streamTable,
       jdbcSource)



  def registerSampleTableOn[A <: Product : u.TypeTag](tableName: String,
      samplingOptions: Map[String, Any], streamTable: Option[String] = None,
      jdbcSource: Option[Map[String, String]] = None): DataFrame =
      aqpContext.registerSampleTableOn(self, tableName,
        samplingOptions, streamTable,
      jdbcSource)


  def registerTopK(tableName: String, streamTableName: String,
      topkOptions: Map[String, Any], isStreamSummary: Boolean): Unit =
      aqpContext.registerTopK(self, tableName, streamTableName,
        topkOptions, isStreamSummary)

  override def createExternalTable(
      tableName: String,
      provider: String,
      options: Map[String, String]): DataFrame = {
    val plan = createTable(catalog.newQualifiedTableName(tableName), provider,
      userSpecifiedSchema = None, schemaDDL = None,
      SaveMode.ErrorIfExists, options)
    DataFrame(self, plan)
  }

  override def createExternalTable(
      tableName: String,
      provider: String,
      schema: StructType,
      options: Map[String, String]): DataFrame = {
    val plan = createTable(catalog.newQualifiedTableName(tableName), provider,
      Some(schema), schemaDDL = None, SaveMode.ErrorIfExists, options)
    DataFrame(self, plan)
  }

  /**
    * Create an external table with given options.
    */
  private[sql] def createTable(
      tableIdent: QualifiedTableName,
      provider: String,
      userSpecifiedSchema: Option[StructType],
      schemaDDL: Option[String],
      mode: SaveMode,
      options: Map[String, String]): LogicalPlan = {

    if (catalog.tableExists(tableIdent)) {
      mode match {
        case SaveMode.ErrorIfExists =>
          throw new AnalysisException(
            s"createExternalTable: Table $tableIdent already exists.")
        case _ =>
          return catalog.lookupRelation(tableIdent, None)
      }
    }

    // add tableName in properties if not already present
    val dbtableProp = JdbcExtendedUtils.DBTABLE_PROPERTY
    val params = if (options.keysIterator.exists(_.equalsIgnoreCase(
      dbtableProp))) {
      options
    }
    else {
      options + (dbtableProp -> tableIdent.toString)
    }

    val source = SnappyContext.getProvider(provider)
    val resolved = schemaDDL match {
      case Some(schema) => JdbcExtendedUtils.externalResolvedDataSource(self,
        schema, source, mode, params)

      case None =>
        // add allowExisting in properties used by some implementations
        ResolvedDataSource(self, userSpecifiedSchema, Array.empty[String],
          source, params + (JdbcExtendedUtils.ALLOW_EXISTING_PROPERTY ->
              (mode != SaveMode.ErrorIfExists).toString))
    }

    catalog.registerExternalTable(tableIdent, userSpecifiedSchema,
      Array.empty[String], source, params,
      ExternalTableType.getTableType(resolved.relation))
    LogicalRelation(resolved.relation)
  }

  /**
    * Create an external table with given options.
    */
  private[sql] def createTable(
      tableIdent: QualifiedTableName,
      provider: String,
      partitionColumns: Array[String],
      mode: SaveMode,
      options: Map[String, String],
      query: LogicalPlan): LogicalPlan = {

    var data = DataFrame(self, query)
    if (catalog.tableExists(tableIdent)) {
      mode match {
        case SaveMode.ErrorIfExists =>
          throw new AnalysisException(s"Table $tableIdent already exists. " +
              "If using SQL CREATE TABLE, you need to use the " +
              s"APPEND or OVERWRITE mode, or drop $tableIdent first.")
        case _ =>
          // existing table schema could have nullable columns
          val schema = data.schema
          if (schema.exists(!_.nullable)) {
            data = internalCreateDataFrame(data.queryExecution.toRdd,
              schema.asNullable)
          }
      }
    }

    // add tableName in properties if not already present
    val dbtableProp = JdbcExtendedUtils.DBTABLE_PROPERTY
    val params = if (options.keysIterator.exists(_.equalsIgnoreCase(
      dbtableProp))) {
      options
    }
    else {
      options + (dbtableProp -> tableIdent.toString)
    }

    // this gives the provider..

    val source = SnappyContext.getProvider(provider)
    val resolved = ResolvedDataSource(self, source, partitionColumns,
      mode, params, data)

    if (catalog.tableExists(tableIdent) && mode == SaveMode.Overwrite) {
      // uncache the previous results and don't register again
      cacheManager.tryUncacheQuery(data)
    }
    else {
      catalog.registerExternalTable(tableIdent, Some(data.schema),
        partitionColumns, source, params, ExternalTableType.getTableType(resolved.relation))
    }
    LogicalRelation(resolved.relation)
  }

  /**
    * Drop an external table created by a call to createExternalTable.
    */
  def dropExternalTable(tableName: String, ifExists: Boolean = false): Unit = {
    val qualifiedTable = catalog.newQualifiedTableName(tableName)
    val plan = try {
      catalog.lookupRelation(qualifiedTable, None)
    } catch {
      case ae: AnalysisException =>
        if (ifExists) return else throw ae
    }
    // additional cleanup for external tables, if required
    snappy.unwrapSubquery(plan) match {
      case LogicalRelation(br) =>
        cacheManager.tryUncacheQuery(DataFrame(self, plan))
        catalog.unregisterExternalTable(qualifiedTable)
        br match {
          case d: DestroyRelation => d.destroy(ifExists)
        }
      case _ => throw new AnalysisException(
        s"dropExternalTable: Table $tableName not an external table")
    }
  }

  /**
   * Create Index on an external table (created by a call to createExternalTable).
   */
  def createIndexOnExternalTable(tableName: String, sql: String): Unit = {
    //println("create-index" + " tablename=" + tableName    + " ,sql=" + sql)

    if (!catalog.tableExists(tableName)) {
      throw new AnalysisException(
        s"$tableName is not an indexable table")
    }

    val qualifiedTable = catalog.newQualifiedTableName(tableName)
    //println("qualifiedTable=" + qualifiedTable)
    snappy.unwrapSubquery(catalog.lookupRelation(qualifiedTable, None)) match {
      case LogicalRelation(i: IndexableRelation) =>
        i.createIndex(tableName, sql)
      case _ => throw new AnalysisException(
        s"$tableName is not an indexable table")
    }
  }

  /**
   * Create Index on an external table (created by a call to createExternalTable).
   */
  def dropIndexOnExternalTable(sql: String): Unit = {
    //println("drop-index" + " sql=" + sql)

    var conn: Connection = null
    try {
      val (url, _, _, connProps, _) =
        ExternalStoreUtils.validateAndGetAllProps(sc, new mutable.HashMap[String, String])
      conn = ExternalStoreUtils.getConnection(url, connProps)
      JdbcExtendedUtils.executeUpdate(sql, conn)
    } catch {
      case sqle: java.sql.SQLException =>
        if (sqle.getMessage.contains("No suitable driver found")) {
          throw new AnalysisException(s"${sqle.getMessage}\n" +
            "Ensure that the 'driver' option is set appropriately and " +
            "the driver jars available (--jars option in spark-submit).")
        } else {
          throw sqle
        }
    } finally {
      if (conn != null) {
        conn.close()
      }
    }
  }

  /**
   * Drop a temporary table.
   */
  def dropTempTable(tableName: String, ifExists: Boolean = false): Unit = {
    val qualifiedTable = catalog.newQualifiedTableName(tableName)
    val plan = try {
      catalog.lookupRelation(qualifiedTable, None)
    } catch {
      case ae: AnalysisException =>
        if (ifExists) return else throw ae
    }
    cacheManager.tryUncacheQuery(DataFrame(self, plan))
    catalog.unregisterTable(qualifiedTable)
  }

  // insert/update/delete operations on an external table

  def insert(tableName: String, rows: Row*): Int = {
    catalog.lookupRelation(tableName) match {
      case LogicalRelation(r: RowInsertableRelation) => r.insert(rows)
      case _ => throw new AnalysisException(
        s"$tableName is not a row insertable table")
    }
  }

  def update(tableName: String, filterExpr: String, newColumnValues: Row,
      updateColumns: String*): Int = {
    catalog.lookupRelation(tableName) match {
      case LogicalRelation(u: UpdatableRelation) =>
        u.update(filterExpr, newColumnValues, updateColumns)
      case _ => throw new AnalysisException(
        s"$tableName is not an updatable table")
    }
  }

  def delete(tableName: String, filterExpr: String): Int = {
    catalog.lookupRelation(tableName) match {
      case LogicalRelation(d: DeletableRelation) => d.delete(filterExpr)
      case _ => throw new AnalysisException(
        s"$tableName is not a deletable table")
    }
  }

  // end of insert/update/delete operations


  @transient
  override protected[sql] lazy val analyzer: Analyzer =
    new Analyzer(catalog, functionRegistry, conf) {
      override val extendedResolutionRules =
        ExtractPythonUDFs ::
            sparkexecution.datasources.PreInsertCastAndRename ::
         //   ReplaceWithSampleTable ::
          //  WeightageRule ::
            //TestRule::
            Nil

      override val extendedCheckRules = Seq(
        sparkexecution.datasources.PreWriteCheck(catalog))
    }

  @transient
  override protected[sql] val planner = this.aqpContext.getPlanner(this)




  /**
    * Queries the topK structure between two points in time. If the specified
    * time lies between a topK interval the whole interval is considered
    *
    * @param topKName - The topK structure that is to be queried.
    * @param startTime start time as string of the format "yyyy-mm-dd hh:mm:ss".
    *                  If passed as null, oldest interval is considered as the start interval.
    * @param endTime  end time as string of the format "yyyy-mm-dd hh:mm:ss".
    *                 If passed as null, newest interval is considered as the last interval.
    * @param k Optional. Number of elements to be queried.
    *          This is to be passed only for stream summary
    * @return returns the top K elements with their respective frequencies between two time
    */
  def queryTopK[T: ClassTag](topKName: String,
      startTime: String = null, endTime: String = null,
      k: Int = -1): DataFrame =
      aqpContext.queryTopK[T](this, topKName,
        startTime, endTime, k)


  def queryTopK[T: ClassTag](topKName: String,
      startTime: Long, endTime: Long): DataFrame =
    queryTopK[T](topKName, startTime, endTime, -1)

  def queryTopK[T: ClassTag](topK: String,
      startTime: Long, endTime: Long, k: Int): DataFrame =
    aqpContext.queryTopK[T](this, topK, startTime, endTime, k)

  private var storeConfig: Map[String, String] = _

  def setExternalStoreConfig(conf: Map[String, String]): Unit = {
    self.storeConfig = conf
  }

  def getExternalStoreConfig: Map[String, String] = {
    storeConfig
  }
}

// scalastyle:off
object snappy extends Serializable {
  // scalastyle:on

  implicit def snappyOperationsOnDataFrame(df: DataFrame): SnappyOperations = {
    df.sqlContext match {
      case sc: SnappyContext => SnappyOperations(sc, df)
      case sc => throw new AnalysisException("Extended snappy operations " +
          s"require SnappyContext and not ${sc.getClass.getSimpleName}")
    }
  }

  def unwrapSubquery(plan: LogicalPlan): LogicalPlan = {
    plan match {
      case Subquery(_, child) => unwrapSubquery(child)
      case _ => plan
    }
  }


  implicit def snappyOperationsOnDStream[T: ClassTag](
      ds: DStream[T]): SnappyDStreamOperations[T] =
    SnappyDStreamOperations(SnappyContext(ds.context.sparkContext), ds)

  implicit class SparkContextOperations(val s: SparkContext) {
    def getOrCreateStreamingContext(batchInterval: Int = 2): StreamingContext = {
      StreamingCtxtHolder(s, batchInterval)
    }
  }

  implicit class RDDExtensions[T: ClassTag](rdd: RDD[T]) extends Serializable {

    /**
      * Return a new RDD by applying a function to all elements of this RDD.
      */
    def mapPreserve[U: ClassTag](f: T => U): RDD[U] = rdd.withScope {
      val cleanF = rdd.sparkContext.clean(f)
      new MapPartitionsPreserveRDD[U, T](rdd,
        (context, pid, iter) => iter.map(cleanF))
    }

    /**
      * Return a new RDD by applying a function to each partition of given RDD.
      * This variant also preserves the preferred locations of parent RDD.
      *
      * `preservesPartitioning` indicates whether the input function preserves
      * the partitioner, which should be `false` unless this is a pair RDD and
      * the input function doesn't modify the keys.
      */
    def mapPartitionsPreserve[U: ClassTag](
        f: Iterator[T] => Iterator[U],
        preservesPartitioning: Boolean = false): RDD[U] = rdd.withScope {
      val cleanedF = rdd.sparkContext.clean(f)
      new MapPartitionsPreserveRDD(rdd,
        (context: TaskContext, index: Int, iter: Iterator[T]) => cleanedF(iter),
        preservesPartitioning)
    }
  }

}

object GlobalSnappyInit {
  @volatile private[this] var _globalSNContextInitialized: Boolean = false
  private[this] val contextLock = new AnyRef

  private[sql] def initGlobalSnappyContext(sc: SparkContext) = {
    if (!_globalSNContextInitialized ) {
      contextLock.synchronized {
        if (!_globalSNContextInitialized) {
          invokeServices(sc)
          _globalSNContextInitialized = true
        }
      }
    }
  }

  private[sql] def  resetGlobalSNContext:Unit = _globalSNContextInitialized=false

  private def invokeServices(sc: SparkContext): Unit = {
    SnappyContext.getClusterMode(sc) match {
      case SnappyEmbeddedMode(_, _) =>
        // NOTE: if Property.jobServer.enabled is true
        // this will trigger SnappyContext.apply() method
        // prior to `new SnappyContext(sc)` after this
        // method ends.
        ToolsCallbackInit.toolsCallback.invokeLeadStartAddonService(sc)
      case SnappyShellMode(_, _) =>
        ToolsCallbackInit.toolsCallback.invokeStartFabricServer(sc,
          hostData = false)
      case ExternalEmbeddedMode(_, url) =>
        SnappyContext.urlToConf(url, sc)
        ToolsCallbackInit.toolsCallback.invokeStartFabricServer(sc,
          hostData = false)
      case LocalMode(_, url) =>
        SnappyContext.urlToConf(url, sc)
        ToolsCallbackInit.toolsCallback.invokeStartFabricServer(sc,
          hostData = true)
      case _ => // ignore
    }
  }

}

object SnappyContext extends Logging {

  @volatile private[this] var _anySNContext: SnappyContext = _
  @volatile private[this] var _clusterMode: ClusterMode = _

  private val aqpContextImplClass = "org.apache.spark.sql.execution.AQPContextImpl"

  var SnappySC:SnappyContext = null

  private[this] val contextLock = new AnyRef




  private val builtinSources = Map(
    "jdbc" -> classOf[row.DefaultSource].getCanonicalName,
    "row" -> "org.apache.spark.sql.rowtable.DefaultSource",
    "column" -> classOf[columnar.DefaultSource].getCanonicalName
  )

  def globalSparkContext: SparkContext = SparkContext.activeContext.get()

  private def newSnappyContext(sc: SparkContext) = {
    val snc = new SnappyContext(sc)
    // No need to synchronize. any occurrence would do
    if (_anySNContext == null) {
      _anySNContext = snc
    }
    snc
  }

  def apply(): SnappyContext = {
    val gc = globalSparkContext
    if (gc != null) {
      newSnappyContext(gc)
    } else {
      null
    }
  }

  def apply(sc: SparkContext): SnappyContext = {
    if (sc != null) {
      newSnappyContext(sc)
    } else {
      apply()
    }
  }

  def getOrCreate(sc: SparkContext): SnappyContext = {
    val gnc = _anySNContext
    if (gnc != null) gnc
    else contextLock.synchronized {
      val gnc = _anySNContext
      if (gnc != null) gnc
      else {
        apply(sc)
      }
    }
  }


  def urlToConf(url: String, sc: SparkContext): Unit = {
    val propValues = url.split(';')
    propValues.foreach { s =>
      val propValue = s.split('=')
      // propValue should always give proper result since the string
      // is created internally by evalClusterMode
      sc.conf.set(Constant.STORE_PROPERTY_PREFIX + propValue(0),
        propValue(1))
    }
  }

  def getClusterMode(sc: SparkContext): ClusterMode = {
    val mode = _clusterMode
    if ((mode != null && mode.sc == sc) || sc == null) {
      mode
    } else if (mode != null) {
      evalClusterMode(sc)
    } else contextLock.synchronized {
      val mode = _clusterMode
      if ((mode != null && mode.sc == sc) || sc == null) {
        mode
      } else if (mode != null) {
        evalClusterMode(sc)
      } else {
        _clusterMode = evalClusterMode(sc)
        _clusterMode
      }
    }
  }

  private def evalClusterMode(sc: SparkContext): ClusterMode = {
    if (sc.master.startsWith(Constant.JDBC_URL_PREFIX)) {
      if (ToolsCallbackInit.toolsCallback == null) {
        throw new SparkException(
          "Missing 'io.snappydata.ToolsCallbackImpl$' from SnappyData tools package")
      }
      SnappyEmbeddedMode(sc,
        sc.master.substring(Constant.JDBC_URL_PREFIX.length))
    } else if (ToolsCallbackInit.toolsCallback != null) {
      val conf = sc.conf
      val local = Utils.isLoner(sc)
      val embedded = conf.getOption(Property.embedded).exists(_.toBoolean)
      conf.getOption(Property.locators).collectFirst {
        case s if !s.isEmpty =>
          val url = "locators=" + s + ";mcast-port=0"
          if (local) LocalMode(sc, url)
          else if (embedded) ExternalEmbeddedMode(sc, url)
          else SnappyShellMode(sc, url)
      }.orElse(conf.getOption(Property.mcastPort).collectFirst {
        case s if s.toInt > 0 =>
          val url = "mcast-port=" + s
          if (local) LocalMode(sc, url)
          else if (embedded) ExternalEmbeddedMode(sc, url)
          else SnappyShellMode(sc, url)
      }).getOrElse {
        if (local) LocalMode(sc, "mcast-port=0")
        else ExternalClusterMode(sc, sc.master)
      }
    } else {
      ExternalClusterMode(sc, sc.master)
    }
  }

  def stop(): Unit = {
    val sc = globalSparkContext
    if (sc != null && !sc.isStopped) {
      // clean up the connection pool on executors first
      Utils.mapExecutors(sc, { (tc, p) =>
        ConnectionPool.clear()
        Iterator.empty
      }).count()
      // then on the driver
      ConnectionPool.clear()
      // clear current hive catalog connection
      SnappyStoreHiveCatalog.closeCurrent()
      if (ExternalStoreUtils.isExternalShellMode(sc)) {
        ToolsCallbackInit.toolsCallback.invokeStopFabricServer(sc)
      }
      sc.stop()
    }
    _clusterMode = null
    _anySNContext = null
    GlobalSnappyInit.resetGlobalSNContext
  }

  def getProvider(providerName: String): String =
    builtinSources.getOrElse(providerName, providerName)
}

// end of SnappyContext

abstract class ClusterMode {
  val sc: SparkContext
  val url: String
}

case class SnappyEmbeddedMode(override val sc: SparkContext,
    override val url: String) extends ClusterMode

case class SnappyShellMode(override val sc: SparkContext,
    override val url: String) extends ClusterMode

case class ExternalEmbeddedMode(override val sc: SparkContext,
    override val url: String) extends ClusterMode

case class LocalMode(override val sc: SparkContext,
    override val url: String) extends ClusterMode

case class ExternalClusterMode(override val sc: SparkContext,
    override val url: String) extends ClusterMode

private[sql] case class SnappyOperations(context: SnappyContext,
    df: DataFrame) {

  /**
    * Creates stratified sampled data from given DataFrame
    * {{{
    *   peopleDf.stratifiedSample(Map("qcs" -> Array(1,2), "fraction" -> 0.01))
    * }}}
    */
  def stratifiedSample(options: Map[String, Any]): SampleDataFrame =
    new SampleDataFrame(context,
      context.aqpContext.convertToStratifiedSample(options, df.logicalPlan) )

  def createTopK(ident: String, options: Map[String, Any]): Unit =
    context.aqpContext.createTopK(df, context, ident, options)


  /**
    * Table must be registered using #registerSampleTable.
    */
  def insertIntoSampleTables(sampleTableName: String*): Unit =
    context.aqpContext.collectSamples(context, df.rdd, sampleTableName, System.currentTimeMillis())




  /**
    * Append to an existing cache table.
    * Automatically uses #cacheQuery if not done already.
    */
  def appendToCache(tableName: String): Unit =  context.appendToCache(df, tableName)

}

private[sql] case class SnappyDStreamOperations[T: ClassTag](
    context: SnappyContext, ds: DStream[T]) {

  def saveStream(sampleTab: Seq[String],
      formatter: (RDD[T], StructType) => RDD[Row],
      schema: StructType,
      transform: RDD[Row] => RDD[Row] = null): Unit =
      context.aqpContext.saveStream(context, ds, sampleTab, formatter, schema, transform)



  def saveToExternalTable[A <: Product : TypeTag](externalTable: String,
      jdbcSource: Map[String, String]): Unit = {
    val schema: StructType = ScalaReflection.schemaFor[A].dataType.asInstanceOf[StructType]
    saveStreamToExternalTable(externalTable, schema, jdbcSource)
  }

  def saveToExternalTable(externalTable: String, schema: StructType,
      jdbcSource: Map[String, String]): Unit = {
    saveStreamToExternalTable(externalTable, schema, jdbcSource)
  }

  private def saveStreamToExternalTable(externalTable: String,
      schema: StructType, jdbcSource: Map[String, String]): Unit = {
    require(externalTable != null && externalTable.length > 0,
      "saveToExternalTable: expected non-empty table name")

    val tableIdent = context.catalog.newQualifiedTableName(externalTable)
    val externalStore = context.catalog.getExternalTable(jdbcSource)
    context.catalog.createExternalTableForCachedBatches(tableIdent.table,
      externalStore)
    val attributeSeq = schema.toAttributes

    val dummyDF = {
      val plan: LogicalRDD = LogicalRDD(attributeSeq,
        new DummyRDD(context))(context)
      DataFrame(context, plan)
    }

    context.catalog.tables.put(tableIdent, dummyDF.logicalPlan)
    context.cacheManager.cacheQuery_ext(dummyDF, Some(tableIdent.table),
      externalStore)

    ds.foreachRDD((rdd: RDD[T], time: Time) => {
      context.appendToCacheRDD(rdd, tableIdent.table, schema)
    })
  }
}
