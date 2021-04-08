import copy
import gc
import importlib
import json
import logging
import ai.zipline.api.ttypes as api
import ai.zipline.utils as utils
import ai.zipline.repo.extract_objects as eo
from typing import List, Dict, Union, Optional, Iterable

logging.basicConfig(level=logging.INFO)


def JoinPart(group_by: api.GroupBy,
             keyMapping: Dict[str, str] = None,  # mapping of key columns from the join
             selectors: Optional[List[Union[api.AggregationSelector, str]]] = None,
             prefix: str = None  # all aggregations will be prefixed with that name
             ) -> api.JoinPart:
    # used for reset for next run
    import_copy = __builtins__['__import__']
    # get group_by's module info from garbage collector
    gc.collect()
    group_by_module_name = None
    for ref in gc.get_referrers(group_by):
        if '__name__' in ref and ref['__name__'].startswith("group_bys"):
            group_by_module_name = ref['__name__']
            break
    logging.debug("group_by's module info from garbage collector {}".format(group_by_module_name))
    group_by_module = importlib.import_module(group_by_module_name)
    __builtins__['__import__'] = eo.import_module_set_name(group_by_module, api.GroupBy)
    if keyMapping:
        utils.check_contains(keyMapping.values(),
                             group_by.keyColumns,
                             "key",
                             group_by.name)

    join_part = api.JoinPart(
        groupBy=group_by,
        keyMapping=keyMapping,
        prefix=prefix
    )
    # reset before next run
    __builtins__['__import__'] = import_copy
    return join_part


def Join(left: api.Source,
         rightParts: List[api.JoinPart],
         check_consistency: bool = False,
         additional_args: List[str] = None,
         additional_env: List[str] = None,
         online: bool = False,
         production: bool = False) -> api.Join:
    # create a deep copy for case: multiple LeftOuterJoin use the same left,
    # validation will fail after the first iteration
    updated_left = copy.deepcopy(left)
    if left.events:
        assert "ts" not in left.events.query.select.keys(), "'ts' is a reserved key word for Zipline," \
            " please specify the expression in timeColumn"
        # mapping ts to query.timeColumn to events only
        updated_left.events.query.select.update({"ts": updated_left.events.query.timeColumn})
    # name is set externally, cannot be set here.
    root_base_source = updated_left.entities if updated_left.entities else updated_left.events
    #root_keys = set(root_base_source.query.select.keys())
    #for joinPart in rightParts:
    #    mapping = joinPart.keyMapping if joinPart.keyMapping else {}
    #    # TODO: Add back validation? Or not?
    #    #utils.check_contains(mapping.keys(), root_keys, "root key", "")
    #    uncovered_keys = set(joinPart.groupBy.keyColumns) - set(mapping.values()) - root_keys
    #    assert not uncovered_keys, f"""
    #    Not all keys columns needed to join with GroupBy:{joinPart.groupBy.name} are present.
    #    Missing keys are: {uncovered_keys},
    #    Missing keys should be either mapped or selected in root.
    #    KeyMapping only mapped: {mapping.values()}
    #    Root only selected: {root_keys}
    #    """

    right_sources = [joinPart.groupBy.sources for joinPart in rightParts]
    # flattening
    right_sources = [source for source_list in right_sources for source in source_list]

    customJson = {
        "check_consistency": check_consistency
    }

    if additional_args:
        customJson["additional_args"] = additional_args

    if additional_env:
        customJson["additional_env"] = additional_env

    metadata = api.MetaData(online=online, production=production, customJson=json.dumps(customJson))

    return api.Join(
        left=updated_left,
        joinParts=rightParts,
        metaData=metadata
    )