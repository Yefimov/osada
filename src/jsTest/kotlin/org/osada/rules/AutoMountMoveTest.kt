package org.osada.rules

import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Transport
import org.osada.model.moveUnit
import org.osada.model.setMoveRange
import org.osada.model.undoLastMove
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Open General's ride-to-get-there (`rules/AutoMount`, `docs/og-fidelity-plan.md` §P): ordering a
 * formation somewhere its legs cannot reach mounts its own transport instead of refusing.
 *
 * The claim these tests defend is that **nothing new became possible** — the same hexes were always
 * reachable by pressing Mount and then moving — so what is asserted is where the boundary sits (on
 * foot, only riding, or out of reach entirely), that the formation ends up mounted, and that undo
 * puts it back on the ground.
 */
class AutoMountMoveTest : OgRulesTestHarness() {
    private val riflemenEqid = 960
    private val lorryEqid = 961

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            riflemenEqid,
            EquipmentData().apply {
                name = "Rifle Company"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 2
                ammo = 6
                spotrange = 1
            },
        )
        Equipment.putEquipment(
            lorryEqid,
            EquipmentData().apply {
                name = "Lorry"
                uclass = UnitClass.GROUND_TRANSPORT.value
                movmethod = MovMethod.WHEELED.value
                // Wheeled costs 2 per clear hex (`movTableDry` row 2), so 8 points is 4 hexes,
                // against the riflemen's 2 -- the gap this whole feature is about.
                movpoints = 8
                spotrange = 1
            },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    @Test
    fun aHexWithinWalkingDistanceIsNotATransportHex() {
        val map = clearWorld()
        val unit = rifles(map)

        assertFalse(AutoMount.requiresTransport(map, unit, Cell(2, 3)))
    }

    @Test
    fun aHexOnlyTheLorryCanReachIsMarkedAsOne() {
        val map = clearWorld()
        val unit = rifles(map)

        assertTrue(AutoMount.requiresTransport(map, unit, Cell(2, 5)), "3 hexes: past 2 on foot, inside 4 on wheels")
    }

    @Test
    fun aFormationWithNoTransportIsNeverOfferedTheRide() {
        val map = clearWorld()
        val unit = place(map, riflemenEqid, 2, 2, side = 0)

        assertFalse(AutoMount.canRideOwnTransport(unit))
        assertEquals(emptyList(), AutoMount.transportOnlyCells(map, unit))
    }

    @Test
    fun theOverlayMarksTheTransportHexesAndTheFootHexesStayPlain() {
        val map = clearWorld()
        val unit = rifles(map)

        map.setMoveRange(unit)

        assertTrue(map.map!![2][3].isMoveSel)
        assertFalse(map.map!![2][3].needsTransport, "two hexes away is a walk")
        assertTrue(map.map!![2][5].isMoveSel)
        assertTrue(map.map!![2][5].needsTransport, "three hexes away is a ride")
    }

    @Test
    fun orderingTheLongMoveMountsTheFormationAndDrivesIt() {
        val map = clearWorld()
        val unit = rifles(map)
        map.setMoveRange(unit)

        map.moveUnit(unit, 2, 5)

        assertEquals(2 to 5, unit.getPos()?.let { it.row to it.col })
        assertTrue(unit.isMounted, "it arrived riding, exactly as Mount-then-move would have left it")
    }

    @Test
    fun undoingTheRidePutsTheFormationBackOnFootWhereItStarted() {
        val map = clearWorld()
        val unit = rifles(map)
        map.setMoveRange(unit)
        map.moveUnit(unit, 2, 5)

        map.undoLastMove()

        assertEquals(2 to 2, unit.getPos()?.let { it.row to it.col })
        assertFalse(unit.isMounted, "undo rewinds the mount too -- the player never asked for it")
    }

    @Test
    fun aShortMoveStillWalksAndLeavesTheLorryBehind() {
        val map = clearWorld()
        val unit = rifles(map)
        map.setMoveRange(unit)

        map.moveUnit(unit, 2, 3)

        assertEquals(2 to 3, unit.getPos()?.let { it.row to it.col })
        assertFalse(unit.isMounted, "walking distance is walked")
    }

    @Test
    fun woodsTheLorryRefusesStayAFootHexAndAreNotMarked() {
        val map = clearWorld()
        for (col in 3..7) map.map!![3][col].terrain = TerrainType.MOUNTAIN.value
        val unit = rifles(map)

        map.setMoveRange(unit)

        assertTrue(map.map!![3][3].isMoveSel, "the riflemen still walk into the mountains")
        assertFalse(
            map.map!![3][3].needsTransport,
            "a hex the transport cannot enter is never a transport hex, whoever else can reach it",
        )
    }

    private fun clearWorld(): GameMap =
        world().apply {
            for (r in 0 until rows) {
                for (c in 0 until cols) map!![r][c].terrain = TerrainType.CLEAR.value
            }
        }

    private fun rifles(map: GameMap): GameUnit =
        place(map, riflemenEqid, 2, 2, side = 0).apply { transport = Transport(lorryEqid) }
}
