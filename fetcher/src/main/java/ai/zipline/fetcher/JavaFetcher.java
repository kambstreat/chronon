package ai.zipline.fetcher;

import ai.zipline.api.KVStore;
import ai.zipline.api.Constants;
import ai.zipline.fetcher.Fetcher;
import ai.zipline.fetcher.Fetcher.Request;
import ai.zipline.fetcher.Fetcher.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import scala.collection.JavaConversions;
import scala.collection.JavaConverters;
import scala.collection.Seq;
import scala.collection.mutable.ArrayBuffer;
import scala.collection.mutable.Buffer;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.compat.java8.FutureConverters;
import scala.concurrent.duration.Duration;

public class JavaFetcher  {
  public static final Long DEFAULT_TIMEOUT = 10000L;

  Fetcher fetcher;

  public JavaFetcher(KVStore kvStore, String metaDataSet, Long timeoutMillis) {
    this.fetcher = new Fetcher(kvStore, metaDataSet, timeoutMillis);
  }

  public JavaFetcher(KVStore kvStore, String metaDataSet) {
    this(kvStore, metaDataSet, DEFAULT_TIMEOUT);
  }

  public JavaFetcher(KVStore kvStore, Long timeoutMillis) {
    this(kvStore, Constants.ZiplineMetadataKey(), timeoutMillis);
  }

  public JavaFetcher(KVStore kvStore) {
    this(kvStore, Constants.ZiplineMetadataKey());
  }


  private List<Response> convertResponses(Future<Seq<Response>> responses) throws Exception {
    return JavaConversions.seqAsJavaList(Await.result(responses, Duration.Inf()));
//    return FutureConverters
//        .toJava(responses)
//        .toCompletableFuture()
//        .thenApply(JavaConversions::seqAsJavaList);
  }

  private Seq<Request> convertJavaRequestList(List<JavaRequest> requests) {
    ArrayBuffer<Request> scalaRequests = new ArrayBuffer<>();
    for (JavaRequest request : requests) {
      Request convertedRequest = request.toScalaRequest();
      scalaRequests.$plus$eq(convertedRequest);
    }
    return scalaRequests.toSeq();
  }

  public List<Response> fetchGroupBys(List<JavaRequest> requests) throws Exception {
    // Get responses from the fetcher
    Future<Seq<Response>> responses = this.fetcher.fetchGroupBys(convertJavaRequestList(requests));
    // Convert responses to CompletableFuture
    return convertResponses(responses);
  }

  public List<Response> fetchJoin(List<JavaRequest> requests) throws Exception {
    Future<Seq<Response>> responses = this.fetcher.fetchJoin(convertJavaRequestList(requests));
    // Convert responses to CompletableFuture
    return convertResponses(responses);
  }

}
