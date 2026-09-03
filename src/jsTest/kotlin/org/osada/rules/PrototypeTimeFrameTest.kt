package org.osada.rules

import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.absoluteMonth
import org.osada.scenario.PROTOTYPE_DEFAULT_MONTHS
import org.osada.scenario.Scenario
import org.osada.scenario.getPrototypeUnitsAvailable
import kotlin.js.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OG's **prototype time frame** (`.xscn` `@848`, gated on `opt_custom_time_frame`) — the window the
 * brilliant-victory award and the prototype trigger draw from.
 *
 * OSADA has had the award since the port began and drew it from `date.year + 1` only, which is a
 * calendar-year approximation of a MONTH count measured from the scenario date. 69 of the 397
 * deployed scenarios whose source parses author a window.
 *
 * The year-wrap cases the backlog asks for are the ones a year-based rule cannot express at all:
 * December + 1 lands in the following January, and a window longer than twelve months spans three
 * calendar years.
 */
class PrototypeTimeFrameTest : OgRulesTestHarness() {
    private companion object {
        /** Above `PROTOTYPE_MIN_COST` once `CURRENCY_MULTIPLIER` is applied. */
        const val EXPENSIVE = 400
        const val FIRST_EQID = 980
    }

    /** eqid -> the (year, month) that record enters service, so a test can name what it expects. */
    private val entering = mutableMapOf<Int, Pair<Int, Int>>()

    @BeforeTest
    fun setup() {
        installTestWorld()
        entering.clear()
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    /** A prototype-eligible tank entering service in [year]/[month] (1-based). */
    private fun tank(
        eqid: Int,
        year: Int,
        month: Int,
    ): Int {
        Equipment.putEquipment(
            eqid,
            EquipmentData().apply {
                name = "Prototype $year-$month"
                uclass = UnitClass.TANK.value
                target = UnitType.HARD.value
                movmethod = MovMethod.TRACKED.value
                country = 1
                cost = EXPENSIVE
                yearavailable = year
                monthavailable = month
                yearexpired = year + 10
                monthexpired = 12
            },
        )
        entering[eqid] = year to month
        return eqid
    }

    private fun scenario(
        year: Int,
        month: Int,
        months: Int? = null,
    ): Scenario =
        Scenario(null).apply {
            // Date's month argument is 0-based; the tests speak in 1-based calendar months.
            date = Date(year, month - 1, 15)
            prototypeTimeFrameMonths = months
        }

    private fun drawnFrom(scenario: Scenario): Set<Pair<Int, Int>> =
        scenario.getPrototypeUnitsAvailable(1).mapNotNull { entering[it] }.toSet()

    /** The ordinal is what makes every year-wrap case fall out with no special case. */
    @Test
    fun theMonthOrdinalIsContinuousAcrossAYearBoundary() {
        assertEquals(
            1,
            absoluteMonth(1944, 1) - absoluteMonth(1943, 12),
            "January follows December by one month",
        )
        assertEquals(
            absoluteMonth(1943, 1),
            absoluteMonth(1943, 0),
            "a month of 0 is clamped to January rather than landing in the previous December",
        )
    }

    /**
     * **December + 1.** The window opens the month AFTER the scenario date, so a battle fought in
     * December 1943 with a one-month frame reaches exactly January 1944 — which a year-based rule
     * would state as "all of 1944".
     */
    @Test
    fun aOneMonthWindowInDecemberReachesTheFollowingJanuaryAndNothingElse() {
        tank(FIRST_EQID, 1943, 12)
        tank(FIRST_EQID + 1, 1944, 1)
        tank(FIRST_EQID + 2, 1944, 2)

        assertEquals(
            setOf(1944 to 1),
            drawnFrom(scenario(1943, 12, months = 1)),
            "December itself is already here, and February is past the window",
        )
    }

    /** **December + 9** — the manual's own default window, opened at the year boundary. */
    @Test
    fun theDefaultWindowInDecemberSpansTheFollowingJanuaryToSeptember() {
        tank(FIRST_EQID, 1944, 1)
        tank(FIRST_EQID + 1, 1944, 9)
        tank(FIRST_EQID + 2, 1944, 10)

        assertEquals(
            setOf(1944 to 1, 1944 to 9),
            drawnFrom(scenario(1943, 12, months = PROTOTYPE_DEFAULT_MONTHS)),
        )
    }

    /**
     * **A window over twelve months.** The shipped corpus exercises 1-12 only, but OG 25.10.27.0
     * raised the accepted maximum to 60, so nothing here may clamp at a year.
     */
    @Test
    fun aWindowLongerThanAYearSpansThreeCalendarYears() {
        tank(FIRST_EQID, 1943, 6)
        tank(FIRST_EQID + 1, 1943, 7)
        tank(FIRST_EQID + 2, 1944, 6)
        tank(FIRST_EQID + 3, 1945, 6)
        tank(FIRST_EQID + 4, 1945, 8)

        assertEquals(
            setOf(1943 to 7, 1944 to 6, 1945 to 6),
            drawnFrom(scenario(1943, 6, months = 24)),
            "July 1943 through June 1945: the scenario's own June is behind the window and " +
                "August 1945 is past it",
        )
    }

    /**
     * The 433 deployed scenarios that author no window keep the pre-existing next-calendar-year
     * selection. That is deliberate: the month window needs month-granular `MonthAvail` data, and
     * Panzer Marshal's own inherited equipment sets carry none.
     */
    @Test
    fun aScenarioWithNoAuthoredWindowKeepsTheNextCalendarYearRule() {
        tank(FIRST_EQID, 1944, 1)
        tank(FIRST_EQID + 1, 1944, 12)
        tank(FIRST_EQID + 2, 1945, 1)

        assertEquals(
            setOf(1944 to 1, 1944 to 12),
            drawnFrom(scenario(1943, 6, months = null)),
            "the whole of next year, whatever month the battle was fought in",
        )
    }

    /** The trigger's own parameter overrides the scenario for one award. */
    @Test
    fun anExplicitMonthCountOverridesTheScenariosOwnWindow() {
        tank(FIRST_EQID, 1943, 8)
        tank(FIRST_EQID + 1, 1943, 12)
        val battle = scenario(1943, 6, months = 3)

        assertEquals(
            setOf(1943 to 8),
            battle.getPrototypeUnitsAvailable(1).mapNotNull { entering[it] }.toSet(),
            "the scenario's own three-month window reaches July to September",
        )
        assertEquals(
            setOf(1943 to 8, 1943 to 12),
            battle.getPrototypeUnitsAvailable(1, months = 6).mapNotNull { entering[it] }.toSet(),
            "a trigger's six-month parameter widens it for that one draw",
        )
    }

    /** The window widens the pool; it does not bypass the class and cost filters around it. */
    @Test
    fun theWindowDoesNotAdmitIneligibleClassesOrCheapRecords() {
        tank(FIRST_EQID, 1943, 8)
        Equipment.putEquipment(
            FIRST_EQID + 1,
            EquipmentData().apply {
                name = "Supply Column"
                uclass = UnitClass.GROUND_TRANSPORT.value
                country = 1
                cost = EXPENSIVE
                yearavailable = 1943
                monthavailable = 8
            },
        )
        Equipment.putEquipment(
            FIRST_EQID + 2,
            EquipmentData().apply {
                name = "Cheap Tankette"
                uclass = UnitClass.TANK.value
                country = 1
                cost = 1
                yearavailable = 1943
                monthavailable = 8
            },
        )

        val drawn = scenario(1943, 6, months = 6).getPrototypeUnitsAvailable(1)
        assertEquals(listOf(FIRST_EQID), drawn)
        assertTrue(entering[FIRST_EQID] == 1943 to 8)
    }
}
