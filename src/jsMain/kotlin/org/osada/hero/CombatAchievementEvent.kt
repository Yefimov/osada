package org.osada.hero

/**
 * The shared structured event of design brief §19 — the common source [HeroProgressionProcessor]
 * turns into specialization evidence, and that both a hero's [HeroEvent] service record and its
 * formation's [FormationEvent] history are appended from. Recognition (Phase 2) and leader XP stay
 * their own numbers rather than being folded into this event, matching §9.3's requirement that the
 * concepts stay separate — but they are counted from the same [AchievementType] list this event
 * carries, which is what makes the formation's and the leader's records "separate but generated
 * from shared structured events" (§29.12) rather than two independently-invented logs.
 *
 * Only the fields Phase 3 can actually populate from a resolved combat are present — see
 * `docs/hero-leader-implementation-phases.md` for the ones deliberately left out (encirclement,
 * objective id, enemy strength) until a later phase's pipeline can supply them honestly.
 */
data class CombatAchievementEvent(
    val campaignId: String,
    val scenarioId: String,
    val turn: Int,
    val formationId: FormationId,
    val achievementType: AchievementType,
    val date: String? = null,
    val location: String? = null,
)
