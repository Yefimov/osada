package org.osada.i18n

import org.osada.TerrainType
import org.osada.groundConditionNames
import org.osada.monthNamesShort
import org.osada.movMethodNames
import org.osada.rules.SupplyContext
import org.osada.rules.SupplyContextRules
import org.osada.rules.SupplySource
import org.osada.sideNames
import org.osada.terrainNames
import org.osada.unitClassNames
import org.osada.unitTypeNames
import org.osada.weatherConditionNames

/**
 * Stable-key localization for enum-like gameplay labels that legacy code still stores as lists.
 * 13 vs. the 11-function budget: one line per enum-like list (month/unitClass/unitType/movMethod/
 * side/weather/groundCondition + a couple of composites). Splitting would scatter one coherent
 * concept across files for no readability gain.
 */
@Suppress("TooManyFunctions")
internal object GameText {
    fun monthShort(monthIndex: Int): String = indexed("calendar.month.short", monthIndex, monthNamesShort)

    fun unitClass(unitClass: Int): String = indexed("game.unit_class", unitClass, unitClassNames)

    fun unitType(unitType: Int): String = indexed("game.unit_type", unitType, unitTypeNames)

    fun movementType(movementType: Int): String = indexed("game.movement_type", movementType, movMethodNames)

    fun weather(weather: Int): String = indexed("game.weather", weather, weatherConditionNames)

    fun weatherShort(weather: Int): String = indexed("game.weather.short", weather, weatherConditionNames)

    fun ground(ground: Int): String = indexed("game.ground", ground, groundConditionNames)

    fun side(side: Int): String = indexed("game.side", side, sideNames)

    fun terrain(terrain: Int): String = indexed("game.terrain", terrain, terrainNames)

    fun equipmentStatLabel(index: Int): String = I18n.t("equipment.detail.stat.$index.label")

    fun equipmentStatHelp(index: Int): String = I18n.t("equipment.detail.stat.$index.help")

    fun unitStatHelp(id: String): String = I18n.t("unit_info.stat.$id.help")

    /** Short one-line summary of a [SupplyContext] -- supply source plus enemy
     *  pressure. The exact per-factor breakdown belongs in the action tooltip, not here. */
    fun supplyContextSummary(context: SupplyContext): String {
        val base = I18n.t(supplyContextSourceKey(context))
        return if (context.adjacentEnemies <= 0) {
            base
        } else {
            I18n.plural(
                "unit_info.supply_context.with_adjacent_enemies",
                context.adjacentEnemies,
                mapOf("base" to base),
            )
        }
    }

    /** Same summary rebuilt from the two stable tokens a turn-report entry stores, so a log row
     *  written under one language still renders in the language selected now. */
    fun supplyContextSummary(
        source: String?,
        adjacentEnemies: Int,
    ): String {
        val key =
            when (source) {
                SupplySource.AIRFIELD_CARRIER.name -> "unit_info.supply_context.airfield_carrier"
                SupplySource.NAVAL.name -> "unit_info.supply_context.naval"
                SupplySource.NONE.name -> "unit_info.supply_context.none"
                SupplyContextRules.CITY_SUPPLY_TOKEN -> "unit_info.supply_context.city"
                SupplySource.GROUND.name -> "unit_info.supply_context.field"
                else -> return ""
            }
        val base = I18n.t(key)
        return if (adjacentEnemies <= 0) {
            base
        } else {
            I18n.plural("unit_info.supply_context.with_adjacent_enemies", adjacentEnemies, mapOf("base" to base))
        }
    }

    private fun supplyContextSourceKey(context: SupplyContext): String =
        when (context.source) {
            SupplySource.AIRFIELD_CARRIER -> "unit_info.supply_context.airfield_carrier"
            SupplySource.NAVAL -> "unit_info.supply_context.naval"
            SupplySource.NONE -> "unit_info.supply_context.none"
            SupplySource.GROUND ->
                if (context.terrain == TerrainType.CITY.value) {
                    "unit_info.supply_context.city"
                } else {
                    "unit_info.supply_context.field"
                }
        }

    private fun indexed(
        prefix: String,
        index: Int,
        fallback: List<String>,
    ): String {
        val key = "$prefix.$index"
        val value = I18n.t(key)
        return if (value == key) fallback.getOrNull(index) ?: I18n.t("common.unknown") else value
    }
}
