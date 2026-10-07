package org.osada.ui

import org.osada.model.MoveReactionKind

/**
 * The i18n keys for fire a MOVE drew, rather than an attack the player ordered.
 *
 * One owner for the banner ([InterceptionBanner]) and the HUD log ([AnimationOrchestrator]), so the
 * same event cannot be named "intercepted" in one surface and "fired on" in the other — the drift
 * already fixed once for combat predicates, applied to wording.
 */
internal object MoveReactionText {
    fun titleKey(kind: MoveReactionKind): String =
        when (kind) {
            MoveReactionKind.AA_INTERCEPTION -> "combat.interception.title"
            MoveReactionKind.COUNTER_BATTERY -> "combat.counter_battery.title"
        }

    fun lineKey(
        kind: MoveReactionKind,
        destroyed: Boolean,
    ): String =
        when (kind) {
            MoveReactionKind.AA_INTERCEPTION ->
                if (destroyed) "combat.interception.destroyed" else "combat.interception.damaged"

            MoveReactionKind.COUNTER_BATTERY ->
                if (destroyed) "combat.counter_battery.destroyed" else "combat.counter_battery.damaged"
        }
}
