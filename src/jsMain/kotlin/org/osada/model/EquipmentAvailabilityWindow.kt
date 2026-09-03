package org.osada.model

/** Months in a year, and the base of the [absoluteMonth] ordinal. */
const val MONTHS_PER_YEAR = 12

/**
 * A month of the calendar as one ordinal, so a window may cross a year boundary with no special
 * case. [month] is 1-based, matching `MonthAvail` and [EquipmentData.monthavailable].
 *
 * An out-of-range month is clamped rather than trusted: a record whose CSV carries 0 would otherwise
 * land in the previous December and be admitted a month early.
 */
fun absoluteMonth(
    year: Int,
    month: Int,
): Int = year * MONTHS_PER_YEAR + (month.coerceIn(1, MONTHS_PER_YEAR) - 1)

/**
 * [country]'s equipment whose availability BEGINS inside the inclusive [start]..[end] window, both
 * expressed as [absoluteMonth] ordinals.
 *
 * The month-granular sibling of [getCountryEquipmentByYearRange], written for OG's prototype time
 * frame ([org.osada.scenario.Scenario.prototypeTimeFrameMonths]). It selects on the month a record
 * ENTERS service, which is what "a prototype of what is coming" means -- not on what happens to be
 * buyable today.
 *
 * In its own file rather than beside its sibling in `EquipmentQueries.kt` only because that file is
 * at detekt's per-file function budget.
 */
fun Equipment.getCountryEquipmentByAvailabilityWindow(
    start: Int,
    end: Int,
    country: Int,
): List<Int> {
    val ids =
        equipmentMap.entries
            .filter { (_, eq) ->
                eq.country == country && absoluteMonth(eq.yearavailable, eq.monthavailable) in start..end
            }.map { it.key }
    return applyAvailabilityFilter(ids)
}
