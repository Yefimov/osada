package org.osada.model

/**
 * One AA gun firing on one moving aircraft, as it actually resolved
 * (`docs/design/aa-interception.md`, `docs/player-comfort-roadmap.md` P1).
 *
 * Carried out of the rules layer on [MovementResults] so the HUD can raise an obvious, non-modal
 * event for something the player otherwise only discovers by noticing their aircraft is suddenly
 * weaker. The combat log keeps the full blow-by-blow detail; this is the "look here, now" surface.
 *
 * It exists only for interceptions that HAPPENED. Nothing about a hidden AA gun is published before
 * it fires — that ambush is the entire mechanic.
 */
data class InterceptionEvent(
    val interceptor: GameUnit,
    val plane: GameUnit,
    /** Strength points the aircraft actually lost to this gun. */
    val losses: Int,
    val planeDestroyed: Boolean,
    /** Which reaction this was, so the banner and the HUD log can name it correctly. Defaults to
     *  AA interception, the case this type was built for. */
    val kind: MoveReactionKind = MoveReactionKind.AA_INTERCEPTION,
)

/**
 * The kinds of fire a MOVE can draw, as opposed to an attack the player ordered.
 *
 * One vocabulary rather than three parallel event types, because every one of them has the same
 * player-facing problem and therefore the same answer: a formation arrives weaker from a combat
 * nobody watched, so it must be named out loud in the same banner and the same HUD log
 * (`DEFERRED.md` §1.1 — *"Movement damage with no visible cause reads as a bug"*).
 */
enum class MoveReactionKind {
    /** Anti-aircraft fire on a moving aircraft (`rules/AAInterception`). */
    AA_INTERCEPTION,

    /** Opportunity fire by an `Overwatch` commander (`rules/OverwatchFire`). */
    OVERWATCH,

    /**
     * A battery answering enemy artillery that fired on a friendly unit (`rules/CounterBatteryFire`,
     * OG manual 9.4).
     *
     * The odd one out in this enum's own name: counterbattery answers an ATTACK, not a move. It
     * belongs here anyway, because the vocabulary exists for the player-facing problem rather than
     * for the trigger -- a formation that arrives weaker from a combat nobody watched needs the
     * same banner and the same HUD line whichever of the three produced it.
     */
    COUNTER_BATTERY,
}
