package org.osada.scenario

import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * [ScenarioLoader]'s `<reserve>` element parser: OG's UNDEPLOYED units, which start the battle in
 * their owner's deployment tray instead of on the map.
 *
 * OG marks them in the unit record itself (`.xscn @30 != 2` with no arrival turn at `@31`), and the
 * player places them by hand. The importer used to put them on the owner's deploy hexes, so Falciu 1
 * opened with seven Soviet formations on the map that OG keeps in the pool
 * (`tools/og-import/add_undeployed_reserve.py`, 346 units in 15 scenarios, 2026-09-29).
 */
internal object ScenarioReserveParser {
    fun parse(
        scenario: Scenario,
        doc: Document,
    ) {
        val reserves = doc.getElementsByTagName("reserve")
        for (i in 0 until reserves.length) {
            val el = reserves.item(i) ?: continue
            for (j in 0 until el.childNodes.length) {
                val unitNode = el.childNodes.item(j) as? Element
                if (unitNode == null || unitNode.nodeName != "unit") continue
                ScenarioUnitParser.parse(unitNode, scenario)?.let { scenario.authoredReserve.add(it) }
            }
        }
    }
}
