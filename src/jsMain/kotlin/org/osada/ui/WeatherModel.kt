package org.osada.ui

import org.osada.GameHolder
import org.osada.rules.GroundConditionModel
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
 * On a change it drives the [WeatherRenderer] overlay and refreshes the status-bar glyph.
 *
 * The ground itself is [GroundConditionModel]'s job and is ticked EVERY turn, not only on a weather
 * change: OG mires and freezes ground after a run of turns, so the interesting turn is usually one
 * where the sky did not change at all. Gated on the scenario's "weather can change ground" option,
 * which 155 of the 502 shipped scenarios set.
 *
 * Per-scenario state; call [init] on scenario load and [advance] each turn.
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

    /**
     * The half of this model's state that a save has to carry, because it cannot be re-derived
     * from the scenario: which kind of spell is running and how much of it is left.
     *
     * `atmosferic` says what the sky IS; it says nothing about how many more turns of it are
     * coming. Without this, every load re-rolled a fresh full-length spell from the current sky,
     * so a save taken on the last turn of a downpour resumed into a brand-new downpour, and a
     * player could reroll the weather by saving and loading. The ground runs
     * ([GroundConditionModel]) are here for the same reason: a save two turns into a three-turn
     * rain run came back with the run reset to zero and the mud never arrived.
     */
    data class Snapshot(
        val clearPhase: Boolean,
        val counter: Int,
        val rainRun: Int,
        val snowRun: Int,
        val dryRun: Int,
    )

    /** Parked by the restore and consumed by the next [init], which is the only point at which the
     *  scenario this belongs to actually exists. Cleared on use, so a later NEW battle cannot
     *  inherit a restored battle's spell. */
    private var pendingRestore: Snapshot? = null

    fun snapshot(): Snapshot? =
        if (!active) {
            null
        } else {
            GroundConditionModel.runs().let { (rain, snow, dry) -> Snapshot(clearPhase, counter, rain, snow, dry) }
        }

    fun restoreSnapshot(snapshot: Snapshot?) {
        pendingRestore = snapshot
    }

    fun init(s: Scenario?) {
        if (s == null || weatherZones.isEmpty()) {
            active = false
            pendingRestore = null
            return
        }
        zone = s.latitude.coerceIn(0, weatherZones.size - 1)
        month = monthOf(s)
        val restored = pendingRestore
        pendingRestore = null
        if (restored != null) {
            clearPhase = restored.clearPhase
            counter = restored.counter
            GroundConditionModel.restoreRuns(restored.rainRun, restored.snowRun, restored.dryRun)
        } else {
            clearPhase = s.atmosferic == FAIR
            counter = phaseLen(if (clearPhase) 0 else 1)
            GroundConditionModel.reset()
        }
        lastTurn = s.map.turn
        active = true
    }

    /** JS `Date.getMonth()` is 0-based. Read on every advance, never cached across turns: a battle
     *  that runs from January into February must roll February's table from February onwards, and
     *  the month captured at load stopped being true the moment the calendar crossed over. */
    private fun monthOf(s: Scenario): Int = (s.date.getMonth() + 1).coerceIn(1, MONTHS_IN_YEAR)

    fun stop() {
        active = false
    }

    fun advance(s: Scenario?) {
        if (!active || s == null || s.map.turn == lastTurn) return // fire once per game turn
        lastTurn = s.map.turn
        month = monthOf(s)
        rollIfSpellEnded(s)
        // Every turn, including the ones the sky spent unchanged -- that is where a run completes.
        advanceGround(s)
    }

    private fun rollIfSpellEnded(s: Scenario) {
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
        GameHolder.instance?.ui?.refreshWeatherDisplay()
    }

    /**
     * One turn of ground simulation. The scenario's own ground stands unless the author opted into
     * weather-driven ground with `weatherchg` — or the ruleset overrides that either way.
     */
    private fun advanceGround(s: Scenario) {
        if (!GroundConditionModel.followsWeather(s.weatherCanChangeGround)) return
        val newGround = GroundConditionModel.advance(s.ground, s.atmosferic)
        if (newGround == s.ground) return
        s.ground = newGround
        // Activate the movement table (mud/frozen = reduced move; frozen crosses rivers).
        s.setMoveTable()
        GameHolder.instance?.ui?.refreshWeatherDisplay()
    }
}
