package org.osada.hero

import org.osada.LeaderType
import org.osada.hero.LegacyTraitMapping.toTraitId

/**
 * Bridge between the legacy integer trait world and the string trait ids the hero model uses.
 *
 * Phase 1 does not introduce the data-driven `HeroTraitDefinition` catalogue of §20 — that belongs
 * with the promotion system, and inventing it early would mean guessing at trait shapes before the
 * evidence tracks that justify them exist. Instead a hero's learned traits are stored as the
 * existing [LeaderType] names in a namespaced string form (`legacy.TENACIOUS_DEFENSE`).
 *
 * Storing the NAME rather than the ordinal matters: `LeaderType.value` is a save-visible integer
 * today, but the hero roster is new data with no back-compat obligation, and names survive any
 * future renumbering of the enum.
 */
object LegacyTraitMapping {
    private const val PREFIX = "legacy."

    /** Trait id for a legacy [LeaderType], e.g. `legacy.TANK_KILLER`. */
    fun toTraitId(leader: LeaderType): String = PREFIX + leader.name

    /** Trait id for a raw legacy trait integer, or null when it maps to no known [LeaderType]. */
    fun toTraitId(leaderValue: Int): String? =
        LeaderType.entries.firstOrNull { it.value == leaderValue }?.let { toTraitId(it) }

    /** Inverse of [toTraitId]; null for ids that are not legacy-namespaced or name no known trait. */
    fun fromTraitId(traitId: String): LeaderType? {
        if (!traitId.startsWith(PREFIX)) return null
        val name = traitId.removePrefix(PREFIX)
        return LeaderType.entries.firstOrNull { it.name == name }
    }
}
