package ai.chronon.api

import ai.chronon.api.Extensions.WindowUtils

import scala.collection.JavaConverters._

// mostly used by tests to define confs easily
object Builders {

  object Selects {
    def apply(clauses: String*): Map[String, String] = {
      clauses.map { col => col -> col }.toMap
    }
  }

  object Query {
    def apply(selects: Map[String, String] = null,
              wheres: Seq[String] = null,
              startPartition: String = null,
              endPartition: String = null,
              timeColumn: String = null,
              setups: Seq[String] = null,
              mutationTimeColumn: String = null,
              reversalColumn: String = null): Query = {
      val result = new Query()
      if (selects != null)
        result.setSelects(selects.asJava)
      if (wheres != null)
        result.setWheres(wheres.asJava)
      result.setStartPartition(startPartition)
      result.setEndPartition(endPartition)
      result.setTimeColumn(timeColumn)
      if (setups != null)
        result.setSetups(setups.asJava)
      result.setMutationTimeColumn(mutationTimeColumn)
      result.setReversalColumn(reversalColumn)
      result
    }
  }

  object AggregationPart {
    def apply(operation: Operation,
              inputColumn: String,
              window: Window = WindowUtils.Unbounded,
              argMap: Map[String, String] = null,
              bucket: String = null): AggregationPart = {
      val result = new AggregationPart()
      result.setOperation(operation)
      result.setInputColumn(inputColumn)
      result.setWindow(window)
      if (argMap != null)
        result.setArgMap(argMap.asJava)
      if (bucket != null) {
        result.setBucket(bucket)
      }
      result
    }
  }

  object Aggregation {
    def apply(operation: Operation,
              inputColumn: String,
              windows: Seq[Window] = null,
              argMap: Map[String, String] = null,
              buckets: Seq[String] = null): Aggregation = {
      val result = new Aggregation()
      result.setOperation(operation)
      result.setInputColumn(inputColumn)
      if (argMap != null)
        result.setArgMap(argMap.asJava)
      if (windows != null)
        result.setWindows(windows.asJava)
      if (buckets != null)
        result.setBuckets(buckets.asJava)
      result
    }
  }

  object Source {
    def entities(query: Query,
                 snapshotTable: String,
                 mutationTable: String = null,
                 mutationTopic: String = null): Source = {
      val result = new EntitySource()
      result.setQuery(query)
      result.setSnapshotTable(snapshotTable)
      result.setMutationTable(mutationTable)
      result.setMutationTopic(mutationTopic)
      val source = new Source()
      source.setEntities(result)
      source
    }

    def events(query: Query, table: String, topic: String = null, isCumulative: Boolean = false): Source = {
      val result = new EventSource()
      result.setQuery(query)
      result.setTable(table)
      result.setTopic(topic)
      result.setIsCumulative(isCumulative)
      val source = new Source()
      source.setEvents(result)
      source
    }
  }

  object GroupBy {
    def apply(
        metaData: MetaData = null,
        sources: Seq[Source] = null,
        keyColumns: Seq[String] = null,
        aggregations: Seq[Aggregation] = null,
        accuracy: Accuracy = null,
        backfillStartDate: String = null
    ): GroupBy = {
      val result = new GroupBy()
      result.setMetaData(metaData)
      if (sources != null)
        result.setSources(sources.asJava)
      if (keyColumns != null)
        result.setKeyColumns(keyColumns.asJava)
      if (aggregations != null)
        result.setAggregations(aggregations.asJava)
      if (accuracy != null)
        result.setAccuracy(accuracy)
      if (backfillStartDate != null)
        result.setBackfillStartDate(backfillStartDate)
      result
    }
  }

  object Join {
    def apply(metaData: MetaData = null, left: Source = null, joinParts: Seq[JoinPart] = null): Join = {
      val result = new Join()
      result.setMetaData(metaData)
      result.setLeft(left)
      if (joinParts != null)
        result.setJoinParts(joinParts.asJava)
      result
    }
  }

  object JoinPart {
    def apply(
        groupBy: GroupBy = null,
        keyMapping: Map[String, String] = null,
        selectors: Seq[AggregationSelector] = null,
        prefix: String = null
    ): JoinPart = {
      val result = new JoinPart()
      result.setGroupBy(groupBy)
      if (keyMapping != null)
        result.setKeyMapping(keyMapping.asJava)

      if (selectors != null) // TODO: selectors are unused right now - we select everything
        result.setSelectors(selectors.asJava)
      result.setPrefix(prefix)
      result
    }
  }

  object MetaData {
    def apply(
        name: String = null,
        online: Boolean = false,
        production: Boolean = false,
        customJson: String = null,
        dependencies: Seq[String] = null,
        namespace: String = null,
        team: String = null,
        samplePercent: Double = 0
    ): MetaData = {
      val result = new MetaData()
      result.setName(name)
      result.setOnline(online)
      result.setProduction(production)
      result.setCustomJson(customJson)
      result.setOutputNamespace(namespace)
      result.setTeam(Option(team).getOrElse("chronon"))
      if (dependencies != null)
        result.setDependencies(dependencies.asJava)
      if (samplePercent > 0)
        result.setSamplePercent(samplePercent)
      result
    }
  }

  object StagingQuery {
    def apply(
        query: String = null,
        metaData: MetaData = null,
        startPartition: String = null,
        setups: Seq[String] = null
    ): StagingQuery = {
      val stagingQuery = new StagingQuery()
      stagingQuery.setQuery(query)
      stagingQuery.setMetaData(metaData)
      stagingQuery.setStartPartition(startPartition)
      if (setups != null) stagingQuery.setSetups(setups.asJava)
      stagingQuery
    }
  }
}