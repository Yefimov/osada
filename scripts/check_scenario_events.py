#!/usr/bin/env python3
"""
Static validation of authored `<events>` in scenario XML.

Run by `./gradlew verifyScenarioEvents` (and therefore by `check`). Scenarios that author no
events - which today is all but one of them - pass trivially and cost one XML parse each.

Scenario events fail CLOSED at runtime: an event whose gate can never hold simply never fires,
and nothing in the game says so. That silence is the reason this check exists. Every rule below
is a way for an authored event to be permanently dead:

  * unique event ids           - a duplicate is dropped at load with only a console warning
  * resolvable references      - `afterAny` / `requiresUnitsFrom` / `removeFrom` naming an event
                                 that does not exist means the gate can never be satisfied
  * no self-reference          - an event cannot wait for itself
  * references point backwards - events are evaluated once per pass in document order, so an
                                 event that depends on one declared LATER waits an extra pass at
                                 best and reads as a bug at worst
  * anchor on the map          - a proximity centre outside rows/cols can never be approached
  * sane radius                - a negative radius is unreachable
  * spawn eqids exist          - in the merged equipment DB; a missing one places nothing
  * spawn hexes on the map     - `deployReinforcement` searches outward from the named hex
  * contradictory flag gates   - the same flag in both `allFlags` and `noneFlags`

Exits non-zero and prints every problem found.
"""

import json
import os
import sys
from collections import Counter
from xml.etree import ElementTree

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "jsMain", "resources")
SCENARIO_DIR = os.path.join(RES, "resources", "scenarios", "data")
EQUIPMENT_DIR = os.path.join(RES, "resources", "equipment", "eqp-united")

REFERENCE_ATTRS = ("afterAny", "requiresUnitsFrom", "removeFrom")


def all_eqids():
    """Every eqid in the merged DB. Country files are per-country, but an event's spawn is placed
    directly rather than purchased, so the merged set is the right (and only) reachability test."""
    ids = set()
    if not os.path.isdir(EQUIPMENT_DIR):
        return None
    for name in os.listdir(EQUIPMENT_DIR):
        if not name.startswith("equipment-country-") or not name.endswith(".json"):
            continue
        with open(os.path.join(EQUIPMENT_DIR, name), encoding="utf-8") as fh:
            ids.update(int(k) for k in json.load(fh).get("units", {}))
    return ids


def id_list(raw):
    return [part.strip() for part in (raw or "").split(",") if part.strip()]


def int_attr(el, name, default=0):
    try:
        return int(el.get(name, default))
    except (TypeError, ValueError):
        return None


def check_ids(events, where, problems):
    ids = [e.get("id") for e in events]
    for event in events:
        if not event.get("id"):
            problems.append("%s has an <event> without an id" % where)
    for name, count in Counter(i for i in ids if i).items():
        if count > 1:
            problems.append("%s duplicate event id '%s' (%d times)" % (where, name, count))
    return [i for i in ids if i]


def check_references(events, order, where, problems):
    known = set(order)
    for index, event in enumerate(events):
        eid = event.get("id")
        for attr in REFERENCE_ATTRS:
            for ref in id_list(event.get(attr)):
                if ref == eid:
                    problems.append("%s event %s %s references itself" % (where, eid, attr))
                elif ref not in known:
                    problems.append("%s event %s %s -> unknown event '%s'" % (where, eid, attr, ref))
                elif order.index(ref) > index:
                    problems.append("%s event %s %s -> '%s' is declared later; declare the cause "
                                    "before the consequence" % (where, eid, attr, ref))


def check_gates(event, where, problems):
    eid = event.get("id")
    both = set(id_list(event.get("allFlags"))) & set(id_list(event.get("noneFlags")))
    if both:
        problems.append("%s event %s requires and forbids the same flag(s): %s"
                        % (where, eid, sorted(both)))


def check_geometry(event, rows, cols, where, problems):
    eid = event.get("id")
    row, col = int_attr(event, "row"), int_attr(event, "col")
    if row is None or col is None or not on_map(row, col, rows, cols):
        problems.append("%s event %s anchor (%s,%s) is outside the %sx%s map"
                        % (where, eid, event.get("row"), event.get("col"), rows, cols))
    radius = int_attr(event, "radius", 1)
    if radius is None or radius < 0:
        problems.append("%s event %s has an unusable radius '%s'" % (where, eid, event.get("radius")))


def on_map(row, col, rows, cols):
    return rows is not None and cols is not None and 0 <= row < rows and 0 <= col < cols


def check_spawns(event, rows, cols, eqids, where, problems):
    eid = event.get("id")
    for spawn in event.findall("spawn"):
        row, col = int_attr(spawn, "row"), int_attr(spawn, "col")
        if row is None or col is None or not on_map(row, col, rows, cols):
            problems.append("%s event %s spawn hex (%s,%s) is outside the %sx%s map"
                            % (where, eid, spawn.get("row"), spawn.get("col"), rows, cols))
        for unit in spawn.findall("unit"):
            eqid = int_attr(unit, "id", -1)
            if eqid is None or eqid < 0:
                problems.append("%s event %s spawns a <unit> without a usable id" % (where, eid))
            elif eqids is not None and eqid not in eqids:
                problems.append("%s event %s spawns unknown eqid %d" % (where, eid, eqid))


def check_scenario(path, eqids, problems):
    name = os.path.basename(path)
    try:
        root = ElementTree.parse(path).getroot()
    except ElementTree.ParseError as exc:
        problems.append("%s is not valid XML: %s" % (name, exc))
        return 0

    events = list(root.iter("event"))
    if not events:
        return 0

    rows, cols = int_attr(root, "rows", None), int_attr(root, "cols", None)
    order = check_ids(events, name, problems)
    check_references(events, order, name, problems)
    for event in events:
        check_gates(event, name, problems)
        check_geometry(event, rows, cols, name, problems)
        check_spawns(event, rows, cols, eqids, name, problems)
    return len(events)


def main():
    if not os.path.isdir(SCENARIO_DIR):
        print("scenario data directory not found: %s" % SCENARIO_DIR)
        return 1

    eqids = all_eqids()
    if eqids is None:
        print("  note: merged equipment DB not found; spawn eqid checks skipped")
    problems = []
    scenarios = authored = total = 0

    for name in sorted(os.listdir(SCENARIO_DIR)):
        if not name.endswith(".xml"):
            continue
        scenarios += 1
        count = check_scenario(os.path.join(SCENARIO_DIR, name), eqids, problems)
        if count:
            authored += 1
            total += count
            print("  %-28s %d event(s)" % (name, count))

    print("scenario event check: %d scenarios, %d with events, %d events total"
          % (scenarios, authored, total))
    if problems:
        print("\n%d problem(s):" % len(problems))
        for problem in problems:
            print("  ! %s" % problem)
        return 1
    print("scenario event check: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
