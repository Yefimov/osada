package org.osada.ui

import org.osada.CombatLog
import org.osada.PlayerType
import org.osada.addSurrender
import org.osada.evaluateScenarioEvents
import org.osada.handleMoveVictory
import org.osada.hero.HeroCampaign
import org.osada.i18n.I18n
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
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.rules.ExtendedVictory
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

    @Suppress("ReturnCount")
    fun uiUnitMove(
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Boolean {
        val map = ui.game.scenario?.map
        val startPos = unit.getPos()
        if (map == null || startPos == null) return false
        if (OsadaMultiplayer.active) {
            return OsadaMultiplayer.submitMove(unit, row, col)
        }
        val radius = getUnitRenderRadius(unit)

        ui.game.waitUIAnimation = true
        val result = map.moveUnit(unit, row, col)
        // §1.12: off-screen was not modelled at all -- a spotted unit moving outside the current
        // viewport used to animate in full with nothing scrolling to it. Nothing here forces a
        // scroll (that would yank the camera off whatever the player is doing); it just stops
        // tweening a move nobody could see.
        val animated = result.passedCells.size > 1 && result.isVisible && !isPathEntirelyOffScreen(result)

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

    /** True when none of [result]'s path cells are inside `#game`'s current scroll viewport --
     *  §1.12's second gap, distinct from [MovementResults.isVisible]'s fog check. No margin here
     *  (unlike [uiScrollUnitIntoView]'s "comfortably visible" one): the only question is whether
     *  the player could see any part of the move happen, not whether it is comfortably placed. */
    private fun isPathEntirelyOffScreen(result: MovementResults): Boolean {
        val gameDiv = byId("game")?.asDynamic()
        val clientWidth = (gameDiv?.clientWidth as? Number)?.toDouble()
        val clientHeight = (gameDiv?.clientHeight as? Number)?.toDouble()
        if (gameDiv == null || clientWidth == null || clientHeight == null) return false
        val scrollLeft = (gameDiv.scrollLeft as? Number)?.toDouble() ?: 0.0
        val scrollTop = (gameDiv.scrollTop as? Number)?.toDouble() ?: 0.0
        return result.passedCells.none { cell ->
            val pos = ui.render.cellToScreen(cell.row, cell.col, true)
            pos.x in scrollLeft..(scrollLeft + clientWidth) && pos.y in scrollTop..(scrollTop + clientHeight)
        }
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
        val preview = GameRules.calculateAttackResults(attacker, defender, true, map.getUnits().toList())
        val attackerOldStrength = attacker.strength

        resolveSupportFire(map, attacker, defender, attackerPos, preview)

        val attackStopped =
            attacker.destroyed || GameRules.isLossOverRetreatThreshold(attacker.strength, attackerOldStrength)
        val defenderOldStrength = defender.strength
        val result = resolveAttackResult(map, attacker, defender, preview, attackStopped)
        reportCounterBattery(ui, map, attackerPos)

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
            ui.render.addAnimation(supportPos.row, supportPos.col, it, supportDir, support)
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
        val retreatPos = GameRules.getRetreatPosition(map.map, defender, map.rows)
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
            // Combat-side hero processing ran while the defender was still alive. Failed-retreat
            // surrender destroys it afterwards, so resolve the commander's fate now.
            HeroCampaign.recordCasualty(defender, map.turn)
            val captorSide = attacker.player?.side
            if (surrenderPos != null && captorSide != null) {
                CombatLog.addSurrender(defender, surrenderPos, captorSide, prestige)
            }
            surrenderPos?.let {
                ui.render.addAnimation(it.row, it.col, "explosion", 0, defender)
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
                ui.render.addAnimation(attackerPos.row, attackerPos.col, it, attackDir, attacker)
            }

            if (!defender.destroyed && result.defcanfire) {
                val defenderAnimType = attackAnimationByClass.getOrNull(defender.unitData(true).uclass)
                val defendDir =
                    GameRules.getDirection(defenderPos.row, defenderPos.col, attackerPos.row, attackerPos.col)
                        ?: defender.facing
                defenderAnimType?.let {
                    ui.render.addAnimation(defenderPos.row, defenderPos.col, it, defendDir, defender)
                }
            }
        }

        if (attacker.destroyed) {
            ui.render.addAnimation(attackerPos.row, attackerPos.col, "explosion", 0, attacker)
        }
        if (defender.destroyed) {
            ui.render.addAnimation(defenderPos.row, defenderPos.col, "explosion", 0, defender)
        }
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
        reportInterceptions(ui, unit, result, pos)
        reportMinefield(ui, unit, result, pos)
        reportTrigger(ui, unit, result, pos)
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
            // OG reports the reward on capture ("You gain 40 prestige"); the amount was computed
            // in applyHexCapture but never surfaced, so a capture read as worth nothing.
            // A silent prestige trigger on a captured flag (Kieler Hafen) is the capture reward,
            // not a second unrelated discovery. Fold it into this one line; an authored trigger
            // message is still reported separately by reportTrigger.
            val totalPrestige = result.capturePrestige + result.triggerPrestige
            val reward = if (totalPrestige > 0) " (+$totalPrestige prestige)" else ""
            HudLog.addAt(pos.row, pos.col, "${unit.unitData(true).name} captured $place$reward")
        }
        if (result.isVictorySide >= 0) {
            ui.game.handleMoveVictory(result.isVictorySide)
        }
        // OG manual 3.7: an extended condition is an ALTERNATIVE route to victory -- "you must only
        // meet ONE of them to win" -- so it is checked beside the ordinary capture win rather than
        // instead of it. Retreating the last required formation, or killing the last required
        // enemy, ends the scenario the moment it happens.
        reportWithdrawal(ui, unit, result, pos)
        ui.game.scenario?.let { scenario ->
            ExtendedVictory.satisfiedSide(scenario, scenario.map)?.let { ui.game.handleMoveVictory(it) }
            // Manual 3.7.1 is a DEFEAT condition, so the side that fails it hands the scenario to
            // the OTHER one. Checked here as well as after combat because a Must-Survive formation
            // can also be lost to a minefield or to running out of fuel on a move.
            ExtendedVictory.defeatedSide(scenario, scenario.map)?.let { loser ->
                ui.game.handleMoveVictory(if (loser == 0) 1 else 0)
            }
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
        // After the camera has settled on the unit that moved: an authored proximity event fires
        // because THIS arrival brought someone into its radius, and its own anchored callout must
        // not be scrolled out from under itself.
        ui.game.evaluateScenarioEvents()
        ui.game.waitUIAnimation = false
        ui.game.uiAnimationFinished()
    }
}

/**
 * This move walked into a minefield (`rules/Minefields`, OG 9.9).
 *
 * Reported for the same reason interception is: the formation stopped short, and possibly lost
 * strength, from something the player did not order and did not watch. `DEFERRED.md` §1.1 is
 * explicit that movement damage with no visible cause reads as a bug, and an undetected minefield is
 * the one case in this engine that can produce it — so it gets an alert on the hex and a clickable
 * HUD-log line, and the wording distinguishes a field that was already known from one that was not.
 *
 * Top-level for the same reason as [reportInterceptions]: [AnimationOrchestrator]'s function budget.
 */

private fun reportMinefield(
    ui: UI,
    unit: GameUnit,
    result: MovementResults,
    pos: Cell,
) {
    if (!result.hitMinefield) return
    val key =
        when {
            result.minefieldLosses > 0 -> "combat.minefield.hidden"
            result.minefieldWasHidden -> "combat.minefield.hidden.nodamage"
            else -> "combat.minefield.known"
        }
    val text =
        I18n.t(
            key,
            mapOf("unit" to unit.unitData(true).name, "losses" to result.minefieldLosses),
        )
    ui.showAlert(pos.row, pos.col, I18n.t("combat.minefield.alert"), true)
    HudLog.addAt(pos.row, pos.col, HudLog.Segment(text, ownLoss = result.minefieldLosses > 0))
    ui.showUnitInfo(unit)
}

/**
 * A formation left the map through an OG escape hex (`rules/ExtendedVictory`, manual 3.7.4).
 *
 * Announced for the same reason a capture is: the unit is GONE from the player's order of battle
 * and they did not order it destroyed. Saying so, with the running count against the quota, is what
 * makes an evacuation objective legible -- otherwise a division simply vanishes.
 */
private fun reportWithdrawal(
    ui: UI,
    unit: GameUnit,
    result: MovementResults,
    pos: Cell,
) {
    val scenario = ui.game.scenario
    val side = unit.player?.side
    if (!result.withdrew || scenario == null || side == null) return
    val text =
        I18n.t(
            "victory.withdrawn",
            mapOf(
                "unit" to unit.unitData(true).name,
                "count" to (scenario.unitsWithdrawn.getOrNull(side) ?: 0),
                "total" to (scenario.retreatUnitsPerSide.getOrNull(side) ?: 0),
            ),
        )
    ui.showAlert(pos.row, pos.col, I18n.t("victory.withdrawn.alert"), true)
    HudLog.addAt(pos.row, pos.col, text)
}

/**
 * This move set off an OG trigger hex (`rules/TriggerHexes`, OG 9.10).
 *
 * Reported for the same reason a capture is: something happened that the player did not order and
 * that changed their position -- prestige, experience, a leader or a formation appeared out of the
 * map. `DEFERRED.md` 1.1's rule is about damage with no visible cause, and a silent GIFT is the
 * same failure with the sign flipped: a player who never sees it cannot tell the mechanic works.
 *
 * The author's own text is used verbatim where they wrote one, because it is authored scenario
 * prose in the same sense a briefing is -- 33 of the corpus's 850 triggers carry text, and OG's
 * own examples ("Through exploration, experience is found", "From this cliff you see very, very
 * far...") say more than a generated line could. Where there is none, the generic line names the
 * action so the reward is still attributable.
 */
private fun reportTrigger(
    ui: UI,
    unit: GameUnit,
    result: MovementResults,
    pos: Cell,
) {
    if (!result.firedTrigger) return
    if (result.isCapture && result.triggerMessage == null && result.triggerPrestige > 0) return
    // The reward is stated, not hinted at. Reported of the old wording -- *"Discovery! Frigate
    // found something here (+40 prestige)"* -- that it reads like a treasure chest in a war game;
    // the generic line now names the gain itself, and the prestige suffix is localized rather than
    // an English literal appended to a translated sentence.
    val name = unit.unitData(true).name
    val prestige = result.triggerPrestige
    val text =
        when {
            result.triggerMessage != null -> result.triggerMessage!!
            prestige > 0 -> I18n.t("trigger.fired.prestige", mapOf("unit" to name, "amount" to prestige))
            else -> I18n.t("trigger.fired", mapOf("unit" to name))
        }
    // Only appended to an AUTHORED message: the generic prestige line above already says the number,
    // and repeating it would print the amount twice.
    val reward =
        if (prestige > 0 && result.triggerMessage != null) {
            I18n.t("trigger.reward.prestige", mapOf("amount" to prestige))
        } else {
            ""
        }
    ui.showAlert(pos.row, pos.col, I18n.t("trigger.alert"), true)
    HudLog.addAt(pos.row, pos.col, "$text$reward")
    ui.showUnitInfo(unit)
}

/**
 * Counterbattery fire the player's attack (or the AI's) just drew, reported through exactly the
 * surface an AA interception gets: a non-modal banner plus a clickable HUD-log line, pinned to the
 * hex of the artillery that was answered.
 *
 * The player DID order this combat, unlike an interception -- but they did not order the reply, and
 * an artillery piece that comes back from a successful bombardment two points weaker with nothing
 * on screen to explain it is the same `DEFERRED.md` 1.1 failure by another route.
 *
 * `GameMap.lastCounterBattery` is a one-shot channel cleared at the start of every attack, so this
 * can be called unconditionally: it does nothing on a combat that drew none.
 *
 * Top-level (not a method) to keep [AnimationOrchestrator] within the project's
 * function-per-class limit, the same reason [reportInterceptions] is.
 */
private fun reportCounterBattery(
    ui: UI,
    map: GameMap,
    pos: Cell,
) {
    val events = map.lastCounterBattery
    if (events.isEmpty()) return
    val observerSide = ui.game.spotSide
    InterceptionBanner.show(ui, events, observerSide)
    events
        .filter { it.plane.player?.side == observerSide || it.interceptor.player?.side == observerSide }
        .forEach { event ->
            HudLog.addAt(
                pos.row,
                pos.col,
                HudLog.Segment(
                    I18n.t(
                        MoveReactionText.lineKey(event.kind, event.planeDestroyed),
                        mapOf(
                            "gun" to event.interceptor.unitData(true).name,
                            "plane" to event.plane.unitData(true).name,
                            "losses" to event.losses,
                        ),
                    ),
                    ownLoss = event.plane.player?.side == observerSide,
                ),
            )
        }
}

/**
 * AA fired on this move. The player never chose this combat and never watched it resolve, so it
 * gets an obvious non-modal banner plus a clickable HUD-log line; the combat log keeps the full
 * detail it always did. Nothing is published before a gun fires -- these events only exist for
 * interceptions that already happened.
 *
 * Top-level (not a method) to keep [AnimationOrchestrator] within the project's
 * function-per-class limit.
 */
private fun reportInterceptions(
    ui: UI,
    unit: GameUnit,
    result: MovementResults,
    pos: Cell,
) {
    if (result.interceptions.isEmpty()) return
    val observerSide = ui.game.spotSide
    InterceptionBanner.show(ui, result.interceptions, observerSide)
    result.interceptions
        .filter { it.plane.player?.side == observerSide || it.interceptor.player?.side == observerSide }
        .forEach { event ->
            HudLog.addAt(
                pos.row,
                pos.col,
                HudLog.Segment(
                    I18n.t(
                        MoveReactionText.lineKey(event.kind, event.planeDestroyed),
                        mapOf(
                            "gun" to event.interceptor.unitData(true).name,
                            "plane" to event.plane.unitData(true).name,
                            "losses" to event.losses,
                        ),
                    ),
                    ownLoss = event.plane.player?.side == observerSide,
                ),
            )
        }
    // The aircraft's own state changed under the player; refresh what shows it.
    ui.showUnitInfo(unit)
}
