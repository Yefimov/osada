package org.osada.ui

import org.osada.PlayerType
import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.model.getUnits
import org.osada.rules.GameRules
import org.osada.rules.getUnitAttackCells
import org.w3c.dom.events.MouseEvent

/**
 * Top-bar ready-unit navigator (cycling + count) and the End Turn badge it drives. Split from
 * the former `MenuController` god-class to stay within the project's function-count/class-size
 * limits. The actual turn-ending flow lives in [EndTurnFlow].
 */
internal class ReadyUnitNavigator(
    private val ui: UI,
) {
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
            else -> GameRules.getUnitAttackCells(map.map, unit, map.rows, map.cols).isNotEmpty()
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

    private fun updateEndTurnButton(n: Int) {
        val btn = byId("osadaEndTurn") ?: return
        if (btn.getAttribute("confirming") == "on") return // don't clobber an active inline confirm
        btn.className = "osada-et " + if (n > 0) "osada-et--warn" else "osada-et--ready"
        clearTag(btn)
        val label = addTag(btn, "span")
        label.className = "osada-et__label"
        label.setAttribute("data-mobile-label", I18n.t("hud.end_turn.short_label"))
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
