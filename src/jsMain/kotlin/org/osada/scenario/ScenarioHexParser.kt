package org.osada.scenario

import org.osada.model.Hex
import org.osada.model.getPlayers
import org.osada.model.setHex
import org.osada.model.setVictoryTiersForSide
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
        applyHexAttributes(el, hex, scenario)
        parseHexUnits(el, hex, scenario)
        scenario.map.setHex(row, col)
    }

    private fun applyHexAttributes(
        el: Element,
        hex: Hex,
        scenario: Scenario,
    ) {
        el.getAttribute("terrain")?.toIntOrNull()?.let { hex.terrain = it }
        el.getAttribute("road")?.toIntOrNull()?.let { hex.road = it }
        el.getAttribute("rail")?.toIntOrNull()?.let { hex.rail = it }
        // OG's railroad station, recovered 2026-08-27 (`Hex.station`). Authored map data like
        // `rail` beside it, so it is read unconditionally rather than behind a ruleset key.
        el.getAttribute("station")?.toIntOrNull()?.let { hex.station = it != 0 }
        applyDiffRecoveredAttributes(el, hex)
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
        applyTypedVictoryAttributes(el, hex, scenario)
        el.getAttribute("deploy")?.toIntOrNull()?.let { hex.isDeployment = it }
        el.getAttribute("supply")?.toIntOrNull()?.let { hex.isDeployment = it }
    }

    /**
     * OG's Typed VH masks (manual 3.7.2), normalized by importer to OSADA side. Absent means 7 —
     * counts for every level. `victiers` is the one-mask format briefly deployed on 2026-08-30;
     * keep reading it and attach it to the objective's owning side so old saves/custom XML work.
     */
    private fun applyTypedVictoryAttributes(
        el: Element,
        hex: Hex,
        scenario: Scenario,
    ) {
        el.getAttribute("victiers")?.toIntOrNull()?.let { legacy ->
            val ownerSide =
                scenario.map
                    .getPlayers()
                    .firstOrNull { it.id == hex.owner }
                    ?.side
                    ?: hex.victorySide.takeIf { it in 0..1 }
                    ?: 0
            hex.setVictoryTiersForSide(ownerSide, legacy)
        }
        el.getAttribute("victiers0")?.toIntOrNull()?.let { hex.victoryTiersSide0 = it }
        el.getAttribute("victiers1")?.toIntOrNull()?.let { hex.victoryTiersSide1 = it }
    }

    /**
     * The three per-hex properties recovered by the 2026-08-29 controlled OpenSuite diff
     * (`SCENARIO_FORMAT_NOTES.md`), split from [applyHexAttributes] purely to keep that function
     * inside detekt's complexity budget — adding them took it from 12 to 17.
     *
     * All authored map data, like `station` and `rail`, so all read unconditionally rather than
     * behind a ruleset key. Whether any of it DOES anything is a rule's question:
     * [org.osada.rules.AirfieldQuality] for the dirt strip, [org.osada.rules.TriggerHexes] for the
     * trigger.
     */
    private fun applyDiffRecoveredAttributes(
        el: Element,
        hex: Hex,
    ) {
        // Dirt airfield/port -- `.xscn` grid @19 bit 6, one flag covering both.
        el.getAttribute("dirt")?.toIntOrNull()?.let { hex.dirt = it != 0 }
        // OG's escape hexes (manual 3.7.4), `@13` bit 3 and `@12` bit 4 -- separate ground and air
        // exits, either or both on one hex.
        el.getAttribute("escapeground")?.toIntOrNull()?.let { hex.escapeGround = it != 0 }
        el.getAttribute("escapeair")?.toIntOrNull()?.let { hex.escapeAir = it != 0 }
        // Trigger hexes -- @20 type, @21 parameter, @22 equipment id for actions 8/9, and the
        // message text inlined from OG's own `.xtrig` sidecar rather than kept as its line index.
        el.getAttribute("trigger")?.toIntOrNull()?.let { hex.trigger = it }
        el.getAttribute("trigparam")?.toIntOrNull()?.let { hex.triggerParam = it }
        el.getAttribute("trigequip")?.toIntOrNull()?.let { hex.triggerEquip = it }
        el.getAttribute("trigmsg")?.let { hex.triggerMessage = it }
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
