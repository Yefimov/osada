package org.osada.hero

/*
 * Enumerated hero vocabulary from the design brief §17.
 *
 * These are serialized BY NAME, not by ordinal, so reordering or inserting entries stays
 * save-compatible. HeroSerializer resolves unknown names to the documented fallback rather than
 * failing the load, because save data is untrusted input.
 */

/** Where a hero's identity came from. Phase 1 only ever produces [PROCEDURAL]. */
enum class HeroOrigin {
    PROCEDURAL,
    AUTHORED_FICTIONAL,
    HISTORICAL,
    SCRIPTED,
}

/** Service status. Phase 1 only ever produces [ACTIVE]; wounds/death arrive in a later phase. */
enum class HeroStatus {
    ACTIVE,
    RESERVE,
    WOUNDED,
    SERIOUSLY_WOUNDED,
    MISSING,
    CAPTURED,
    RETIRED,
    KILLED,
}

/**
 * Starting quality (§7.3). Explicitly NOT a ceiling — a [LINE_OFFICER] can still reach
 * [HeroRenown.LEGEND], which §26 requires.
 */
enum class HeroPotential {
    LINE_OFFICER,
    PROMISING,
    DISTINGUISHED,
    AUTHORED_LEGENDARY,
}

/** Earned public standing (§4.4). Independent of [HeroPotential]. */
enum class HeroRenown {
    UNKNOWN,
    EXPERIENCED,
    DISTINGUISHED,
    HERO,
    LEGEND,
}
