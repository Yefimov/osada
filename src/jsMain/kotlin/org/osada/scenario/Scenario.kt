package org.osada.scenario

import org.osada.GroundCondition
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.TerrainEx
import org.osada.model.getPlayer
import org.osada.model.getPlayers
import org.osada.model.getUnits
import org.osada.model.invalidateWaterAccessCache
import org.osada.movTable
import org.osada.scoreGains
import kotlin.js.Date
import kotlin.js.json

@JsExport
@JsName("Scenario")
class Scenario(
    val file: String?,
) {
    companion object {
        private const val MILLIS_PER_DAY = 86400000
        private const val HOLD_OUTCOME_TIERS = 3
        private const val BRILLIANT_TIER = 0
        private const val VICTORY_TIER = 1
        private const val TACTICAL_TIER = 2

        /** OG `iconset`: 0=Default 1=Snow 2=Desert 3=Jungle. */
        private const val ICONSET_DEFAULT = 0
        private const val ICONSET_SNOW = 1
    }

    var name: String = ""
    var maxTurns: Int = 0
    var date: Date = Date()
    var atmosferic: Int = 0
    var latitude: Int = 0
    var ground: Int = 0
    var iconset: Int = 0
    internal var lockedEffectiveIconset: Int? = null

    /**
     * The unit iconset selected at scenario load: [iconset] as authored, except that a
     * **Frozen**-ground scenario which authored no iconset at all starts with snow unit art.
     *
     * OG scenario authors set `iconset` by hand and forgot it often. Measured across the shipped
     * scenarios, 47 of the 53 with Frozen ground do set snow, so the pairing is the intent and the
     * omissions are oversights — the visible symptom was Operation Uranus (November 1942, frozen,
     * snowing, snow-covered map art) fielding summer-camo paratroopers. Frozen ground is the only
     * initial trigger: falling snow over unfrozen ground must not change uniforms, and Dry/Mud are
     * left strictly alone. The locked value also survives later weather/ground transitions.
     *
     * Read this, never [iconset], on unit rendering paths. [iconset] stays the raw imported value;
     * saves persist both it and this fixed battle-season snapshot. Map art is always shown as
     * authored and never filtered from either value.
     */
    val effectiveIconset: Int
        get() =
            lockedEffectiveIconset
                ?: if (iconset == ICONSET_DEFAULT && ground == GroundCondition.FROZEN.value) {
                    ICONSET_SNOW
                } else {
                    iconset
                }

    var weatherCanChangeGround: Boolean = false

    /**
     * Open General's own per-scenario **Game settings** switches, imported 2026-08-26 from the
     * `.xscn` bitfield at bytes 1009-1017 (`tools/og-import/SCENARIO_FORMAT_NOTES.md`).
     *
     * **`null` means the scenario was never re-exported, and is NOT the same as `false`.** 397 of
     * the 502 deployed scenarios carry these attributes; the rest name a source this install cannot
     * read (29 legacy `.scn`) or cannot find (76). A rule that consults them must treat `null` as
     * *"the author did not say"* and fall back to its ruleset key alone — otherwise re-exporting a
     * scenario would be the difference between a mechanic existing and not, which is the §5.10
     * hazard in a new costume.
     *
     * These were the missing half of `rules.og_fidelity.gap.authored_options` — the profile gap
     * entry that §T retired, because with `TrueDLOF` and `UnitsBlockDLOF` built there is nothing
     * left in it. OG lets each scenario decide, and until §O OSADA applied one set of rules to all
     * of them. Measured over the
     * 397 scenarios that do carry them: Repair 362, Build 360, Blow 350, Extended LOS 321,
     * barrage 298, extended naval 203, TrueDLOF 131, Air ZOC 79, UnitsBlockDLOF 23, and air
     * missions **0** — the last of which is why building air missions would serve no shipped
     * battle at all.
     */
    var canBuild: Boolean? = null
    var canBlow: Boolean? = null
    var canRepair: Boolean? = null
    var extendedLos: Boolean? = null

    /**
     * The rest of the option bitfield. Two of these are read and three are not, and the group is
     * declared together because the whole bitfield is parsed in one place.
     *
     * **Read:** [barrageAllowed] since schema 7, as the scenario half of the `barrage` key's gate
     * in `rules/Barrage` (356 of the 457 readable scenarios allow it); [trueDirectLof] and
     * [unitsBlockLof] since §T, in `rules/ExtendedLos.hasLineOfFire` (131 and 23 of the 397
     * scenarios that carry the bitfield).
     *
     * **Still unread:** [airZoc] and [extendedNaval], both named to the player in the profile's own
     * gap list, and [airMissions] — the only one **no** shipped scenario sets at all, which is why
     * `docs/og-fidelity-plan.md` §M puts building it last.
     */
    var trueDirectLof: Boolean? = null
    var unitsBlockLof: Boolean? = null
    var barrageAllowed: Boolean? = null
    var airZoc: Boolean? = null
    var airMissions: Boolean? = null
    var extendedNaval: Boolean? = null

    /**
     * OG's *"air units can be fired on when entering AD range"* — the scenario's own switch for
     * anti-aircraft interception, authored by **404 of the 457** scenarios whose source parses.
     *
     * Null means the scenario's source could not be read, and every reader treats that as
     * permitted, exactly as the other authored switches do (`docs/og-fidelity-plan.md` §AD).
     */
    var airIntercept: Boolean? = null

    /** OG's *"ports do not supply hexes"*. 84 scenarios. */
    var portsNoSupply: Boolean? = null

    /** OG's *"ports do not deploy naval units"*. 48 scenarios. */
    var portsNoNavalDeploy: Boolean? = null

    var turnsPerDay: Int = 1
    var dayTurn: Int = 0
    var reinforcements: MutableMap<Int, MutableList<Reinforcement>> = mutableMapOf()

    /** Optional authored message box per reinforcement turn (`<reinforce turn="2" message="...">`),
     *  shown when that wave actually deploys. Empty for scenarios that do not author one. */
    var reinforcementMessages: MutableMap<Int, String> = mutableMapOf()

    /**
     * Optional authored `<events>` (see [ScenarioEvent]): declarative, once-only reactions to the
     * battle reaching a place or a state. Empty for every scenario that does not author any, which
     * is what makes the feature invisible to the ~700 imported scenarios.
     *
     * `internal` deliberately: [Scenario] is `@JsExport`, and events are engine-side data with no
     * JS-facing consumer.
     */
    internal var events: MutableList<ScenarioEvent> = mutableListOf()

    /** Optional OG-style objective-hold thresholds for the turn-limit outcome, ordered
     * brilliant / victory / tactical. Empty keeps the legacy all-objectives-or-defeat rule. */
    var victoryHoldCounts: List<Int> = emptyList()
    var map: GameMap = GameMap()
    var expPerSide: MutableList<dynamic> =
        mutableListOf(
            json("exp" to 0, "count" to 0),
            json("exp" to 0, "count" to 0),
        )
    var unitsCostPerSide: MutableList<Int> = mutableListOf(0, 0)
    var isLoaded: Boolean = false
    var eqp: String = Equipment.DEFAULT_NAME
    private var description: String = ""
    private var onLoadFinished: (() -> Unit)? = null

    init {
        if (file != null) {
            val data = ScenarioLoader.getScenarioDataByFileName(file)
            if (data != null) {
                name = data[1] as String
                description = data[2] as String
            }
        }
    }

    fun load(callback: () -> Unit) {
        onLoadFinished = callback
        ScenarioLoader.loadScenario(this)
    }

    fun onLoadFinished() {
        if (isLoaded) {
            setMoveTable()
            val scoreMap = mutableMapOf<Int, Int>()
            val players = map.getPlayers()
            players.forEach { player ->
                scoreMap[player.id] =
                    map.sidesVictoryHexes[player.side].size * (scoreGains["objectivePerTurn"] ?: 0) / map.maxTurns
            }
            map.getUnits().forEach { unit ->
                val playerId = unit.player?.id ?: -1
                scoreMap[playerId] =
                    (scoreMap[playerId] ?: 0) +
                    if (unit.isCore) (scoreGains["coreUnit"] ?: 0) else (scoreGains["normalUnit"] ?: 0)
            }
            players.forEach { player ->
                player.updateScore(scoreMap[player.id] ?: 0)
            }
        }
        onLoadFinished?.invoke()
    }

    fun checkDefeat(
        side: Int,
        humanSides: Int,
    ): Boolean {
        if (map.turn >= map.maxTurns && (side == humanSides || humanSides == 2)) {
            return map.getPlayers().none { it.side == side && it.playedTurn < map.maxTurns }
        }
        return false
    }

    fun checkVictory(): String =
        when {
            map.turn <= map.victoryTurns[0] -> "briliant"
            map.turn <= map.victoryTurns[1] -> "victory"
            map.turn <= map.victoryTurns[2] -> "tactical"
            else -> "lose"
        }

    /** Returns the authored result when the final human turn has actually completed. */
    fun checkTimedOutcome(
        side: Int,
        humanSides: Int,
    ): String? {
        if (!checkDefeat(side, humanSides)) return null
        val result =
            if (victoryHoldCounts.size < HOLD_OUTCOME_TIERS) {
                "lose"
            } else {
                var held = 0
                for (row in 0 until map.rows) {
                    for (col in 0 until map.cols) {
                        val hex = map.map?.getOrNull(row)?.getOrNull(col) ?: continue
                        if (hex.victorySide != -1 && hex.owner != -1 && map.getPlayer(hex.owner).side == side) held++
                    }
                }
                when {
                    held >= victoryHoldCounts[BRILLIANT_TIER] -> "briliant"
                    held >= victoryHoldCounts[VICTORY_TIER] -> "victory"
                    held >= victoryHoldCounts[TACTICAL_TIER] -> "tactical"
                    else -> "lose"
                }
            }
        return result
    }

    fun getDescription(): String = description

    fun setDescription(desc: String) {
        description = desc
    }

    fun endTurn() {
        dayTurn++
        if (dayTurn >= turnsPerDay) {
            dayTurn = 0
            date = Date(date.getTime() + MILLIS_PER_DAY)
        }
        map.endTurn()
    }

    /**
     * Publishes the active movement-cost table for this scenario's ground condition.
     *
     * [TerrainEx.movementCostTable] returns PM's own `movTableDry`/`movTableFrozen`/`movTableMud`
     * with the active efile's OG `[terrain-cost]` laid over it, and falls back to the PM table
     * unchanged for any efile that ships no TerrainEx data. Called on scenario load, on save
     * restore, and on every weather change that flips the ground condition, so an efile switch
     * always lands before the next read of `movTable`.
     *
     * `hasWaterAccess`/`hasOpenWaterAccess` cache answers derived from this table, so they are
     * dropped here rather than left to go stale across a ground-condition change.
     */
    fun setMoveTable() {
        movTable = TerrainEx.movementCostTable(ground)
        map.invalidateWaterAccessCache()
    }

    fun showStatistics() {
        // Intentional no-op: the legacy JS's own `showStatistics` was already an empty stub
        // (`this.showStatistics = function() {}`, never implemented upstream either).
    }

    fun copy(other: Scenario) {
        maxTurns = other.maxTurns
        date = Date(other.date.getTime())
        atmosferic = other.atmosferic
        latitude = other.latitude
        weatherCanChangeGround = other.weatherCanChangeGround
        canBuild = other.canBuild
        canBlow = other.canBlow
        canRepair = other.canRepair
        extendedLos = other.extendedLos
        trueDirectLof = other.trueDirectLof
        unitsBlockLof = other.unitsBlockLof
        barrageAllowed = other.barrageAllowed
        airZoc = other.airZoc
        airMissions = other.airMissions
        extendedNaval = other.extendedNaval
        iconset = other.iconset
        ground = other.ground
        lockedEffectiveIconset = other.effectiveIconset
        turnsPerDay = other.turnsPerDay
        eqp = other.eqp
        reinforcementMessages.clear()
        reinforcementMessages.putAll(other.reinforcementMessages)
        victoryHoldCounts = other.victoryHoldCounts.toList()
        reinforcements.clear()
        other.reinforcements.forEach { (turn, list) ->
            list.forEach { r ->
                val unit = GameUnit(r.unit.eqid).apply { copy(r.unit) }
                addReinforcement(turn, r.row, r.col, unit)
            }
        }
        copyEventsFrom(other)
        map.copy(other.map)
        file?.let { /* keep */ }
        setMoveTable()
    }

    data class Reinforcement(
        val turn: Int,
        val row: Int,
        val col: Int,
        val unit: GameUnit,
        val id: Int,
    )
}
