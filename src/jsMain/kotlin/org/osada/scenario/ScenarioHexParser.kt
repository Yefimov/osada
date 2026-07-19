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
