package org.osada.ui

import kotlinx.browser.window
import org.osada.GameHolder
import org.osada.i18n.I18n
import org.osada.scenario.ObjectiveReport
import org.osada.scenario.objectiveReport
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * The victory-deadline panel, shown by hovering the top bar's `Turn n/max` field.
 *
 * It used to be two permanent sections of the objectives rail (`Victory deadlines` / `End-of-limit
 * thresholds`). The rail is a narrow column that already carries the objective checklist, and three
 * tier rows of `Brilliant victory — by turn 4` pushed the objectives themselves out of view for
 * information that never changes during a scenario and is only consulted when the player is
 * thinking about the clock. The clock is the top bar's turn field, so that is where they live now —
 * anchored, on hover, to the number they are about.
 *
 * Deliberately the SAME panel as the weather tooltip (`.osada-wtip`, GameplayLocalization
 * `showWeatherTooltip`): green for a grade still within reach, red for one already lost. Two
 * hover panels on one bar must not be two different designs.
 *
 * Deadlines are scenario-level data and may always be shown — `docs/design/
 * action-affordances-and-objectives.md` §9 — so this reveals nothing Observer Mode gates.
 */
internal object VictoryDeadlineTooltip {
    private const val TIP_ID = "osadaVictoryTip"
    private const val FALLBACK_TOP = 40.0
    private const val GAP_PX = 6
    private const val MAX_LEFT_INSET = 360.0
    private const val MIN_LEFT_INSET = 6.0

    /** Re-attached after every `#statusmsg` innerHTML rewrite, which destroys the old field. */
    fun attach(field: HTMLElement) {
        field.title = ""
        field.onmouseenter = { _: MouseEvent -> show(field) }
        field.onmouseleave = { _: MouseEvent -> hide() }
    }

    fun hide() {
        byId(TIP_ID)?.style?.display = "none"
    }

    private fun show(anchor: HTMLElement) {
        val game = GameHolder.instance ?: return
        val scenario = game.scenario ?: return
        val report = scenario.objectiveReport(game.spotSide, revealHidden = false)
        val tip =
            byId(TIP_ID) ?: addTag("mainbody", "div").also {
                it.id = TIP_ID
                it.className = "osada-wtip"
            }
        tip.innerHTML = tooltipHtml(report)
        tip.style.display = "block"
        val rect = anchor.asDynamic().getBoundingClientRect()
        val left =
            ((rect.left as? Number)?.toDouble() ?: 0.0)
                .coerceAtMost(window.innerWidth.toDouble() - MAX_LEFT_INSET)
                .coerceAtLeast(MIN_LEFT_INSET)
        tip.style.left = "${left.toInt()}px"
        tip.style.top = "${((rect.bottom as? Number)?.toDouble() ?: FALLBACK_TOP).toInt() + GAP_PX}px"
    }

    private fun tooltipHtml(report: ObjectiveReport): String {
        val title =
            I18n.t(
                "hud.turn.tooltip.title",
                mapOf("turn" to report.turn, "maxTurns" to report.maxTurns),
            )
        val remaining = (report.maxTurns - report.turn).coerceAtLeast(0)
        val story =
            if (remaining <= 0) {
                I18n.t("hud.turn.tooltip.last_turn")
            } else {
                I18n.plural("hud.turn.tooltip.remaining", remaining, mapOf("count" to remaining))
            }
        return "<div class=\"osada-wtip__title\">$title</div>" +
            "<div class=\"osada-wtip__story\">$story</div>" +
            gradeLines(report)
    }

    /**
     * Which strip is shown follows the evaluator that will actually decide this scenario —
     * `checkTimedOutcome` when the scenario authored hold counts, `checkVictory`'s turn tiers
     * otherwise. Saying "capture all by turn n" for a hold-count scenario would be a straight
     * falsehood, which is the same reason the rail used to branch here.
     */
    private fun gradeLines(report: ObjectiveReport): String =
        when {
            report.gradedByHoldCount -> holdThresholdLines(report)
            report.deadlines.isEmpty() ->
                "<div class=\"osada-wtip__note\">${I18n.t("hud.turn.tooltip.no_deadlines")}</div>"

            else -> deadlineLines(report)
        }

    private fun holdThresholdLines(report: ObjectiveReport): String =
        "<div class=\"osada-wtip__subtitle\">${I18n.t("hud.objective.tiers.hold.title")}</div>" +
            "<div class=\"osada-wtip__note\">${I18n.t("hud.objective.tiers.hold.help")}</div>" +
            report.holdThresholds.joinToString("") { threshold ->
                line(
                    "dim",
                    I18n.t("hud.objective.tier.${threshold.tier.name.lowercase()}"),
                    I18n.plural(
                        "hud.objective.tier.hold",
                        threshold.count,
                        mapOf("count" to threshold.count),
                    ),
                )
            }

    private fun deadlineLines(report: ObjectiveReport): String =
        "<div class=\"osada-wtip__subtitle\">${I18n.t("hud.objective.tiers.title")}</div>" +
            "<div class=\"osada-wtip__note\">${I18n.t("hud.objective.tiers.help")}</div>" +
            report.deadlines.joinToString("") { deadline ->
                val missed = report.missed(deadline)
                line(
                    if (missed) "bad" else "good",
                    I18n.t("hud.objective.tier.${deadline.tier.name.lowercase()}"),
                    I18n.t(
                        if (missed) "hud.objective.tier.missed" else "hud.objective.tier.by_turn",
                        mapOf("turn" to deadline.byTurn),
                    ),
                )
            }

    private fun line(
        kind: String,
        name: String,
        value: String,
    ): String =
        "<div class=\"osada-wtip__line osada-wtip__line--$kind osada-wtip__line--split\">" +
            "<span class=\"osada-wtip__line-name\">$name</span>" +
            "<span class=\"osada-wtip__line-value\">$value</span>" +
            "</div>"
}
