package org.osada.hero

import org.osada.LeaderType

/**
 * The distinguishing battlefield action that produced an officer (§7.1, §8.3, §14.1).
 *
 * This is the justification thread the whole design turns on: §4.1 wants every improvement to have
 * a stated reason, and the very first one is the officer's own emergence. The event decides three
 * things — the character label the player sees ("Bold", "Steadfast"), the reason line in the new-
 * leader event, and the *preferred* personal trait ([preferredTrait]).
 *
 * ## Why the personal trait is a legacy [LeaderType] and not a new Phase 3 trait
 *
 * §8.3's example trait ids (STEADFAST, BOLD, ENCIRCLEMENT_SPECIALIST…) belong to the data-driven
 * trait catalogue of §20, which Phase 3 owns and which this phase deliberately does not invent.
 * Rather than ship personal traits with no mechanical effect, Phase 2 maps each emergence event to
 * an existing reachable [LeaderType] whose effect fits the story. The result plugs straight into
 * [HeroTraitResolver] — a hero's learned trait is honoured in combat regardless of unit class — so
 * a newly emerged officer fights with a real, justified bonus from turn one, exactly as a migrated
 * one does. When the Phase 3 catalogue lands, [preferredTrait] is the seam that retargets it.
 *
 * [ProceduralHeroGenerator] resolves [preferredTrait] against the unit's class list so the choice
 * is always class-appropriate; this enum only states the intent.
 *
 * Display strings are the English source text. §29.20 asks for localization-ready strings: they are
 * centralised here and keyed by [eventId], so a later i18n pass swaps the bodies without touching
 * any call site.
 */
enum class EmergenceEvent(
    /** Stable id stored in [HeroBiographyFacts.emergenceEventId] and used as a localization key. */
    val eventId: String,
    /** The character word shown beside the officer's name (§14.1, §14.2). */
    val characterLabel: String,
    /** The trait the action argues for; resolved to a class-compatible one by the generator. */
    val preferredTrait: LeaderType,
    /** One-line justification for the new-leader event (§14.1). */
    val reason: String,
) {
    DESTROYED_STRONGER_ENEMY(
        eventId = "destroyed_stronger_enemy",
        characterLabel = "Bold",
        preferredTrait = LeaderType.AGGRESSIVE_ATTACK,
        reason = "Distinguished themselves by destroying a stronger enemy formation.",
    ),
    DESTROYED_ENEMY(
        eventId = "destroyed_enemy",
        characterLabel = "Aggressive",
        preferredTrait = LeaderType.FIRST_STRIKE,
        reason = "Led the attack that destroyed an enemy unit outright.",
    ),
    HELD_UNDER_ATTACK(
        eventId = "held_under_attack",
        characterLabel = "Steadfast",
        preferredTrait = LeaderType.DETERMINED_DEFENSE,
        reason = "Held the position under a determined enemy attack.",
    ),
    SURVIVED_CRITICAL_DAMAGE(
        eventId = "survived_critical_damage",
        characterLabel = "Resilient",
        preferredTrait = LeaderType.DETERMINED_DEFENSE,
        reason = "Kept the formation in the fight after taking crippling losses.",
    ),
    DISTINGUISHED_SERVICE(
        eventId = "distinguished_service",
        characterLabel = "Capable",
        preferredTrait = LeaderType.AGGRESSIVE_MANEUVER,
        reason = "Recognised for sustained distinguished service with the formation.",
    ),
    ;

    companion object {
        /** Defaulting lookup by [eventId]; unknown ids fall back to [DISTINGUISHED_SERVICE]. */
        fun byId(id: String?): EmergenceEvent = entries.firstOrNull { it.eventId == id } ?: DISTINGUISHED_SERVICE
    }
}
