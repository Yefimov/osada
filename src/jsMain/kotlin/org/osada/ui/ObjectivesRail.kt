package org.osada.ui

import org.osada.Game
import org.osada.buildScenarioEndState
import org.osada.campaign.ScenarioActionEvaluator
import org.osada.campaign.ScenarioActionParser
import org.osada.campaign.ScenarioActionRule
import org.osada.i18n.I18n
import org.osada.model.Cell
import org.osada.scenario.ObjectiveKind
import org.osada.scenario.ObjectiveReport
import org.osada.scenario.ObjectiveRow
import org.osada.scenario.VictoryDeadline
import org.osada.scenario.VictoryTier
import org.osada.scenario.getCurrentScenarioActions
import org.osada.scenario.objectiveReport
import org.osada.uiSettings
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * The sidebar OBJECTIVES panel
 * (`docs/design/action-affordances-and-objectives.md` §§8, 9).
 *
 * Four sections, each of which exists only when the scenario actually has that thing:
 *  1. required victory objectives, with a `held / visible` summary;
 *  2. optional capture points, labelled as optional and never counted toward the summary;
 *  3. the victory-tier strip -- turn deadlines, or the authored hold thresholds when the scenario
 *     is graded by how many objectives survive the turn limit instead;
 *  4. for a campaign scenario, its authored `scenario.actions` as a SECOND objective phase, marked
 *     "currently satisfied - checked at mission end" rather than complete.
 *
 * The top-left `Turn n/max` field is untouched; this panel never repeats it.
 */
internal object ObjectivesRail {
    fun render(
        container: HTMLElement,
        game: Game,
    ) {
        val scenario = game.scenario ?: return
        clearTag(container)
        // The sidebar belongs to the OBSERVING campaign player: during an AI turn `currentPlayer`
        // is the opponent, which inverts every Held/Enemy label.
        val report = scenario.objectiveReport(game.spotSide, uiSettings.showHiddenVictoryHexes)

        if (report.rows.isEmpty()) {
            val empty = addTag(container, "div")
            empty.className = "osada-side-empty"
            empty.textContent = I18n.t("hud.objective.none_visible")
        } else {
            renderVictory(container, report, game)
            renderOptional(container, report, game)
            renderHidden(container, report, game)
        }
        renderTiers(container, report)
        renderEndStateObjectives(container, game)
        byId("osadaRailObjCounter")?.textContent = "${report.victoryHeld}/${report.victoryTotal}"
    }

    private fun renderVictory(
        container: HTMLElement,
        report: ObjectiveReport,
        game: Game,
    ) {
        if (report.victory.isEmpty()) return
        section(
            container,
            I18n.t(
                "hud.objective.summary",
                mapOf("held" to report.victoryHeld, "total" to report.victoryTotal),
            ),
            I18n.t("hud.objective.summary.help"),
        )
        report.victory.forEach { row -> objectiveRow(container, row, game) }
    }

    private fun renderOptional(
        container: HTMLElement,
        report: ObjectiveReport,
        game: Game,
    ) {
        if (report.optional.isEmpty()) return
        section(container, I18n.t("hud.objective.optional.title"), I18n.t("hud.objective.optional.help"))
        report.optional.forEach { row -> objectiveRow(container, row, game) }
    }

    private fun renderHidden(
        container: HTMLElement,
        report: ObjectiveReport,
        game: Game,
    ) {
        if (report.hidden.isEmpty()) return
        section(container, I18n.t("hud.objective.hidden.title"), I18n.t("hud.objective.hidden.help"))
        report.hidden.forEach { row -> objectiveRow(container, row, game) }
    }

    /**
     * Deadlines are scenario-level data and may always be shown. Which strip appears follows the
     * evaluator that will actually decide this scenario: `checkTimedOutcome` when the scenario
     * authored hold counts, `checkVictory`'s turn tiers otherwise. Saying `capture all by turn n`
     * for a hold-count scenario would be a straight falsehood.
     */
    private fun renderTiers(
        container: HTMLElement,
        report: ObjectiveReport,
    ) {
        if (report.gradedByHoldCount) {
            section(container, I18n.t("hud.objective.tiers.hold.title"), I18n.t("hud.objective.tiers.hold.help"))
            report.holdThresholds.forEach { threshold ->
                val row = tierRow(container, threshold.tier)
                row.lastElementChild?.textContent =
                    I18n.plural("hud.objective.tier.hold", threshold.count, mapOf("count" to threshold.count))
            }
            return
        }
        if (report.deadlines.isEmpty()) return
        section(container, I18n.t("hud.objective.tiers.title"), I18n.t("hud.objective.tiers.help"))
        report.deadlines.forEach { deadline -> deadlineRow(container, report, deadline) }
    }

    private fun deadlineRow(
        container: HTMLElement,
        report: ObjectiveReport,
        deadline: VictoryDeadline,
    ) {
        val row = tierRow(container, deadline.tier)
        if (report.missed(deadline)) row.classList.add("osada-obj-tier--missed")
        row.lastElementChild?.textContent =
            I18n.t(
                if (report.missed(deadline)) "hud.objective.tier.missed" else "hud.objective.tier.by_turn",
                mapOf("turn" to deadline.byTurn),
            )
    }

    private fun tierRow(
        container: HTMLElement,
        tier: VictoryTier,
    ): HTMLElement {
        val row = addTag(container, "div")
        row.className = "osada-obj-tier"
        val name = addTag(row, "span")
        name.className = "osada-obj-tier__name"
        name.textContent = I18n.t("hud.objective.tier.${tier.name.lowercase()}")
        val value = addTag(row, "span")
        value.className = "osada-obj-tier__value"
        return row
    }

    /**
     * The second objective phase. These are the campaign's authored `scenario.actions`, which the
     * engine evaluates exactly ONCE, at scenario end. The preview reuses the same predicates through
     * `ScenarioActionEvaluator.evaluate`, which records nothing -- opening this panel must never
     * write campaign state.
     */
    private fun renderEndStateObjectives(
        container: HTMLElement,
        game: Game,
    ) {
        val campaign = game.campaign ?: return
        val rules = ScenarioActionParser.parseList(campaign.getCurrentScenarioActions())
        val state = game.buildScenarioEndState()
        if (rules.isEmpty() || state == null) return
        val satisfied = ScenarioActionEvaluator.evaluate(rules, state)
        section(container, I18n.t("hud.objective.end_state.title"), I18n.t("hud.objective.end_state.help"))
        rules.forEach { rule -> endStateRow(container, rule, rule.id in satisfied, state.turn) }
    }

    private fun endStateRow(
        container: HTMLElement,
        rule: ScenarioActionRule,
        satisfied: Boolean,
        turn: Int,
    ) {
        val missedDeadline = rule is ScenarioActionRule.FinishedByTurn && turn > rule.turn
        val row = addTag(container, "div")
        row.className =
            "osada-obj-end" +
            when {
                missedDeadline -> " osada-obj-end--missed"
                satisfied -> " osada-obj-end--ok"
                else -> ""
            }
        val name = addTag(row, "span")
        name.className = "osada-obj-end__name"
        name.textContent = rule.label ?: ScenarioObjectiveText.describe(rule)
        val state = addTag(row, "span")
        state.className = "osada-obj-end__state"
        state.textContent =
            I18n.t(
                when {
                    missedDeadline -> "hud.objective.end_state.missed"
                    satisfied -> "hud.objective.end_state.satisfied"
                    else -> "hud.objective.end_state.pending"
                },
            )
        row.title = state.textContent + " — " + name.textContent
    }

    private fun objectiveRow(
        container: HTMLElement,
        entry: ObjectiveRow,
        game: Game,
    ) {
        val label = entry.name.ifEmpty { "(${entry.col},${entry.row})" }
        val row = addTag(container, "div")
        row.className = "osada-obj" + if (entry.held) " osada-obj--held" else ""
        if (entry.kind == ObjectiveKind.HIDDEN_VICTORY) row.classList.add("osada-obj--hidden")
        row.title =
            I18n.t(
                if (entry.held) "hud.objective.held.help" else "hud.objective.enemy.help",
                mapOf("name" to label),
            )
        val name = addTag(row, "span")
        name.className = "osada-obj__name"
        name.textContent = label
        name.title = label
        val state = addTag(row, "span")
        state.className = "osada-obj__state"
        val mark = addTag(state, "span")
        mark.className = "osada-obj__mark"
        mark.textContent = if (entry.held) "✓" else "⚑" // check / flag
        val stateLabel = addTag(state, "span")
        stateLabel.textContent =
            I18n.t(if (entry.held) "hud.objective.held.label" else "hud.objective.enemy.label")
        row.onclick = { _: MouseEvent -> game.ui?.uiSetCellOnViewPort(Cell(entry.row, entry.col)) }
    }

    private fun section(
        container: HTMLElement,
        title: String,
        help: String,
    ) {
        val heading = addTag(container, "div")
        heading.className = "osada-obj-section"
        heading.textContent = title
        heading.title = help
    }
}
