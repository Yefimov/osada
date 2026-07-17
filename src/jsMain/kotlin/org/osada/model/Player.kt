package org.osada.model

import org.osada.GameHolder
import org.osada.OVERSTRENGTH_PENALTY
import org.osada.PlayerType
import org.osada.UnitClass
import org.osada.difficultyModifiers
import org.osada.outcomeNames
import org.osada.rules.GameRules
import org.osada.scoreGains
import org.osada.sideNames
import kotlin.js.json

@JsExport
@JsName("Player")
class Player {
    var id: Int = -1
    var side: Int = -1
    var country: Int = -1
    var prestige: Int = 0
    var score: Int = 0
    var playedTurn: Int = -1
    var type: PlayerType = PlayerType.HUMAN_LOCAL
    var handler: dynamic = null
    var airTransports: Int = 0
    var navalTransports: Int = 0
    var supportCountries: MutableList<Int> = mutableListOf()
    var prestigePerTurn: MutableList<Int> = mutableListOf()

    private val coreUnits: MutableList<GameUnit> = mutableListOf()
    var dossier: dynamic = null

    fun getCoreUnitList(): List<GameUnit> = coreUnits.toList()

    fun addCoreUnit(unit: GameUnit?): Boolean {
        if (unit == null) return false
        unit.isCore = true
        coreUnits.add(unit)
        return true
    }

    fun removeUndeployedCoreUnit(index: Int) {
        if (index in coreUnits.indices) {
            coreUnits.removeAt(index)
        }
    }

    fun setCoreUnitList(list: List<GameUnit>) {
        coreUnits.clear()
        coreUnits.addAll(list)
    }

    fun initDossier() {
        val lostaux = json()
        val lostcore = json()
        val killed = json()
        UnitClass.values().forEach { uc ->
            val key = uc.value.toString()
            lostaux[key] = 0
            lostcore[key] = 0
            killed[key] = 0
        }
        val units = json(
            Pair("lostaux", lostaux),
            Pair("lostcore", lostcore),
            Pair("killed", killed),
        )
        val outcomes = json()
        outcomeNames.keys.forEach { outcomes[it] = js("[]") }
        dossier = json(Pair("units", units), Pair("outcomes", outcomes))
    }

    fun copyDossier(other: Player) {
        initDossier()
        val otherDossier = other.dossier ?: return
        UnitClass.values().forEach { uc ->
            val key = uc.value.toString()
            dossier.units.lostaux[key] = otherDossier.units.lostaux[key]
            dossier.units.lostcore[key] = otherDossier.units.lostcore[key]
            dossier.units.killed[key] = otherDossier.units.killed[key]
        }
        outcomeNames.keys.forEach { outcome ->
            val src = otherDossier.outcomes[outcome]
            if (src != null) {
                val dst = dossier.outcomes[outcome]
                for (i in 0 until src.length) {
                    dst.push(src[i])
                }
            }
        }
    }

    fun getCountryName(): String = Equipment.getCountryName(country)
    fun getSideName(): String = sideNames[side]

    fun hasUndeployedUnits(): Boolean = coreUnits.any { !it.isDeployed }

    fun buyUnit(eqid: Int, transportEqid: Int): Boolean {
        val cost = GameRules.calculateUnitCosts(eqid, transportEqid)
        if (cost > prestige) return false
        if (acquireUnit(eqid, transportEqid)) {
            prestige -= cost
            return true
        }
        return false
    }

    fun acquireUnit(eqid: Int, transportEqid: Int): Boolean {
        val unit = GameUnit(eqid)
        if (transportEqid > 0) {
            unit.setTransport(transportEqid)
        }
        unit.owner = id
        unit.flag = country + 1
        unit.player = this
        if (GameHolder.instance?.campaign == null) {
            unit.experience = GameHolder.instance?.scenario?.getSideUnitsAvgExp(1 - side) ?: 0
        }
        addCoreUnit(unit)
        return true
    }

    fun upgradeUnit(unit: GameUnit, newEqid: Int, transportEqid: Int): Boolean {
        val cost = GameRules.calculateUpgradeCosts(unit, newEqid, transportEqid)
        if (cost > prestige || !unit.upgrade(newEqid, transportEqid)) return false
        prestige -= cost
        return true
    }

    fun sellUnit(unit: GameUnit): Boolean {
        if (unit == null) return false
        val cost = GameRules.calculateUnitSellCost(unit)
        prestige += cost
        return true
    }

    fun resupplyUnit(unit: GameUnit, supply: Supply) {
        updateScore(scoreGains["resupply"] ?: 0)
        unit.resupply(supply)
    }

    fun reinforceUnit(unit: GameUnit, strength: Int, overStrength: Boolean): Int {
        val penalty = if (overStrength) OVERSTRENGTH_PENALTY else 1.0
        val costPerStrength = GameRules.calculateUnitCostPerStrength(unit)
        val unitCost = kotlin.math.round(costPerStrength * penalty).toInt()
        val maxAffordable = prestige / unitCost
        if (maxAffordable < 1) return 0
        val toReinforce = kotlin.math.min(maxAffordable, strength)
        // Nothing to add (e.g. unit ineligible for overstrength): bail out WITHOUT calling
        // unit.reinforce(), which would mark the unit hasMoved/hasFired and wrongly end its turn.
        if (toReinforce < 1) return 0
        prestige -= toReinforce * unitCost
        updateScore(scoreGains["reinforce"] ?: 0, strength)
        unit.reinforce(toReinforce, overStrength)
        return toReinforce
    }

    fun updateScore(amount: Int, multiplier: Int = 1) {
        val coef = GameHolder.instance?.campaign?.let { difficultyModifiers[it.difficulty]?.scoreCoef } ?: 1.0
        score += (amount * multiplier * coef).toInt()
    }

    fun addDestroyedUnitToDossier(unit: GameUnit) {
        if (dossier == null || dossier.units == undefined) initDossier()
        val uclass = unit.unitData().uclass.toString()
        if (id == unit.player?.id) {
            if (unit.isCore) {
                dossier.units.lostcore[uclass] = (dossier.units.lostcore[uclass] as? Int ?: 0) + 1
            } else {
                dossier.units.lostaux[uclass] = (dossier.units.lostaux[uclass] as? Int ?: 0) + 1
            }
        } else {
            dossier.units.killed[uclass] = (dossier.units.killed[uclass] as? Int ?: 0) + 1
        }
    }

    fun addOutcomeToDossier(outcome: String, scenarioName: String) {
        if (dossier == null || dossier.outcomes == undefined) initDossier()
        dossier.outcomes[outcome].push(scenarioName)
    }

    fun endTurn(turn: Int) {
        playedTurn = turn
        if (turn < prestigePerTurn.size) {
            prestige += prestigePerTurn[turn]
        }
        updateScore(scoreGains["endTurn"] ?: 0)
    }

    fun setPlayerToHQ() {
        supportCountries.clear()
        prestigePerTurn.clear()
        airTransports = 0
        navalTransports = 0
        val iter = coreUnits.iterator()
        while (iter.hasNext()) {
            val unit = iter.next()
            if (unit.destroyed) {
                iter.remove()
            } else {
                unit.unmount()
                unit.carrier = 0
                unit.hasMoved = false
                unit.hasFired = false
                unit.hasResupplied = false
                unit.isDeployed = false
                if (unit.strength < 10) unit.strength = 10
                unit.refillAmmoFuel()
                unit.moveLeft = unit.unitData().movpoints
                unit.entrenchment = 0
                unit.hits = 0
                unit.setHex(null)
            }
        }
    }

    fun copy(other: Player, accumulateScore: Boolean = false) {
        id = other.id
        side = other.side
        country = other.country
        prestige = other.prestige
        playedTurn = other.playedTurn
        type = other.type
        if (accumulateScore) {
            score += other.score
        } else {
            score = other.score
            airTransports = other.airTransports
            navalTransports = other.navalTransports
            supportCountries.clear()
            supportCountries.addAll(other.supportCountries)
            prestigePerTurn.clear()
            prestigePerTurn.addAll(other.prestigePerTurn)
        }
        setCoreUnitList(other.getCoreUnitList())
        if (other.dossier != null) {
            copyDossier(other)
        }
    }
}
