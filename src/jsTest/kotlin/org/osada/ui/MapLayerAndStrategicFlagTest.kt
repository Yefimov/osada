package org.osada.ui

import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MapLayerAndStrategicFlagTest {
    @Test
    fun ownAircraftAboveEnemyGroundUnitIsClickableWithoutAirMode() {
        val ownPlayer =
            Player().apply {
                id = 0
                side = 0
            }
        val enemyPlayer =
            Player().apply {
                id = 1
                side = 1
            }
        val ownAircraft = GameUnit(1).apply { player = ownPlayer }
        val enemyGround = GameUnit(2).apply { player = enemyPlayer }
        val hex =
            Hex(0, 0).apply {
                unit = enemyGround
                airunit = ownAircraft
            }

        val resolved = resolveVisibleUnitForClick(hex, airMode = false, currentPlayerSide = 0, currentPlayerId = 0)

        assertSame(ownAircraft, resolved)
    }

    @Test
    fun activeLayerStillWinsWhenBothUnitsAreFriendly() {
        val player =
            Player().apply {
                id = 0
                side = 0
            }
        val ground = GameUnit(1).apply { this.player = player }
        val air = GameUnit(2).apply { this.player = player }
        val hex =
            Hex(0, 0).apply {
                unit = ground
                airunit = air
            }

        assertSame(ground, resolveVisibleUnitForClick(hex, false, 0, 0))
        assertSame(air, resolveVisibleUnitForClick(hex, true, 0, 0))
    }

    /**
     * A hex is ambiguous, and therefore worth marking, only when BOTH layers are occupied.
     *
     * This is the same `hex.unit != null && hex.airunit != null` test `HexCellRenderer` recesses
     * and marks on, and it has to agree with `Hex.getUnit`: on a hex with one occupant both modes
     * resolve to that occupant, so recessing it would state that a click misses when it does not.
     */
    @Test
    fun onlyAHexHoldingBothLayersIsAmbiguous() {
        val player =
            Player().apply {
                id = 0
                side = 0
            }
        val ground = GameUnit(1).apply { this.player = player }
        val air = GameUnit(2).apply { this.player = player }

        val stacked =
            Hex(0, 0).apply {
                unit = ground
                airunit = air
            }
        val groundOnly = Hex(0, 0).apply { unit = ground }
        val airOnly = Hex(0, 0).apply { airunit = air }
        val empty = Hex(0, 0)

        assertTrue(stacked.unit != null && stacked.airunit != null)
        assertFalse(groundOnly.unit != null && groundOnly.airunit != null)
        assertFalse(airOnly.unit != null && airOnly.airunit != null)
        assertFalse(empty.unit != null && empty.airunit != null)

        // ...and the reason it matters: the lone occupant is what BOTH modes resolve to.
        assertSame(ground, groundOnly.getUnit(true))
        assertSame(ground, groundOnly.getUnit(false))
        assertSame(air, airOnly.getUnit(false))
    }

    /** On a stacked hex the two modes resolve to different units, which is exactly the case the
     *  recessed sprite and the layer marker exist to make visible before the click. */
    @Test
    fun aStackedHexResolvesToADifferentUnitInEachMode() {
        val player =
            Player().apply {
                id = 0
                side = 0
            }
        val ground = GameUnit(1).apply { this.player = player }
        val air = GameUnit(2).apply { this.player = player }
        val hex =
            Hex(0, 0).apply {
                unit = ground
                airunit = air
            }

        assertSame(ground, hex.getUnit(false))
        assertSame(air, hex.getUnit(true))
    }

    /** `GameUnit.flag` is one-based (the XML writes `country + 1`), so the ordinary case — a unit
     *  flying its own owner's flag — must land on that owner's zero-based sprite column. */
    @Test
    fun strategicMapConvertsTheOneBasedUnitFlagToASpriteColumn() {
        val player = Player().apply { country = 13 }
        val unit =
            GameUnit(1).apply {
                this.player = player
                flag = 14
            }

        assertEquals(13, strategicUnitFlag(unit))
    }

    /** ...but the unit's own flag still wins: a scenario-authored override (partisans, foreign
     *  volunteers, captured kit) must not be re-derived from the owner's country. */
    @Test
    fun strategicMapUsesScenarioUnitFlagInsteadOfPlayerCountry() {
        val player = Player().apply { country = 13 }
        val unit =
            GameUnit(1).apply {
                this.player = player
                flag = 41
            }

        assertEquals(40, strategicUnitFlag(unit))
    }

    /** A unit with no flag at all must clamp to column 0 rather than index the sheet at -1. */
    @Test
    fun strategicMapClampsAnUnsetUnitFlagToTheFirstColumn() {
        val unit = GameUnit(1).apply { flag = 0 }

        assertEquals(0, strategicUnitFlag(unit))
    }
}
