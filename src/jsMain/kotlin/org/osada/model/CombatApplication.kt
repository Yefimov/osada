package org.osada.model

import org.osada.CombatLog
import org.osada.LeaderType
import org.osada.UnitClass
import org.osada.addObjectiveCapture
import org.osada.prestigeGains
import org.osada.rules.GameRules
import org.osada.rules.calculateAttackResults
import org.osada.rules.getDirection
import org.osada.rules.isBridgeForSide
import org.osada.scoreGains
import kotlin.js.json

/**
 * Applies combat outcomes to the live game state: damage, experience, leader generation,
 * hex capture, and unit retreat. Extracted from the former [GameMap] god-class (SRP).
 *
 * All mutations flow through the [GameMap] facade so the exported surface is unchanged.
 */
internal class CombatApplication(
    private val gameMap: GameMap,
) {
    fun attackUnit(
        attacker: GameUnit,
        defender: GameUnit,
        supportFire: Boolean,
        isOverrun: Boolean = false,
    ): CombatResults {
        gameMap.undoState.unit = null
        val from = attacker.getPos()
        val to = defender.getPos()
        return if (from != null && to != null) {
            resolveCombat(attacker, defender, from, to, supportFire, isOverrun)
        } else {
            CombatResults()
        }
    }

    private fun resolveCombat(
        attacker: GameUnit,
        defender: GameUnit,
        from: Cell,
        to: Cell,
        supportFire: Boolean,
        isOverrun: Boolean,
    ): CombatResults {
        val combatResult = GameRules.calculateAttackResults(attacker, defender, true)
        val logId = CombatLog.addCombatStart(attacker, defender, gameMap.turn)
        applyCombatFacing(attacker, defender, from, to)
        unmountDefenderIfNeeded(defender)
        if (isOverrun) applyOverrun(attacker, defender, combatResult)
        applyCombatDamage(attacker, defender, combatResult, supportFire, isOverrun)
        updateCombatScores(attacker, defender, combatResult)
        generateCombatLeaders(attacker, defender, combatResult)
        CombatLog.addCombatEnd(attacker, defender, logId, supportFire)
        return combatResult
    }

    private fun applyCombatFacing(
        attacker: GameUnit,
        defender: GameUnit,
        from: Cell,
        to: Cell,
    ) {
        if (!GameRules.isBridgeForSide(attacker.getHex(), attacker.player?.side ?: -1)) {
            attacker.facing = GameRules.getDirection(from.row, from.col, to.row, to.col) ?: attacker.facing
        }
        if (!GameRules.isBridgeForSide(defender.getHex(), defender.player?.side ?: -1)) {
            defender.facing = GameRules.getDirection(to.row, to.col, from.row, from.col) ?: defender.facing
        }
    }

    private fun unmountDefenderIfNeeded(defender: GameUnit) {
        if (defender.isMounted &&
            !defender.isSurprised &&
            defender.unitData(true).uclass == UnitClass.INFANTRY.value
        ) {
            gameMap.unmountUnitHandler(defender)
        }
    }

    private fun applyOverrun(
        attacker: GameUnit,
        defender: GameUnit,
        combatResult: CombatResults,
    ) {
        combatResult.kills = defender.strength
        combatResult.isOverrun = true
        combatResult.defcanfire = false
        if (attacker.moveLeft > 0) attacker.hasMoved = false
        attacker.moveLeft += 1
    }

    private fun applyCombatDamage(
        attacker: GameUnit,
        defender: GameUnit,
        combatResult: CombatResults,
        supportFire: Boolean,
        isOverrun: Boolean,
    ) {
        attacker.experience = kotlin.math.round(attacker.experience + combatResult.atkExpGained.toDouble()).toInt()
        defender.experience = kotlin.math.round(defender.experience + combatResult.defExpGained.toDouble()).toInt()
        if (supportFire || isOverrun) attacker.fire(false) else attacker.fire(true)
        defender.hit(combatResult.kills)
        if (combatResult.defcanfire && !supportFire) {
            defender.fire(false)
            attacker.hit(combatResult.losses)
        }
        if (!supportFire) gameMap.delAttackSel()
    }

    private fun updateCombatScores(
        attacker: GameUnit,
        defender: GameUnit,
        combatResult: CombatResults,
    ) {
        attacker.player?.updateScore(scoreGains["damage"] ?: 0, combatResult.kills)
        attacker.player?.updateScore(
            if (attacker.isCore) {
                scoreGains["casualtyCore"] ?: 0
            } else {
                scoreGains["casualty"]
                    ?: 0
            },
            combatResult.losses,
        )
        defender.player?.updateScore(scoreGains["damage"] ?: 0, combatResult.losses)
        defender.player?.updateScore(
            if (defender.isCore) {
                scoreGains["casualtyCore"] ?: 0
            } else {
                scoreGains["casualty"]
                    ?: 0
            },
            combatResult.kills,
        )
    }

    private fun generateCombatLeaders(
        attacker: GameUnit,
        defender: GameUnit,
        combatResult: CombatResults,
    ) {
        val atkLeader = Leaders.generateLeaderWithChance(attacker, combatResult.atkExpGained)
        if (atkLeader != -1) {
            attacker.leader = atkLeader
            combatResult.atkLeaderGain = true
            CombatLog.addLeader(attacker)
        }
        val defLeader = Leaders.generateLeaderWithChance(defender, combatResult.defExpGained)
        if (defLeader != -1) {
            defender.leader = defLeader
            combatResult.defLeaderGain = true
            CombatLog.addLeader(defender)
        }
    }

    fun retreatUnit(
        unit: GameUnit,
        to: Cell,
    ): MovementResults {
        gameMap.currentMoveRange.add(to)
        val moveLeft = unit.moveLeft
        val hasMoved = unit.hasMoved
        val hasOverstrength = unit.hasOverstrength
        val prevCurrent = gameMap.currentUnit
        val result = gameMap.moveUnit(unit, to.row, to.col)
        unit.moveLeft = moveLeft
        unit.hasMoved = hasMoved
        unit.hasOverstrength = hasOverstrength
        prevCurrent?.let { gameMap.selectUnit(it) }
        return result
    }

    fun captureHex(
        hex: Hex,
        unit: GameUnit,
    ): dynamic {
        val result = json(Pair("isWin", false), Pair("isCapture", false))
        val player = unit.player ?: return result
        applyHexCapture(hex, unit, player, result)
        return result
    }

    private fun applyHexCapture(
        hex: Hex,
        unit: GameUnit,
        player: Player,
        result: dynamic,
    ) {
        val side = player.side
        val notCapturable = hex.owner == -1 && hex.flag == -1
        val oldOwnerSide = if (hex.owner != -1) gameMap.getPlayer(hex.owner).side else -1
        if (notCapturable || oldOwnerSide == side) return

        gameMap.undoState.oldOwner = hex.owner
        hex.owner = player.id
        val multiplier = if (Leaders.unitHasLeader(unit, LeaderType.LIBERATOR)) 2 else 1
        var prestigeGain = 0
        var scoreGain = 0

        if (hex.flag != -1) {
            gameMap.undoState.oldFlag = hex.flag
            hex.flag = player.country
            if (hex.victorySide == -1) {
                prestigeGain += (prestigeGains["flagCapture"] ?: 0) * multiplier
                scoreGain += (scoreGains["flagCapture"] ?: 0) * multiplier
                result["isCapture"] = true
            }
        }

        if (hex.victorySide != -1) {
            gameMap.undoState.oldVictorySide = hex.victorySide
            hex.victorySide = 1 - side
            val isWin = gameMap.updateVictorySides(side, hex.getPos())
            result["isWin"] = isWin
            prestigeGain += (prestigeGains["objectiveCapture"] ?: 0) * multiplier
            scoreGain += (scoreGains["objectiveCapture"] ?: 0) * multiplier
            result["isCapture"] = true
            CombatLog.addObjectiveCapture(hex.getPos(), side)
        }

        gameMap.undoState.prestigeGain = prestigeGain
        gameMap.undoState.scoreGain = scoreGain
        player.prestige += prestigeGain
        player.updateScore(scoreGain)
    }
}
