package org.osada.scenario

import org.osada.GroundCondition
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.getPlayer
import org.osada.model.getPlayers
import org.osada.model.getUnits
import org.osada.movTable
import org.osada.movTableDry
import org.osada.movTableFrozen
import org.osada.movTableMud
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
    }

    var name: String = ""
    var maxTurns: Int = 0
    var date: Date = Date()
    var atmosferic: Int = 0
    var latitude: Int = 0
    var ground: Int = 0
    var iconset: Int = 0
    var weatherCanChangeGround: Boolean = false
    var turnsPerDay: Int = 1
    var dayTurn: Int = 0
    var reinforcements: MutableMap<Int, MutableList<Reinforcement>> = mutableMapOf()

    /** Optional authored message box per reinforcement turn (`<reinforce turn="2" message="...">`),
     *  shown when that wave actually deploys. Empty for scenarios that do not author one. */
    var reinforcementMessages: MutableMap<Int, String> = mutableMapOf()

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

    fun setMoveTable() {
        movTable =
            when (ground) {
                GroundCondition.DRY.value -> movTableDry
                GroundCondition.FROZEN.value -> movTableFrozen
                GroundCondition.MUD.value -> movTableMud
                else -> movTableDry
            }
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
        iconset = other.iconset
        ground = other.ground
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
