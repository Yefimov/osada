package org.osada.ui

import kotlinx.browser.window
import org.osada.CombatLog
import org.osada.PlayerType
import org.osada.UnitClass
import org.osada.i18n.I18n
import org.osada.model.getCountriesBySide
import org.osada.model.getUnits
import org.osada.uiSettings
import org.w3c.dom.events.MouseEvent

/**
 * The End Turn click -> (optional inline confirm) -> actual turn-end flow. Split from the former
 * `MenuController` god-class to stay within the project's function-count/class-size limits. Ready-
 * unit counts come from [readyUnitNavigator]; the badge itself lives there too.
 */
internal class EndTurnFlow(
    private val ui: UI,
    private val readyUnitNavigator: ReadyUnitNavigator,
) {
    private val endTurnConfirmTimeoutMs = 3000
    private var endTurnConfirmTimer: Int = 0

    fun onEndTurnClick() {
        val map = ui.game.scenario?.map
        if (map == null || map.currentPlayer?.type != PlayerType.HUMAN_LOCAL) return
        if (ui.game.waitUIAnimation || ui.game.gameEnded) return
        val n = readyUnitNavigator.fullyReadyCount()
        if (n == 0 || !uiSettings.confirmEndTurn) {
            performEndTurn()
        } else {
            showEndTurnConfirm(n)
        }
    }

    /** Inline (no-modal) confirm: the button morphs into "N can still act. End turn? ✓ ✗" for ~3s. */
    private fun showEndTurnConfirm(n: Int) {
        val btn = byId("osadaEndTurn") ?: return
        btn.setAttribute("confirming", "on")
        btn.className = "osada-et osada-et--confirm"
        btn.title = ""
        clearTag(btn)
        val msg = addTag(btn, "span")
        msg.className = "osada-et__msg"
        msg.textContent = I18n.plural("hud.end_turn.confirm", n)
        val yes = addTag(btn, "span")
        yes.className = "osada-et__yes"
        yes.innerHTML = "✓"
        yes.title = I18n.t("hud.end_turn.confirm_yes.help")
        val no = addTag(btn, "span")
        no.className = "osada-et__no"
        no.innerHTML = "✗"
        no.title = I18n.t("common.cancel.label")
        yes.onclick = { e: MouseEvent ->
            e.stopPropagation()
            cancelEndTurnConfirm()
            performEndTurn()
        }
        no.onclick = { e: MouseEvent ->
            e.stopPropagation()
            cancelEndTurnConfirm()
            readyUnitNavigator.updateTurnControls()
        }
        btn.onclick = { e: MouseEvent -> e.stopPropagation() }
        endTurnConfirmTimer =
            window.setTimeout({
                cancelEndTurnConfirm()
                readyUnitNavigator.updateTurnControls()
            }, endTurnConfirmTimeoutMs)
    }

    private fun cancelEndTurnConfirm() {
        if (endTurnConfirmTimer != 0) {
            window.clearTimeout(endTurnConfirmTimer)
            endTurnConfirmTimer = 0
        }
        byId("osadaEndTurn")?.removeAttribute("confirming")
    }

    /** The actual turn-end: closes transient windows and hands off to the game engine, then
     *  refreshes for the next player. Extracted from the old double-tap handler. */
    private fun performEndTurn() {
        val map = ui.game.scenario?.map ?: return
        cancelEndTurnConfirm()
        if (isVisible("equipment")) {
            hideEquipmentWindow()
            makeHidden("container-unitlist")
            uiSettings.deployMode = false
            byId("buy")?.let { toggleButton(it, false) }
            ui.hideUnitInfoIfNotPinned()
        }
        if (isVisible("unit-info")) makeHidden("unit-info")
        UICombatLog.forceClose()
        makeHidden("uiToolTip")
        if (map.currentPlayer?.type == PlayerType.HUMAN_LOCAL) {
            ui.game.endTurn()
            if (ui.game.gameEnded && ui.game.gameStarted) {
                UIBuilder.message(
                    I18n.t("hud.defeat.title"),
                    I18n.t("hud.defeat.objectives_in_time"),
                )
            } else {
                ui.countriesOnSpotSide = map.getCountriesBySide(ui.game.spotSide)
                UIBuilder.setDefaultUserSelections()
                CombatLog.reset()
                ui.updateEquipmentWindow(UnitClass.TANK.value)
                uiUnitSelectNext()
                ui.updateStatusBar()
                // map.currentPlayer is already the NEXT player at this point (GameMap.endTurn
                // advances it); log whose turn is starting.
                map.currentPlayer?.let { next ->
                    HudLog.add(
                        I18n.t(
                            "hud.turn_started",
                            mapOf(
                                "turn" to map.turn,
                                "maxTurns" to map.maxTurns,
                                "country" to next.getCountryName(),
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun uiUnitSelectNext() {
        val map = ui.game.scenario?.map
        val player = map?.currentPlayer
        if (map == null || player == null) return
        val unit = map.getUnits().firstOrNull { isSelectableGroundOrNavalUnit(it, player.id) } ?: return
        ui.uiUnitSelect(unit)
        unit.getPos()?.let { ui.uiSetCellOnViewPort(it) }
    }

    private fun isSelectableGroundOrNavalUnit(
        unit: org.osada.model.GameUnit,
        playerId: Int,
    ): Boolean {
        val uclass = if (unit.player?.id == playerId) unit.unitData(true).uclass else null
        return uclass != null &&
            uclass != UnitClass.FIGHTER.value &&
            uclass != UnitClass.LEVEL_BOMBER.value &&
            uclass != UnitClass.TACTICAL_BOMBER.value
    }
}
