package org.osada

import org.osada.scenario.CalendarRate
import org.osada.scenario.Scenario
import org.osada.scenario.advanceCalendar
import org.osada.scenario.configureCalendarForPlayerCount
import org.osada.scenario.parseCalendarRate
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals

class ScenarioDayTurnsTest {
    @Test
    fun zeroValuesFallBackToOneCompleteRoundPerDay() {
        assertEquals(
            CalendarRate(completeRoundsPerDateStep = 1, daysPerDateStep = 1),
            parseCalendarRate("0", "0"),
        )
    }

    @Test
    fun missingOrInvalidValuesFallBackToOneCompleteRoundPerDay() {
        val expected = CalendarRate(completeRoundsPerDateStep = 1, daysPerDateStep = 1)
        assertEquals(expected, parseCalendarRate(null, null))
        assertEquals(expected, parseCalendarRate("", ""))
        assertEquals(expected, parseCalendarRate("invalid", "invalid"))
    }

    @Test
    fun turnsPerDayRetainsAuthoredRoundCount() {
        assertEquals(
            CalendarRate(completeRoundsPerDateStep = 3, daysPerDateStep = 1),
            parseCalendarRate(" 3 ", "0"),
        )
    }

    @Test
    fun daysPerTurnRetainsAuthoredCalendarStep() {
        assertEquals(
            CalendarRate(completeRoundsPerDateStep = 1, daysPerDateStep = 3),
            parseCalendarRate("0", "3"),
        )
    }

    @Test
    fun daysPerTurnAdvancesAfterACompleteRound() {
        val scenario = Scenario(null)
        scenario.date = Date(Date.UTC(1918, 5, 8))
        scenario.turnsPerDay = 2
        scenario.daysPerTurn = 3

        scenario.advanceCalendar()
        assertEquals(8, scenario.date.getUTCDate())

        scenario.advanceCalendar()
        assertEquals(11, scenario.date.getUTCDate())
    }

    @Test
    fun turnsPerDayAdvancesOnlyAfterTheAuthoredNumberOfRounds() {
        val scenario = Scenario(null)
        scenario.date = Date(Date.UTC(1919, 5, 14))
        scenario.turnsPerDay = 4
        scenario.daysPerTurn = 1

        repeat(3) { scenario.advanceCalendar() }
        assertEquals(14, scenario.date.getUTCDate())

        scenario.advanceCalendar()
        assertEquals(15, scenario.date.getUTCDate())
    }

    @Test
    fun aCompleteRoundIncludesEveryPlayer() {
        val scenario = Scenario(null)
        scenario.calendarRoundsPerDateStep = 2
        scenario.configureCalendarForPlayerCount(3)

        assertEquals(6, scenario.turnsPerDay)
    }
}
