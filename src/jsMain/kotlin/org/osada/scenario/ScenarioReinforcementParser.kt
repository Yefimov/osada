package org.osada.scenario

import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * [ScenarioLoader]'s `<reinforce>` element parser. Split out purely to keep [ScenarioLoader]
 * within the project's function-count/class-size limits -- not expected to be called from
 * elsewhere.
 */
internal object ScenarioReinforcementParser {
    fun parse(
        scenario: Scenario,
        doc: Document,
    ) {
        val reinforceElements = doc.getElementsByTagName("reinforce")
        for (i in 0 until reinforceElements.length) {
            val el = reinforceElements.item(i) ?: continue
            parseReinforceElement(el, scenario)
        }
    }

    private fun parseReinforceElement(
        el: Element,
        scenario: Scenario,
    ) {
        val turn = el.getAttribute("turn")?.toIntOrNull() ?: return
        for (j in 0 until el.childNodes.length) {
            val atNode = el.childNodes.item(j) as? Element
            if (atNode == null || atNode.nodeName != "at") continue
            parseReinforceAtNode(atNode, turn, scenario)
        }
    }

    private fun parseReinforceAtNode(
        atNode: Element,
        turn: Int,
        scenario: Scenario,
    ) {
        val row = atNode.getAttribute("row")?.toIntOrNull() ?: return
        val col = atNode.getAttribute("col")?.toIntOrNull() ?: return
        for (k in 0 until atNode.childNodes.length) {
            val unitNode = atNode.childNodes.item(k) as? Element
            if (unitNode == null || unitNode.nodeName != "unit") continue
            ScenarioUnitParser.parse(unitNode, scenario)?.let { scenario.addReinforcement(turn, row, col, it) }
        }
    }
}
