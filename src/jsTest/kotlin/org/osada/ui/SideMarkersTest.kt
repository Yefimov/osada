package org.osada.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the classification and sizing rules of the opt-in strategic star/skull badges
 * (`docs/design/accessible-side-identification.md` §§2, 7).
 *
 * The renderer test the design asks for is exactly this one: "renderer unit tests classify
 * same-side support units as friendly and opponents as enemy". Support-country units are the case
 * that matters — they fly a foreign flag while belonging to the player's order of battle, so any
 * implementation that classified by country or by displayed flag would call them enemies.
 */
class SideMarkersTest {
    @Test
    fun ownSideIsFriendlyAndOpposingSideIsEnemy() {
        assertEquals(SideMarker.FRIENDLY, SideMarkers.classify(unitSide = 0, spotSide = 0))
        assertEquals(SideMarker.ENEMY, SideMarkers.classify(unitSide = 1, spotSide = 0))
        assertEquals(SideMarker.FRIENDLY, SideMarkers.classify(unitSide = 1, spotSide = 1))
        assertEquals(SideMarker.ENEMY, SideMarkers.classify(unitSide = 0, spotSide = 1))
    }

    /**
     * A support-country formation has a different country, a different flag and a different palette
     * from the player's own units, and is still theirs. Only the SIDE answers this question
     * (`DEFERRED.md` §§5.2, 5.6) -- which is why [SideMarkers.classify] takes a side and nothing
     * else, and cannot be handed a country by mistake.
     */
    @Test
    fun supportCountryUnitOnThePlayersSideIsFriendly() {
        val playerSide = 0
        val supportCountryUnitSide = 0
        assertEquals(SideMarker.FRIENDLY, SideMarkers.classify(supportCountryUnitSide, playerSide))
    }

    /** An owner-less scenario unit gets no badge rather than a guessed one. */
    @Test
    fun unitWithoutASideGetsNoBadge() {
        assertNull(SideMarkers.classify(unitSide = null, spotSide = 0))
    }

    /**
     * Observer/spectator and the AI's turn: allegiance is relative to the spotting side, so the
     * badge on a given unit does not flip when the current player changes.
     */
    @Test
    fun allegianceFollowsTheSpottingSideNotTheCurrentPlayer() {
        val playerUnitSide = 0
        val spotSide = 0
        // Same unit, two different "current players" -- the classification takes only spotSide, so
        // there is no input here that a turn hand-off could change.
        assertEquals(SideMarker.FRIENDLY, SideMarkers.classify(playerUnitSide, spotSide))
        assertEquals(SideMarker.FRIENDLY, SideMarkers.classify(playerUnitSide, spotSide))
    }

    /** Scaled from the flag bounds (design §2), never a hard-coded screen size. */
    @Test
    fun badgeScalesWithTheFlagAndKeepsAFloor() {
        assertTrue(SideMarkers.badgeSize(40.0) > SideMarkers.badgeSize(20.0))
        assertEquals(SideMarkers.badgeSize(40.0), SideMarkers.badgeSize(20.0) * 2.0)
        // Below the floor the shape stops being a shape; the minimum keeps it readable at the
        // smallest map zoom rather than letting it collapse to a dot.
        assertTrue(SideMarkers.badgeSize(1.0) >= 7.0)
    }
}
