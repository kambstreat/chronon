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

package ai.chronon.online

import ai.chronon.aggregator.windowing
import ai.chronon.aggregator.row.ColumnAggregator
import ai.chronon.aggregator.windowing.{FinalBatchIr, SawtoothOnlineAggregator, TiledIr, TsUtils}
import ai.chronon.api.Constants.ChrononMetadataKey
import ai.chronon.api._
import ai.chronon.online.Fetcher.{ColumnSpec, PrefixedRequest, Request, Response}
import ai.chronon.online.FetcherCache.{BatchResponses, CachedBatchResponse, KvStoreBatchResponse}
import ai.chronon.online.KVStore.{GetRequest, GetResponse, TimedValue}
import ai.chronon.online.Metrics.Name
import ai.chronon.api.Extensions.{DerivationOps, GroupByOps, JoinOps, ThrowableOps}
import com.google.gson.Gson
import java.util

import scala.collection.JavaConverters._
import scala.collection.{Seq, mutable}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

import ai.chronon.online.OnlineDerivationUtil.{
  DerivationFunc,
  applyDeriveFunc,
  buildDerivationFunction,
  buildRenameOnlyDerivationFunction,
  timeFields
}

// Does internal facing fetching
//   1. takes join request or groupBy requests
//   2. does the fan out and fan in from kv store in a parallel fashion
//   3. does the post aggregation
class FetcherBase(kvStore: KVStore,
                  metaDataSet: String = ChrononMetadataKey,
                  timeoutMillis: Long = 10000,
                  debug: Boolean = false)
    extends MetadataStore(kvStore, metaDataSet, timeoutMillis)
    with FetcherCache {
  import FetcherBase._

  /**
    * A groupBy request is split into batchRequest and optionally a streamingRequest. This method decodes bytes
    * (of the appropriate avro schema) into chronon rows aggregates further if necessary.
    *
    * @param batchResponses a BatchResponses, which encapsulates either a response from kv store or a cached batch IR.
    * @param streamingResponsesOpt a response from kv store, if the GroupBy was streaming data.
    * @param oldServingInfo the GroupByServingInfo used to fetch the GroupBys.
    * @param queryTimeMs the Request timestamp
    * @param startTimeMs time when we started fetching the KV store
    * @param overallLatency the time it took to get the values from the KV store
    * @param context the Metrics.Context to use for recording metrics
    * @param totalResponseValueBytes the total size of the response from the KV store
    * @param keys the keys used to fetch the GroupBy
    * @return
    */
  private def constructGroupByResponse(batchResponses: BatchResponses,
                                       streamingResponsesOpt: Option[Seq[TimedValue]],
                                       oldServingInfo: GroupByServingInfoParsed,
                                       queryTimeMs: Long,
                                       startTimeMs: Long,
                                       overallLatency: Long,
                                       context: Metrics.Context,
                                       totalResponseValueBytes: Int,
                                       keys: Map[String, Any] // The keys are used only for caching
  ): Map[String, AnyRef] = {
    val servingInfo = getServingInfo(oldServingInfo, batchResponses)

    // Batch metrics
    batchResponses match {
      case kvStoreResponse: KvStoreBatchResponse =>
        kvStoreResponse.response.map(
          reportKvResponse(context.withSuffix("batch"), _, queryTimeMs, overallLatency, totalResponseValueBytes)
        )
      case _: CachedBatchResponse => // no-op;
    }

    // The bulk upload may not have removed an older batch values. We manually discard all but the latest one.
    val batchBytes: Array[Byte] = batchResponses.getBatchBytes(servingInfo.batchEndTsMillis)

    val responseMap: Map[String, AnyRef] = if (servingInfo.groupBy.aggregations == null) { // no-agg
      getMapResponseFromBatchResponse(batchResponses,
                                      batchBytes,
                                      servingInfo.selectedCodec.decodeMap,
                                      servingInfo,
                                      keys)
    } else if (streamingResponsesOpt.isEmpty) { // snapshot accurate
      getMapResponseFromBatchResponse(batchResponses, batchBytes, servingInfo.outputCodec.decodeMap, servingInfo, keys)
    } else { // temporal accurate
      val streamingResponses = streamingResponsesOpt.get
      val mutations: Boolean = servingInfo.groupByOps.dataModel == DataModel.Entities
      val aggregator: SawtoothOnlineAggregator = servingInfo.aggregator
      if (aggregator.batchEndTs > queryTimeMs) {
        context.incrementException(
          new IllegalArgumentException(
            s"Request time of $queryTimeMs is less than batch time ${aggregator.batchEndTs}" +
              s" for groupBy ${servingInfo.groupByOps.metaData.getName}"))
        null
      } else if (batchBytes == null && (streamingResponses == null || streamingResponses.isEmpty)) {
        if (debug) logger.info("Both batch and streaming data are null")
        return null
      }

      // Streaming metrics
      reportKvResponse(context.withSuffix("streaming"),
                       streamingResponses,
                       queryTimeMs,
                       overallLatency,
                       totalResponseValueBytes)

      // If caching is enabled, we try to fetch the batch IR from the cache so we avoid the work of decoding it.
      val batchIr: FinalBatchIr =
        getBatchIrFromBatchResponse(batchResponses, batchBytes, servingInfo, toBatchIr, keys)

      val output: Array[Any] = if (servingInfo.isTilingEnabled) {
        val streamingIrs: Iterator[TiledIr] = streamingResponses.iterator
          .filter(tVal => tVal.millis >= servingInfo.batchEndTsMillis)
          .map { tVal =>
            val (tile, _) = servingInfo.tiledCodec.decodeTileIr(tVal.bytes)
            TiledIr(tVal.millis, tile)
          }

        if (debug) {
          val gson = new Gson()
          logger.info(s"""
                 |batch ir: ${gson.toJson(batchIr)}
                 |streamingIrs: ${gson.toJson(streamingIrs)}
                 |batchEnd in millis: ${servingInfo.batchEndTsMillis}
                 |queryTime in millis: $queryTimeMs
                 |""".stripMargin)
        }

        aggregator.lambdaAggregateFinalizedTiled(batchIr, streamingIrs, queryTimeMs)
      } else {
        val selectedCodec = servingInfo.groupByOps.dataModel match {
          case DataModel.Events   => servingInfo.valueAvroCodec
          case DataModel.Entities => servingInfo.mutationValueAvroCodec
        }

        val streamingRows: Array[Row] = streamingResponses.iterator
          .filter(tVal => tVal.millis >= servingInfo.batchEndTsMillis)
          .map(tVal => selectedCodec.decodeRow(tVal.bytes, tVal.millis, mutations))
          .toArray

        if (debug) {
          val gson = new Gson()
          logger.info(s"""
                 |batch ir: ${gson.toJson(batchIr)}
                 |streamingRows: ${gson.toJson(streamingRows)}
                 |batchEnd in millis: ${servingInfo.batchEndTsMillis}
                 |queryTime in millis: $queryTimeMs
                 |""".stripMargin)
        }

        aggregator.lambdaAggregateFinalized(batchIr, streamingRows.iterator, queryTimeMs, mutations)
      }
      servingInfo.outputCodec.fieldNames.iterator.zip(output.iterator.map(_.asInstanceOf[AnyRef])).toMap
    }

    context.distribution("group_by.latency.millis", System.currentTimeMillis() - startTimeMs)
    responseMap
  }

  def reportKvResponse(ctx: Metrics.Context,
                       response: Seq[TimedValue],
                       queryTsMillis: Long,
                       latencyMillis: Long,
                       totalResponseBytes: Int): Unit = {
    val latestResponseTs = response.iterator.map(_.millis).reduceOption(_ max _)
    val responseBytes = response.iterator.map(_.bytes.length).sum
    val context = ctx.withSuffix("response")
    context.distribution(Name.RowCount, response.length)
    context.distribution(Name.Bytes, responseBytes)
    latestResponseTs.foreach { ts =>
      context.distribution(Name.FreshnessMillis, queryTsMillis - ts)
      context.distribution(Name.FreshnessMinutes, (queryTsMillis - ts) / 60000)
    }
    context.distribution("attributed_latency.millis",
                         ((responseBytes.toDouble / totalResponseBytes.toDouble) * latencyMillis).toLong)
  }

  /**
    * Get the latest serving information based on a batch response.
    *
    * The underlying metadata store used to store the latest GroupByServingInfoParsed will be updated if needed.
    *
    * @param oldServingInfo The previous serving information before fetching the latest KV store data.
    * @param batchResponses the latest batch responses (either a fresh KV store response or a cached batch ir).
    * @return the GroupByServingInfoParsed containing the latest serving information.
    */
  private[online] def getServingInfo(oldServingInfo: GroupByServingInfoParsed,
                                     batchResponses: BatchResponses): GroupByServingInfoParsed = {
    batchResponses match {
      case batchTimedValuesTry: KvStoreBatchResponse => {
        val latestBatchValue: Try[TimedValue] = batchTimedValuesTry.response.map(_.maxBy(_.millis))
        latestBatchValue.map(timedVal => updateServingInfo(timedVal.millis, oldServingInfo)).getOrElse(oldServingInfo)
      }
      case _: CachedBatchResponse => {
        // If there was cached batch data, there's no point try to update the serving info; it would be the same.
        // However, there's one edge case to be handled. If all batch requests are cached and we never hit the kv store,
        // we will never try to update the serving info. In that case, if new batch data were to land, we would never
        // know of it. So, we force a refresh here to ensure that we are still periodically asynchronously hitting the
        // KV store to update the serving info. (See CHIP-1)
        getGroupByServingInfo.refresh(oldServingInfo.groupByOps.metaData.name)

        oldServingInfo
      }
    }
  }

  /**
    * If `batchEndTs` is ahead of `groupByServingInfo.batchEndTsMillis`, update the MetadataStore with the new
    * timestamp. In practice, this means that new batch data has landed, so future kvstore requests should fetch
    * streaming data after the new batchEndTsMillis.
    *
    * @param batchEndTs the new batchEndTs from the latest batch data
    * @param groupByServingInfo the current GroupByServingInfo
    */
  private def updateServingInfo(batchEndTs: Long,
                                groupByServingInfo: GroupByServingInfoParsed): GroupByServingInfoParsed = {
    val name = groupByServingInfo.groupBy.metaData.name
    if (batchEndTs > groupByServingInfo.batchEndTsMillis) {
      logger.info(s"""$name's value's batch timestamp of $batchEndTs is
           |ahead of schema timestamp of ${groupByServingInfo.batchEndTsMillis}.
           |Forcing an update of schema.""".stripMargin)
      getGroupByServingInfo
        .force(name)
        .recover {
          case ex: Throwable =>
            logger.error(s"Couldn't update GroupByServingInfo of $name. Proceeding with the old one.", ex)
            ex.printStackTrace()
            groupByServingInfo
        }
        .get
    } else {
      groupByServingInfo
    }
  }

  // 1. fetches GroupByServingInfo
  // 2. encodes keys as keyAvroSchema
  // 3. Based on accuracy, fetches streaming + batch data and aggregates further.
  // 4. Finally converted to outputSchema
  def fetchGroupBys(requests: scala.collection.Seq[Request]): Future[scala.collection.Seq[Response]] = {
    // split a groupBy level request into its kvStore level requests
    val groupByRequestToKvRequest: Seq[(Request, Try[GroupByRequestMeta])] = requests.iterator.map { request =>
      val groupByRequestMetaTry: Try[GroupByRequestMeta] = getGroupByServingInfo(request.name)
        .map { groupByServingInfo =>
          val context =
            request.context.getOrElse(Metrics.Context(Metrics.Environment.GroupByFetching, groupByServingInfo.groupBy))
          context.increment("group_by_request.count")
          var batchKeyBytes: Array[Byte] = null
          var streamingKeyBytes: Array[Byte] = null
          try {
            // The formats of key bytes for batch requests and key bytes for streaming requests may differ based
            // on the KVStore implementation, so we encode each distinctly.
            batchKeyBytes =
              kvStore.createKeyBytes(request.keys, groupByServingInfo, groupByServingInfo.groupByOps.batchDataset)
            streamingKeyBytes =
              kvStore.createKeyBytes(request.keys, groupByServingInfo, groupByServingInfo.groupByOps.streamingDataset)
          } catch {
            // TODO: only gets hit in cli path - make this code path just use avro schema to decode keys directly in cli
            // TODO: Remove this code block
            case ex: Exception =>
              val castedKeys = groupByServingInfo.keyChrononSchema.fields.map {
                case StructField(name, typ) => name -> ColumnAggregator.castTo(request.keys.getOrElse(name, null), typ)
              }.toMap
              try {
                batchKeyBytes =
                  kvStore.createKeyBytes(castedKeys, groupByServingInfo, groupByServingInfo.groupByOps.batchDataset)
                streamingKeyBytes =
                  kvStore.createKeyBytes(castedKeys, groupByServingInfo, groupByServingInfo.groupByOps.streamingDataset)
              } catch {
                case exInner: Exception =>
                  exInner.addSuppressed(ex)
                  throw new RuntimeException("Couldn't encode request keys or casted keys", exInner)
              }
          }
          val batchRequest = GetRequest(batchKeyBytes, groupByServingInfo.groupByOps.batchDataset)
          val streamingRequestOpt = groupByServingInfo.groupByOps.inferredAccuracy match {
            // fetch batch(ir) and streaming(input) and aggregate
            case Accuracy.TEMPORAL =>
              Some(
                GetRequest(streamingKeyBytes,
                           groupByServingInfo.groupByOps.streamingDataset,
                           Some(groupByServingInfo.batchEndTsMillis)))
            // no further aggregation is required - the value in KvStore is good as is
            case Accuracy.SNAPSHOT => None
          }
          GroupByRequestMeta(groupByServingInfo, batchRequest, streamingRequestOpt, request.atMillis, context)
        }
      if (groupByRequestMetaTry.isFailure) {
        request.context.foreach(_.increment("group_by_serving_info_failure.count"))
      }
      request -> groupByRequestMetaTry
    }.toSeq

    // If caching is enabled, we check if any of the GetRequests are already cached. If so, we store them in a Map
    // and avoid the work of re-fetching them.
    val cachedRequests: Map[GetRequest, CachedBatchResponse] = getCachedRequests(groupByRequestToKvRequest)
    // Collect cache metrics once per fetchGroupBys call; Caffeine metrics aren't tagged by groupBy
    maybeBatchIrCache.foreach(cache =>
      LRUCache.collectCaffeineCacheMetrics(caffeineMetricsContext, cache.cache, cache.cacheName))

    val allRequestsToFetch: Seq[GetRequest] = groupByRequestToKvRequest.flatMap {
      case (_, Success(GroupByRequestMeta(_, batchRequest, streamingRequestOpt, _, _))) => {
        // If a request is cached, don't include it in the list of requests to fetch.
        if (cachedRequests.contains(batchRequest)) streamingRequestOpt else Some(batchRequest) ++ streamingRequestOpt
      }
      case _ => Seq.empty
    }

    val startTimeMs = System.currentTimeMillis()
    val kvResponseFuture: Future[Seq[GetResponse]] = if (allRequestsToFetch.nonEmpty) {
      kvStore.multiGet(allRequestsToFetch)
    } else {
      Future(Seq.empty[GetResponse])
    }

    kvResponseFuture
      .map { kvResponses: Seq[GetResponse] =>
        val multiGetMillis = System.currentTimeMillis() - startTimeMs
        val responsesMap: Map[GetRequest, Try[Seq[TimedValue]]] = kvResponses.map { response =>
          response.request -> response.values
        }.toMap
        val totalResponseValueBytes =
          responsesMap.iterator
            .map(_._2)
            .filter(_.isSuccess)
            .flatMap(_.get.map(v => Option(v.bytes).map(_.length).getOrElse(0)))
            .sum

        val responses: Seq[Response] = groupByRequestToKvRequest.iterator.map {
          case (request, requestMetaTry) =>
            val responseMapTry: Try[Map[String, AnyRef]] = requestMetaTry.map { requestMeta =>
              val GroupByRequestMeta(groupByServingInfo, batchRequest, streamingRequestOpt, _, context) = requestMeta

              context.count("multi_get.batch.size", allRequestsToFetch.length)
              context.distribution("multi_get.bytes", totalResponseValueBytes)
              context.distribution("multi_get.response.length", kvResponses.length)
              context.distribution("multi_get.latency.millis", multiGetMillis)

              // pick the batch version with highest timestamp
              val batchResponses: BatchResponses =
                // Check if the get request was cached. If so, use the cache. Otherwise, try to get it from response.
                cachedRequests.get(batchRequest) match {
                  case None =>
                    BatchResponses(
                      responsesMap
                        .getOrElse(
                          batchRequest,
                          // Fail if response is neither in responsesMap nor in cache
                          Failure(new IllegalStateException(
                            s"Couldn't find corresponding response for $batchRequest in responseMap or cache"))
                        ))
                  case Some(cachedResponse: CachedBatchResponse) => cachedResponse
                }

              val streamingResponsesOpt =
                streamingRequestOpt.map(responsesMap.getOrElse(_, Success(Seq.empty)).getOrElse(Seq.empty))
              val queryTs = request.atMillis.getOrElse(System.currentTimeMillis())
              val groupByResponse: Map[String, AnyRef] =
                try {
                  if (debug)
                    logger.info(
                      s"Constructing response for groupBy: ${groupByServingInfo.groupByOps.metaData.getName} " +
                        s"for keys: ${request.keys}")
                  constructGroupByResponse(batchResponses,
                                           streamingResponsesOpt,
                                           groupByServingInfo,
                                           queryTs,
                                           startTimeMs,
                                           multiGetMillis,
                                           context,
                                           totalResponseValueBytes,
                                           request.keys)
                } catch {
                  case ex: Exception =>
                    // not all exceptions are due to stale schema, so we want to control how often we hit kv store
                    getGroupByServingInfo.refresh(groupByServingInfo.groupByOps.metaData.name)
                    context.incrementException(ex)
                    ex.printStackTrace()
                    throw ex
                }
              if (groupByServingInfo.groupBy.hasDerivations) {
                val derivedMapTry: Try[Map[String, AnyRef]] = Try {
                  applyDeriveFunc(groupByServingInfo.deriveFunc, request, groupByResponse)
                }
                val derivedMap = derivedMapTry match {
                  case Success(derivedMap) =>
                    derivedMap
                  // If the derivation failed we want to return the exception map and rename only derivation
                  case Failure(exception) => {
                    context.incrementException(exception)
                    val derivedExceptionMap =
                      Map("derivation_fetch_exception" -> exception.traceString.asInstanceOf[AnyRef])
                    val renameOnlyDeriveFunction =
                      buildRenameOnlyDerivationFunction(groupByServingInfo.groupBy.derivationsScala)
                    val renameOnlyDerivedMapTry: Try[Map[String, AnyRef]] = Try {
                      renameOnlyDeriveFunction(request.keys, groupByResponse)
                        .mapValues(_.asInstanceOf[AnyRef])
                        .toMap
                    }
                    // if the rename only derivation also failed we want to return the exception map
                    val renameOnlyDerivedMap: Map[String, AnyRef] = renameOnlyDerivedMapTry match {
                      case Success(renameOnlyDerivedMap) =>
                        renameOnlyDerivedMap
                      case Failure(exception) =>
                        context.incrementException(exception)
                        Map("derivation_rename_exception" -> exception.traceString.asInstanceOf[AnyRef])
                    }
                    renameOnlyDerivedMap ++ derivedExceptionMap
                  }
                }
                derivedMap
              } else {
                groupByResponse
              }
            }
            Response(request, responseMapTry)
        }.toList
        responses
      }
  }

  /**
    * Convert an array of bytes to a FinalBatchIr.
    */
  def toBatchIr(bytes: Array[Byte], gbInfo: GroupByServingInfoParsed): FinalBatchIr = {
    if (bytes == null) return null
    val batchRecord =
      AvroConversions
        .toChrononRow(gbInfo.irCodec.decode(bytes), gbInfo.irChrononSchema)
        .asInstanceOf[Array[Any]]
    val collapsed = gbInfo.aggregator.windowedAggregator.denormalize(batchRecord(0).asInstanceOf[Array[Any]])
    val tailHops = batchRecord(1)
      .asInstanceOf[util.ArrayList[Any]]
      .iterator()
      .asScala
      .map(
        _.asInstanceOf[util.ArrayList[Any]]
          .iterator()
          .asScala
          .map(hop => gbInfo.aggregator.baseAggregator.denormalizeInPlace(hop.asInstanceOf[Array[Any]]))
          .toArray)
      .toArray
    windowing.FinalBatchIr(collapsed, tailHops)
  }

  // prioritize passed in joinOverrides over the ones in metadata store
  // used in stream-enrichment and in staging testing
  def fetchJoin(requests: scala.collection.Seq[Request]): Future[scala.collection.Seq[Response]] = {
    val startTimeMs = System.currentTimeMillis()
    // convert join requests to groupBy requests
    val joinDecomposed: scala.collection.Seq[(Request, Try[Seq[Either[PrefixedRequest, KeyMissingException]]])] =
      requests.map { request =>
        val joinTry: Try[JoinOps] = getJoinConf(request.name)
        var joinContext: Option[Metrics.Context] = None
        val decomposedTry = joinTry.map { join =>
          joinContext = Some(Metrics.Context(Metrics.Environment.JoinFetching, join.join))
          joinContext.get.increment("join_request.count")
          join.joinPartOps.map { part =>
            val joinContextInner = Metrics.Context(joinContext.get, part)
            val missingKeys = part.leftToRight.keys.filterNot(request.keys.contains)
            if (missingKeys.nonEmpty) {
              Right(KeyMissingException(part.fullPrefix, missingKeys.toSeq, request.keys))
            } else {
              val rightKeys = part.leftToRight.map { case (leftKey, rightKey) => rightKey -> request.keys(leftKey) }
              Left(
                PrefixedRequest(
                  part.fullPrefix,
                  Request(part.groupBy.getMetaData.getName, rightKeys, request.atMillis, Some(joinContextInner))))
            }
          }
        }
        request.copy(context = joinContext) -> decomposedTry
      }

    val groupByRequests = joinDecomposed.flatMap {
      case (_, gbTry) =>
        gbTry match {
          case Failure(_)        => Iterator.empty
          case Success(requests) => requests.iterator.flatMap(_.left.toOption).map(_.request)
        }
    }

    val groupByResponsesFuture = fetchGroupBys(groupByRequests)

    // re-attach groupBy responses to join
    groupByResponsesFuture
      .map { groupByResponses =>
        val responseMap = groupByResponses.iterator.map { response => response.request -> response.values }.toMap
        val responses = joinDecomposed.iterator.map {
          case (joinRequest, decomposedRequestsTry) =>
            val joinValuesTry = decomposedRequestsTry.map { groupByRequestsWithPrefix =>
              groupByRequestsWithPrefix.iterator.flatMap {
                case Right(keyMissingException) => {
                  Map(keyMissingException.requestName + "_exception" -> keyMissingException.getMessage)
                }
                case Left(PrefixedRequest(prefix, groupByRequest)) => {
                  responseMap
                    .getOrElse(groupByRequest,
                               Failure(new IllegalStateException(
                                 s"Couldn't find a groupBy response for $groupByRequest in response map")))
                    .map { valueMap =>
                      if (valueMap != null) {
                        valueMap.map { case (aggName, aggValue) => prefix + "_" + aggName -> aggValue }
                      } else {
                        Map.empty[String, AnyRef]
                      }
                    }
                    // prefix feature names
                    .recover { // capture exception as a key
                      case ex: Throwable =>
                        if (debug || Math.random() < 0.001) {
                          logger.error(s"Failed to fetch $groupByRequest", ex)
                        }
                        Map(groupByRequest.name + "_exception" -> ex.traceString)
                    }
                    .get
                }
              }.toMap
            }
            joinValuesTry match {
              case Failure(ex) => joinRequest.context.foreach(_.incrementException(ex))
              case Success(responseMap) =>
                joinRequest.context.foreach { ctx =>
                  ctx.distribution("response.keys.count", responseMap.size)
                }
            }
            joinRequest.context.foreach { ctx =>
              ctx.distribution("internal.latency.millis", System.currentTimeMillis() - startTimeMs)
              ctx.increment("internal.request.count")
            }
            Response(joinRequest, joinValuesTry)
        }.toSeq
        responses
      }
  }

  /**
    * Fetch method to simulate a random access interface for Chronon
    * by distributing requests to relevant GroupBys. This is a batch
    * API which allows the caller to provide a sequence of ColumnSpec
    * queries and receive a mapping of results.
    *
    * TODO: Metrics
    * TODO: Collection identifier for metrics
    * TODO: Consider removing prefix interface for this method
    * TODO: Consider using simpler response type since mapping is redundant
    *
    * @param columnSpecs – batch of ColumnSpec queries
    * @return Future map of query to GroupBy response
    */
  def fetchColumns(
      columnSpecs: Seq[ColumnSpec]
  ): Future[Map[ColumnSpec, Response]] = {
    val startTimeMs = System.currentTimeMillis()

    // Generate a mapping from ColumnSpec query --> GroupBy request
    val groupByRequestsByQuery: Map[ColumnSpec, Request] =
      columnSpecs.map {
        case query =>
          val prefix = query.prefix.getOrElse("")
          val requestName = s"${query.groupByName}.${query.columnName}"
          val keyMap = query.keyMapping.getOrElse(Map())
          query -> PrefixedRequest(prefix, Request(requestName, keyMap, Some(startTimeMs), None)).request
      }.toMap

    // Start I/O and generate a mapping from query --> GroupBy response
    val groupByResponsesFuture = fetchGroupBys(groupByRequestsByQuery.values.toList)
    groupByResponsesFuture.map { groupByResponses =>
      val resultsByRequest = groupByResponses.iterator.map { response => response.request -> response.values }.toMap
      val responseByQuery = groupByRequestsByQuery.map {
        case (query, request) =>
          val results = resultsByRequest
            .getOrElse(
              request,
              Failure(new IllegalStateException(s"Couldn't find a groupBy response for $request in response map"))
            )
            .map { valueMap =>
              if (valueMap != null) {
                valueMap.map {
                  case (aggName, aggValue) =>
                    val resultKey = query.prefix.map(p => s"${p}_${aggName}").getOrElse(aggName)
                    resultKey -> aggValue
                }
              } else {
                Map.empty[String, AnyRef]
              }
            }
            .recoverWith { // capture exception as a key
              case ex: Throwable =>
                if (debug || Math.random() < 0.001) {
                  logger.error(s"Failed to fetch $request", ex)
                }
                Failure(ex)
            }
          val response = Response(request, results)
          query -> response
      }

      responseByQuery
    }
  }
}

object FetcherBase {
  private[online] case class GroupByRequestMeta(
      groupByServingInfoParsed: GroupByServingInfoParsed,
      batchRequest: GetRequest,
      streamingRequestOpt: Option[GetRequest],
      endTs: Option[Long],
      context: Metrics.Context
  )
}
