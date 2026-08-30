package org.osada.scenario

import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.getPlayer
import org.osada.model.setTransport
import org.osada.rules.GameRules
import org.osada.rules.calculateUnitCosts
import org.osada.rules.isSea
import org.w3c.dom.Element

/**
 * [ScenarioLoader]'s `<unit>` element parser, shared by [ScenarioReinforcementParser] and
 * [ScenarioHexParser]. Split out purely to keep those objects within the project's
 * function-count/class-size limits.
 */
internal object ScenarioUnitParser {
    fun parse(
        el: Element,
        scenario: Scenario,
    ): GameUnit? {
        val eqid = el.getAttribute("id")?.toIntOrNull()
        val owner = el.getAttribute("owner")?.toIntOrNull()
        val isValid = eqid != null && owner != null && eqid >= 0 && owner >= 0
        if (!isValid) return null
        val unit = GameUnit(eqid)
        unit.owner = owner
        applyUnitAttributes(el, unit, scenario)
        applyCostAccounting(unit, owner, scenario)
        return unit
    }

    private fun applyUnitAttributes(
        el: Element,
        unit: GameUnit,
        scenario: Scenario,
    ) {
        el.getAttribute("face")?.toIntOrNull()?.let { unit.facing = it }
        el.getAttribute("flag")?.toIntOrNull()?.let { unit.flag = it }
        el.getAttribute("transport")?.toIntOrNull()?.let { unit.setTransport(it) }
        el.getAttribute("carrier")?.toIntOrNull()?.let { unit.carrier = it }
        el.getAttribute("exp")?.toIntOrNull()?.let { unit.experience = it }
        el.getAttribute("ent")?.toIntOrNull()?.let { unit.entrenchment = it }
        el.getAttribute("str")?.toIntOrNull()?.let { unit.strength = it }
        unit.isTemporaryBorrowed = el.getAttribute("temporaryBorrowed")?.toBooleanStrictOrNull() ?: false
        // Scripted non-combatants (detainees, refugees, civilian columns) are not formations: their
        // destruction must not be filed as an equipment loss, and surviving must not enrol them in
        // the campaign core. Absent attribute keeps every existing scenario's behaviour unchanged.
        unit.nodossier = el.getAttribute("nodossier")?.toBooleanStrictOrNull() ?: false
        // OG's scenario-designated Depot (`GameUnit.isScenarioDepot`), recovered 2026-08-29 from
        // `.xscn` unit @50 bit 1. Authored data, read unconditionally; `DepotSupply` decides
        // whether it does anything.
        el.getAttribute("depot")?.toIntOrNull()?.let { unit.isScenarioDepot = it != 0 }
        applyBasicStrength(el, unit, scenario)
        // OG's Must-Survive Unit (manual 3.7.1), `.xscn` unit @43 bit 0. Already gated on the
        // scenario's AllowMSU switch by the importer, so a bare attribute means the author meant it.
        el.getAttribute("msu")?.toIntOrNull()?.let { unit.mustSurvive = it != 0 }
        if (el.hasAttribute("ldr")) {
            unit.leader = Leaders.generateLeader(unit)
        }
    }

    /**
     * OG's Basic Strength (`GameUnit.basicStrength`, scenario unit `@23`) and the reset its own
     * option controls.
     *
     * > *"Use current / basic strength as defined (**so no reset current to basic**)"* — the
     * > OpenSuite report's own gloss on `opt_use_basic_strength`
     *
     * So the option ON leaves both values as the author wrote them, and OFF resets
     * `current := basic` — which makes **OFF the harsher setting**, since a formation authored
     * `10/5` starts at 5 there. 332 of the 397 deployed scenarios whose source parses set it.
     *
     * A scenario whose source could not be read is `null` and keeps its authored current strength,
     * the direction that takes nothing from the player.
     */
    private fun applyBasicStrength(
        el: Element,
        unit: GameUnit,
        scenario: Scenario,
    ) {
        val basic = el.getAttribute("bstr")?.toIntOrNull() ?: return
        unit.basicStrength = basic
        // Read off the scenario BEING LOADED, never off `GameHolder`: during load the holder still
        // points at the previous scenario (or at nothing), so a unit would be reset according to
        // the last battle's option rather than this one's.
        if (scenario.useBasicStrength == false) {
            unit.strength = basic
        }
    }

    private fun applyCostAccounting(
        unit: GameUnit,
        owner: Int,
        scenario: Scenario,
    ) {
        if (GameRules.isSea(unit)) return
        val unitSide = scenario.map.getPlayer(owner).side
        scenario.unitsCostPerSide[unitSide] += GameRules.calculateUnitCosts(unit.eqid, unit.transport?.eqid ?: -1)
        scenario.expPerSide[unitSide].exp = (scenario.expPerSide[unitSide].exp as Int) + unit.experience
        scenario.expPerSide[unitSide].count = (scenario.expPerSide[unitSide].count as Int) + 1
    }
}
