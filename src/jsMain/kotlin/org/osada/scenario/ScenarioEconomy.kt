package org.osada.scenario

import org.osada.CURRENCY_MULTIPLIER
import org.osada.PROTOTYPE_MIN_COST
import org.osada.SCENARIO_START_PRESTIGE
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.absoluteMonth
import org.osada.model.canBeAwardedAsPrototype
import org.osada.model.getCountryEquipmentByAvailabilityWindow
import org.osada.model.getCountryEquipmentByYearRange
import kotlin.random.Random

/**
 * OG's own documented default prototype window, *"Parameter: time frame of the prototype, default
 * 9"* (`Manual_OSuite-Scenario.pdf` p.23) — nine months.
 *
 * Shared by the scenario setting and by `rules/TriggerHexes`' own prototype trigger, which quotes
 * the same sentence, so the manual's number is written down once.
 */
const val PROTOTYPE_DEFAULT_MONTHS = 9

/**
 * The pool a brilliant victory's prototype award is drawn from: Tank..Anti-Tank and
 * Artillery..Tactical Bomber records for [country], above [PROTOTYPE_MIN_COST], entering service
 * inside the scenario's prototype TIME FRAME.
 *
 * ## The window
 *
 * OG's time frame is a month count measured from the scenario date — *"the month after the scenario
 * date through that many months ahead"* — and the author may set it (`.xscn` `@848`, gated on
 * `opt_custom_time_frame` `@1010` bit 0; 69 of the 397 deployed scenarios whose source parses turn
 * it on). [months] overrides the scenario's own value for one draw, which is what the prototype
 * TRIGGER's parameter is.
 *
 * **A scenario that authors no window keeps the old year-based rule**, and that is deliberate
 * rather than an omission. The month window needs month-granular `MonthAvail` data, and PM's own
 * inherited equipment sets (adlerkorps, pacific) have none — every record there reads as January,
 * so a nine-month window opened in, say, February would admit nothing at all and the award would
 * silently never arrive. OG-imported content carries real months and gets the real rule; content
 * with no months keeps the rule that was written for it.
 *
 * **OG's `No Prototype` (`attr` bit 17) is honoured since 2026-08-27** — see
 * [org.osada.model.canBeAwardedAsPrototype]. It is the author's own opt-out from exactly this
 * draw, and 5,989 of the 56,970 merged records carry it; until it was read, every one of them was
 * an eligible award.
 *
 * Prestige/prototype/experience economy queries for [Scenario], split out to keep its function
 * count in bounds.
 */
fun Scenario.getPrototypeUnitsAvailable(
    country: Int,
    months: Int? = null,
): List<Int> {
    val list = prototypeCandidates(country, months).toMutableList()
    val iter = list.iterator()
    while (iter.hasNext()) {
        val eqid = iter.next()
        val eq = Equipment.equipment[eqid] ?: continue
        val uclass = eq.uclass
        val outOfTankRange = uclass < UnitClass.TANK.value || uclass > UnitClass.ANTI_TANK.value
        val outOfArtilleryRange = uclass < UnitClass.ARTILLERY.value || uclass > UnitClass.TACTICAL_BOMBER.value
        val outOfEligibleClasses = outOfTankRange && outOfArtilleryRange
        val tooCheap = eq.cost * CURRENCY_MULTIPLIER < PROTOTYPE_MIN_COST
        if (outOfEligibleClasses || tooCheap || !Equipment.canBeAwardedAsPrototype(eqid)) {
            iter.remove()
        }
    }
    return list
}

/**
 * The unfiltered draw pool: the authored month window when there is one, otherwise next calendar
 * year. Split out so [getPrototypeUnitsAvailable] keeps one loop and one complexity budget.
 */
private fun Scenario.prototypeCandidates(
    country: Int,
    months: Int?,
): List<Int> {
    val window = months?.takeIf { it > 0 } ?: prototypeTimeFrameMonths ?: return nextYearCandidates(country)
    // `getMonth()` is 0-based; the window opens the month AFTER the scenario date and runs for
    // `window` months, so December + 1 is the following January with no year special case.
    val first = absoluteMonth(date.getFullYear(), date.getMonth() + 1) + 1
    return Equipment.getCountryEquipmentByAvailabilityWindow(first, first + window - 1, country)
}

private fun Scenario.nextYearCandidates(country: Int): List<Int> {
    val year = date.getFullYear() + 1
    return Equipment.getCountryEquipmentByYearRange(year, year, country)
}

fun Scenario.getRandomPrototype(
    country: Int,
    months: Int? = null,
): Int {
    val available = getPrototypeUnitsAvailable(country, months)
    if (available.isEmpty()) return -1
    return available[(Random.nextDouble() * available.size).toInt()]
}

fun Scenario.getBalancedPrestige(side: Int): Int {
    var prestige = SCENARIO_START_PRESTIGE + unitsCostPerSide[1 - side] - unitsCostPerSide[side]
    if (prestige < SCENARIO_START_PRESTIGE) prestige = SCENARIO_START_PRESTIGE
    return prestige
}

fun Scenario.getSideUnitsAvgExp(side: Int): Int {
    val data = expPerSide[side]
    val count = data.count as Int
    val exp = data.exp as Int
    return if (count > 0) kotlin.math.round(exp.toDouble() / count).toInt() else 0
}
