package org.osada.ui

import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.i18n.GameText
import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.TerrainEx
import org.osada.rules.Attachments
import org.osada.rules.Craters
import org.osada.rules.EntrenchmentDefence
import org.osada.rules.UnitPredicates
import org.osada.rules.WeatherCombatRules

/**
 * The entrenchment tooltip on both unit cards -- the friendly card's ⛨ badge and the enemy card's
 * ENT chip.
 *
 * Every figure is read from the rule that applies it: the terrain baseline from
 * [EntrenchmentDefence.terrainBaseline] (the value `GameUnit.entrench` snaps to), the defence from
 * [EntrenchmentDefence.defenderBonus] (the value combat adds). Current level and terrain baseline
 * are shown as two separate facts on purpose: moving resets the level, so the terrain alone never
 * promises "+5 on arrival".
 */
internal object EntrenchmentTooltip {
    /** The BASE rugged-defence chance: 5% per level (`RuggedCombat.ruggedDefenceChance` with no
     *  Tenacious Defence on either side). */
    private const val RUGGED_PERCENT_PER_LEVEL = 5

    /** Whether the badge/chip is worth showing: the unit holds a level, or can dig one. */
    fun visible(unit: GameUnit): Boolean = unit.entrenchment > 0 || UnitPredicates.canEntrench(unit)

    /** The full explanation; [attacker] adds the line for that exchange when it is a hostile unit. */
    fun text(
        unit: GameUnit,
        attacker: GameUnit? = null,
    ): String =
        buildList {
            val level = unit.entrenchment
            add(I18n.t("unit_info.entrenchment.value", mapOf("value" to level)))
            addAll(terrainLines(unit))
            add(I18n.t("unit_info.entrenchment.combat", mapOf("value" to level)))
            val hostile = attacker?.takeIf { it !== unit && it.player?.side != unit.player?.side }
            if (unit.unitData().uclass == UnitClass.INFANTRY.value) {
                add(I18n.t("unit_info.entrenchment.doubled", mapOf("value" to level * 2)))
                addAll(ruggedLines(unit, hostile))
            }
            attackerLine(unit, hostile)?.let(::add)
            add(I18n.t("unit_info.entrenchment.weather"))
            val weatherBonus = WeatherCombatRules.defenseBonus()
            if (weatherBonus > 0) {
                add(I18n.t("unit_info.entrenchment.precipitation", mapOf("value" to weatherBonus)))
            }
        }.joinToString("\n")

    private fun terrainLines(unit: GameUnit): List<String> {
        val hex = unit.getHex()
        return when {
            !UnitPredicates.canEntrench(unit) -> listOf(I18n.t("unit_info.entrenchment.cannot_dig"))
            hex == null -> emptyList()
            else -> diggingLines(unit, hex)
        }
    }

    private fun diggingLines(
        unit: GameUnit,
        hex: Hex,
    ): List<String> {
        val baseline = EntrenchmentDefence.terrainBaseline(unit, hex)
        val terrainBase = TerrainEx.baseEntrenchment(hex.terrain)
        val craterFloor = Craters.entrenchmentFloor(hex)
        val attachment = Attachments.bonus(unit, Attachments.SLOT_FAST_ENTRENCH)
        return buildList {
            add(
                I18n.t(
                    "unit_info.entrenchment.terrain",
                    mapOf(
                        "terrain" to GameText.terrain(hex.terrain),
                        "base" to terrainBase,
                    ),
                ),
            )
            if (craterFloor > terrainBase) add(I18n.t("unit_info.entrenchment.crater", mapOf("value" to craterFloor)))
            if (attachment > 0) add(I18n.t("unit_info.entrenchment.attachment", mapOf("value" to attachment)))
            add(
                I18n.t(
                    "unit_info.entrenchment.growth",
                    mapOf("base" to baseline, "max" to baseline + EntrenchmentDefence.MAX_ABOVE_TERRAIN),
                ),
            )
            add(I18n.t("unit_info.entrenchment.loss"))
            add(
                I18n.t(
                    "unit_info.entrenchment.compare",
                    mapOf(
                        "city" to TerrainEx.baseEntrenchment(TerrainType.CITY.value),
                        "forest" to TerrainEx.baseEntrenchment(TerrainType.FOREST.value),
                        "fortification" to TerrainEx.baseEntrenchment(TerrainType.FORTIFICATION.value),
                    ),
                ),
            )
        }
    }

    /**
     * The rugged-defence line. With a hostile [attacker] selected it is the pair's own odds, from the
     * function combat rolls against ([EntrenchmentDefence.ruggedOdds]) -- including "none" when this
     * pair cannot raise one. With no attacker it is only the BASE chance, stated as such, with OG's
     * conditions beside it: a bare "25%" read as a promise a defender with no ammunition, or a
     * surprised attack, would never keep.
     */
    private fun ruggedLines(
        unit: GameUnit,
        attacker: GameUnit?,
    ): List<String> {
        if (attacker != null) {
            val name = mapOf("unit" to nameOf(attacker))
            val odds = EntrenchmentDefence.ruggedOdds(attacker, unit)
            return listOf(
                if (odds == null || odds <= 0.0) {
                    I18n.t("unit_info.entrenchment.rugged.against.none", name)
                } else {
                    I18n.t("unit_info.entrenchment.rugged.against", name + ("value" to odds))
                },
            )
        }
        return buildList {
            add(
                I18n.t(
                    "unit_info.entrenchment.rugged",
                    mapOf("value" to unit.entrenchment * RUGGED_PERCENT_PER_LEVEL),
                ),
            )
            if (unit.getAmmo() <= 0) add(I18n.t("unit_info.entrenchment.rugged.no_ammo"))
        }
    }

    private fun nameOf(unit: GameUnit): String = unit.customName ?: unit.unitData(true).name

    private fun attackerLine(
        unit: GameUnit,
        attacker: GameUnit?,
    ): String? {
        val against = attacker?.let { EntrenchmentDefence.forecast(it, unit) }
        if (attacker == null || against == null) return null
        val args = mapOf("unit" to nameOf(attacker), "value" to against.bonus)
        return when {
            against.level <= 0 -> null
            against.bypassed -> I18n.t("unit_info.entrenchment.against.bypassed", args)
            against.doubled -> I18n.t("unit_info.entrenchment.against.doubled", args)
            else -> I18n.t("unit_info.entrenchment.against", args)
        }
    }
}
