package org.osada.ui

import org.osada.GameHolder
import org.osada.legacyWeatherZones
import org.osada.model.EfileConfig
import org.osada.model.weatherZones
import org.osada.rules.GameRandomSource
import org.osada.rules.GroundConditionModel
import org.osada.scenario.Scenario
import org.osada.ui.WeatherModel.advance
import org.osada.ui.WeatherModel.init
import kotlin.random.Random

/**
 * Per-turn weather, as Open General's author specifies it (`luis-guzman.com/OpenGen_Features.html`,
 * *Weather*; `docs/design/combat-movement-heroes-weather-audit.md` §7.1):
 *
 * 1. clear and overcast PERIODS alternate; the scenario's authored sky says which one is running at
 *    the start;
 * 2. a period lasts `((r1+r2+r3+r4)+2)/2` turns, each `r` uniform in `[0, avg-1]`, clamped to
 *    `[avg/2, 3*avg/2]` -- `avg` being the zone's average clear or overcast period for the month;
 * 3. each overcast period is given ONE precipitation type -- snow with the zone's `ProbOfSnow`,
 *    otherwise rain -- that never changes within it;
 * 4. on every turn of an overcast period, precipitation actually falls with `ProbOfPrecip`; a turn
 *    without it is overcast, not clear.
 *
 * The climate table is the active efile's own (`EfileConfig.weatherZones`), OG's default where it
 * ships none. `force_weather=1` keeps the authored sky on the first turn; otherwise (every efile that
 * does not set it) the first turn is drawn by the same rule as every other turn of the authored
 * period. Draw order on a fresh battle: the period's length (4 draws), the precipitation type (1 draw,
 * only when an overcast period starts under an overcast sky, which does not name one), then the
 * first turn's sky (1 draw, only under `force_weather=0` in an overcast period).
 *
 * All draws come from the shared, persisted gameplay stream ([GameRandomSource]), so a multiplayer
 * host and a restore reproduce them. The ground is [GroundConditionModel]'s job, ticked every turn;
 * OG's WaterLevel ground model is NOT adopted here (the atmosphere and the ground are separate
 * decisions), and the scenario's own `weatherchg` switch still decides whether the ground follows.
 *
 * **Old saves keep the model they started with.** A battle saved before this model has no
 * [Snapshot.model] and runs [MODEL_LEGACY] -- spells of one sky, rolled from the LXF table -- until
 * it ends; the version is saved explicitly, so saving and loading never switches it. Every new
 * battle runs [MODEL_OG].
 */
object WeatherModel {
    private const val FAIR = 0
    private const val OVERCAST = 1
    private const val RAIN = 2
    private const val SNOW = 3

    /** The pre-2026-09-27 model: whole spells of one sky, uniform lengths, kotlin.random. */
    const val MODEL_LEGACY = 1

    /** The author's model above. */
    const val MODEL_OG = 2

    private const val MONTHS_IN_YEAR = 12
    private const val MAX_PERIOD_FLIPS = 2

    private var table: Array<Array<IntArray>> = emptyArray()
    private var zone = 0
    private var month = 1 // 1-12
    private var clearPhase = true // in a clear period (vs an overcast one)
    private var counter = 0 // turns left in the current period, this one included
    private var precipSnow = false // the current overcast period's precipitation type
    private var model = MODEL_OG
    private var lastTurn = -1
    private var active = false
    private var initializedFor: Scenario? = null

    /**
     * What a save carries, because none of it can be re-derived from the scenario: which period is
     * running, how much of it is left, its precipitation type, the ground runs -- and which MODEL
     * produced them, so a save never changes model on reload.
     */
    data class Snapshot(
        val clearPhase: Boolean,
        val counter: Int,
        val rainRun: Int,
        val snowRun: Int,
        val dryRun: Int,
        val model: Int = MODEL_LEGACY,
        val precipSnow: Boolean? = null,
    )

    /** Parked by the restore and consumed by the next [init]. Cleared on use, so a later NEW
     *  battle cannot inherit a restored battle's period. */
    private var pendingRestore: Snapshot? = null

    fun snapshot(): Snapshot? =
        if (!active) {
            null
        } else {
            GroundConditionModel.runs().let { (rain, snow, dry) ->
                Snapshot(clearPhase, counter, rain, snow, dry, model, precipSnow)
            }
        }

    fun restoreSnapshot(snapshot: Snapshot?) {
        pendingRestore = snapshot
    }

    /**
     * Starts the model for [s]. Idempotent for one scenario object: the game calls it as soon as a
     * battle is loaded or restored, and the UI calls it again when the map is shown, and only the
     * first call may draw anything.
     */
    fun init(s: Scenario?) {
        if (s == null) {
            active = false
            pendingRestore = null
            return
        }
        if (active && s === initializedFor) return
        val restored = pendingRestore
        pendingRestore = null
        val restoredModel = restored?.model ?: MODEL_OG
        table = if (restoredModel == MODEL_LEGACY) legacyWeatherZones else EfileConfig.weatherZones()
        zone = s.latitude.coerceIn(0, table.size - 1)
        month = monthOf(s)
        if (restored != null) {
            model = restored.model
            clearPhase = restored.clearPhase
            counter = restored.counter
            precipSnow = restored.precipSnow ?: (s.atmosferic == SNOW)
            GroundConditionModel.restoreRuns(restored.rainRun, restored.snowRun, restored.dryRun)
        } else {
            model = MODEL_OG
            GroundConditionModel.reset()
            startFreshBattle(s)
        }
        lastTurn = s.map.turn
        active = true
        initializedFor = s
    }

    /** The authored sky decides the running period; see the class comment for the draw order. */
    private fun startFreshBattle(s: Scenario) {
        val row = row()
        clearPhase = s.atmosferic == FAIR
        counter = WeatherDraws.periodLength(row, clearPhase)
        if (!clearPhase) {
            precipSnow =
                when (s.atmosferic) {
                    SNOW -> true
                    RAIN -> false
                    else -> WeatherDraws.snowType(row)
                }
        }
        val keepAuthoredSky = EfileConfig.intKey("force_weather", 0) == 1
        if (!keepAuthoredSky) setSky(s, WeatherDraws.sky(row, clearPhase, precipSnow))
    }

    /** JS `Date.getMonth()` is 0-based. Read on every advance: a battle that runs from January into
     *  February rolls February's row from February onwards. */
    private fun monthOf(s: Scenario): Int = (s.date.getMonth() + 1).coerceIn(1, MONTHS_IN_YEAR)

    fun stop() {
        active = false
        initializedFor = null
    }

    fun advance(s: Scenario?) {
        if (!active || s == null || s.map.turn == lastTurn) return // fire once per game turn
        lastTurn = s.map.turn
        month = monthOf(s)
        if (model == MODEL_LEGACY) {
            val step = LegacyWeather.roll(row(), clearPhase, counter)
            clearPhase = step.clearPhase
            counter = step.counter
            step.sky?.let { setSky(s, it) }
        } else {
            ogRoll(s)
        }
        // Every turn, including the ones the sky spent unchanged -- that is where a run completes.
        advanceGround(s)
    }

    private fun ogRoll(s: Scenario) {
        val row = row()
        counter--
        // A zero-length period (an average of 0 turns) is skipped outright; two flips at most, since
        // no zone row has both averages at zero.
        repeat(MAX_PERIOD_FLIPS) {
            if (counter <= 0) {
                clearPhase = !clearPhase
                counter = WeatherDraws.periodLength(row, clearPhase)
                if (!clearPhase) precipSnow = WeatherDraws.snowType(row)
            }
        }
        setSky(s, WeatherDraws.sky(row, clearPhase, precipSnow))
    }

    private fun row(): IntArray = table[zone][month - 1]

    private fun setSky(
        s: Scenario,
        sky: Int,
    ) {
        if (s.atmosferic == sky) return
        s.atmosferic = sky
        WeatherRenderer.start(sky)
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

// Table row layout: [avgClear, avgOvercast, probSnow%, probPrecip%].
private const val AVG_CLEAR_INDEX = 0
private const val AVG_OVERCAST_INDEX = 1
private const val SNOW_PROB_INDEX = 2
private const val PRECIP_PROB_INDEX = 3
private const val PERCENT = 100
private const val SKY_FAIR = 0
private const val SKY_OVERCAST = 1
private const val SKY_RAIN = 2
private const val SKY_SNOW = 3

/** The author's draws for [WeatherModel.MODEL_OG], each from the shared gameplay stream. */
internal object WeatherDraws {
    private const val PERIOD_DRAWS = 4
    private const val UPPER_BOUND_NUMERATOR = 3

    /** `((r1+r2+r3+r4)+2)/2`, each r in `[0, avg-1]`, clamped to `[avg/2, 3*avg/2]`. */
    fun periodLength(
        row: IntArray,
        clear: Boolean,
    ): Int {
        val avg = row[if (clear) AVG_CLEAR_INDEX else AVG_OVERCAST_INDEX]
        if (avg <= 0) return 0
        val sum = (1..PERIOD_DRAWS).sumOf { GameRandomSource.nextInt(avg) }
        return ((sum + 2) / 2).coerceIn(avg / 2, UPPER_BOUND_NUMERATOR * avg / 2)
    }

    /** An overcast period's one precipitation type: snow with the zone's `ProbOfSnow`. */
    fun snowType(row: IntArray): Boolean = GameRandomSource.nextInt(PERCENT) < row[SNOW_PROB_INDEX]

    /** Today's sky: fair in a clear period; in an overcast one, precipitation of the period's type
     *  with `ProbOfPrecip`, else overcast. */
    fun sky(
        row: IntArray,
        clearPhase: Boolean,
        precipSnow: Boolean,
    ): Int =
        when {
            clearPhase -> SKY_FAIR
            GameRandomSource.nextInt(PERCENT) < row[PRECIP_PROB_INDEX] -> if (precipSnow) SKY_SNOW else SKY_RAIN
            else -> SKY_OVERCAST
        }
}

/**
 * The pre-2026-09-27 model, kept only so a battle saved under it finishes under it: spells of one sky
 * on the LXF table it always used ([org.osada.legacyWeatherZones]), lengths uniform in
 * `[avg/2, 1.5*avg]`, precipitation decided once per spell, drawn from `kotlin.random`.
 */
internal object LegacyWeather {
    class Step(
        val clearPhase: Boolean,
        val counter: Int,
        val sky: Int?,
    )

    fun roll(
        row: IntArray,
        clearPhase: Boolean,
        counter: Int,
    ): Step {
        if (counter > 1) return Step(clearPhase, counter - 1, null)
        return if (clearPhase) {
            val sky =
                if (Random.nextInt(PERCENT) < row[PRECIP_PROB_INDEX]) {
                    if (Random.nextInt(PERCENT) < row[SNOW_PROB_INDEX]) SKY_SNOW else SKY_RAIN
                } else {
                    SKY_OVERCAST
                }
            Step(false, phaseLen(row[AVG_OVERCAST_INDEX]), sky)
        } else {
            Step(true, phaseLen(row[AVG_CLEAR_INDEX]), SKY_FAIR)
        }
    }

    private fun phaseLen(average: Int): Int {
        val avg = average.coerceAtLeast(1)
        return (avg / 2 + Random.nextInt(avg + 1)).coerceAtLeast(1)
    }
}
