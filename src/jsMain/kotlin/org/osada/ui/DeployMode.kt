package org.osada.ui

import org.osada.GameHolder
import org.osada.uiSettings

/**
 * The one writer of `uiSettings.deployMode`, so every change to it is logged with the side it was
 * made for.
 *
 * That flag decides whether the deploy overlay is drawn, but it carries no owner — the overlay
 * resolves the side at paint time from `currentPlayer` ([MapRenderer]). When the two disagree the
 * player sees the *enemy's* deploy zone, which is exactly the bug reported on 2026-08-01, and
 * nothing in the console log said so: `deployMode` was assigned from six different places and
 * logged from none of them. Routing the assignments through here makes the transition visible and
 * gives it a stated reason, so the next report of this shape is one grep.
 *
 * Assignments are deliberately NOT hidden behind a property setter: a setter would log the save
 * restore and every no-op write too, and the value still has to be readable directly by the
 * renderer on the hot path.
 */
internal fun setDeployMode(
    enabled: Boolean,
    reason: String,
) {
    if (uiSettings.deployMode == enabled) return
    uiSettings.deployMode = enabled
    val map = GameHolder.instance?.scenario?.map
    val player = map?.currentPlayer
    console.log(
        "[OSADA] deployMode=$enabled ($reason) turn=${map?.turn} " +
            "player=${player?.id} side=${player?.side} ${player?.type?.name}",
    )
}
