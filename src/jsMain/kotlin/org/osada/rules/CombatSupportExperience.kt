package org.osada.rules

import org.osada.model.GameUnit
import kotlin.math.roundToInt

/**
 * A Combat Support unit learns from the formations it supports (owner's decision 2026-10-07; an
 * OSADA rule, not OG's).
 *
 * In OG a staff element earns experience only by being shot at, since it has no attack of its own,
 * so an HQ that lends its bars all battle never gains any. Here, whenever a formation gains combat
 * experience, every Combat Support unit lending it bars at that moment ([UnitCapabilities.combatSupporters])
 * gains [SHARE_PERCENT] of that gain, rounded half up, within the experience cap.
 */
internal object CombatSupportExperience {
    const val SHARE_PERCENT = 25
    private const val PERCENT = 100.0

    /** What a supporter gains when the unit it supports gains [gained]. */
    fun share(gained: Int): Int = if (gained <= 0) 0 else (gained * SHARE_PERCENT / PERCENT).roundToInt()

    /** Credits the share of [gained] to everyone lending bars to [recipient]. */
    fun award(
        units: List<GameUnit>,
        recipient: GameUnit,
        gained: Int,
    ) {
        val share = share(gained)
        if (share <= 0) return
        val cap = UnitExperience.cap()
        UnitCapabilities.combatSupporters(units, recipient).forEach { supporter ->
            if (supporter.experience < cap) supporter.experience = minOf(cap, supporter.experience + share)
        }
    }
}
