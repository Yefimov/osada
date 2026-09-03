#!/usr/bin/env python3
"""
Static validation of campaign dialogue, conditions, effects and scenario actions.

Run by `./gradlew verifyStaticChecks` (and therefore by `check`). Validates EVERY campaign in
resources/campaigns/data, so a campaign that has no authored dialogue simply passes trivially.

What it enforces, and why each rule exists:

  * routing is intact              - the dialogue layer must never alter campaign progression
  * unique node ids per scenario   - duplicate ids make `next` ambiguous
  * `next` / choice `next` resolve - a dangling pointer strands the player mid-conversation
  * orders are reachable           - the LAST node must be one the runtime's resolveNext can end
                                     on, or the conversation never hands over to the orders stage
  * no unintended cycle            - a conversation must not loop forever
  * known condition keys           - a typo would silently change which lines appear
  * known effect / action types    - an unknown type is dropped at runtime, so catch it here
  * globally unique effect ids     - the effect id IS the idempotency key; a collision would
                                     silently suppress the second effect for the whole campaign
  * effect values within clamps    - keeps authored consequences inside the balance envelope
  * grantUnit eqids exist          - in the campaign's OWN country equipment file
  * eventFired events are declared - in the scenario XML the action belongs to, or the objective
                                     is permanently unreachable and nothing says so at runtime
  * portrait files exist           - a missing asset degrades to initials, but silently

Exits non-zero and prints every problem found.
"""

import json
import os
import sys
from collections import Counter
from xml.etree import ElementTree

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "jsMain", "resources")
CAMPAIGN_DIR = os.path.join(RES, "resources", "campaigns", "data")
EQUIPMENT_DIR = os.path.join(RES, "resources", "equipment", "eqp-united")
CAMPAIGN_LIST = os.path.join(RES, "resources", "campaigns", "campaignlist.js")

GRADES = ("briliant", "victory", "tactical", "lose")

CONDITION_KEYS = {
    "campaignFile", "currentScenario", "previousScenario", "previousOutcome", "scenarioOutcome",
    "selectedChoices", "allFlags", "anyFlags", "noneFlags", "completedActions", "failedActions",
    "minSuccesses", "maxSuccesses", "minScenarioIndex", "maxScenarioIndex",
}
EFFECT_TYPES = {
    "setFlag", "clearFlag", "prestige", "grantUnit", "experience", "resupply",
    "shiftReinforcements", "unlockEquipment", "deploymentSlots", "route",
}
ACTION_TYPES = {"hexesHeld", "hexesNotHeld", "unitsSurvived", "finishedByTurn", "coreLossesAtMost",
                "eventFired"}
SCENARIO_DIR = os.path.join(RES, "resources", "scenarios", "data")

# Mirrors EffectLimits.kt. Kept in sync deliberately: the parser clamps at runtime, but silently,
# so authored data that relies on being clamped is a balance bug we want surfaced at build time.
CLAMPS = {"prestige": 400, "experience": 150, "shiftReinforcements": 3, "deploymentSlots": 4}


def campaign_countries():
    """campaign file -> country flag, read from campaignlist.js (a JS array literal)."""
    out = {}
    try:
        with open(CAMPAIGN_LIST, encoding="utf-8") as fh:
            text = fh.read()
        start = text.index("[")
        entries = json.loads(text[start:text.rindex("]") + 1])
        for e in entries:
            if "file" in e and "flag" in e:
                out[e["file"]] = e["flag"]
    except (OSError, ValueError) as exc:
        print("  note: could not read campaignlist.js (%s); eqid checks skipped" % exc)
    return out


def valid_eqids(flag):
    """Equipment ids available to a campaign whose country flag is `flag`."""
    path = os.path.join(EQUIPMENT_DIR, "equipment-country-%d.json" % (flag + 1))
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as fh:
        return {int(k) for k in json.load(fh)["units"].keys()}


def check_graph(dialogue, where, problems):
    ids = [line.get("id") for line in dialogue]
    if len(ids) != len(set(ids)):
        dupes = [i for i, n in Counter(ids).items() if n > 1]
        problems.append("%s duplicate node ids: %s" % (where, dupes))
    idset = set(ids)

    for line in dialogue:
        lid = line.get("id")
        if not line.get("speaker") or not line.get("text"):
            problems.append("%s node %s missing speaker or text" % (where, lid))
        if line.get("next") and line["next"] not in idset:
            problems.append("%s node %s -> unknown next '%s'" % (where, lid, line["next"]))
        for choice in line.get("choices", []):
            if not choice.get("id"):
                problems.append("%s node %s has a choice without an id" % (where, lid))
            if choice.get("next") and choice["next"] not in idset:
                problems.append("%s choice %s -> unknown next '%s'" % (where, choice.get("id"), choice["next"]))

    if dialogue and not reaches_orders(dialogue):
        problems.append("%s has no terminal node - dialogue can never reach the orders stage" % where)

    check_cycles(dialogue, idset, where, problems)


def reaches_orders(dialogue):
    """Whether the conversation can hand over to the orders stage.

    This mirrors `ScenarioBriefingNavigation.resolveNext` exactly, which is NOT "some node has
    neither `next` nor `choices`". The runtime resolves `choice.next ?: line.next`, and when both
    are absent it falls through to `BriefingData.nextSequential` - the following entry in the array.
    So the ONLY node that can end the conversation is the last one, and it ends it when it carries
    no `next` of its own and at least one way out of it (its plain flow, or one of its choices) has
    no `next` either.

    Getting this wrong in the strict direction is what flagged six perfectly playable single-node
    path-selection briefings (`forward15`, `bn4s03` and friends): one choice node, no `next`
    anywhere, which the runtime happily walks straight into the orders stage.
    """
    last = dialogue[-1]
    if last.get("next"):
        return False
    choices = last.get("choices") or []
    return not choices or any(not c.get("next") for c in choices)


def check_cycles(dialogue, idset, where, problems):
    """Walk `next` edges only. A choice branching back is authorial intent; an unconditional
    `next` chain that revisits a node is an infinite loop the player cannot escape."""
    by_id = {l.get("id"): l for l in dialogue}
    for start in idset:
        seen, node = set(), start
        while node is not None:
            if node in seen:
                problems.append("%s unconditional next-cycle involving '%s'" % (where, node))
                break
            seen.add(node)
            line = by_id.get(node)
            node = line.get("next") if line and not line.get("choices") else None


def check_conditions(line, where, problems):
    cond = line.get("conditions")
    if not isinstance(cond, dict):
        return
    for key in cond:
        if key not in CONDITION_KEYS:
            problems.append("%s node %s unknown condition key '%s'" % (where, line.get("id"), key))
    for grade in cond.get("previousOutcome", []):
        if grade not in GRADES:
            problems.append("%s node %s previousOutcome '%s' is not an engine grade"
                            % (where, line.get("id"), grade))
    for scen, grades in (cond.get("scenarioOutcome") or {}).items():
        for grade in grades:
            if grade not in GRADES:
                problems.append("%s node %s scenarioOutcome[%s] '%s' is not an engine grade"
                                % (where, line.get("id"), scen, grade))


def check_effects(choice, where, eqids, seen_ids, problems):
    for eff in choice.get("effects", []):
        eid = eff.get("id")
        if not eid:
            problems.append("%s choice %s has an effect without an id" % (where, choice.get("id")))
            continue
        seen_ids[eid] += 1
        etype = eff.get("type")
        if etype not in EFFECT_TYPES:
            problems.append("%s effect %s unknown type '%s'" % (where, eid, etype))
            continue
        if etype in CLAMPS:
            field = "amount" if etype in ("prestige", "experience") else (
                "turns" if etype == "shiftReinforcements" else "delta")
            if abs(eff.get(field, 0)) > CLAMPS[etype]:
                problems.append("%s effect %s %s=%s exceeds clamp %d"
                                % (where, eid, field, eff.get(field), CLAMPS[etype]))
        if etype in ("setFlag", "clearFlag") and not eff.get("flag"):
            problems.append("%s effect %s missing 'flag'" % (where, eid))
        if etype == "grantUnit":
            if "eqid" not in eff:
                problems.append("%s effect %s missing 'eqid'" % (where, eid))
            elif eqids is not None and eff["eqid"] not in eqids:
                problems.append("%s effect %s eqid %s is not in this campaign's equipment"
                                % (where, eid, eff["eqid"]))


def scenario_event_ids(scenario):
    """Authored `<event id>` values in a scenario XML, or None when the file is unreadable.

    An `eventFired` action naming an event the scenario does not declare can never be satisfied,
    so the objective would silently be unreachable forever - exactly the class of authoring
    mistake that is invisible at runtime (the rule simply resolves to "not achieved").
    """
    path = os.path.join(SCENARIO_DIR, scenario)
    if not os.path.exists(path):
        return None
    try:
        root = ElementTree.parse(path).getroot()
    except ElementTree.ParseError as exc:
        print("  note: %s could not be parsed (%s); event checks skipped" % (scenario, exc))
        return None
    return {e.get("id") for e in root.iter("event") if e.get("id")}


def check_actions(entry, where, scenario, problems):
    declared = None
    for action in entry.get("actions", []):
        if action.get("type") not in ACTION_TYPES:
            problems.append("%s unknown action type '%s'" % (where, action.get("type")))
        if not action.get("id"):
            problems.append("%s action without an id" % where)
        if action.get("type") != "eventFired":
            continue
        events = action.get("events") or []
        if not events:
            problems.append("%s action %s is eventFired with no 'events'" % (where, action.get("id")))
            continue
        if declared is None:
            declared = scenario_event_ids(scenario)
        if declared is None:
            continue
        for event_id in events:
            if event_id not in declared:
                problems.append("%s action %s references event '%s' not declared in %s"
                                % (where, action.get("id"), event_id, scenario))


def check_epilogues(epilogues, where, problems):
    ids = [entry.get("id") for entry in epilogues]
    if len(ids) != len(set(ids)):
        dupes = [i for i, n in Counter(ids).items() if n > 1]
        problems.append("%s duplicate epilogue ids: %s" % (where, dupes))
    for entry in epilogues:
        if not entry.get("id"):
            problems.append("%s epilogue without an id" % where)
        if not entry.get("speaker") or not entry.get("text"):
            problems.append("%s epilogue %s missing speaker or text" % (where, entry.get("id")))
        for grade in entry.get("outcomes", []):
            if grade not in GRADES:
                problems.append("%s epilogue %s outcome '%s' is not an engine grade"
                                % (where, entry.get("id"), grade))
        check_conditions(entry, "%s/epilogue" % where, problems)


def check_campaign(path, countries, problems):
    name = os.path.basename(path)
    with open(path, encoding="utf-8") as fh:
        try:
            data = json.load(fh)
        except ValueError as exc:
            problems.append("%s is not valid JSON: %s" % (name, exc))
            return 0

    flag = countries.get(name)
    eqids = valid_eqids(flag) if flag is not None else None
    seen_effect_ids = Counter()
    dialogue_lines = 0

    for index, entry in enumerate(data):
        scenario = entry.get("scenario", "<index %d>" % index)
        where = "%s/%s" % (name, scenario)

        outcome = entry.get("outcome") or {}
        for grade in GRADES:
            if grade not in outcome:
                problems.append("%s missing outcome branch '%s'" % (where, grade))
            else:
                for field in ("goto", "prestige"):
                    if field not in outcome[grade]:
                        problems.append("%s outcome.%s missing '%s'" % (where, grade, field))

        check_actions(entry, where, entry.get("scenario"), problems)
        check_epilogues(entry.get("epilogues", []), where, problems)

        briefing = entry.get("briefing")
        if not briefing:
            continue
        dialogue = briefing.get("dialogue", [])
        if not dialogue:
            # A briefing may exist purely to carry presentation data (e.g. `background`, the
            # scenario's chapter wallpaper) with no conversation authored yet. There is no graph
            # to validate, and demanding a terminal node from an empty one is meaningless.
            continue
        dialogue_lines += len(dialogue)
        check_graph(dialogue, where, problems)
        for line in dialogue:
            check_conditions(line, where, problems)
            portrait = line.get("portrait")
            if portrait and not os.path.exists(os.path.join(RES, portrait)):
                problems.append("%s node %s portrait not found: %s" % (where, line.get("id"), portrait))
            for choice in line.get("choices", []):
                check_effects(choice, where, eqids, seen_effect_ids, problems)

    for eid, count in seen_effect_ids.items():
        if count > 1:
            problems.append("%s effect id '%s' used %d times - ids must be unique (idempotency key)"
                            % (name, eid, count))
    return dialogue_lines


def main():
    if not os.path.isdir(CAMPAIGN_DIR):
        print("campaign data directory not found: %s" % CAMPAIGN_DIR)
        return 1

    countries = campaign_countries()
    problems = []
    checked = authored = 0

    for name in sorted(os.listdir(CAMPAIGN_DIR)):
        if not name.endswith(".json"):
            continue
        checked += 1
        lines = check_campaign(os.path.join(CAMPAIGN_DIR, name), countries, problems)
        if lines:
            authored += 1
            print("  %-28s %d dialogue lines" % (name, lines))

    print("campaign dialogue check: %d campaigns, %d with authored dialogue" % (checked, authored))
    if problems:
        print("\n%d problem(s):" % len(problems))
        for p in problems:
            print("  ! %s" % p)
        return 1
    print("campaign dialogue check: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
