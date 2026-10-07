package org.osada.ui

import kotlinx.browser.window
import org.osada.PlayerType
import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.model.getUnits
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.multiplayer.command.EndPlayerTurn
import org.osada.multiplayer.coop.CoopRuntime
import org.osada.multiplayer.ui.CoopHud
import org.osada.rules.GameRules
import org.osada.rules.getUnitAttackCells
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * Top-bar ready-unit navigator (cycling + count) and the End Turn badge it drives. Split from
 * the former `MenuController` god-class to stay within the project's function-count/class-size
 * limits. The actual turn-ending flow lives in [EndTurnFlow].
 */
internal class ReadyUnitNavigator(
    private val ui: UI,
) {
    /** How close to the screen's right edge the End Turn plate may come before its phone label is
     *  cut back to the countless one. Two pixels: this is a "did it overflow" test, not a margin. */
    private val topBarEdgeMarginPx = 2.0

    /** Own units that have done NOTHING at all yet this turn — the End Turn badge/confirm-nag
     *  definition. Deliberately narrower than [actionableUnits] below: a unit that already moved
     *  but can still fire is not "fully ready" for this count, even though it can still act. */
    private fun fullyReadyUnits(): List<GameUnit> {
        val map = ui.game.scenario?.map
        val player = map?.currentPlayer
        if (map == null || player == null || player.type != PlayerType.HUMAN_LOCAL) return emptyList()
        return map.getUnits().filter {
            it.player?.id == player.id && !it.hasMoved && !it.hasFired && !it.destroyed && hasAnyAction(it)
        }
    }

    /** Whether [unit] has any action actually available. Units that can still move always count;
     *  a unit that CANNOT move (fortifications like Zborow's Ukreplenie have 0 move points by
     *  design) only counts while it can still shoot at something reachable — otherwise the End
     *  Turn badge nags "N units can still act" every turn about a foxhole with nothing to do,
     *  and the navigator keeps cycling to it. Reuses [GameRules.getUnitAttackCells] (the same
     *  check the attack ring uses) rather than re-deriving range/target/spotting rules; the
     *  target scan only runs for immobile units, so the cost stays negligible. */
    fun hasAnyAction(unit: GameUnit): Boolean {
        val map = ui.game.scenario?.map
        return when {
            unit.getMovesLeft() > 0 -> true
            map == null -> false
            else ->
                org.osada.multiplayer.client.OsadaMultiplayer.preview(unit) {
                    GameRules.getUnitAttackCells(map.map, unit, map.rows, map.cols).isNotEmpty()
                }
        }
    }

    /** Own units that can STILL act this turn: haven't moved yet, OR have moved but can still fire
     *  (mirrors AttackRingBuilder's own !hasFired && ammo>0 check). Drives the navigator arrows/
     *  count and cycling — broader than [fullyReadyUnits] so a moved-but-not-fired unit isn't
     *  skipped as "done" when cycling through what's left to do this turn. */
    private fun actionableUnits(): List<GameUnit> {
        val map = ui.game.scenario?.map
        val player = map?.currentPlayer
        if (map == null || player == null || player.type != PlayerType.HUMAN_LOCAL) return emptyList()
        return map.getUnits().filter {
            it.player?.id == player.id &&
                !it.destroyed &&
                (!it.hasMoved || (!it.hasFired && it.getAmmo() > 0)) &&
                hasAnyAction(it) &&
                !TurnSleep.isAsleep(map, it)
        }
    }

    /** Count of units that have done nothing at all yet this turn — drives [EndTurnFlow]'s
     *  immediate-vs-confirm decision and its confirm message. */
    fun fullyReadyCount(): Int = fullyReadyUnits().size

    /** Whether [unit] is currently asleep (excluded from the navigator/its count, but still
     *  counted by the End Turn nag — see [TurnSleep]). */
    fun isUnitAsleep(unit: GameUnit): Boolean {
        val map = ui.game.scenario?.map ?: return false
        return TurnSleep.isAsleep(map, unit)
    }

    /** Toggles [unit]'s asleep state and refreshes the top-bar turn controls immediately so the
     *  navigator count/cycle reflect it without waiting for the next move/attack. */
    fun toggleUnitSleep(unit: GameUnit) {
        val map = ui.game.scenario?.map ?: return
        TurnSleep.toggle(map, unit)
        // In a cooperative room "Wait" is also the way to take a unit without moving it.
        if (CoopRuntime.governs(unit)) OsadaMultiplayer.takeUnitForSelf(unit.id)
        updateTurnControls()
    }

    /** Refreshes the two turn-scoped top-bar widgets (navigator + End Turn). Cheap; called from
     *  [StatusBarController.updateStatusBar] and after every move/attack so the count stays live. */
    fun updateTurnControls() {
        val navCount = actionableUnits().size
        byId("osadaNavCount")?.textContent = navCount.toString()
        byId("osadaNav")?.let {
            if (navCount ==
                0
            ) {
                it.classList.add("osada-tb-nav--empty")
            } else {
                it.classList.remove("osada-tb-nav--empty")
            }
        }
        updateEndTurnButton(fullyReadyUnits().size)
    }

    /**
     * In a cooperative room, once this commander is done and teammates are not, the button reads
     * "waiting for ..." and its ✗ takes the "done" back -- the same inline shape as the confirm.
     */
    private fun showCoopWaiting(btn: org.w3c.dom.HTMLElement): Boolean {
        val waiting = CoopHud.waitingForOthers()
        val actor = CoopRuntime.state?.humanPlayerId
        if (waiting == null || actor == null) return false
        btn.className = "osada-et osada-et--confirm"
        btn.title = ""
        clearTag(btn)
        addTag(btn, "span").also {
            it.className = "osada-et__msg"
            it.textContent = waiting
        }
        addTag(btn, "span").also {
            it.className = "osada-et__no"
            it.innerHTML = "✗"
            it.title = I18n.t("common.cancel.label")
            it.onclick = { e: MouseEvent ->
                e.stopPropagation()
                OsadaMultiplayer.submitOrder(EndPlayerTurn(actor))
            }
        }
        btn.onclick = { e: MouseEvent -> e.stopPropagation() }
        return true
    }

    private fun updateEndTurnButton(n: Int) {
        val btn = byId("osadaEndTurn") ?: return
        // Don't clobber an active inline confirm; in a cooperative room, "waiting for ..." wins.
        if (btn.getAttribute("confirming") == "on" || showCoopWaiting(btn)) return
        btn.className = "osada-et " + if (n > 0) "osada-et--warn" else "osada-et--ready"
        clearTag(btn)
        val label = addTag(btn, "span")
        label.className = "osada-et__label"
        // The phone renders `data-mobile-label` in place of the text (`mobile.css`
        // `.osada-et__label::after`), so the count has to be carried by BOTH or the phone button
        // says only "Завершить" while the desktop one says "Завершить ход · 10". On a portrait
        // phone this label is the ONLY warning there is -- `EndTurnFlow.onEndTurnClick` ends the
        // turn on the first tap there, because the inline confirm's sentence does not fit.
        label.setAttribute(
            "data-mobile-label",
            if (n > 0) {
                I18n.t("hud.end_turn.short_label_with_ready", mapOf("count" to n))
            } else {
                I18n.t("hud.end_turn.short_label")
            },
        )
        label.textContent =
            if (n > 0) {
                I18n.t("hud.end_turn.with_ready", mapOf("count" to n))
            } else {
                I18n.t("hud.end_turn.label")
            }
        btn.title =
            if (n > 0) {
                I18n.plural("hud.end_turn.with_ready.help", n)
            } else {
                I18n.t("hud.end_turn.help")
            }
        btn.onclick = { e: MouseEvent ->
            e.stopPropagation()
            ui.onEndTurnClick()
        }
        if (n > 0) dropCountIfItOverflows(btn, label)
    }

    /**
     * Keeps the count on the phone button only while the button still fits on screen.
     *
     * The plate is `flex: 0 0 auto` behind a `margin-left: auto`, so the count first eats the free
     * space to its left and then simply hangs off the right edge of the screen -- the same failure
     * the portrait padding rules in `mobile.css` were written for, and the reason the label is set
     * optimistically above and cut back here instead of being guessed from a width threshold. The
     * button's own right edge is the honest test: it is the thing that goes off screen.
     *
     * Landscape passes this every time at any supported width, which is why there is no
     * orientation branch -- the measurement answers the question for both.
     */
    private fun dropCountIfItOverflows(
        btn: HTMLElement,
        label: HTMLElement,
    ) {
        if (!MobileLayoutController.mode.isPhone) return
        // A rect that cannot be read is not evidence of an overflow, so it keeps the count: 0.0
        // fails the test below, which is the same answer as "it fits".
        val right = (btn.asDynamic().getBoundingClientRect().right as? Number)?.toDouble() ?: 0.0
        if (right > window.innerWidth.toDouble() - topBarEdgeMarginPx) {
            label.setAttribute("data-mobile-label", I18n.t("hud.end_turn.short_label"))
        }
    }

    /** Cycles map selection through the ready units. Reuses [UI.uiUnitSelect] (the existing
     *  selection path) rather than a new one; wraps around the filtered ready list. */
    fun cycleReadyUnit(direction: Int) {
        val list = actionableUnits()
        if (list.isEmpty()) return
        val current =
            ui.game.scenario
                ?.map
                ?.currentUnit
        val idx = if (current != null) list.indexOfFirst { it.id == current.id } else -1
        val nextIdx =
            if (idx == -1) {
                (if (direction > 0) 0 else list.size - 1)
            } else {
                (((idx + direction) % list.size) + list.size) % list.size
            }
        val unit = list[nextIdx]
        ui.uiUnitSelect(unit)
        unit.getPos()?.let { ui.uiSetCellOnViewPort(it) }
    }
}
