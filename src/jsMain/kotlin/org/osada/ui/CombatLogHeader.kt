package org.osada.ui

import org.osada.CombatLog
import org.osada.groundIconImg
import org.osada.i18n.GameText
import org.osada.i18n.I18n
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.weatherIconImg
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * [UICombatLog]'s "Turn Report" window header (title/briefing/dossier/close) and summary stat
 * tiles. Split out purely to keep [UICombatLog] within the project's function-count/class-size
 * limits -- not expected to be called from elsewhere.
 */
internal object CombatLogHeader {
    fun buildHeader(container: HTMLElement): HTMLElement {
        val game = gameRef()
        val map = game?.scenario?.map as? GameMap
        val currentPlayer = map?.currentPlayer
        val header = addTag(container, "div")
        header.className = "osada-tr-header"

        val titleBlock = addTag(header, "div")
        titleBlock.className = "osada-tr-titleblock"
        val titleRow = addTag(titleBlock, "div")
        titleRow.className = "osada-tr-title-row"
        val title = addTag(titleRow, "div")
        title.className = "osada-tr-title"
        title.textContent =
            currentPlayer?.let { player ->
                I18n.t(
                    "turn_report.title_with_turn",
                    mapOf("country" to player.getCountryName(), "turn" to map.turn, "maxTurns" to map.maxTurns),
                )
            } ?: I18n.t("turn_report.title")

        val sub = addTag(titleBlock, "div")
        sub.className = "osada-tr-sub"
        val atmos = game?.scenario?.atmosferic as? Int ?: 0
        val ground = game?.scenario?.ground as? Int ?: 0
        sub.innerHTML = "<i>${game?.scenario?.name}</i>, ${game?.scenario?.date?.toDateString()} · " +
            "${weatherIconImg(atmos, "osada-tr-weather-img")}${GameText.weather(atmos)} · " +
            "${groundIconImg(ground, "osada-tr-weather-img")}${GameText.ground(ground)}"

        buildHeaderActions(header, game)
        return header
    }

    // Briefing lives with Dossier in the actions cluster (both "view info" actions, grouped
    // together, ahead of Close) rather than beside the title — user feedback, reversing an
    // earlier placement that itself was working around a legacy-CSS overlap bug now fixed at
    // its actual root cause (.osada-tr-header .combatLogInfoButton no longer position:absolute).
    private fun buildHeaderActions(
        header: HTMLElement,
        game: dynamic,
    ) {
        val actions = addTag(header, "div")
        actions.className = "osada-tr-actions"
        val descButton = addTag(actions, "span")
        descButton.className = "smallButtonMenu combatLogInfoButton osada-tr-briefing-btn"
        descButton.title = I18n.t("turn_report.briefing.help")
        descButton.style.fontSize = "16px"
        descButton.textContent = "g"
        descButton.onclick = { _: MouseEvent ->
            // Campaign battles reopen the full dialogue/orders screen. Standalone scenarios and
            // older states without a cached briefing keep the existing narrative message fallback.
            val reopened = game?.ui?.reopenScenarioBriefing() as? Boolean ?: false
            if (!reopened) {
                UIBuilder.message(
                    game?.scenario?.name ?: "",
                    game?.scenario?.getDescription() ?: "",
                    narrative = true,
                )
            }
        }
        if (game?.campaign != null) {
            val dossierButton = addTag(actions, "span")
            dossierButton.className = "smallButtonMenu combatLogInfoButton"
            dossierButton.title = I18n.t("turn_report.dossier.help")
            dossierButton.textContent = "@"
            dossierButton.onclick = { _: MouseEvent ->
                UICombatLog.forceClose()
                UIBuilder.showDossier(true, null)
            }
        }
        val closeButton = addTag(actions, "span")
        closeButton.className = "osada-ico osada-ico--close combatLogCloseBut"
        closeButton.title = I18n.t("common.close_esc.help")
        closeButton.onclick = { _: MouseEvent -> UICombatLog.toggleCombatLog() }
    }

    fun buildSummaryTiles(
        container: HTMLElement,
        map: GameMap,
    ): HTMLElement {
        val game = gameRef()
        val currentPlayer = map.currentPlayer
        val row = addTag(container, "div")
        row.className = "osada-tr-summary"
        if (currentPlayer == null) return row

        fun tile(
            label: String,
            value: String,
            sub: String? = null,
            explanation: String,
        ) {
            val t = addTag(row, "div")
            t.className = "osada-tr-tile"
            t.title = explanation
            val v = addTag(t, "div")
            v.className = "osada-tr-tile__value"
            v.innerHTML = value
            val l = addTag(t, "div")
            l.className = "osada-tr-tile__label"
            l.textContent = label
            if (sub != null) {
                val s = addTag(t, "div")
                s.className = "osada-tr-tile__sub"
                s.innerHTML = sub
            }
        }

        val objectivesLeft = map.sidesVictoryHexes.getOrElse(currentPlayer.side) { mutableListOf<Cell>() }.size
        tile(
            I18n.t("turn_report.objectives_left.label"),
            objectivesLeft.toString(),
            explanation = I18n.t("turn_report.objectives_left.help"),
        )

        // Only the NEAREST upcoming victory tier is shown (spec: compact, not the old three-tier
        // sentence) — the further-out tiers stop mattering once a closer one is reachable.
        nearestVictoryTier(map)?.let { (turns, outcome) ->
            tile(
                I18n.t("turn_report.turns_to.label", mapOf("outcome" to outcome)),
                turns.toString(),
                explanation = I18n.t("turn_report.turns_to.help"),
            )
        }

        tile(
            I18n.t("turn_report.score.label"),
            currentPlayer.score.toString(),
            explanation = I18n.t("turn_report.score.help"),
        )
        val nextTurnPrestige = currentPlayer.prestigePerTurn.getOrNull(map.turn + 1) ?: 0
        tile(
            I18n.t("turn_report.prestige.label"),
            "${currentPlayer.prestige}&nbsp;${UIBuilder.currencyIcon}",
            if (nextTurnPrestige != 0) {
                I18n.t("turn_report.prestige.next_turn", mapOf("amount" to nextTurnPrestige))
            } else {
                null
            },
            I18n.t("turn_report.prestige.help"),
        )

        // Casualties: summed from this turn's combat log for the viewing side — the same data
        // the Combat group below itemizes per-unit, just totaled for an at-a-glance read.
        val (inflicted, taken) = combatTotals(game)
        tile(
            I18n.t("turn_report.inflicted.label"),
            inflicted.toString(),
            explanation = I18n.t("turn_report.inflicted.help"),
        )
        tile(
            I18n.t("turn_report.losses.label"),
            taken.toString(),
            explanation = I18n.t("turn_report.losses.help"),
        )

        return row
    }

    private fun nearestVictoryTier(
        map: GameMap,
        currentTurn: Int = map.turn,
    ): Pair<Int, String>? {
        val tiers =
            listOfNotNull(
                ((map.victoryTurns.getOrNull(0) ?: 0) - currentTurn + 1)
                    .takeIf { it > 0 }
                    ?.let { it to I18n.t("turn_report.outcome.brilliant") },
                ((map.victoryTurns.getOrNull(1) ?: 0) - currentTurn + 1)
                    .takeIf { it > 0 }
                    ?.let { it to I18n.t("turn_report.outcome.victory") },
                ((map.victoryTurns.getOrNull(2) ?: 0) - currentTurn + 1)
                    .takeIf { it > 0 }
                    ?.let { it to I18n.t("turn_report.outcome.tactical") },
            )
        return tiers.minByOrNull { it.first }
    }

    private fun combatTotals(game: dynamic): Pair<Int, Int> {
        var inflicted = 0
        var taken = 0
        if (game != null) {
            val combatKeys = js("Object.keys")(CombatLog.log.combat) as Array<String>
            for (key in combatKeys) {
                val entry = CombatLog.log.combat[key]
                if (entry == null || entry.side != game.spotSide) continue
                inflicted += entry.kills as? Int ?: 0
                taken += entry.losses as? Int ?: 0
            }
        }
        return inflicted to taken
    }
}
