package org.osada.ui

import org.osada.PlayerType
import org.osada.evaluateScenarioEvents
import org.osada.forCurrentScenario
import org.osada.handleMoveVictory
import org.osada.hero.HeroCampaign
import org.osada.i18n.I18n
import org.osada.model.AttackOutcome
import org.osada.model.Cell
import org.osada.model.CombatResults
import org.osada.model.ExtendedCell
import org.osada.model.ForcedMove
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.InterceptionEvent
import org.osada.model.MovementResults
import org.osada.model.Surrender
import org.osada.model.executeAttack
import org.osada.model.moveUnit
import org.osada.model.surpriseTarget
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.rules.AirborneLanding
import org.osada.rules.ExtendedVictory
import org.osada.rules.GameRules
import org.osada.rules.getDirection
import org.osada.rules.getUnitAttackRange
import org.osada.rules.getUnitMoveRange
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
            // A refused order (paused, or one still pending) must not leave the AI's turn waiting
            // on an animation that will never run; an accepted one is released by the host.
            return OsadaMultiplayer.submitMove(unit, row, col).also { if (!it) ui.game.waitUIAnimation = false }
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
            // Hidden from the map BEFORE this render. `moveUnit` has already put the unit on its
            // destination hex, and `moveAnimation` only raised the flag itself, after this render
            // had drawn it there -- so a unit (most visibly an enemy coming out of the fog) popped
            // up at its destination first and only then flew in from its start.
            unit.hasAnimation = true
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
        // In a room the order goes to the host, which resolves it and publishes the result -- the
        // same road a move takes (`uiUnitMove`).
        return if (OsadaMultiplayer.active) {
            OsadaMultiplayer.submitAttack(attacker, defender).also { if (!it) ui.game.waitUIAnimation = false }
        } else {
            resolveAndAnimateAttack(attacker, defender)
        }
    }

    private fun resolveAndAnimateAttack(
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
        // The whole order -- support fire, "Attack Stopped", overrun, the exchange, retreat or
        // surrender -- is resolved by the model, exactly as a multiplayer command resolves it. This
        // function only shows what happened.
        val outcome = map.executeAttack(attacker, defender)
        queueSupportAnimations(outcome, attackerPos)
        reportCounterBattery(ui, map, attackerPos)
        outcome.surrender?.let(::presentSurrender)
        dispatchAttackAnimations(attacker, defender, attackerPos, defenderPos, outcome.attackStopped, outcome.result)

        ui.render.runAnimation(
            animationCallback {
                animateRetreat(outcome.retreat) {
                    attackResultPresenter.present(attacker, defender, outcome, radius)
                }
            },
        )

        return true
    }

    /**
     * Plays a combat retreat as a one-hex move once the exchange has been shown, then continues with
     * [then]. The model already put the unit on its new hex; this only lets the player SEE it go
     * there, the way Panzer Marshal animated a retreat. Skipped for a move the viewing side cannot
     * see at either end, which must not be drawn at all.
     */
    private fun animateRetreat(
        move: ForcedMove?,
        then: () -> Unit,
    ) {
        val side = ui.game.spotSide
        val grid =
            ui.game.scenario
                ?.map
                ?.map
        val visible =
            move != null &&
                (
                    move.unit.player?.side == side ||
                        grid?.getOrNull(move.from.row)?.getOrNull(move.from.col)?.isSpotted(side) == true ||
                        grid?.getOrNull(move.to.row)?.getOrNull(move.to.col)?.isSpotted(side) == true
                )
        if (move == null || !visible || move.unit.destroyed) {
            then()
            return
        }
        val path =
            MovementResults().apply {
                isVisible = true
                passedCells.add(ExtendedCell(move.from.row, move.from.col).apply { isVisible = true })
                passedCells.add(ExtendedCell(move.to.row, move.to.col).apply { isVisible = true })
            }
        move.unit.hasAnimation = true
        ui.render.moveAnimation(
            jsObject {
                this.unit = move.unit
                this.moveResults = path
                this.cbfunc = animationCallback { then() }
            },
        )
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

    /** The defender's supporting units firing, each turned toward the attacker's hex. */
    private fun queueSupportAnimations(
        outcome: AttackOutcome,
        attackerPos: Cell,
    ) {
        outcome.supportShots.forEach { shot ->
            val support = shot.supporter
            val animType = attackAnimationByClass.getOrNull(support.unitData(true).uclass) ?: return@forEach
            val dir =
                GameRules.getDirection(shot.supporterPos.row, shot.supporterPos.col, attackerPos.row, attackerPos.col)
                    ?: support.facing
            ui.render.addAnimation(shot.supporterPos.row, shot.supporterPos.col, animType, dir, support)
        }
    }

    /**
     * A defender that owed a retreat and had nowhere to go. Named distinctly from an ordinary kill:
     * the player needs to see that cutting off the retreat is what did it, and what it earned.
     */
    private fun presentSurrender(surrender: Surrender) {
        val pos = surrender.pos
        ui.render.addAnimation(pos.row, pos.col, "explosion", 0, surrender.unit)
        ui.showAlert(pos.row, pos.col, I18n.t("combat.alert.surrendered"), false)
        val reward =
            if (surrender.prestige > 0) {
                I18n.t("trigger.reward.prestige", mapOf("amount" to surrender.prestige))
            } else {
                ""
            }
        HudLog.addAt(pos.row, pos.col, I18n.t("hud.log.surrendered", mapOf("unit" to surrender.name)) + reward)
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
        val complete = ui.game.forCurrentScenario(onComplete)
        val cb: (dynamic) -> Unit = { _: dynamic -> complete() }
        val wrapper = js("{}")
        wrapper.cbfunc = cb
        return wrapper
    }

    private fun finishMoveAnimation(
        unit: GameUnit,
        result: MovementResults,
        radius: Int,
    ) {
        // `uiUnitMove` raises it before the animation starts; make sure no path leaves it up.
        unit.hasAnimation = false
        // A commander lost on the move itself (a minefield, an AA gun) is announced now, as a
        // combat casualty is announced after its combat.
        HeroCasualtyPresenter.present(HeroCampaign.drainCasualties())
        reportAirborneLanding(ui, unit, result)
        val pos =
            unit.getPos() ?: run {
                ui.game.waitUIAnimation = false
                ui.game.uiAnimationFinished()
                return
            }
        ui.render.render(pos.row, pos.col, radius)

        // Looked up while the flag is still up: it is what makes the forced combat below a
        // SURPRISED one (`GameMap.surpriseTarget`).
        // Walking into a hidden unit is announced on the hex it refused; an ambush
        // (`rules/MoveAmbush`) over the unit it stopped.
        val surprisedAt = result.surpriseCell.firstOrNull() ?: pos.takeIf { result.ambusher != null }
        val ambusher =
            surprisedAt?.let { cell ->
                ui.showAlert(cell.row, cell.col, I18n.t("combat.alert.surprised"), true)
                ui.game.scenario
                    ?.map
                    ?.surpriseTarget(unit, result)
            }
        reportInterceptions(ui, unit, result, pos)
        reportMinefield(ui, unit, result, pos)
        reportContact(ui, unit, result, pos)
        reportTrigger(ui, unit, result, pos)
        if (result.startedBridge) PontoonBridgeAnnouncements.laying(ui, unit)
        reportCapture(ui, unit, result, pos)
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
        // The forced combat runs last, as PM ran it: everything the move itself caused has been
        // reported by now. `uiUnitAttack` applies the result synchronously, so the flag can drop
        // straight after it, and its presenter is what releases `waitUIAnimation` once the combat
        // animation ends -- releasing it here as well would let the AI act mid-animation.
        if (ambusher != null && !ui.game.gameEnded) {
            uiUnitAttack(unit, ambusher)
            unit.isSurprised = false
            return
        }
        unit.isSurprised = false
        ui.game.waitUIAnimation = false
        ui.game.uiAnimationFinished()
    }
}

/** This move took a flag: the alert on the hex and the clickable HUD-log line with its reward.
 *  Top-level for the same reason as [reportInterceptions]: [AnimationOrchestrator]'s function budget. */
private fun reportCapture(
    ui: UI,
    unit: GameUnit,
    result: MovementResults,
    pos: Cell,
) {
    if (!result.isCapture) return
    ui.showAlert(pos.row, pos.col, I18n.t("combat.alert.captured"), true)
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
    val reward =
        if (totalPrestige > 0) I18n.t("trigger.reward.prestige", mapOf("amount" to totalPrestige)) else ""
    val line = I18n.t("hud.log.captured", mapOf("unit" to unit.unitData(true).name, "place" to place))
    HudLog.addAt(pos.row, pos.col, line + reward)
}

/**
 * OG's airborne misdrop (`rules/AirborneLanding`): a drop that did not land where it was ordered is
 * always explained, because the unit ended somewhere the player did not choose -- or is gone. A
 * drop on target says nothing. Called before the position lookup, since a lost unit has none.
 */
private fun reportAirborneLanding(
    ui: UI,
    unit: GameUnit,
    result: MovementResults,
) {
    val outcome = result.airborneLanding ?: return
    val key =
        when (outcome.kind) {
            AirborneLanding.Kind.ON_TARGET -> return
            AirborneLanding.Kind.SCATTERED -> "combat.misdrop.scattered"
            AirborneLanding.Kind.HIDDEN_ENEMY -> "combat.misdrop.enemy"
            AirborneLanding.Kind.DRIFTED -> "combat.misdrop.drifted"
            AirborneLanding.Kind.LOST -> "combat.misdrop.lost"
        }
    val values =
        mapOf("unit" to unit.unitData(true).name, "losses" to outcome.losses, "chance" to outcome.chancePercent)
    val text = I18n.t(key, values)
    val segment = HudLog.Segment(text, ownLoss = outcome.losses > 0 || outcome.kind == AirborneLanding.Kind.LOST)
    val landing = outcome.landing
    if (landing == null) {
        HudLog.add(segment)
    } else {
        ui.showAlert(landing.row, landing.col, I18n.t("combat.misdrop.alert"), true)
        HudLog.addAt(landing.row, landing.col, segment)
    }
}

/**
 * This move walked into a minefield (`rules/Minefields`, OG 9.9).
 *
 * Reported for the same reason interception is: the formation stopped short, and possibly lost
 * strength, from something the player did not order and did not watch.
 * `docs/design/action-affordances-and-objectives.md` §1.1 is explicit that movement damage with no visible cause reads
 * as a bug, and an undetected minefield is the one case in this engine that can produce it — so it gets an alert on the
 * hex and a clickable HUD-log line, and the wording distinguishes a field that was already known from one that was not.
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
 * The move stopped short on meeting an enemy the side had not seen (`rules/ZoneOfControlContact`).
 * No combat happened, but the player ordered a longer move, so the stop is named rather than left to
 * look like a pathfinding fault. Shown only to the side that can see the mover.
 */
private fun reportContact(
    ui: UI,
    unit: GameUnit,
    result: MovementResults,
    pos: Cell,
) {
    val side = ui.game.spotSide
    val visible = unit.player?.side == side || unit.getHex()?.isSpotted(side) == true
    if (!result.stoppedByContact || !visible) return
    ui.showAlert(pos.row, pos.col, I18n.t("combat.alert.contact"), true)
    HudLog.addAt(pos.row, pos.col, I18n.t("hud.log.contact", mapOf("unit" to unit.unitData(true).name)))
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
 * map. The visible-cause rule (`docs/design/action-affordances-and-objectives.md` §1.1) covers
 * gifts too: a silent GIFT is the same failure with the sign flipped: a player who never sees it cannot tell the
 * mechanic works.
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
 * on screen to explain it is the same `docs/design/action-affordances-and-objectives.md` §1.1 failure by another route.
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
    InterceptionAnimation.play(ui, result.interceptions, observerSide, pos)
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

/**
 * Plays the fire a MOVE drew -- AA interception and its sibling reactions -- as the ordinary attack
 * animation and sound, instead of only reporting the result.
 *
 * Until 2026-09-25 an interception produced a banner and a log line and nothing on the map: the
 * aircraft simply arrived weaker, or vanished, with no shot fired and no sound (user report). The
 * attack path already knows how to show a gun firing (`attackAnimationByClass`, whose sprite carries
 * the class sound, with the equipment's own OG sound laid over it by `MapAnimator.addAnimation`), so
 * this queues the same animation on the gun's hex, turned toward the aircraft, plus the explosion
 * when the aircraft goes down.
 *
 * **Only a gun the observer can see is drawn.** A hidden battery must never be placed on the map by
 * the UI (`docs/design/action-affordances-and-objectives.md` §1.1). Firing already reveals a gun
 * for the turn (`tempSpotted`), so in practice the shooter is drawn -- but that is the rules' decision, read here, not
 * re-made.
 */
internal object InterceptionAnimation {
    fun play(
        ui: UI,
        events: List<InterceptionEvent>,
        observerSide: Int,
        planePos: Cell,
    ) {
        var queued = false
        events.forEach { event ->
            val gun = event.interceptor
            val gunPos = gun.getPos()
            val gunVisible = gun.tempSpotted || gun.getHex()?.isSpotted(observerSide) == true
            val animType = attackAnimationByClass.getOrNull(gun.unitData(true).uclass)
            if (gunPos != null && gunVisible && animType != null) {
                val dir =
                    GameRules.getDirection(gunPos.row, gunPos.col, planePos.row, planePos.col) ?: gun.facing
                queued = ui.render.addAnimation(gunPos.row, gunPos.col, animType, dir, gun) || queued
            }
            if (event.planeDestroyed) {
                queued = ui.render.addAnimation(planePos.row, planePos.col, "explosion", 0, event.plane) || queued
            }
        }
        if (queued) ui.render.runAnimation(null)
    }
}
