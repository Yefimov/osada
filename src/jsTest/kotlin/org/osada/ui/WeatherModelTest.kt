package org.osada.ui

import org.osada.model.EfileConfig
import org.osada.model.weatherZones
import org.osada.rules.GameRandomSource
import org.osada.scenario.Scenario
import kotlin.js.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.osada.weatherZones as ogDefaultWeatherZones

/**
 * Open General's weather as its author specifies it (`ui/WeatherModel`): alternating clear/overcast periods of
 * `((r1+r2+r3+r4)+2)/2` turns, one precipitation type per overcast period, precipitation rolled every
 * turn, the efile's own climate table, `force_weather`, and no reroll on load.
 */
class WeatherModelTest {
    private companion object {
        const val FAIR = 0
        const val OVERCAST = 1
        const val RAIN = 2
        const val SNOW = 3
        const val ZONES = 22
        const val MONTHS = 12
    }

    @BeforeTest
    fun setup() {
        GameRandomSource.resetForTest()
        WeatherModel.stop()
        WeatherModel.restoreSnapshot(null)
    }

    @AfterTest
    fun teardown() {
        WeatherModel.stop()
        EfileConfig.resetForTest()
    }

    /** Every zone and month the same row, so a test states its climate in one line. */
    private fun climate(
        avgClear: Int,
        avgOvercast: Int,
        probSnow: Int,
        probPrecip: Int,
        forceWeather: Int? = null,
    ) {
        val row = intArrayOf(avgClear, avgOvercast, probSnow, probPrecip)
        EfileConfig.setForTest(
            intKeyMap = forceWeather?.let { mapOf("force_weather" to it) } ?: emptyMap(),
            weatherTable = Array(ZONES) { Array(MONTHS) { row.copyOf() } },
        )
    }

    private fun scenario(sky: Int): Scenario =
        Scenario(null).apply {
            atmosferic = sky
            latitude = 2
            date = Date(1939, 11, 7)
            map.turn = 1
        }

    private fun nextTurn(s: Scenario): Int {
        s.map.turn += 1
        WeatherModel.advance(s)
        return s.atmosferic
    }

    // ------------------------------------------------------------------ the table

    @Test
    fun theEfilesOwnTableIsUsedAndOgsDefaultOtherwise() {
        climate(5, 14, 100, 75)
        assertEquals(75, EfileConfig.weatherZones()[2][11][3], "the efile's own table")
        EfileConfig.setForTest()
        assertSame(ogDefaultWeatherZones, EfileConfig.weatherZones(), "no efile table: OG's default")
    }

    // ------------------------------------------------------------------ periods

    @Test
    fun aPeriodLastsBetweenHalfAndOneAndAHalfTimesItsAverage() {
        climate(5, 14, 100, 0, forceWeather = 1)
        repeat(40) { seed ->
            GameRandomSource.start(seed + 1L)
            WeatherModel.stop()
            WeatherModel.init(scenario(SNOW))
            val counter = assertNotNull(WeatherModel.snapshot()).counter
            assertTrue(counter in 7..21, "an overcast period of average 14 lasted $counter")
        }
    }

    /** OG: precipitation is decided EVERY turn of an overcast period, and a dry turn is overcast. */
    @Test
    fun anOvercastPeriodAlternatesSnowAndOvercastButNeverClears() {
        climate(5, 40, 100, 50, forceWeather = 1)
        val s = scenario(SNOW)
        WeatherModel.init(s)
        val skies = (2..12).map { nextTurn(s) }.toSet()

        assertTrue(SNOW in skies && OVERCAST in skies, "both occur within one period: $skies")
        assertFalse(FAIR in skies || RAIN in skies, "no clearing and no rain inside a snow period: $skies")
    }

    /** OG: an overcast period is given ONE precipitation type, which never changes within it. */
    @Test
    fun anOvercastPeriodKeepsOnePrecipitationType() {
        climate(5, 40, 50, 100, forceWeather = 1)
        repeat(20) { seed ->
            GameRandomSource.start(seed + 100L)
            WeatherModel.stop()
            val s = scenario(OVERCAST)
            WeatherModel.init(s)
            val skies = (2..10).map { nextTurn(s) }.toSet()
            assertEquals(1, skies.size, "one type per period, saw $skies")
        }
    }

    // ------------------------------------------------------------------ force_weather

    @Test
    fun forceWeatherKeepsTheAuthoredFirstTurn() {
        climate(5, 14, 100, 0, forceWeather = 1)
        val s = scenario(SNOW)
        WeatherModel.init(s)
        assertEquals(SNOW, s.atmosferic)
    }

    /** Without `force_weather` (ATOMIC, the Forward campaign's efile) turn 1 is drawn like any other
     *  turn of the authored period -- here, a snow period with no chance of precipitation. */
    @Test
    fun withoutForceWeatherTheFirstTurnIsDrawn() {
        climate(5, 14, 100, 0)
        val s = scenario(SNOW)
        WeatherModel.init(s)
        assertEquals(OVERCAST, s.atmosferic, "the authored snow period, but no snow falling today")
    }

    // ------------------------------------------------------------------ persistence

    /** A restore resumes the saved period; it draws nothing and changes no sky. */
    @Test
    fun aRestoreDoesNotReroll() {
        climate(5, 14, 100, 50)
        val original = scenario(SNOW)
        WeatherModel.init(original)
        val saved = assertNotNull(WeatherModel.snapshot())
        val sky = original.atmosferic
        val cursor = GameRandomSource.cursor()

        WeatherModel.stop()
        WeatherModel.restoreSnapshot(saved)
        val reloaded = scenario(sky)
        WeatherModel.init(reloaded)

        assertEquals(saved, WeatherModel.snapshot())
        assertEquals(sky, reloaded.atmosferic)
        assertEquals(cursor, GameRandomSource.cursor(), "no draw on load")
    }

    /** A battle saved under the old model finishes under it; the version survives save and load. */
    @Test
    fun aLegacySaveKeepsTheLegacyModel() {
        WeatherModel.restoreSnapshot(WeatherModel.Snapshot(false, 5, 0, 0, 0))
        val s = scenario(SNOW)
        WeatherModel.init(s)
        nextTurn(s)

        assertEquals(WeatherModel.MODEL_LEGACY, assertNotNull(WeatherModel.snapshot()).model)
    }

    @Test
    fun aNewBattleRunsTheOgModel() {
        climate(5, 14, 100, 50)
        WeatherModel.init(scenario(FAIR))
        assertEquals(WeatherModel.MODEL_OG, assertNotNull(WeatherModel.snapshot()).model)
    }

    /** A scenario that does not let weather change the ground keeps its ground whatever falls. */
    @Test
    fun disabledGroundChangeLeavesTheGroundAlone() {
        climate(1, 40, 100, 100, forceWeather = 1)
        val s = scenario(SNOW).apply { weatherCanChangeGround = false }
        val ground = s.ground
        WeatherModel.init(s)
        repeat(10) { nextTurn(s) }
        assertEquals(ground, s.ground)
    }
}
