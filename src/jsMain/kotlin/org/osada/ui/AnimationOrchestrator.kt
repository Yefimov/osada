package org.osada.ui

import org.osada.*
import org.osada.model.*
import org.osada.rules.GameRules

/** Max of move range, attack range and spot range — the render radius needed to redraw after any unit action. */
internal fun getUnitRenderRadius(unit: GameUnit): Int {
    val moveRange = GameRules.getUnitMoveRange(unit)
    val attackRange = GameRules.getUnitAttackRange(unit)
    val spotRange = unit.unitData().spotrange
    return maxOf(moveRange, attackRange, spotRange)
}

/**
 * Sequences unit-move and unit-attack animations: drives the Render layer, resolves support
 * fire and defender retreat, and fires the game-state callbacks once animations finish.
 * Extracted from the former [UI] god-class (SRP).
 */
internal class AnimationOrchestrator(private val ui: UI) {

    fun uiUnitMove(unit: GameUnit, row: Int, col: Int): Boolean {
        val map = ui.game.scenario?.map ?: return false
        val startPos = unit.getPos() ?: return false
        val radius = getUnitRenderRadius(unit)

        ui.game.waitUIAnimation = true
        val result = map.moveUnit(unit, row, col)

        if (result.passedCells.size <= 1 || !result.isVisible) {
            finishMoveAnimation(unit, result, radius)
            return false
        }

        playMoveSound(unit)
        ui.render.render(startPos.row, startPos.col, radius)

        val params = jsObject {
            this.unit = unit
            this.moveResults = result
            this.cbfunc = animationCallback {
                finishMoveAnimation(unit, result, radius)
            }
        }
        ui.render.moveAnimation(params)
        return true
    }

    fun uiUnitAttack(attacker: GameUnit, defender: GameUnit): Boolean {
        val map = ui.game.scenario?.map ?: return false
        val attackerPos = attacker.getPos() ?: return false
        val defenderPos = defender.getPos() ?: return false
        console.log("[OpenPanzer] uiUnitAttack attacker=${attacker.id}(${attacker.unitData(true).name}) at ${attackerPos.row},${attackerPos.col} defender=${defender.id}(${defender.unitData(true).name}) at ${defenderPos.row},${defenderPos.col}")
        val radius = maxOf(getUnitRenderRadius(attacker), getUnitRenderRadius(defender))

        ui.game.waitUIAnimation = true
        UIBuilder.showAttackInfo(attacker, defender)
        val preview = GameRules.calculateAttackResults(attacker, defender, true)
        val attackerOldStrength = attacker.strength

        // Resolve support fire from adjacent artillery / flak / fighters.
        if (!attacker.isSurprised && !preview.isOverrun) {
            val supportUnits = GameRules.getSupportFireUnits(map.getUnits().toList(), attacker, defender)
            for (support in supportUnits) {
                if (attacker.destroyed) break
                val supportPos = support.getPos() ?: continue
                map.attackUnit(support, attacker, true)
                val supportClass = support.unitData(true).uclass
                val supportAnimType = attackAnimationByClass.getOrNull(supportClass)
                val supportDir = GameRules.getDirection(supportPos.row, supportPos.col, attackerPos.row, attackerPos.col)
                    ?: support.facing
                supportAnimType?.let {
                    ui.render.addAnimation(supportPos.row, supportPos.col, it, supportDir)
                }
            }
        }

        val attackStopped = attacker.destroyed || GameRules.isLossOverRetreatThreshold(attacker.strength, attackerOldStrength)
        val defenderOldStrength = defender.strength

        val result = if (!attackStopped) {
            map.attackUnit(attacker, defender, false, preview.isOverrun)
        } else {
            attacker.hasFired = true
            preview
        }

        // Defender retreat for ground-vs-ground combat when losses exceed the threshold.
        if (!attackStopped && !defender.destroyed && GameRules.shouldDefenderRetreat(attacker, defender, defenderOldStrength)) {
            val retreatPos = GameRules.getRetreatPosition(map.map, defender, map.rows, map.cols, map.hasRailData())
            if (retreatPos != null) {
                map.retreatUnit(defender, retreatPos)
            }
        }

        val attackerClass = attacker.unitData(true).uclass
        val defenderClass = defender.unitData(true).uclass
        val attackerAnimType = attackAnimationByClass.getOrNull(attackerClass)
        val defenderAnimType = attackAnimationByClass.getOrNull(defenderClass)

        if (!attackStopped) {
            val attackDir = GameRules.getDirection(attackerPos.row, attackerPos.col, defenderPos.row, defenderPos.col)
                ?: attacker.facing
            attackerAnimType?.let {
                ui.render.addAnimation(attackerPos.row, attackerPos.col, it, attackDir)
            }

            if (!defender.destroyed && result.defcanfire) {
                val defendDir = GameRules.getDirection(defenderPos.row, defenderPos.col, attackerPos.row, attackerPos.col)
                    ?: defender.facing
                defenderAnimType?.let {
                    ui.render.addAnimation(defenderPos.row, defenderPos.col, it, defendDir)
                }
            }
        }

        if (attacker.destroyed) ui.render.addAnimation(attackerPos.row, attackerPos.col, "explosion", 0)
        if (defender.destroyed) ui.render.addAnimation(defenderPos.row, defenderPos.col, "explosion", 0)

        ui.render.runAnimation(animationCallback {
            finishAttackAnimation(attacker, defender, result, radius)
        })

        return true
    }

    private fun animationCallback(onComplete: () -> Unit): dynamic {
        val cb: (dynamic) -> Unit = { _: dynamic -> onComplete() }
        return js("""var o = {}; o.cbfunc = cb; o;""")
    }

    private fun finishMoveAnimation(unit: GameUnit, result: MovementResults, radius: Int) {
        val pos = unit.getPos() ?: run {
            ui.game.waitUIAnimation = false
            ui.game.uiAnimationFinished()
            return
        }
        ui.render.render(pos.row, pos.col, radius)

        result.surpriseCell.firstOrNull()?.let { cell ->
            ui.showAlert(cell.row, cell.col, "Surprised", true)
            unit.isSurprised = false
        }
        if (result.isCapture) {
            ui.showAlert(pos.row, pos.col, "Captured", true)
            val hexName = ui.game.scenario?.map?.map?.getOrNull(pos.row)?.getOrNull(pos.col)?.name
            // Coordinates only as a last-resort label when the hex has no name (nothing else
            // would identify it) — clickable either way, same as the combat log lines.
            val place = if (!hexName.isNullOrEmpty()) hexName else "(${pos.col},${pos.row})"
            HudLog.addAt(pos.row, pos.col, "${unit.unitData(true).name} captured $place")
        }
        if (result.isVictorySide >= 0) {
            ui.game.handleMoveVictory(result.isVictorySide)
        }

        // Scroll-into-view-if-needed, NOT an unconditional recenter — see uiScrollUnitIntoView's
        // doc comment. A local move (e.g. driving a tank up next to an enemy) that already keeps
        // the unit on screen should never yank the camera, since that shifts the very target the
        // player is about to click right as they click it (their next click can land on the wrong
        // hex — reported as "I click the enemy but it doesn't attack, it just shows the card").
        ui.uiScrollUnitIntoView(unit)
        // Action row (Undo, etc.) was never refreshed after a move completed — canUndoMove(unit)
        // only just became true, but nothing re-ran buildUnitContext to pick that up, so Undo
        // stayed invisible until the player deselected and reselected the unit (which happens to
        // rebuild the row from scratch). Same reasoning as executeUnitContext's post-action
        // refresh below in UnitInfoPanel.
        ui.buildUnitContext(unit)
        ui.showUnitInfo(unit)
        // Ready-unit navigator + "End turn · N" reflect the freshly-moved unit.
        if (ui.game.scenario?.map?.currentPlayer?.type == PlayerType.HUMAN_LOCAL) ui.updateStatusBar()
        ui.game.waitUIAnimation = false
        ui.game.uiAnimationFinished()
    }

    private fun finishAttackAnimation(attacker: GameUnit, defender: GameUnit, result: CombatResults, radius: Int) {
        console.log("[OpenPanzer] finishAttackAnimation losses=${result.losses} kills=${result.kills} destroyed=atk${attacker.destroyed}/def${defender.destroyed}")
        val attackerPos = attacker.getPos()
        val defenderPos = defender.getPos()

        if (attacker.destroyed || defender.destroyed) {
            ui.game.scenario?.map?.updateUnitList()
        }

        if (result.isOverrun) {
            attackerPos?.let {
                val pos = ui.render.cellToScreen(it.row, it.col, true)
                bounceText(pos.x, pos.y, "Overrun", true)
            }
        }
        if (result.isRugged && !result.isOverrun) {
            attackerPos?.let {
                val pos = ui.render.cellToScreen(it.row, it.col, true)
                bounceText(pos.x, pos.y, "Rugged Defense", false)
            }
        }

        val attackerLosses = result.losses
        val defenderLosses = result.kills
        if (attackerLosses > 0 && attackerPos != null) {
            val pos = ui.render.cellToScreen(attackerPos.row, attackerPos.col, true)
            bounceText(pos.x, pos.y, "-$attackerLosses", false)
        }
        if (defenderLosses > 0 && defenderPos != null) {
            val pos = ui.render.cellToScreen(defenderPos.row, defenderPos.col, true)
            bounceText(pos.x, pos.y, "-$defenderLosses", false)
        }
        // Sidebar log: a full sentence in the spirit of the legacy combatLogInfoBox (who attacked
        // whom, where, casualties inflicted/taken, survivors, XP) — the viewing side's own losses
        // render red (spec). Reuses only data already computed above for the bounce text /
        // showAttackInfo, so nothing new is revealed.
        if (attackerLosses > 0 || defenderLosses > 0 || attacker.destroyed || defender.destroyed) {
            val own = ui.game.spotSide
            val attackerIsOwn = attacker.player?.side == own
            val defenderIsOwn = defender.player?.side == own
            val atkName = "${UIBuilder.unitIDToOrdinal(attacker.id)} ${attacker.unitData(true).name}".trim()
            val defName = defender.unitData(true).name
            // Raw "(col,row)" dropped from the visible text (it was clutter, spec) — the row is
            // clickable instead (jumps to the defender's hex) with the coordinates only in its
            // tooltip, same treatment as the Turn Report's rows.
            val segments = mutableListOf(
                HudLog.Segment("$atkName attacked $defName:")
            )
            val inflicted = StringBuilder("inflicted $defenderLosses")
            if (defender.destroyed) inflicted.append(" — $defName destroyed")
            segments.add(HudLog.Segment("$inflicted,", defenderIsOwn && defenderLosses > 0))
            val taken = StringBuilder("lost $attackerLosses")
            if (attacker.destroyed) taken.append(" — unit destroyed") else taken.append(" (${attacker.strength} remain)")
            segments.add(HudLog.Segment(taken.toString(), attackerIsOwn && attackerLosses > 0))
            if (result.atkExpGained > 0 && !attacker.destroyed) {
                segments.add(HudLog.Segment("· +${result.atkExpGained} XP"))
            }
            if (defenderPos != null) {
                HudLog.addAt(defenderPos.row, defenderPos.col, *segments.toTypedArray())
            } else {
                HudLog.add(*segments.toTypedArray())
            }
        }

        attackerPos?.let { ui.render.render(it.row, it.col, radius) }

        if (result.atkLeaderGain && attackerPos != null) {
            val pos = ui.render.cellToScreen(attackerPos.row, attackerPos.col, true)
            bounceText(pos.x, pos.y, "New Leader", true)
        }
        if (result.defLeaderGain && defenderPos != null) {
            val pos = ui.render.cellToScreen(defenderPos.row, defenderPos.col, true)
            bounceText(pos.x, pos.y, "New Leader", true)
        }

        try {
            val currentUnit = ui.game.scenario?.map?.currentUnit
            if (currentUnit != null) {
                if (currentUnit.id == attacker.id && attacker.destroyed) {
                    ui.game.scenario?.map?.delCurrentUnit()
                } else {
                    ui.game.scenario?.map?.selectUnit(currentUnit)
                }
            }
        } finally {
            // showAttackInfo() overwrote #statusmsg with the combat flags; restore the
            // scenario/turn/date line and refresh the navigator/End-Turn count.
            if (ui.game.scenario?.map?.currentPlayer?.type == PlayerType.HUMAN_LOCAL) ui.updateStatusBar()
            ui.game.waitUIAnimation = false
            ui.game.uiAnimationFinished()
        }
    }
}
