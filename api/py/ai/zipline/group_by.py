import copy
import json
from dataclasses import dataclass
from typing import List, Optional, Union

import ai.zipline.api.ttypes as ttypes
import ai.zipline.utils as utils

OperationType = int  # type(zthrift.Operation.FIRST)

#  The GroupBy's default online/production status is None and it will inherit
# online/production status from the Joins it is included.
# If it is included in multiple joins, it is considered online/production
# if any of the joins are online/production. Otherwise it is not online/production
# unless it is explicitly marked as online/production on the GroupBy itself.
DEFAULT_ONLINE = None
DEFAULT_PRODUCTION = None


def window_to_str_pretty(window: ttypes.Window):
    unit = ttypes.TimeUnit._VALUES_TO_NAMES[window.timeUnit].lower()
    return f"{window.length} {unit}"


def op_to_str(operation: OperationType):
    return ttypes.Operation._VALUES_TO_NAMES[operation].lower()


def Select(*args, **kwargs):
    args = {x: x for x in args}
    return {**args, **kwargs}


def Aggregations(**kwargs):
    """
    fills in missing arguments of the aggregation object.
    default operation is LAST
    default name is {arg_name}_{operation_name}
    default input column is {arg_name}
    """
    aggs = []
    for name, aggregation in kwargs.items():
        assert isinstance(aggregation, ttypes.Aggregation), \
            f"argument for {name}, {aggregation} is not instance of Aggregation"
        if not aggregation.name:
            aggregation.name = name
        if not aggregation.operation:  # Default operation is last
            aggregation.operation = ttypes.Operation.LAST
        if not aggregation.inputColumn:  # Default input column is the variable name
            aggregation.inputColumn = name
        aggs.append(aggregation)
    return aggs


def contains_windowed_aggregation(aggregations: Optional[List[ttypes.Aggregation]]):
    if not aggregations:
        return False
    for agg in aggregations:
        if agg.windows:
            return True
    return False


def validate_group_by(sources: List[ttypes.Source],
                      keys: List[str],
                      aggregations: Optional[List[ttypes.Aggregation]]):
    # check ts is not included in query.select
    #TODO: Actually run this validation - returning for now
    return
    first_source_columns = set(utils.get_columns(sources[0]))
    assert "ts" not in first_source_columns, "'ts' is a reserved key word for Zipline," \
                                             " please specify the expression in timeColumn"
    for src in sources:
        query = utils.get_query(src)
        if src.events:
            assert query.ingestionTimeColumn is None, "ingestionTimeColumn should not be specified for " \
                                                      "event source as it should be the same with timeColumn"
            assert query.reversalColumn is None, "reversalColumn should not be specified for event source " \
                                                 "as it won't have mutations"
        else:
            if contains_windowed_aggregation(aggregations):
                assert query.timeColumn, "Please specify timeColumn for entity source with windowed aggregations"

    # all sources should select the same columns
    for i, source in enumerate(sources[1:]):
        column_set = set(utils.get_columns(source))
        column_diff = column_set ^ first_source_columns
        assert not column_diff, f"""
Mismatched columns among sources [1, {i+2}], Difference: {column_diff}
"""

    # all keys should be present in the selected columns
    unselected_keys = set(keys) - first_source_columns
    assert not unselected_keys, f"""
Keys {unselected_keys}, are unselected in source
"""


def GroupBy(sources: Union[List[ttypes.Source], ttypes.Source],
            keys: List[str],
            aggregations: Optional[List[ttypes.Aggregation]],
            online: bool = DEFAULT_ONLINE,
            production: bool = DEFAULT_PRODUCTION) -> ttypes.GroupBy:
    assert sources, "Sources are not specified"

    if isinstance(sources, ttypes.Source):
        sources = [sources]

    validate_group_by(sources, keys, aggregations)
    # create a deep copy for case: multiple group_bys use the same sources,
    # validation_sources will fail after the first group_by
    updated_sources = copy.deepcopy(sources)
    # mapping ts with query.timeColumn
    for src in updated_sources:
        if src.events:
            src.events.query.select.update({"ts": src.events.query.timeColumn})
        else:
            # timeColumn for entity source is optional
            if src.entities.query.timeColumn:
                src.entities.query.select.update({"ts": src.entities.query.timeColumn})
    query = utils.get_query(updated_sources[0])

    # TODO: Make dependencies work and add to metadata constructor
    # dependencies = [dep for source in updated_sources for dep in utils.get_dependencies(source)]
    # metadata = json.dumps({"dependencies": dependencies})

    metadata = ttypes.MetaData(online=online, production=production)

    return ttypes.GroupBy(
        sources=updated_sources,
        keyColumns=keys,
        aggregations=aggregations,
        metaData=metadata
    )