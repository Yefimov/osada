package org.osada.model

import org.osada.GameHolder
import org.osada.PlayerType
import org.osada.difficultyModifiers
import org.osada.hero.FormationIdentity
import org.osada.hero.HeroCampaign
import org.osada.scoreGains
import org.osada.sideNames

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

    /**
     * Adds [unit] to the campaign core roster, minting its persistent formation id if it has none.
     *
     * Core-roster insertion is one enrollment path (tray, purchase, carry-over and restore). The
     * campaign-wide ownership sweep also covers non-core pre-placement and scripted reinforcement;
     * [org.osada.hero.FormationIdentity.ensure] is idempotent across both paths.
     */
    fun addCoreUnit(unit: GameUnit?): Boolean {
        if (unit == null || coreUnits.any { it === unit }) return false
        unit.isCore = true
        if (!unit.isTemporaryBorrowed) {
            FormationIdentity.ensure(unit, knownFormationIds(getCoreUnitList()))
            HeroCampaign.synchronizeFormation(unit)
        }
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

    fun getCountryName(): String = Equipment.getCountryName(country)

    fun getSideName(): String = sideNames[side]

    fun hasUndeployedUnits(): Boolean = coreUnits.any { !it.isDeployed }

    fun updateScore(
        amount: Int,
        multiplier: Int = 1,
    ) {
        val coef = GameHolder.instance?.campaign?.let { difficultyModifiers[it.difficulty]?.scoreCoef } ?: 1.0
        score += (amount * multiplier * coef).toInt()
    }

    fun endTurn(turn: Int) {
        playedTurn = turn
        if (turn < prestigePerTurn.size) {
            awardPrestige(prestigePerTurn[turn])
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
                // Returns the formation to the tray ready to ACT, not ready to fight: the turn
                // flags and movement are reset, but strength, ammo and fuel are carried forward as
                // the battle left them.
                //
                // This used to also do `if (strength < 10) strength = 10; refillAmmoFuel()` -- a
                // free, unconditional, full refit of the whole army after every scenario. That made
                // attrition nearly meaningless between battles and voided every authored `resupply`
                // campaign effect, which could only ever top up units the pass had already filled.
                // Restoring readiness is now a paid decision the player makes in the reserve tray
                // ([ReserveRefit]).
                unit.hasMoved = false
                unit.hasFired = false
                unit.hasResupplied = false
                unit.isSurprised = false
                unit.isDeployed = false
                unit.moveLeft = unit.unitData().movpoints
                unit.entrenchment = 0
                unit.entrenchTicks = 0
                unit.hits = 0
                unit.setHex(null)
            }
        }
    }

    fun copy(
        other: Player,
        accumulateScore: Boolean = false,
    ) {
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
