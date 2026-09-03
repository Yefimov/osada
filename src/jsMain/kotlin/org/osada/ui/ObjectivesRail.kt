package org.osada.ui

import org.osada.Game
import org.osada.GameHolder
import org.osada.i18n.I18n
import org.osada.model.Cell
import org.osada.scenario.ObjectiveKind
import org.osada.scenario.ObjectiveReport
import org.osada.scenario.ObjectiveRow
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
 *  2. authored evacuation, kill and Must-Survive conditions, with live progress;
 *  3. optional capture points -- COLLAPSED behind a count, see [renderOptional];
 *  4. hidden victory objectives, and only under Observer Mode.
 *
 * Two sections were removed on 2026-09-02, and both left the rail for a surface that suits them
 * better rather than being deleted:
 *
 *  - the **victory-tier strip** (turn deadlines / authored hold thresholds) is now the hover panel
 *    on the top bar's `Turn n/max` field ([VictoryDeadlineTooltip]). It is fixed scenario data
 *    that never changes during play, it is only ever consulted about the clock, and three
 *    permanent rows of it were pushing the live objectives out of a narrow column.
 *  - the **campaign end-state conditions** (`scenario.actions`, checked once at mission end) were
 *    dropped from the UI entirely. In the rail they read as a second, contradictory list of
 *    objectives -- full sentences wrapping over three lines each, directly under a checklist of
 *    one-line hex names. They were briefly moved to the briefing's orders sheet instead, and that
 *    was rejected too ("не нужен"). [ScenarioObjectiveText] still renders one of these rules and
 *    is still tested; it currently has no caller, and is kept because the rules themselves are a
 *    live authored campaign feature that the engine still evaluates at scenario end.
 *
 * The top-left `Turn n/max` field is otherwise untouched; this panel never repeats the count.
 */
internal object ObjectivesRail {
    /** At most this many optional capture points open without asking; see [renderOptional]. */
    private const val OPTIONAL_AUTO_EXPAND = 4

    /** null = follow [OPTIONAL_AUTO_EXPAND]; set once the player has folded or unfolded by hand. */
    private var optionalExpanded: Boolean? = null

    fun render(
        container: HTMLElement,
        game: Game,
    ) {
        val scenario = game.scenario ?: return
        clearTag(container)
        // The sidebar belongs to the OBSERVING campaign player: during an AI turn `currentPlayer`
        // is the opponent, which inverts every Held/Enemy label.
        val report = scenario.objectiveReport(game.spotSide, uiSettings.showHiddenVictoryHexes)

        if (report.rows.isEmpty() && report.extended.isEmpty()) {
            val empty = addTag(container, "div")
            empty.className = "osada-side-empty"
            empty.textContent = I18n.t("hud.objective.none_visible")
        } else {
            renderVictory(container, report, game)
            ExtendedObjectivesRail.render(container, report)
            renderOptional(container, report, game)
            renderHidden(container, report, game)
        }
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

    /**
     * Optional capture points, folded behind their own count.
     *
     * These are NOT the hidden victory hexes Observer Mode reveals -- an optional capture point is
     * `flag != -1 && victorySide == -1`, i.e. an ordinary named town flag the player can already
     * see on the map, worth prestige and score and required by nothing. Measured across the 502
     * shipped scenarios: **7283 of them in 491 scenarios**, and `bn9s18` alone authors 103. Listing
     * every one of them turned a 3-objective checklist into a hundred-row scroll in which the
     * objectives that decide the scenario were off-screen -- reported as simply not understanding
     * what the section was.
     *
     * So the heading carries the held/total count and the rows fold away under it. Up to
     * [OPTIONAL_AUTO_EXPAND] of them still open by default: for a scenario with two, a click to see
     * two names is friction with nothing behind it. A click on the heading overrides that either
     * way and the choice sticks for the session, because this rail re-renders on every status
     * update and a fold that reopened itself would be worse than no fold at all.
     */
    private fun renderOptional(
        container: HTMLElement,
        report: ObjectiveReport,
        game: Game,
    ) {
        val optional = report.optional
        if (optional.isEmpty()) return
        val held = optional.count { it.held }
        val expanded = optionalExpanded ?: (optional.size <= OPTIONAL_AUTO_EXPAND)
        val heading =
            section(
                container,
                I18n.t(
                    "hud.objective.optional.title_count",
                    mapOf("title" to I18n.t("hud.objective.optional.title"), "held" to held, "total" to optional.size),
                ),
                I18n.t("hud.objective.optional.help"),
            )
        heading.classList.add("osada-obj-section--fold")
        heading.classList.toggle("osada-obj-section--open", expanded)
        heading.onclick = { _: MouseEvent ->
            optionalExpanded = !expanded
            GameHolder.instance?.let { render(container, it) }
        }
        if (!expanded) return
        optional.forEach { row -> objectiveRow(container, row, game) }
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

    private fun objectiveRow(
        container: HTMLElement,
        entry: ObjectiveRow,
        game: Game,
    ) {
        val label = entry.name.ifEmpty { "(${entry.col},${entry.row})" }
        val row = addTag(container, "div")
        row.className = "osada-obj" + if (entry.held) " osada-obj--held" else ""
        if (entry.kind == ObjectiveKind.HIDDEN_VICTORY) row.classList.add("osada-obj--hidden")
        val focused = ObjectiveFocus.current
        if (focused?.row == entry.row && focused.col == entry.col) row.classList.add("osada-obj--focused")
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
        // Centre the camera AND mark the hex: on a dense map the jump alone does not say which of
        // the flags now on screen was the one asked for. Clicking the same row again clears it.
        row.onclick = { _: MouseEvent ->
            val cell = Cell(entry.row, entry.col)
            ObjectiveFocus.toggle(cell)
            game.ui?.uiSetCellOnViewPort(cell)
            game.ui?.render?.render()
            render(container, game)
        }
    }

    private fun section(
        container: HTMLElement,
        title: String,
        help: String,
    ): HTMLElement {
        val heading = addTag(container, "div")
        heading.className = "osada-obj-section"
        heading.textContent = title
        heading.title = help
        return heading
    }
}
