package org.osada.ui

import org.osada.GameHolder
import org.osada.scenario.Scenario
import org.osada.ui.WeatherModel.advance
import org.osada.ui.WeatherModel.init
import org.osada.weatherZones
import kotlin.random.Random

/**
 * OG-faithful per-turn weather simulation. Each new game turn it transitions the scenario's
 * atmospheric condition (Fair/Overcast/Rain/Snow) using the climate-zone monthly table
 * ([weatherZones], indexed by `scenario.latitude` + month): clear spells alternate with overcast
 * spells, and an overcast spell may precipitate (rain, or snow by the zone's snow probability).
 * On a change it drives the [WeatherRenderer] overlay, refreshes the status-bar glyph, and — when
 * the scenario's "weather can change ground" option is set — flips the ground to Mud (rain) /
 * Frozen (snow). Per-scenario state; call [init] on scenario load and [advance] each turn.
 */
object WeatherModel {
    private const val FAIR = 0
    private const val OVERCAST = 1
    private const val RAIN = 2
    private const val SNOW = 3

    private const val MONTHS_IN_YEAR = 12
    private const val WEATHER_ROLL_SCALE = 100

    // weatherZones row layout: [avgClear, avgOvercast, probSnow%, probPrecip%].
    private const val PRECIP_PROB_INDEX = 3

    private var zone = 0
    private var month = 1 // 1-12
    private var clearPhase = true // in a clear spell (vs an overcast spell)
    private var counter = 0 // turns left in the current spell
    private var lastTurn = -1
    private var active = false
    private var initialGround = 0 // scenario's designed ground; weather-induced mud/frozen reverts here
    private var groundByWeather = false

    fun init(s: Scenario?) {
        if (s == null || weatherZones.isEmpty()) {
            active = false
            return
        }
        zone = s.latitude.coerceIn(0, weatherZones.size - 1)
        month = (s.date.getMonth() + 1).coerceIn(1, MONTHS_IN_YEAR) // JS Date.getMonth() is 0-based
        clearPhase = s.atmosferic == FAIR
        counter = phaseLen(if (clearPhase) 0 else 1)
        lastTurn = s.map.turn
        initialGround = s.ground
        groundByWeather = false
        active = true
    }

    fun stop() {
        active = false
    }

    fun advance(s: Scenario?) {
        if (!active || s == null || s.map.turn == lastTurn) return // fire once per game turn
        lastTurn = s.map.turn
        if (counter > 0) {
            counter--
            if (counter > 0) return
        }
        // current spell ended -> flip phase and roll the weather for the new spell
        val prev = s.atmosferic
        val row = weatherZones[zone][month - 1] // [avgClear, avgOvercast, probSnow%, probPrecip%]
        if (clearPhase) {
            clearPhase = false
            counter = phaseLen(1)
            s.atmosferic =
                if (Random.nextInt(WEATHER_ROLL_SCALE) < row[PRECIP_PROB_INDEX]) {
                    if (Random.nextInt(WEATHER_ROLL_SCALE) < row[2]) SNOW else RAIN
                } else {
                    OVERCAST
                }
        } else {
            clearPhase = true
            counter = phaseLen(0)
            s.atmosferic = FAIR
        }
        if (s.atmosferic != prev) onChange(s)
    }

    /** Spell length ~ uniform in [avg/2, 1.5*avg], min 1 turn. which: 0=clear avg, 1=overcast avg. */
    private fun phaseLen(which: Int): Int {
        val avg = weatherZones[zone][month - 1][which].coerceAtLeast(1)
        return (avg / 2 + Random.nextInt(avg + 1)).coerceAtLeast(1)
    }

    private fun onChange(s: Scenario) {
        WeatherRenderer.start(s.atmosferic)
        if (s.weatherCanChangeGround) {
            val newGround =
                when (s.atmosferic) {
                    RAIN -> {
                        groundByWeather = true
                        2
                    } // Mud
                    SNOW -> {
                        groundByWeather = true
                        1
                    } // Frozen
                    FAIR ->
                        if (groundByWeather) {
                            groundByWeather = false
                            initialGround
                        } else {
                            s.ground // clear spell dries back to designed ground
                        }
                    else -> s.ground // Overcast: leave ground as-is
                }
            if (newGround != s.ground) {
                s.ground = newGround
                s.setMoveTable() // activate the movement table (mud/frozen = reduced move; frozen crosses rivers)
            }
        }
        GameHolder.instance?.ui?.refreshWeatherDisplay()
    }
}
