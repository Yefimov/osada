package org.osada.scenario

import kotlin.js.Date

/**
 * The scenario calendar: how an authored rate is read, and how the date advances under it.
 *
 * Split out of [Scenario] and [ScenarioLoader] on the same grounds as `ScenarioReinforcements.kt` —
 * both were over detekt's function budget — but the two halves belong together anyway: the parser
 * decides what a complete round costs in days and this is the only code that spends it.
 *
 * **The bug this exists because of.** OpenSuite writes `Turns/Day` and `Days/Turn` as two adjacent
 * bytes and zero means "that radio mode is inactive", so every campaign that uses neither stores
 * `0/0`. Panzer Marshal's JavaScript read the field as `value || 1`, which quietly turned that zero
 * into one; the Kotlin port kept the zero, and the date then advanced after EVERY player's turn —
 * two days per displayed turn on a two-sided battle. [parseCalendarRate] is where the `|| 1` went.
 */

private const val MILLIS_PER_DAY = 86_400_000.0

/** An authored rate, resolved: how many complete rounds one date step costs, and how many days it
 *  moves when it comes. */
internal data class CalendarRate(
    val completeRoundsPerDateStep: Int,
    val daysPerDateStep: Int,
)

/**
 * Converts OpenSuite's two mutually exclusive calendar modes into the counters used by [Scenario].
 * `Turns/Day = N` advances one day after N complete rounds; `Days/Turn = N` advances N days after
 * one complete round. A malformed pair falls back to Panzer Marshal's historical default of one
 * complete round per day.
 */
internal fun parseCalendarRate(
    authoredTurnsPerDay: String?,
    authoredDaysPerTurn: String?,
): CalendarRate {
    val turnsPerDay = authoredTurnsPerDay.positiveIntOrNull()
    val daysPerTurn = authoredDaysPerTurn.positiveIntOrNull()
    return when {
        turnsPerDay != null -> CalendarRate(turnsPerDay, 1)
        daysPerTurn != null -> CalendarRate(1, daysPerTurn)
        else -> CalendarRate(1, 1)
    }
}

private fun String?.positiveIntOrNull(): Int? = this?.trim()?.toIntOrNull()?.takeIf { it > 0 }

/**
 * One player phase spent. The date moves only when the counter reaches a whole number of ROUNDS,
 * which is what [configureCalendarForPlayerCount] put into `turnsPerDay`.
 */
internal fun Scenario.advanceCalendar() {
    dayTurn++
    if (dayTurn >= turnsPerDay) {
        dayTurn = 0
        date = Date(date.getTime() + MILLIS_PER_DAY * daysPerTurn)
    }
}

/**
 * Resolves the authored ROUND count into the PHASE count `advanceCalendar` counts down.
 *
 * `Scenario.endTurn` runs once per player, not once per round, so an authored "3 turns per day" is
 * three complete rounds and therefore six phases on a two-sided battle. Called by
 * [ScenarioPlayerParser] as soon as the player list is known, which is why [ScenarioLoader] leaves
 * a two-player-shaped provisional value behind it.
 */
internal fun Scenario.configureCalendarForPlayerCount(playerCount: Int) {
    turnsPerDay = calendarRoundsPerDateStep * playerCount.coerceAtLeast(1)
}
