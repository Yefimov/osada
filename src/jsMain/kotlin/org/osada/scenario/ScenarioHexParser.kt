package org.osada.scenario

import org.osada.model.Hex
import org.osada.model.setHex
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * [ScenarioLoader]'s `<hex>` element parser. Split out purely to keep [ScenarioLoader] within
 * the project's function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal object ScenarioHexParser {
    fun parse(
        scenario: Scenario,
        doc: Document,
    ) {
        val hexElements = doc.getElementsByTagName("hex")
        for (i in 0 until hexElements.length) {
            val el = hexElements.item(i) ?: continue
            parseHexElement(el, scenario)
        }
    }

    private fun parseHexElement(
        el: Element,
        scenario: Scenario,
    ) {
        val row = el.getAttribute("row")?.toIntOrNull()
        val col = el.getAttribute("col")?.toIntOrNull()
        val inBounds = row != null && col != null && row < scenario.map.rows && col < scenario.map.cols
        if (!inBounds) return
        val hex = scenario.map.map!![row][col]
        applyHexAttributes(el, hex)
        parseHexUnits(el, hex, scenario)
        scenario.map.setHex(row, col)
    }

    private fun applyHexAttributes(
        el: Element,
        hex: Hex,
    ) {
        el.getAttribute("terrain")?.toIntOrNull()?.let { hex.terrain = it }
        el.getAttribute("road")?.toIntOrNull()?.let { hex.road = it }
        el.getAttribute("rail")?.toIntOrNull()?.let { hex.rail = it }
        // Pre-placed land minefields, as a per-side bitmask (`1 shl side`). OG authors them in the
        // scenario binary's `byte6` -- bit 1 Axis, bit 2 Allied -- and 27 of the 502 scenarios OSADA
        // ships carry 320 mined hexes between them, every one of which was silently dropped on
        // import before this attribute existed (`docs/og-fidelity-plan.md` C.1). Absent on every
        // scenario not yet re-exported, which reads as "no minefields" and is correct for them.
        //
        // Detection is deliberately NOT authored: a pre-placed field starts undetected by everyone
        // and is revealed by standing next to it, so an authored field ambushes exactly once.
        el.getAttribute("mines")?.toIntOrNull()?.let { hex.mines = it }
        el.getAttribute("name")?.let { hex.name = it }
        el.getAttribute("flag")?.toIntOrNull()?.let { hex.flag = it }
        el.getAttribute("owner")?.toIntOrNull()?.let { hex.owner = it }
        el.getAttribute("victory")?.toIntOrNull()?.let { hex.victorySide = it }
        el.getAttribute("deploy")?.toIntOrNull()?.let { hex.isDeployment = it }
        el.getAttribute("supply")?.toIntOrNull()?.let { hex.isDeployment = it }
    }

    private fun parseHexUnits(
        el: Element,
        hex: Hex,
        scenario: Scenario,
    ) {
        for (j in 0 until el.childNodes.length) {
            val unitNode = el.childNodes.item(j) as? Element
            if (unitNode == null || unitNode.nodeName != "unit") continue
            ScenarioUnitParser.parse(unitNode, scenario)?.let { hex.setUnit(it) }
        }
    }
}
