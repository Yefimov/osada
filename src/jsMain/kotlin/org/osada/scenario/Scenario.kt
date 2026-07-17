package org.osada.scenario

import org.osada.CURRENCY_MULTIPLIER
import org.osada.GroundCondition
import org.osada.PROTOTYPE_MIN_COST
import org.osada.SCENARIO_START_PRESTIGE
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.movTable
import org.osada.movTableDry
import org.osada.movTableFrozen
import org.osada.movTableMud
import org.osada.scoreGains
import kotlin.js.Date
import kotlin.js.json
import kotlin.random.Random

@JsExport
@JsName("Scenario")
class Scenario(val file: String?) {
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
    var map: GameMap = GameMap()
    var expPerSide: MutableList<dynamic> = mutableListOf(
        json("exp" to 0, "count" to 0),
        json("exp" to 0, "count" to 0),
    )
    var unitsCostPerSide: MutableList<Int> = mutableListOf(0, 0)
    var isLoaded: Boolean = false
    var eqp: String = Equipment.defaultName
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

    fun addReinforcement(turn: Int, row: Int, col: Int, unit: GameUnit) {
        val list = reinforcements.getOrPut(turn) { mutableListOf() }
        list.add(Reinforcement(turn, row, col, unit, list.size + 1))
    }

    fun getReinforcements(turn: Int, owner: Int): List<Reinforcement> {
        val result = mutableListOf<Reinforcement>()
        reinforcements.entries.filter { it.key <= turn }.forEach { (_, list) ->
            result.addAll(list.filter { it.unit.owner == owner })
        }
        return result
    }

    fun removeReinforcement(turn: Int, id: Int): Boolean {
        val list = reinforcements[turn] ?: return false
        val iter = list.iterator()
        while (iter.hasNext()) {
            if (iter.next().id == id) {
                iter.remove()
                return true
            }
        }
        return false
    }

    fun checkDefeat(side: Int, humanSides: Int): Boolean {
        if (map.turn >= map.maxTurns && (side == humanSides || humanSides == 2)) {
            return map.getPlayers().none { it.side == side && it.playedTurn < map.maxTurns }
        }
        return false
    }

    fun checkVictory(): String = when {
        map.turn <= map.victoryTurns[0] -> "briliant"
        map.turn <= map.victoryTurns[1] -> "victory"
        map.turn <= map.victoryTurns[2] -> "tactical"
        else -> "lose"
    }

    fun getDescription(): String = description
    fun setDescription(desc: String) {
        description = desc
    }

    fun endTurn() {
        dayTurn++
        if (dayTurn >= turnsPerDay) {
            dayTurn = 0
            date = Date(date.getTime() + 86400000)
        }
        map.endTurn()
    }

    fun setMoveTable() {
        movTable = when (ground) {
            GroundCondition.DRY.value -> movTableDry
            GroundCondition.FROZEN.value -> movTableFrozen
            GroundCondition.MUD.value -> movTableMud
            else -> movTableDry
        }
    }

    fun getPrototypeUnitsAvailable(country: Int): List<Int> {
        val year = date.getFullYear() + 1
        val list = Equipment.getCountryEquipmentByYearRange(year, year, country).toMutableList()
        val iter = list.iterator()
        while (iter.hasNext()) {
            val eqid = iter.next()
            val eq = Equipment.equipment[eqid] ?: continue
            val uclass = eq.uclass
            if ((uclass < UnitClass.TANK.value || uclass > UnitClass.ANTI_TANK.value) &&
                (uclass < UnitClass.ARTILLERY.value || uclass > UnitClass.TACTICAL_BOMBER.value) ||
                eq.cost * CURRENCY_MULTIPLIER < PROTOTYPE_MIN_COST
            ) {
                iter.remove()
            }
        }
        return list
    }

    fun getRandomPrototype(country: Int): Int {
        val available = getPrototypeUnitsAvailable(country)
        if (available.isEmpty()) return -1
        return available[(Random.nextDouble() * available.size).toInt()]
    }

    fun getBalancedPrestige(side: Int): Int {
        var prestige = SCENARIO_START_PRESTIGE + unitsCostPerSide[1 - side] - unitsCostPerSide[side]
        if (prestige < SCENARIO_START_PRESTIGE) prestige = SCENARIO_START_PRESTIGE
        return prestige
    }

    fun showStatistics() {
        // TODO: implement statistics display
    }

    fun getSideUnitsAvgExp(side: Int): Int {
        val data = expPerSide[side]
        val count = data.count as Int
        val exp = data.exp as Int
        return if (count > 0) kotlin.math.round(exp.toDouble() / count).toInt() else 0
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

    data class Reinforcement(val turn: Int, val row: Int, val col: Int, val unit: GameUnit, val id: Int)
}
