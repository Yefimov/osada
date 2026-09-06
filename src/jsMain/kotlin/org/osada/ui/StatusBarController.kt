package org.osada.ui

import org.osada.PlayerType
import org.osada.groundConditionNames
import org.osada.groundIconImg
import org.osada.i18n.I18n
import org.osada.model.GameMap
import org.osada.model.Player
import org.osada.model.effectivePrestigeIncome
import org.osada.model.isInitialDeploymentWindow
import org.osada.monthNamesShort
import org.osada.uiSettings
import org.osada.weatherIconImg
import org.w3c.dom.events.MouseEvent

/**
 * Top-bar status display: scenario/turn/date, weather + its hover panel, prestige, reserves,
 * observer badge, and the sidebar objectives list. Split from the former `MenuController`
 * god-class to stay within the project's function-count/class-size limits.
 */
internal class StatusBarController(
    private val ui: UI,
) {
    // Short weather/ground words for the top bar (spec: "Snow · Frozen").
    private val weatherWords = listOf("Clear", "Overcast", "Rain", "Snow") // by atmosferic 0..3
    private val tooltipFallbackTop = 40.0
    private val tooltipGapPx = 6

    fun updateStatusBar() {
        val scenario = ui.game.scenario ?: return
        val map = scenario.map
        val currentPlayer = map.currentPlayer ?: return

        // --- scenario · turn · date (NOT the campaign name) ---
        val phaseChip = deploymentPhaseChip(map, currentPlayer)
        val dateText =
            "${scenario.date.getDate()} ${monthNamesShort.getOrNull(scenario.date.getMonth()) ?: ""} " +
                "${scenario.date.getFullYear()}"
        byId("statusmsg")?.innerHTML =
            "<span class=\"osada-tb-op\" title=\"${scenario.name}\">${scenario.name}</span>" +
            "<span class=\"osada-tb-field\"><b>Turn</b>${map.turn}/${map.maxTurns}</span>" +
            "<span class=\"osada-tb-field osada-tb-date\">$dateText</span>" +
            phaseChip

        // --- weather as words + weather/ground image icons (asset-sheet extracts) ---
        val atmos = scenario.atmosferic
        val ground = scenario.ground
        byId("weathermsg")?.let { w ->
            w.innerHTML =
                weatherIconImg(atmos, "osada-tb-weather-img") +
                groundIconImg(ground, "osada-tb-weather-img") +
                "<span class=\"osada-tb-weather-txt\">${
                    weatherWords.getOrNull(
                        atmos,
                    ) ?: ""
                } · ${groundConditionNames.getOrNull(ground) ?: ""}</span>"
            // Rich hover panel replaces the bare native title: it spells out what the current
            // weather/ground actually DO to the rules — bonuses green, penalties red.
            w.asDynamic().title = ""
            // The panel itself belongs to [GameplayLocalization], which owns the localized
            // build of it. This object used to carry a second, English-only copy and bind it
            // to the same element, so whichever ran last decided what the player read.
            w.onmouseenter = { _: MouseEvent -> GameplayLocalization.showWeatherTooltip(w) }
            w.onmouseleave = { _: MouseEvent -> AnchoredTip.hide(GameplayLocalization.WEATHER_TIP_ID) }
        }

        updatePrestigeDisplay(currentPlayer, map)
        updateReservesBadge(currentPlayer)
        ui.updateTurnControls()
        updateObjectivesPanel()
        updateObserverBadge()
        // Covers turn-change and (via the Task 1 move/attack-finish hooks that already call
        // updateStatusBar for the human player) unit-move/combat-end refresh triggers too.
        MinimapBuilder.refresh()
        AttackRingBuilder.refresh()
        GameplayLocalization.refreshStatusBar()
    }

    private fun updatePrestigeDisplay(
        player: Player,
        map: GameMap,
    ) {
        val el = byId("osadaPrestige") ?: return
        val delta = player.effectivePrestigeIncome(player.prestigePerTurn.getOrNull(map.turn) ?: 0)
        val deltaHtml = if (delta > 0) "<span class=\"osada-tb-delta\">+$delta</span>" else ""
        el.innerHTML = "<span class=\"osada-tb-prestige-val\">${player.prestige}</span>$deltaHtml"
        el.title =
            if (delta > 0) {
                I18n.t(
                    "hud.prestige.value_with_income",
                    mapOf("prestige" to player.prestige, "delta" to delta),
                )
            } else {
                I18n.t("hud.prestige.value", mapOf("prestige" to player.prestige))
            }
    }

    /** Task 5: persistent "OBSERVER" badge in the top bar while any Observer Mode toggle is on —
     *  they all "affect game balance", and the player should never forget one is active.
     *
     *  Stalin Regime counts. It is the single largest balance override in the game (every combat,
     *  movement and prestige number for the local player ×10) and it now lives in this section for
     *  exactly that reason, so leaving it out of the badge would have been the one balance switch
     *  with no persistent reminder. */
    private fun updateObserverBadge() {
        // The checkbox is the whole answer: the mode applies the moment it is ticked, so the badge
        // and the setting can no longer disagree the way they did while a battle froze the rule.
        val on = uiSettings.noFOW || uiSettings.showHiddenVictoryHexes || uiSettings.stalinRegime
        byId("osadaObserverBadge")?.style?.display = if (on) "flex" else "none"
    }

    private fun updateReservesBadge(player: Player) {
        val badge = byId("osadaReservesBadge") ?: return
        val count = player.getCoreUnitList().count { !it.isDeployed }
        if (count > 0) {
            badge.textContent = count.toString()
            badge.style.display = "inline-flex"
        } else {
            badge.style.display = "none"
        }
    }

    /** Fills the sidebar OBJECTIVES panel. The panel's own model and markup live in
     *  [ObjectivesRail]; this stays the single refresh hook, called from [updateStatusBar] so it
     *  covers both turn-change and post-capture refreshes (capturing a hex as the human player
     *  already triggers updateStatusBar via the move-finish hook). */
    private fun updateObjectivesPanel() {
        val container = byId("osadaObjectives") ?: return
        ObjectivesRail.render(container, ui.game)
    }

    fun showStatusExtension() {
        makeHidden("statusbar-extension")
        val scenario = ui.game.scenario ?: return
        val map = scenario.map
        val player = map.currentPlayer ?: return
        updateStatusBar()
        if (player.type == PlayerType.HUMAN_LOCAL || player.type == PlayerType.AI_SCRIPTED) {
            UICombatLog.toggleCombatLog(true, false)
            ui.removeAllSmallToolTips(true)
            // NOT addSmallToolTips(true): all=true skips the hex-name/objective block entirely
            // (see its own `if (!all && ...)` gate) and only rebuilds unit ammo/fuel warnings.
            // This ran on every turn start, so hex-name and "show optional objectives tooltips"
            // labels were wiped by the removeAllSmallToolTips(true) just above and never rebuilt —
            // they only reappeared after an unrelated full rebuild (Grid toggle, map zoom).
            ui.addSmallToolTips()
        } else {
            UIBuilder.showAIStatus(true)
        }
    }
}

/** Kept in one place so a locale refresh and an ordinary status refresh render the same phase. */
internal fun deploymentPhaseChip(
    map: GameMap,
    player: Player,
): String =
    if (
        player.hasUndeployedUnits() &&
        player.type == PlayerType.HUMAN_LOCAL &&
        map.isInitialDeploymentWindow(player)
    ) {
        "<span class=\"osada-tb-field osada-tb-field--phase\" " +
            "title=\"${I18n.t("hud.phase.deploy.help")}\"><b>${I18n.t("hud.phase.label")}</b>" +
            I18n.t("hud.phase.deploy.label") + "</span>"
    } else {
        ""
    }
