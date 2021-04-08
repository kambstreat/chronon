namespace py api
namespace java ai.zipline.api

// cd /path/to/zipline
// thrift --gen py -out api/py/ai/zipline api/thrift/api.thrift

struct Query {
    1: optional map<string, string> selects
    2: optional list<string> wheres
    3: optional string startPartition
    4: optional string endPartition
    5: optional string timeColumn
    6: optional list<string> setups = []
    7: optional string mutationTimeColumn
    8: optional string reversalColumn
}


struct StagingQuery {
    1: optional string name
    // query should be written with `{{ start_date }}` and `{{ end_date }}` templates
    2: optional string query
    // on the first run, `{{ start_date }}` will be set to this user provided start date, future incremental runs will
    // set it to the latest existing partition + 1 day
    3: optional string startDate
    4: optional list<string> dependencies
    5: optional list<string> querySetupCommands
}

struct EventSource {
    1: optional string table
    2: optional string topic
    3: optional Query query
}

struct EntitySource {
    1: optional string snapshotTable
    2: optional string mutationTable
    3: optional string mutationTopic
    4: optional Query query
}

union Source {
    1: EventSource events
    2: EntitySource entities
}

enum Operation {
    // Un-Deletable operations - Monoids
    // Once an aggregate is created from a group of elements,
    // asking to delete on of the elements is invalid - and ignored.
    MIN = 0
    MAX = 1
    FIRST = 2
    LAST = 3
    UNIQUE_COUNT = 4
    APPROX_UNIQUE_COUNT = 5

    // Deletable operations - Abelian Groups
    // Once an aggregate is created from a group of elements,
    // deletion of any particular element can be done freely.
    COUNT = 6
    SUM = 7
    AVERAGE = 8
    VARIANCE = 9  // TODO
    SKEW = 10     // TODO
    KURTOSIS = 11  // TODO
    APPROX_PERCENTILE = 12 // TODO

    LAST_K = 13
    FIRST_K = 14,
    TOP_K = 15,
    BOTTOM_K = 16
}

// integers map to milliseconds in the timeunit
enum TimeUnit {
    HOURS = 0
    DAYS = 1
}

struct Window {
    1: i32 length
    2: TimeUnit timeUnit
}

// maps to multiple output fields - one per window definition
struct Aggregation {
    1: optional string inputColumn
    2: optional Operation operation
    3: optional map<string, string> argMap
    4: optional list<Window> windows
}

// used internally not exposed - maps 1:1 with a field in the output
struct AggregationPart {
    1: optional string inputColumn
    2: optional Operation operation
    3: optional map<string, string> argMap
    4: optional Window window
}

enum Accuracy {
    TEMPORAL = 0,
    SNAPSHOT = 1
}

// Equivalent to a FeatureSet in zipline terms
struct GroupBy {
    1: optional MetaData metaData
    // CONDITION: all sources select the same columns
    // source[i].select.keys() == source[0].select.keys()
    2: optional list<Source> sources
    // CONDITION: all keyColumns are selected by each of the
    // set(sources[0].select.keys()) <= set(keyColumns)
    3: optional list<string> keyColumns
    // when aggregations are not specified,
    // we assume the source is already grouped by keys
    4: optional list<Aggregation> aggregations
    5: optional Accuracy accuracy
}

struct AggregationSelector {
  1: optional string name
  2: optional list<Window> windows
}



struct JoinPart {
    1: optional GroupBy groupBy
    2: optional map<string, string> keyMapping
    3: optional list<AggregationSelector> selectors
    4: optional string prefix

}

struct MetaData {
    1: optional string name
    // marking this as true means that the conf can be served online
    // once marked online, a conf cannot be changed - compiling the conf won't be allowed
    2: optional bool online
    // marking this as true means that the conf automatically generates a staging copy
    // this flag is also meant to help a monitoring system re-direct alerts appropriately
    3: optional bool production
    4: optional string customJson
    5: optional list<string> dependencies
    6: optional map<string, string> tableProperties
    // todo: add sanity check in materialize script
    7: optional string outputNamespace
}


// A Temporal join - with a root source, with multiple groupby's.
struct Join {
    1: optional MetaData metaData
    2: optional Source left
    3: list<JoinPart> joinParts
    // map of left key column name and values representing the skew keys
    // these skew keys will be excluded from the output
    // specifying skew keys will also help us scan less raw data before aggregation & join
    // example: {"zipcode": ["94107", "78934"], "country": ["'US'", "'IN'"]}
    4: optional map<string,list<string>> skewKeys
}