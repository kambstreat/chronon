package ai.zipline.spark.test

import ai.zipline.aggregator.base.{DoubleType, IntType, LongType, StringType}
import ai.zipline.aggregator.test.{CStream, Column, NaiveAggregator}
import ai.zipline.aggregator.windowing.FiveMinuteResolution
import ai.zipline.api.Extensions._
import ai.zipline.api.{Aggregation, GroupBy => _, _}
import ai.zipline.spark.Extensions._
import ai.zipline.spark._
import com.google.gson.Gson
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{StructField, StructType, LongType => SparkLongType, StringType => SparkStringType}
import org.apache.spark.sql.{Row, SparkSession}
import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable

class GroupByTest {

  lazy val spark: SparkSession = SparkSessionBuilder.build("GroupByTest", local = true)

  @Test
  def testSnapshotEntities(): Unit = {
    val schema = List(
      Column("user", StringType, 10),
      Column(Constants.TimeColumn, LongType, 100), // ts = last 100 days
      Column("session_length", IntType, 10000)
    )
    val df = DataFrameGen.entities(spark, schema, 100000, 10) // ds = last 10 days
    val viewName = "test_group_by_entities"
    df.createOrReplaceTempView(viewName)
    val aggregations: Seq[Aggregation] = Seq(
      Builders
        .Aggregation(Operation.AVERAGE, "session_length", Seq(new Window(10, TimeUnit.DAYS), WindowUtils.Unbounded)))

    val groupBy = new GroupBy(aggregations, Seq("user"), df)
    val actualDf = groupBy.snapshotEntities
    val expectedDf = df.sqlContext.sql(s"""
                                          |SELECT user, 
                                          |       ds, 
                                          |       AVG(IF(ts  >= (unix_timestamp(ds, 'yyyy-MM-dd') - (86400*10)) * 1000, session_length, null)) AS session_length_average_10d,
                                          |       AVG(session_length) as session_length_average
                                          |FROM $viewName
                                          |WHERE ts < unix_timestamp(ds, 'yyyy-MM-dd') * 1000 
                                          |GROUP BY user, ds
                                          |""".stripMargin)

    val diff = Comparison.sideBySide(actualDf, expectedDf, List("user", Constants.PartitionColumn))
    if (diff.count() > 0) {
      diff.show()
      println("diff result rows")
    }
    assertEquals(0, diff.count())
  }

  @Test
  def testSnapshotEvents(): Unit = {
    val schema = List(
      Column("user", StringType, 10), // ts = last 10 days
      Column("session_length", IntType, 2),
      Column("rating", DoubleType, 2000)
    )

    val outputDates = CStream.genPartitions(10)

    val df = DataFrameGen.events(spark, schema, count = 100000, partitions = 100)
    val viewName = "test_group_by_snapshot_events"
    df.createOrReplaceTempView(viewName)
    val aggregations: Seq[Aggregation] = Seq(
      Builders.Aggregation(Operation.MAX, "ts", Seq(new Window(10, TimeUnit.DAYS), WindowUtils.Unbounded)),
      Builders.Aggregation(Operation.APPROX_UNIQUE_COUNT,
                           "session_length",
                           Seq(new Window(10, TimeUnit.DAYS), WindowUtils.Unbounded)),
      Builders.Aggregation(Operation.UNIQUE_COUNT,
                           "session_length",
                           Seq(new Window(10, TimeUnit.DAYS), WindowUtils.Unbounded)),
      Builders.Aggregation(Operation.SUM, "session_length", Seq(new Window(10, TimeUnit.DAYS)))
    )

    val groupBy = new GroupBy(aggregations, Seq("user"), df)
    val actualDf = groupBy.snapshotEvents(PartitionRange(outputDates.min, outputDates.max))
    val outputDatesRdd: RDD[Row] = spark.sparkContext.parallelize(outputDates.map(Row(_)))
    val outputDatesDf = spark.createDataFrame(outputDatesRdd, StructType(Seq(StructField("ds", SparkStringType))))
    val datesViewName = "test_group_by_snapshot_events_output_range"
    outputDatesDf.createOrReplaceTempView(datesViewName)
    val expectedDf = df.sqlContext.sql(s"""
                                          |select user, 
                                          |       $datesViewName.ds, 
                                          |       MAX(IF(ts  >= (unix_timestamp($datesViewName.ds, 'yyyy-MM-dd') - 86400*10) * 1000, ts, null)) AS ts_max_10d,
                                          |       MAX(ts) as ts_max,
                                          |       COUNT(DISTINCT session_length) as session_length_approx_unique_count,
                                          |       COUNT(DISTINCT session_length) as session_length_unique_count,
                                          |       COUNT(DISTINCT IF(ts  >= (unix_timestamp($datesViewName.ds, 'yyyy-MM-dd') - 86400*10) * 1000, session_length, null)) as session_length_approx_unique_count_10d,
                                          |       COUNT(DISTINCT IF(ts  >= (unix_timestamp($datesViewName.ds, 'yyyy-MM-dd') - 86400*10) * 1000, session_length, null)) as session_length_unique_count_10d,
                                          |       SUM(IF(ts  >= (unix_timestamp($datesViewName.ds, 'yyyy-MM-dd') - 86400*10) * 1000, session_length, null)) AS session_length_sum_10d,
                                          |       SUM(IF(ts  >= (unix_timestamp($datesViewName.ds, 'yyyy-MM-dd') - 86400*10) * 1000, rating, null)) AS rating_sum_10d
                                          |FROM $viewName CROSS JOIN $datesViewName
                                          |WHERE ts < unix_timestamp($datesViewName.ds, 'yyyy-MM-dd') * 1000 
                                          |group by user, $datesViewName.ds
                                          |""".stripMargin)

    val diff = Comparison.sideBySide(actualDf, expectedDf, List("user", Constants.PartitionColumn))
    if (diff.count() > 0) {
      diff.show()
      println("diff result rows")
    }
    assertEquals(0, diff.count())
  }

  @Test
  def temporalEventsLastKTest(): Unit = {
    val eventSchema = List(
      Column("user", StringType, 10),
      Column("listing_view", StringType, 100)
    )
    val eventDf = DataFrameGen.events(spark, eventSchema, count = 10000, partitions = 180)
    eventDf.createOrReplaceTempView("events_last_k")

    val querySchema = List(Column("user", StringType, 10))
    val queryDf = DataFrameGen.events(spark, querySchema, count = 1000, partitions = 180)
    queryDf.createOrReplaceTempView("queries_last_k")

    val aggregations: Seq[Aggregation] = Seq(
      Builders.Aggregation(Operation.LAST_K, "listing_view", Seq(WindowUtils.Unbounded), argMap = Map("k" -> "30")),
      Builders.Aggregation(Operation.COUNT, "listing_view", Seq(WindowUtils.Unbounded))
    )
    val keys = Seq("user").toArray
    val groupBy =
      new GroupBy(aggregations,
                  keys,
                  eventDf.selectExpr("user", "ts", "concat(ts, \" \", listing_view) as listing_view"))
    val resultDf = groupBy.temporalEvents(queryDf)
    val computed = resultDf.select("user", "ts", "listing_view_last30", "listing_view_count")
    computed.show()

    val expected = eventDf.sqlContext.sql(s"""
         |SELECT 
         |      events_last_k.user as user,
         |      queries_last_k.ts as ts,
         |      COLLECT_LIST(concat(CAST(events_last_k.ts AS STRING), " ", events_last_k.listing_view)) as listing_view_last30,
         |      SUM(case when events_last_k.listing_view <=> NULL then 0 else 1 end) as listing_view_count
         |FROM events_last_k CROSS JOIN queries_last_k
         |ON events_last_k.user = queries_last_k.user
         |WHERE events_last_k.ts < queries_last_k.ts
         |GROUP BY events_last_k.user, queries_last_k.ts
         |""".stripMargin)

    expected.show()

    val diff = Comparison.sideBySide(computed, expected, List("user", "ts"))
    if (diff.count() > 0) {
      println(s"Actual count: ${computed.count()}")
      println(s"Expected count: ${expected.count()}")
      println(s"Diff count: ${diff.count()}")
      println(s"diff result rows")
      diff.show()
      diff.rdd.foreach { row =>
        val gson = new Gson()
        val computed =
          Option(row(4)).map(_.asInstanceOf[mutable.WrappedArray[String]].toArray).getOrElse(Array.empty[String])
        val expected =
          Option(row(5))
            .map(_.asInstanceOf[mutable.WrappedArray[String]].toArray.sorted.reverse.take(30))
            .getOrElse(Array.empty[String])
        val computedCount = Option(row(2)).map(_.asInstanceOf[Long]).getOrElse(0)
        val expectedCount = Option(row(3)).map(_.asInstanceOf[Long]).getOrElse(0)
        val computedStr = gson.toJson(computed)
        val expectedStr = gson.toJson(expected)
        if (computedStr != expectedStr) {
          println(s"""
                     |computed [$computedCount]: ${gson.toJson(computed)}
                     |expected [$expectedCount]: ${gson.toJson(expected)}
                     |""".stripMargin)
        }
        assertEquals(gson.toJson(computed), gson.toJson(expected))
      }
    }
  }
  @Test
  def testTemporalEvents(): Unit = {
    val eventSchema = List(
      Column("user", StringType, 10),
      Column("session_length", IntType, 10000)
    )

    val eventDf = DataFrameGen.events(spark, eventSchema, count = 10000, partitions = 180)

    val querySchema = List(Column("user", StringType, 10))

    val queryDf = DataFrameGen.events(spark, querySchema, count = 1000, partitions = 180)

    val aggregations: Seq[Aggregation] = Seq(
      Builders.Aggregation(
        Operation.AVERAGE,
        "session_length",
        Seq(new Window(1, TimeUnit.DAYS), new Window(1, TimeUnit.HOURS), new Window(30, TimeUnit.DAYS))))

    val keys = Seq("user").toArray
    val groupBy = new GroupBy(aggregations, keys, eventDf)
    val resultDf = groupBy.temporalEvents(queryDf)

    val keyBuilder = FastHashing.generateKeyBuilder(keys, eventDf.schema)
    // naive aggregation for equivalence testing
    // this will basically explode on even moderate large table
    val queriesByKey: RDD[(KeyWithHash, Array[Long])] = queryDf
      .where("user IS NOT NULL")
      .rdd
      .groupBy(keyBuilder)
      .mapValues { rowIter =>
        rowIter.map {
          _.getAs[Long](Constants.TimeColumn)
        }.toArray
      }

    val eventsByKey: RDD[(KeyWithHash, Iterator[ArrayRow])] = eventDf.rdd
      .groupBy(keyBuilder)
      .mapValues { rowIter =>
        rowIter.map(Conversions.toZiplineRow(_, groupBy.tsIndex)).toIterator
      }

    val windows = aggregations.flatMap(_.unpack.map(_.window)).toArray
    val tailHops = windows.map(FiveMinuteResolution.calculateTailHop)
    val naiveAggregator =
      new NaiveAggregator(groupBy.windowAggregator, windows, tailHops)
    val naiveRdd = queriesByKey.leftOuterJoin(eventsByKey).flatMap {
      case (key, (queries: Array[Long], events: Option[Iterator[ArrayRow]])) =>
        val irs = naiveAggregator.aggregate(events.map(_.toSeq).orNull, queries)
        queries.zip(irs).map {
          case (query: Long, ir: Array[Any]) =>
            (key.data :+ query, groupBy.windowAggregator.finalize(ir))
        }
    }
    val naiveDf = groupBy.toDf(naiveRdd, Seq((Constants.TimeColumn, SparkLongType)))

    val diff = Comparison.sideBySide(naiveDf, resultDf, List("user", Constants.TimeColumn))
    if (diff.count() > 0) {
      diff.show()
      println("diff result rows")
    }
    assertEquals(0, diff.count())
  }

  // Test that the output of Group by with Step Days is the same as the output without Steps (full data range)
  @Test
  def testStepDaysConsistency(): Unit = {
    val (source, endPartition) = createTestSource

    val tableUtils = TableUtils(spark)
    val testSteps = Option(30)
    val namespace = "test_steps"
    val diff = Comparison.sideBySide(
      tableUtils.sql(
        s"SELECT * FROM ${backfill(name = "unit_test_item_views_steps", source = source, endPartition = endPartition, namespace = namespace, tableUtils = tableUtils, stepDays = testSteps)}"),
      tableUtils.sql(
        s"SELECT * FROM ${backfill(name = "unit_test_item_views_no_steps", source = source, endPartition = endPartition, namespace = namespace, tableUtils = tableUtils)}"),
      List("item", Constants.PartitionColumn)
    )
    assertEquals(0, diff.count())
  }

  // test that OrderByLimit and OrderByLimitTimed serialization works well with Spark's data type
  @Test
  def testFirstKLastKTopKBottomKApproxUniqueCount(): Unit = {
    val (source, endPartition) = createTestSource

    val tableUtils = TableUtils(spark)
    val namespace = "test_order_by_limit"
    val aggs = Seq(
      Builders.Aggregation(operation = Operation.LAST_K,
                           inputColumn = "ts",
                           windows = Seq(
                             new Window(15, TimeUnit.DAYS),
                             new Window(60, TimeUnit.DAYS)
                           ),
                           argMap = Map("k" -> "2")),
      Builders.Aggregation(operation = Operation.FIRST_K,
                           inputColumn = "ts",
                           windows = Seq(
                             new Window(15, TimeUnit.DAYS),
                             new Window(60, TimeUnit.DAYS)
                           ),
                           argMap = Map("k" -> "2")),
      Builders.Aggregation(operation = Operation.TOP_K,
                           inputColumn = "ts",
                           windows = Seq(
                             new Window(15, TimeUnit.DAYS),
                             new Window(60, TimeUnit.DAYS)
                           ),
                           argMap = Map("k" -> "2")),
      Builders.Aggregation(operation = Operation.BOTTOM_K,
                           inputColumn = "ts",
                           windows = Seq(
                             new Window(15, TimeUnit.DAYS),
                             new Window(60, TimeUnit.DAYS)
                           ),
                           argMap = Map("k" -> "2")),
      Builders.Aggregation(operation = Operation.APPROX_UNIQUE_COUNT,
                           inputColumn = "ts",
                           windows = Seq(
                             new Window(15, TimeUnit.DAYS),
                             new Window(60, TimeUnit.DAYS)
                           ))
    )
    backfill(name = "unit_test_serialization",
             source = source,
             endPartition = endPartition,
             namespace = namespace,
             tableUtils = tableUtils,
             additionalAgg = aggs)
  }

  private def createTestSource: (Source, String) = {
    val today = Constants.Partition.at(System.currentTimeMillis())
    val startPartition = Constants.Partition.minus(today, new Window(365, TimeUnit.DAYS))
    val endPartition = Constants.Partition.at(System.currentTimeMillis())
    val sourceSchema = List(
      Column("user", StringType, 10000),
      Column("item", StringType, 100),
      Column("time_spent_ms", LongType, 5000)
    )
    val namespace = "zipline_test"
    val sourceTable = s"$namespace.test_group_by_steps"

    spark.sql(s"CREATE DATABASE IF NOT EXISTS $namespace")
    DataFrameGen.events(spark, sourceSchema, count = 1000, partitions = 200).save(sourceTable)
    val source = Builders.Source.events(
      query =
        Builders.Query(selects = Builders.Selects("ts", "item", "time_spent_ms"), startPartition = startPartition),
      table = sourceTable
    )
    (source, endPartition)
  }

  def backfill(name: String,
               source: Source,
               namespace: String,
               endPartition: String,
               tableUtils: TableUtils,
               stepDays: Option[Int] = None,
               additionalAgg: Seq[Aggregation] = Seq.empty): String = {
    spark.sql(s"CREATE DATABASE IF NOT EXISTS $namespace")
    val groupBy = Builders.GroupBy(
      sources = Seq(source),
      keyColumns = Seq("item"),
      aggregations = Seq(
        Builders.Aggregation(operation = Operation.COUNT, inputColumn = "time_spent_ms"),
        Builders.Aggregation(operation = Operation.MIN,
                             inputColumn = "ts",
                             windows = Seq(
                               new Window(15, TimeUnit.DAYS),
                               new Window(60, TimeUnit.DAYS),
                               WindowUtils.Unbounded
                             )),
        Builders.Aggregation(operation = Operation.MAX, inputColumn = "ts")
      ) ++ additionalAgg,
      metaData = Builders.MetaData(name = name, namespace = namespace, team = "zipline"),
      backfillStartDate =
        Constants.Partition.minus(Constants.Partition.at(System.currentTimeMillis()), new Window(60, TimeUnit.DAYS))
    )

    GroupBy.computeBackfill(
      groupBy,
      endPartition = endPartition,
      tableUtils = tableUtils,
      stepDays = stepDays
    )
    s"$namespace.$name"
  }
}
