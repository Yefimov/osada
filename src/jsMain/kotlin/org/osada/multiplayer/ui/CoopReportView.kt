package org.osada.multiplayer.ui

import kotlinx.browser.document
import org.osada.GameHolder
import org.osada.i18n.I18n
import org.osada.multiplayer.coop.CoopCommander
import org.osada.multiplayer.coop.CoopCounters
import org.osada.multiplayer.coop.CoopLedger
import org.osada.multiplayer.coop.CoopState
import org.w3c.dom.HTMLElement

/**
 * The mission report (`docs/multiplayer/cooperative-commanders.md` §8.1): the army's result
 * FIRST, then one card per commander in stable seat order -- never a winner-to-loser ranking.
 *
 * Counters that overlap (support, assists) are labelled as such and are not added to the unique
 * destroyed/captured totals. The unassigned / allied-AI bucket is shown so the unique totals
 * reconcile with what the commanders were credited. Points are an experimental, versioned
 * profile and earn nothing: no prestige, no experience, no ability slot.
 */
object CoopReportView {
    private const val ID = "osadaCoopReport"

    private var campaignTab = false

    fun show(state: CoopState) {
        hide()
        val overlay = HtmlBits.div("osada-coop-report")
        overlay.id = ID
        overlay.setAttribute("role", "dialog")
        overlay.setAttribute("aria-modal", "true")
        overlay.setAttribute("aria-label", I18n.t("multiplayer.coop.report.title"))
        val sheet = HtmlBits.div("osada-coop-report-sheet")
        overlay.appendChild(sheet)
        sheet.appendChild(
            HtmlBits.text(I18n.t("multiplayer.coop.report.title")).apply {
                className =
                    "osada-coop-report-title"
            },
        )
        if (state.summaries.isNotEmpty()) sheet.appendChild(tabs(state))
        val view = if (campaignTab && state.summaries.isNotEmpty()) campaignView(state) else missionView(state)
        view.forEach(sheet::appendChild)
        sheet.appendChild(footnote(state))
        val close = HtmlBits.button(I18n.t("multiplayer.coop.report.close"))
        close.onclick = { hide() }
        sheet.appendChild(close)
        document.body?.appendChild(overlay)
        close.focus()
    }

    fun hide() {
        document.getElementById(ID)?.remove()
    }

    private fun missionView(state: CoopState): List<HTMLElement> =
        listOf(armySummary(state), commanderCards(state, state.ledger.perCommander))

    /** Every finished mission plus the one in progress, summed by commander. */
    internal fun campaignTotals(state: CoopState): Map<String, CoopCounters> {
        val totals = linkedMapOf<String, CoopCounters>()

        fun add(source: Map<String, CoopCounters>) =
            source.forEach { (id, c) ->
                val into = totals.getOrPut(id) { CoopCounters() }
                into.points += c.points
                into.objectives += c.objectives
                into.destroyed += c.destroyed
                into.captured += c.captured
                into.damage += c.damage
                into.reveals += c.reveals
                into.support += c.support
                into.endurance += c.endurance
                into.forcedRetreats += c.forcedRetreats
                into.attacksStopped += c.attacksStopped
                into.strengthLost += c.strengthLost
                into.strengthCaptured += c.strengthCaptured
                into.unitsLost += c.unitsLost
                into.terminalAssists += c.terminalAssists
            }
        state.summaries.forEach { add(it.perCommander) }
        add(state.ledger.perCommander)
        return totals
    }

    private fun campaignView(state: CoopState): List<HTMLElement> {
        val totals = campaignTotals(state)
        val missions = state.summaries.size + 1
        val box = HtmlBits.div("osada-coop-report-army")
        box.appendChild(
            HtmlBits.text(I18n.t("multiplayer.coop.report.campaign", mapOf("count" to missions))).apply {
                className =
                    "osada-coop-hud-name"
            },
        )
        return listOf(box, commanderCards(state, totals))
    }

    private fun tabs(state: CoopState): HTMLElement {
        val row = HtmlBits.div("osada-coop-hud-head")
        val kinds =
            listOf(
                false to "multiplayer.coop.report.tab.mission",
                true to "multiplayer.coop.report.tab.campaign",
            )
        kinds.forEach { (isCampaign, key) ->
            val tab = HtmlBits.button(I18n.t(key))
            tab.setAttribute("aria-pressed", (campaignTab == isCampaign).toString())
            tab.onclick = {
                campaignTab = isCampaign
                show(state)
            }
            row.appendChild(tab)
        }
        return row
    }

    private fun armySummary(state: CoopState): HTMLElement {
        val ledger = state.ledger
        val map = GameHolder.instance?.scenario?.map
        val box = HtmlBits.div("osada-coop-report-army")
        box.appendChild(
            HtmlBits.text(I18n.t("multiplayer.coop.report.army")).apply { className = "osada-coop-hud-name" },
        )
        val objectives = ledger.perCommander.values.sumOf { it.objectives }
        val lines =
            listOfNotNull(
                map?.let { I18n.t("multiplayer.coop.report.turn", mapOf("turn" to it.turn, "max" to it.maxTurns)) },
                I18n.t("multiplayer.coop.report.objectives", mapOf("count" to objectives)),
                I18n.t("multiplayer.coop.report.destroyed", mapOf("count" to ledger.teamDestroyed)),
                I18n.t("multiplayer.coop.report.captured", mapOf("count" to ledger.teamCaptured)),
                I18n.t("multiplayer.coop.report.own_losses", mapOf("count" to ledger.teamOwnLosses)),
            )
        lines.forEach { box.appendChild(HtmlBits.text(it).apply { className = "osada-coop-hud-sub" }) }
        return box
    }

    private fun commanderCards(
        state: CoopState,
        counters: Map<String, CoopCounters>,
    ): HTMLElement {
        val grid = HtmlBits.div("osada-coop-report-grid")
        val displayed = state.commanders + state.retiredCommanders.filter { it.id in counters }
        displayed.sortedBy { it.seat }.forEach { commander ->
            grid.appendChild(card(commander, counters[commander.id] ?: CoopCounters()))
        }
        counters[CoopLedger.UNASSIGNED]?.let { bucket ->
            val el = HtmlBits.div("osada-coop-report-card osada-coop-report-card--bucket")
            el.appendChild(
                HtmlBits.text(I18n.t("multiplayer.coop.report.unassigned")).apply {
                    className =
                        "osada-coop-hud-name"
                },
            )
            el.appendChild(
                HtmlBits.text(I18n.t("multiplayer.coop.report.unassigned.help")).apply {
                    className =
                        "osada-coop-hud-sub"
                },
            )
            statLines(bucket).forEach { el.appendChild(HtmlBits.text(it).apply { className = "osada-coop-hud-sub" }) }
            grid.appendChild(el)
        }
        return grid
    }

    private fun card(
        commander: CoopCommander,
        counters: CoopCounters,
    ): HTMLElement {
        val el = HtmlBits.div("osada-coop-report-card")
        val head = HtmlBits.div("osada-coop-report-head")
        head.appendChild(CoopHud.seatMark(commander, "osada-coop-seat"))
        head.appendChild(CoopHud.portrait(commander, "osada-coop-portrait"))
        head.appendChild(HtmlBits.text(commander.name).apply { className = "osada-coop-hud-name" })
        el.appendChild(head)
        statLines(counters).forEach { el.appendChild(HtmlBits.text(it).apply { className = "osada-coop-hud-sub" }) }
        return el
    }

    internal fun statLines(c: CoopCounters): List<String> =
        listOf(
            I18n.t("multiplayer.coop.report.stat.points", mapOf("count" to c.points)),
            I18n.t("multiplayer.coop.report.stat.objectives", mapOf("count" to c.objectives)),
            I18n.t("multiplayer.coop.report.stat.destroyed", mapOf("count" to c.destroyed)),
            I18n.t("multiplayer.coop.report.stat.captured", mapOf("count" to c.captured)),
            I18n.t("multiplayer.coop.report.stat.assists", mapOf("count" to c.terminalAssists)),
            I18n.t("multiplayer.coop.report.stat.support", mapOf("count" to c.support)),
            I18n.t("multiplayer.coop.report.stat.damage", mapOf("count" to c.damage)),
            I18n.t("multiplayer.coop.report.stat.reveals", mapOf("count" to c.reveals)),
            I18n.t("multiplayer.coop.report.stat.endurance", mapOf("count" to c.endurance)),
            I18n.t(
                "multiplayer.coop.report.stat.forced",
                mapOf(
                    "retreats" to c.forcedRetreats,
                    "stopped" to c.attacksStopped,
                ),
            ),
            I18n.t("multiplayer.coop.report.stat.losses", mapOf("units" to c.unitsLost, "strength" to c.strengthLost)),
            I18n.t("multiplayer.coop.report.stat.prisoners", mapOf("strength" to c.strengthCaptured)),
        )

    private fun footnote(state: CoopState): HTMLElement =
        HtmlBits
            .text(I18n.t("multiplayer.coop.report.footnote", mapOf("version" to state.ledger.scoringVersion)))
            .apply { className = "osada-coop-hud-sub" }
}

/** Tiny DOM helpers shared by the two cooperative views. */
internal object HtmlBits {
    fun div(className: String): HTMLElement = CoopHud.div(className)

    fun text(value: String): HTMLElement = CoopHud.text(value)

    fun button(value: String) = CoopHud.button(value)
}
