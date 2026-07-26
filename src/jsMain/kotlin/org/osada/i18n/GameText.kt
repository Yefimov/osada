package org.osada.i18n

import org.osada.groundConditionNames
import org.osada.monthNamesShort
import org.osada.movMethodNames
import org.osada.sideNames
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

    fun equipmentStatLabel(index: Int): String = I18n.t("equipment.detail.stat.$index.label")

    fun equipmentStatHelp(index: Int): String = I18n.t("equipment.detail.stat.$index.help")

    fun unitStatHelp(id: String): String = I18n.t("unit_info.stat.$id.help")

    fun supplyContext(legacyLabel: String): String {
        val adjacent =
            Regex(
                "(\\d+) adjacent enem(?:y|ies)",
            ).find(legacyLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val baseKey =
            when {
                legacyLabel.startsWith("airfield/carrier") -> "unit_info.supply_context.airfield_carrier"
                legacyLabel.startsWith("naval") -> "unit_info.supply_context.naval"
                legacyLabel.startsWith("no supply") -> "unit_info.supply_context.none"
                legacyLabel.startsWith("city") -> "unit_info.supply_context.city"
                else -> "unit_info.supply_context.field"
            }
        val base = I18n.t(baseKey)
        return if (adjacent == null) {
            base
        } else {
            I18n.plural(
                "unit_info.supply_context.with_adjacent_enemies",
                adjacent,
                mapOf("base" to base),
            )
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
