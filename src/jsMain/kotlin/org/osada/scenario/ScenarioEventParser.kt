package org.osada.scenario

import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * [ScenarioLoader]'s `<event>` element parser. Split out of [ScenarioLoader] for the same reason
 * [ScenarioReinforcementParser] is: to keep that object inside the project's function-count limit.
 *
 * Authored shape (all attributes optional except `id`):
 *
 * ```xml
 * <events>
 *   <event id="prison-alarm" trigger="proximity" row="10" col="34" radius="3" side="1" combat="1"
 *          noneFlags="sailors_liberated"
 *          message="Alarm at the detention compound!">
 *     <spawn row="10" col="34">
 *       <unit id="218" owner="0" flag="189" str="9" temporaryBorrowed="true" nodossier="true"/>
 *     </spawn>
 *   </event>
 *   <event id="prisoners-rescued" trigger="proximity" row="10" col="34" radius="1" side="1"
 *          combat="1" requiresUnitsFrom="prison-alarm" removeFrom="prison-alarm"
 *          message="The gates are open.">
 *     <spawn row="10" col="34"><unit id="46706" owner="0" flag="189" exp="60" str="5"/></spawn>
 *   </event>
 * </events>
 * ```
 *
 * A scenario with no `<events>` element parses to an empty list, which is why every scenario
 * shipped before this feature is untouched by it.
 */
internal object ScenarioEventParser {
    private const val DEFAULT_RADIUS = 1
    private const val ANY_SIDE = -1

    fun parse(
        scenario: Scenario,
        doc: Document,
    ) {
        scenario.events.clear()
        val elements = doc.getElementsByTagName("event")
        for (i in 0 until elements.length) {
            elements.item(i)?.let { el ->
                parseEvent(el, scenario)?.let { event -> addUnlessDuplicate(scenario, event) }
            }
        }
    }

    private fun addUnlessDuplicate(
        scenario: Scenario,
        event: ScenarioEvent,
    ) {
        if (scenario.events.any { it.id == event.id }) {
            console.warn("[OSADA] duplicate scenario event id '${event.id}' ignored")
        } else {
            scenario.events.add(event)
        }
    }

    private fun parseEvent(
        el: Element,
        scenario: Scenario,
    ): ScenarioEvent? {
        val id = el.getAttribute("id")?.trim()?.takeIf { it.isNotEmpty() }
        if (id == null) {
            console.warn("[OSADA] scenario event without an 'id' dropped")
            return null
        }
        return ScenarioEvent(
            id = id,
            trigger = parseTrigger(el),
            gate = parseGate(el),
            message = el.getAttribute("message")?.trim().orEmpty(),
            spawns = parseSpawns(el, scenario),
            removeFrom = idList(el.getAttribute("removeFrom")),
        )
    }

    private fun parseTrigger(el: Element): ScenarioEventTrigger =
        ScenarioEventTrigger(
            kind =
                if (el.getAttribute("trigger")?.trim()?.lowercase() == "start") {
                    ScenarioEventTriggerKind.START
                } else {
                    ScenarioEventTriggerKind.PROXIMITY
                },
            row = el.getAttribute("row")?.toIntOrNull() ?: 0,
            col = el.getAttribute("col")?.toIntOrNull() ?: 0,
            radius = el.getAttribute("radius")?.toIntOrNull() ?: DEFAULT_RADIUS,
            side = el.getAttribute("side")?.toIntOrNull() ?: ANY_SIDE,
            combatOnly = (el.getAttribute("combat")?.toIntOrNull() ?: 0) != 0,
        )

    private fun parseGate(el: Element): ScenarioEventGate =
        ScenarioEventGate(
            allFlags = idList(el.getAttribute("allFlags")),
            noneFlags = idList(el.getAttribute("noneFlags")),
            afterAny = idList(el.getAttribute("afterAny")),
            requiresUnitsFrom = idList(el.getAttribute("requiresUnitsFrom")),
        )

    private fun parseSpawns(
        el: Element,
        scenario: Scenario,
    ): List<ScenarioEventSpawn> {
        val spawns = mutableListOf<ScenarioEventSpawn>()
        for (i in 0 until el.childNodes.length) {
            val node = (el.childNodes.item(i) as? Element)?.takeIf { it.nodeName == "spawn" } ?: continue
            val row = node.getAttribute("row")?.toIntOrNull()
            val col = node.getAttribute("col")?.toIntOrNull()
            if (row != null && col != null) addSpawnUnits(node, row, col, scenario, spawns)
        }
        return spawns
    }

    private fun addSpawnUnits(
        node: Element,
        row: Int,
        col: Int,
        scenario: Scenario,
        into: MutableList<ScenarioEventSpawn>,
    ) {
        for (j in 0 until node.childNodes.length) {
            val unitNode = (node.childNodes.item(j) as? Element)?.takeIf { it.nodeName == "unit" } ?: continue
            // Cost/experience accounting runs here exactly as it does for a reinforcement wave:
            // an event's units are part of the scenario's order of battle even before they appear.
            ScenarioUnitParser.parse(unitNode, scenario)?.let { into.add(ScenarioEventSpawn(row, col, it)) }
        }
    }

    private fun idList(raw: String?): List<String> =
        raw
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
}
