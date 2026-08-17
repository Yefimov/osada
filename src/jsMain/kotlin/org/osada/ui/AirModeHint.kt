package org.osada.ui

import kotlinx.browser.window
import org.osada.i18n.I18n

/**
 * The anchored `switch to Air Mode (A)` hint
 * (`docs/design/action-affordances-and-objectives.md` §7).
 *
 * It appears when the player attacks a stacked hex whose ACTIVE layer has no target while the other
 * layer plainly shows an enemy. The click inspects that enemy and raises this hint; it never
 * attacks and never toggles the mode, because a click that silently changed the global attack layer
 * is exactly the ambiguity Air Mode exists to remove.
 *
 * Two limits keep it from becoming nagging, both required by the design:
 * - it is rate-limited, so repeatedly clicking the same hex does not flash it;
 * - it stops entirely once the player has used Air Mode. At that point they have demonstrably found
 *   the control and the hint has nothing left to teach.
 *
 * Positioned like the existing map tooltips: an absolutely positioned child of `#game`, in `#game`'s
 * own scroll-content space, so it tracks the hex through scrolling and zoom rebuilds.
 */
internal object AirModeHint {
    const val HINT_ID = "osadaAirModeHint"

    private const val REPEAT_COOLDOWN_MS = 6000.0
    private const val ANCHOR_Y_OFFSET = 34

    private var lastShownAt = -REPEAT_COOLDOWN_MS
    private var airModeUsed = false

    /** Called whenever the player toggles Air Mode, from the button or the `A` command. */
    fun markAirModeUsed() {
        airModeUsed = true
        hide()
    }

    /**
     * Shows the hint over hex [row]/[col]. [otherLayerIsAir] is what the INACTIVE layer holds, so
     * the wording names the mode the player would switch to rather than the one they are in.
     * Returns whether anything was shown.
     */
    fun show(
        ui: UI,
        row: Int,
        col: Int,
        otherLayerIsAir: Boolean,
    ): Boolean {
        val now = window.asDynamic().performance?.now() as? Double ?: 0.0
        val game = byId("game")
        if (airModeUsed || game == null || now - lastShownAt < REPEAT_COOLDOWN_MS) return false
        lastShownAt = now
        hide()
        val hint = addTag(game, "div")
        hint.id = HINT_ID
        hint.className = "osada-airmode-hint"
        hint.textContent =
            I18n.t(if (otherLayerIsAir) "map.layer_hint.to_air" else "map.layer_hint.to_ground")
        val pos = ui.render.cellToScreen(row, col, true)
        hint.style.left = "${pos.x.toInt()}px"
        hint.style.top = "${pos.y.toInt() - ANCHOR_Y_OFFSET}px"
        return true
    }

    fun hide() {
        delTag(byId(HINT_ID))
    }

    internal fun resetForTest() {
        lastShownAt = -REPEAT_COOLDOWN_MS
        airModeUsed = false
        hide()
    }
}
