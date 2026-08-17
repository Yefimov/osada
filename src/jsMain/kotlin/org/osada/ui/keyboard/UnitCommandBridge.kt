package org.osada.ui.keyboard

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Runs a unit command through the action chip the player would otherwise click.
 *
 * This is deliberately DOM-level rather than a second call into `UnitContextMenu`: the design's
 * hard rule is that a keyboard command reaches the same controller entry point as its button
 * (`docs/design/keyboard-shortcuts-and-help.md` §4), and the chip strip is already the single place
 * that decides whether an action is applicable, enabled, or blocked-with-a-reason.
 *
 * Consequently the three outcomes fall out for free:
 * - chip absent (the action can never apply to this formation) -> [Outcome.ABSENT], the key is not
 *   consumed and the browser default stands;
 * - chip present but disabled -> [Outcome.BLOCKED]: focusing it opens the very same anchored reason
 *   panel the mouse would, so an unavailable command never fails silently (§5);
 * - chip present and enabled -> [Outcome.RAN].
 */
internal object UnitCommandBridge {
    enum class Outcome { ABSENT, BLOCKED, RAN }

    fun activate(actionId: String): Outcome {
        val chip = chip(actionId) ?: return Outcome.ABSENT
        val blocked = chip.getAttribute("aria-disabled") == "true"
        // Focusing a blocked chip opens its anchored reason panel through the chip's own
        // focus handler -- the command explains itself instead of failing silently.
        if (blocked) chip.focus() else chip.asDynamic().click()
        return if (blocked) Outcome.BLOCKED else Outcome.RAN
    }

    private fun chip(actionId: String): HTMLElement? =
        document.querySelector("#unit-context .osada-action[data-action=\"$actionId\"]") as? HTMLElement
}
