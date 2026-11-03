"""Read both deployed capture formats; grouped codec from capture commit aaf3352c."""

GROUPED_SCHEMA = "htx-realtime-grouped-v1"


def segment_events(value):
    if value.get("schema") == "htx-realtime-segment-v1":
        return value["events"]
    if value.get("schema") != GROUPED_SCHEMA:
        raise ValueError("unknown realtime segment schema")
    groups, order = value["groups"], value["order"]
    if (
        not isinstance(groups, list)
        or not isinstance(order, list)
        or any(not isinstance(g, list) or not g for g in groups)
    ):
        raise ValueError("invalid grouped segment")
    cursors = [0] * len(groups)
    result = []
    for group in order:
        if (
            type(group) is not int
            or not 0 <= group < len(groups)
            or cursors[group] >= len(groups[group])
        ):
            raise ValueError("invalid grouped order index")
        result.append(groups[group][cursors[group]])
        cursors[group] += 1
    if any(c != len(g) for c, g in zip(cursors, groups)):
        raise ValueError("unreferenced grouped observations")
    return result
