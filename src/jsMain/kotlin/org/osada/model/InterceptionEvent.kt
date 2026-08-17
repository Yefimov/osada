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
)
