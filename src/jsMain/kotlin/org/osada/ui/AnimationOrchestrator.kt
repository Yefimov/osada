package org.osada.ui

import org.osada.CombatLog
import org.osada.PlayerType
import org.osada.addSurrender
import org.osada.handleMoveVictory
import org.osada.model.Cell
import org.osada.model.CombatResults
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.MovementResults
import org.osada.model.attackUnit
import org.osada.model.getUnits
import org.osada.model.hasRailData
import org.osada.model.moveUnit
import org.osada.model.retreatUnit
import org.osada.model.surrenderUnit
import org.osada.rules.GameRules
import org.osada.rules.calculateAttackResults
import org.osada.rules.getDirection
import org.osada.rules.getRetreatPosition
import org.osada.rules.getSupportFireUnits
import org.osada.rules.getUnitAttackRange
import org.osada.rules.getUnitMoveRange
import org.osada.rules.isLossOverRetreatThreshold
import org.osada.rules.isRetreatBlockedByOwnUnitsOnly
import org.osada.rules.shouldDefenderRetreat
import org.osada.rules.shouldDefenderSurrender
import org.osada.uiAnimationFinished

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
 * Extracted from the former [UI] god-class (SRP). Attack-result presentation (bounce texts,
 * sidebar HUD log, leader-gain callouts) lives in [AttackResultPresenter].
 */
internal class AnimationOrchestrator(
    private val ui: UI,
) {
    private val attackResultPresenter = AttackResultPresenter(ui)

    fun uiUnitMove(
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Boolean {
        val map = ui.game.scenario?.map
        val startPos = unit.getPos()
        if (map == null || startPos == null) return false
        val radius = getUnitRenderRadius(unit)

        ui.game.waitUIAnimation = true
        val result = map.moveUnit(unit, row, col)
        val animated = result.passedCells.size > 1 && result.isVisible

        if (animated) {
            playMoveSound(unit)
            ui.render.render(startPos.row, startPos.col, radius)
            val params =
                jsObject {
                    this.unit = unit
                    this.moveResults = result
                    this.cbfunc =
                        animationCallback {
                            finishMoveAnimation(unit, result, radius)
                        }
                }
            ui.render.moveAnimation(params)
        } else {
            finishMoveAnimation(unit, result, radius)
        }
        return animated
    }

    fun uiUnitAttack(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean {
        val map = ui.game.scenario?.map
        val attackerPos = attacker.getPos()
        val defenderPos = defender.getPos()
        if (map == null || attackerPos == null || defenderPos == null) return false
        logAttackStart(attacker, defender, attackerPos, defenderPos)
        val radius = maxOf(getUnitRenderRadius(attacker), getUnitRenderRadius(defender))

        ui.game.waitUIAnimation = true
        UIBuilder.showAttackInfo(attacker, defender)
        val preview = GameRules.calculateAttackResults(attacker, defender, true)
        val attackerOldStrength = attacker.strength

        resolveSupportFire(map, attacker, defender, attackerPos, preview)

        val attackStopped =
            attacker.destroyed || GameRules.isLossOverRetreatThreshold(attacker.strength, attackerOldStrength)
        val defenderOldStrength = defender.strength
        val result = resolveAttackResult(map, attacker, defender, preview, attackStopped)

        if (!attackStopped && !defender.destroyed) {
            applyDefenderRetreat(map, attacker, defender, defenderOldStrength)
        }

        dispatchAttackAnimations(attacker, defender, attackerPos, defenderPos, attackStopped, result)

        ui.render.runAnimation(
            animationCallback {
                attackResultPresenter.present(attacker, defender, result, radius)
            },
        )

        return true
    }

    private fun logAttackStart(
        attacker: GameUnit,
        defender: GameUnit,
        attackerPos: Cell,
        defenderPos: Cell,
    ) {
        console.log(
            "[osada] uiUnitAttack attacker=${attacker.id}(${attacker.unitData(true).name}) at " +
                "${attackerPos.row},${attackerPos.col} defender=${defender.id}(${defender.unitData(true).name}) " +
                "at ${defenderPos.row},${defenderPos.col}",
        )
    }

    // Resolve support fire from adjacent artillery / flak / fighters.
    private fun resolveSupportFire(
        map: GameMap,
        attacker: GameUnit,
        defender: GameUnit,
        attackerPos: Cell,
        preview: CombatResults,
    ) {
        if (attacker.isSurprised || preview.isOverrun) return
        val supportUnits = GameRules.getSupportFireUnits(map.getUnits().toList(), attacker, defender)
        for (support in supportUnits) {
            if (attacker.destroyed) break
            applySupportFire(map, support, attacker, attackerPos)
        }
    }

    private fun applySupportFire(
        map: GameMap,
        support: GameUnit,
        attacker: GameUnit,
        attackerPos: Cell,
    ) {
        val supportPos = support.getPos() ?: return
        map.attackUnit(support, attacker, true)
        val supportClass = support.unitData(true).uclass
        val supportAnimType = attackAnimationByClass.getOrNull(supportClass)
        val supportDir =
            GameRules.getDirection(supportPos.row, supportPos.col, attackerPos.row, attackerPos.col)
                ?: support.facing
        supportAnimType?.let {
            ui.render.addAnimation(supportPos.row, supportPos.col, it, supportDir)
        }
    }

    private fun resolveAttackResult(
        map: GameMap,
        attacker: GameUnit,
        defender: GameUnit,
        preview: CombatResults,
        attackStopped: Boolean,
    ): CombatResults =
        if (!attackStopped) {
            map.attackUnit(attacker, defender, false, preview.isOverrun)
        } else {
            attacker.hasFired = true
            preview
        }

    // Defender retreat for ground-vs-ground combat when losses exceed the threshold.
    //
    // ORDERING IS THE RULE, not an implementation detail: the guard below means surrender is
    // reachable ONLY after a retreat has genuinely been triggered by the normal combat rules
    // (shouldDefenderRetreat: ground-vs-ground, not artillery/bomber/fortification, losses past
    // UNIT_RETREAT_THRESHOLD). A unit that merely happens to have no legal adjacent hex — sitting
    // in a corner, ringed by water, boxed in by friendlies — never surrenders while it is not
    // being forced to retreat. Only once the retreat is owed and cannot be paid does the unit
    // surrender. This is COMBAT encirclement, not operational encirclement: see
    // SURRENDER_ON_FAILED_RETREAT.
    private fun applyDefenderRetreat(
        map: GameMap,
        attacker: GameUnit,
        defender: GameUnit,
        defenderOldStrength: Int,
    ) {
        if (!GameRules.shouldDefenderRetreat(attacker, defender, defenderOldStrength)) return
        val retreatPos = GameRules.getRetreatPosition(map.map, defender, map.rows, map.hasRailData())
        if (retreatPos != null) {
            map.retreatUnit(defender, retreatPos)
        } else if (GameRules.shouldDefenderSurrender(
                defender,
                GameRules.isRetreatBlockedByOwnUnitsOnly(map.map, defender, map.rows),
            )
        ) {
            val surrenderPos = defender.getPos()
            val name = defender.unitData(true).name
            val prestige = map.surrenderUnit(defender, attacker)
            val captorSide = attacker.player?.side
            if (surrenderPos != null && captorSide != null) {
                CombatLog.addSurrender(defender, surrenderPos, captorSide, prestige)
            }
            surrenderPos?.let {
                ui.render.addAnimation(it.row, it.col, "explosion", 0)
                ui.showAlert(it.row, it.col, "Surrendered", false)
                // Named distinctly from an ordinary kill: the player needs to see that cutting off
                // the retreat is what did it, not damage — and what it earned.
                val reward = if (prestige > 0) " (+$prestige prestige)" else ""
                HudLog.addAt(it.row, it.col, "$name surrendered — encircled, no retreat$reward")
            }
        }
    }

    private fun dispatchAttackAnimations(
        attacker: GameUnit,
        defender: GameUnit,
        attackerPos: Cell,
        defenderPos: Cell,
        attackStopped: Boolean,
        result: CombatResults,
    ) {
        if (!attackStopped) {
            val attackerAnimType = attackAnimationByClass.getOrNull(attacker.unitData(true).uclass)
            val attackDir =
                GameRules.getDirection(attackerPos.row, attackerPos.col, defenderPos.row, defenderPos.col)
                    ?: attacker.facing
            attackerAnimType?.let {
                ui.render.addAnimation(attackerPos.row, attackerPos.col, it, attackDir)
            }

            if (!defender.destroyed && result.defcanfire) {
                val defenderAnimType = attackAnimationByClass.getOrNull(defender.unitData(true).uclass)
                val defendDir =
                    GameRules.getDirection(defenderPos.row, defenderPos.col, attackerPos.row, attackerPos.col)
                        ?: defender.facing
                defenderAnimType?.let {
                    ui.render.addAnimation(defenderPos.row, defenderPos.col, it, defendDir)
                }
            }
        }

        if (attacker.destroyed) ui.render.addAnimation(attackerPos.row, attackerPos.col, "explosion", 0)
        if (defender.destroyed) ui.render.addAnimation(defenderPos.row, defenderPos.col, "explosion", 0)
    }

    private fun animationCallback(onComplete: () -> Unit): dynamic {
        val cb: (dynamic) -> Unit = { _: dynamic -> onComplete() }
        val wrapper = js("{}")
        wrapper.cbfunc = cb
        return wrapper
    }

    private fun finishMoveAnimation(
        unit: GameUnit,
        result: MovementResults,
        radius: Int,
    ) {
        val pos =
            unit.getPos() ?: run {
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
            val hexName =
                ui.game.scenario
                    ?.map
                    ?.map
                    ?.getOrNull(pos.row)
                    ?.getOrNull(pos.col)
                    ?.name
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
        if (ui.game.scenario
                ?.map
                ?.currentPlayer
                ?.type == PlayerType.HUMAN_LOCAL
        ) {
            ui.updateStatusBar()
        }
        ui.game.waitUIAnimation = false
        ui.game.uiAnimationFinished()
    }
}
