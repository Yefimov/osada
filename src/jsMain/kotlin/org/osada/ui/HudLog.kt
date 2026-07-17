package org.osada.ui

import org.osada.GameHolder
import org.osada.model.Cell
import org.osada.ui.HudLog.add

/**
 * Sidebar LOG panel (Task 2): a small ring buffer of plain-text lines appended from EXISTING
 * message-producing code paths (combat results, captures, turn changes) — no new event system,
 * and nothing is logged here that the player couldn't already see via the existing bounce-text/
 * alert/message calls at the call sites, so this cannot leak fog-of-war information.
 *
 * Newest entry renders on top. Each entry is a list of (text, isOwnLoss) segments rather than a
 * single string, so a combat line can read e.g. "Panzer IV -2  vs  T-34 -4" with only the
 * player's own loss number in red — built as separate DOM spans (never innerHTML), so unit/
 * hex names from data files can't inject markup.
 */
internal object HudLog {

    class Segment(val text: String, val ownLoss: Boolean = false)

    private class Entry(val segments: List<Segment>, val row: Int, val col: Int, val hasPosition: Boolean)

    private const val MAX_ENTRIES = 10
    private const val MAX_SHOWN = 6

    private val entries = mutableListOf<Entry>()
    private var totalAdded = 0
    private var collapsedAtCount = 0

    fun add(vararg segments: Segment) = addEntry(segments.toList(), 0, 0, hasPosition = false)

    fun add(text: String) = add(Segment(text))

    /** Same as [add], but the row becomes clickable (spec: click a log line to jump there, like
     *  the Turn Report rows already do) — the raw "(col,row)" text itself is dropped from the
     *  visible line (it was clutter) and lives only in the row's tooltip. */
    fun addAt(row: Int, col: Int, vararg segments: Segment) = addEntry(segments.toList(), row, col, hasPosition = true)

    fun addAt(row: Int, col: Int, text: String) = addAt(row, col, Segment(text))

    private fun addEntry(segments: List<Segment>, row: Int, col: Int, hasPosition: Boolean) {
        entries.add(0, Entry(segments, row, col, hasPosition))
        if (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        totalAdded++
        render()
    }

    /** Clears the buffer for a new scenario/battle and paints the "No events yet" empty state
     *  (mirrors CombatLog.reset(), called from the same scenario-start code paths). */
    fun reset() {
        entries.clear()
        totalAdded = 0
        collapsedAtCount = 0
        render()
    }

    /** Called when the sidebar collapses: baseline the "new since collapse" counter. */
    fun onSidebarCollapsed() {
        collapsedAtCount = totalAdded
        refreshDot()
    }

    fun onSidebarExpanded() {
        refreshDot()
    }

    private fun render() {
        val container = byId("osadaLog") ?: return
        clearTag(container)
        if (entries.isEmpty()) {
            val empty = addTag(container, "div")
            empty.className = "osada-side-empty"
            empty.textContent = "No events yet"
        } else {
            entries.take(MAX_SHOWN).forEach { entry ->
                val row = addTag(container, "div")
                row.className = if (entry.hasPosition) "osada-log-row osada-log-row--clickable" else "osada-log-row"
                if (entry.hasPosition) {
                    row.title = "Jump to (${entry.col},${entry.row})"
                    row.onclick = { _: org.w3c.dom.events.MouseEvent ->
                        GameHolder.instance?.ui?.uiSetCellOnViewPort(Cell(entry.row, entry.col))
                    }
                }
                entry.segments.forEachIndexed { i, seg ->
                    val span = addTag(row, "span")
                    span.className = if (seg.ownLoss) "osada-log-seg osada-log-seg--loss" else "osada-log-seg"
                    span.textContent = if (i > 0) " ${seg.text}" else seg.text
                }
            }
        }
        refreshDot()
    }

    private fun refreshDot() {
        val dot = byId("osadaRailLogDot") ?: return
        val collapsed = byId("osada-sidebar")?.classList?.contains("osada-sidebar--collapsed") == true
        dot.style.display = if (collapsed && totalAdded > collapsedAtCount) "block" else "none"
    }
}
