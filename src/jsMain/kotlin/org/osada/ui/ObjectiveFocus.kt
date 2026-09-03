package org.osada.ui

import org.osada.model.Cell
import org.osada.model.Hex

/**
 * The one hex the player last asked the objectives rail to show them.
 *
 * Clicking a row in the rail already scrolled the camera onto the objective, and that was the whole
 * of the feedback: on a dense city map the view jumps and nothing says WHICH of the flags now on
 * screen was the one asked for. This marks it, so the answer is visible rather than inferred.
 *
 * Deliberately NOT a `Hex` field like `isBarrageSel`/`isRailSel`, even though those are the
 * established pattern for a transient overlay. Exactly one hex can be focused, so a single [Cell]
 * says that directly, needs no clearing pass over the map, and cannot leak into a save through
 * `GameStateSerializer` the way a new hex property would have to be argued not to.
 */
internal object ObjectiveFocus {
    private var focused: Cell? = null

    /** The rail's row id for [focused], so the list and the map agree on which one is lit. */
    val current: Cell? get() = focused

    /** Focus [cell], or clear the mark when it is already the focused one (click again to dismiss). */
    fun toggle(cell: Cell): Cell? {
        focused = if (focused?.row == cell.row && focused?.col == cell.col) null else cell
        return focused
    }

    /** Any click on the map is the player moving on; the answer has been read. */
    fun clear(): Boolean {
        val had = focused != null
        focused = null
        return had
    }

    fun isFocused(hex: Hex): Boolean {
        val cell = focused ?: return false
        val pos = hex.getPos()
        return pos.row == cell.row && pos.col == cell.col
    }
}
