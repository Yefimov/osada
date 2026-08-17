package org.osada.ui

import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
