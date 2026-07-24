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
        applyUnitAttributes(el, unit)
        applyCostAccounting(unit, owner, scenario)
        return unit
    }

    private fun applyUnitAttributes(
        el: Element,
        unit: GameUnit,
    ) {
        el.getAttribute("face")?.toIntOrNull()?.let { unit.facing = it }
        el.getAttribute("flag")?.toIntOrNull()?.let { unit.flag = it }
        el.getAttribute("transport")?.toIntOrNull()?.let { unit.setTransport(it) }
        el.getAttribute("carrier")?.toIntOrNull()?.let { unit.carrier = it }
        el.getAttribute("exp")?.toIntOrNull()?.let { unit.experience = it }
        el.getAttribute("ent")?.toIntOrNull()?.let { unit.entrenchment = it }
        el.getAttribute("str")?.toIntOrNull()?.let { unit.strength = it }
        if (el.hasAttribute("ldr")) {
            unit.leader = Leaders.generateLeader(unit)
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
