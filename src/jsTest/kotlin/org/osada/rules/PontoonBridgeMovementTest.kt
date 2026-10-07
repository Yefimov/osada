package org.osada.rules

import org.osada.Direction
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.moveUnit
import org.osada.model.resetEquipment
import org.osada.model.setMoveRange
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The LXF Manchurian Offensive starts a tracked IT-28 BL beside a major river. */
class PontoonBridgeMovementTest {
    private val bridgeEqid = 20580
    private val tankEqid = 20581

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(bridgeEqid, trackedEquipment(bridge = true))
        Equipment.putEquipment(tankEqid, trackedEquipment(bridge = false))
    }

    @Test
    fun bridgeUnitCanEnterMajorRiverAndProvideRoadCostToFriendlyUnits() {
        val map = world()
        val bridge = place(map, bridgeEqid, 4, 4)
        repeat(map.rows) { row -> map.map!![row][3].terrain = TerrainType.IMPASSABLE_RIVER.value }
        val majorRiver = map.map!![4][3]

        val entry = MovementRules.getMoveRange(map, bridge).first { it.row == 4 && it.col == 3 }
        assertEquals(254, entry.cost, "entering the major river ends the bridge unit's move")
        assertTrue(entry.canMove)

        map.map!![4][4].delUnit(bridge)
        majorRiver.setUnit(bridge)
        val follower = place(map, tankEqid, 4, 2)
        val crossing = MovementRules.getMoveRange(map, follower).first { it.row == 4 && it.col == 3 }
        assertTrue(MovementRules.isBridgeForSide(majorRiver, 0))
        assertFalse(MovementRules.isBridgeForSide(majorRiver, 1))
        assertEquals(1, crossing.cost, "the friendly pontoon hex uses road cost")
        assertTrue(crossing.canPass, "the follower can pass through the occupied bridge hex")
        assertTrue(
            MovementRules.getMoveRange(map, follower).any { it.row == 4 && it.col == 4 && it.canMove },
            "the follower can reach the far bank through the pontoon despite the river barrier",
        )
    }

    @Test
    fun crossingLaidThisTurnOpensOnlyAtTheOwnersNextTurn() {
        val map = world()
        val bridge = place(map, bridgeEqid, 4, 4)
        val river = map.map!![4][3]
        river.terrain = TerrainType.IMPASSABLE_RIVER.value

        map.setMoveRange(bridge)
        val result = map.moveUnit(bridge, 4, 3)

        assertTrue(result.startedBridge, "the move is reported as the start of a crossing")
        assertTrue(bridge.bridgeLaying)
        assertFalse(MovementRules.isBridgeForSide(river, 0), "nothing crosses on the laying turn")
        assertEquals(listOf(bridge), PontoonBridges.completeLaying(map, 0))
        assertTrue(MovementRules.isBridgeForSide(river, 0), "open from the owner's next turn")
        assertTrue(PontoonBridges.completeLaying(map, 0).isEmpty(), "an open crossing is announced once")
    }

    @Test
    fun startingACrossingEndsEvenAPhasedMove() {
        Equipment.putEquipment(bridgeEqid, trackedEquipment(bridge = true).apply { uclass = UnitClass.RECON.value })
        val map = world()
        val bridge = place(map, bridgeEqid, 4, 4)
        map.map!![4][3].terrain = TerrainType.IMPASSABLE_RIVER.value

        map.setMoveRange(bridge)
        map.moveUnit(bridge, 4, 3)

        assertTrue(bridge.hasMoved, "the recon-class bridge unit cannot drive on from the river")
        assertEquals(0, bridge.moveLeft)
        assertTrue(MovementRules.getMoveRange(map, bridge).isEmpty())
    }

    @Test
    fun leavingTheRiverAbandonsTheCrossingBeingLaid() {
        val map = world()
        val bridge = place(map, bridgeEqid, 4, 3)
        val river = map.map!![4][3]
        river.terrain = TerrainType.RIVER.value

        assertTrue(PontoonBridges.onArrival(bridge, river))
        assertFalse(PontoonBridges.onArrival(bridge, map.map!![4][4]))
        assertFalse(bridge.bridgeLaying)
        assertTrue(PontoonBridges.completeLaying(map, 0).isEmpty())
    }

    /** A road over the river is a real bridge (authored, or built by sappers): a bridge unit arriving
     *  there drives on it and does not start laying a pontoon beside it (reported 2026-10-07). */
    @Test
    fun aBridgedRiverIsNotSpannedAgain() {
        val map = world()
        val bridge = place(map, bridgeEqid, 4, 3)
        val river = map.map!![4][3]
        river.terrain = TerrainType.RIVER.value
        river.road = 255

        assertFalse(PontoonBridges.onArrival(bridge, river))
        assertFalse(bridge.bridgeLaying)
        assertFalse(MovementRules.isBridgeForSide(river, 0), "the road carries traffic, not the unit")
    }

    @Test
    fun bridgeUnitEntersMajorRiverOnlyFromItsStartHex() {
        val map = world()
        val bridge = place(map, bridgeEqid, 4, 5)
        repeat(map.rows) { row -> map.map!![row][3].terrain = TerrainType.IMPASSABLE_RIVER.value }

        val range = MovementRules.getMoveRange(map, bridge)
        assertFalse(range.any { it.col == 3 }, "no river hex borders (4,5), so none may be entered this move")
        assertTrue(range.any { it.row == 4 && it.col == 4 && it.canMove }, "the bank itself is still open")
        assertFalse(range.any { it.col < 3 }, "a refused river hex must not carry the move to the far bank")
    }

    @Test
    fun bridgeUnitOnTheRiverMovesAlongItOneHexAtATime() {
        val map = world()
        val bridge = place(map, bridgeEqid, 4, 3)
        repeat(map.rows) { row -> map.map!![row][3].terrain = TerrainType.IMPASSABLE_RIVER.value }

        val river =
            MovementRules
                .getMoveRange(map, bridge)
                .filter { it.col == 3 }
                .map { it.row }
                .sorted()
        assertEquals(listOf(3, 5), river, "only the two river hexes adjacent to (4,3)")
    }

    @Test
    fun aPontoonStepsOffOneHexAndThatIsItsMove() {
        Equipment.putEquipment(bridgeEqid, trackedEquipment(bridge = true).apply { uclass = UnitClass.RECON.value })
        val map = world()
        val bridge = place(map, bridgeEqid, 4, 3)
        repeat(map.rows) { row -> map.map!![row][3].terrain = TerrainType.IMPASSABLE_RIVER.value }

        val range = MovementRules.getMoveRange(map, bridge).filter { it.canMove }
        assertTrue(range.isNotEmpty())
        assertTrue(
            range.all { HexGeometry.isAdjacent(4, 3, it.row, it.col) },
            "only the hexes next to the river hex, got ${range.map { it.row to it.col }}",
        )

        map.setMoveRange(bridge)
        map.moveUnit(bridge, 4, 4)
        assertTrue(bridge.hasMoved, "stepping ashore ends even a phased move")
        assertFalse(bridge.bridgeLaying)
    }

    @Test
    fun pontoonSpansTheRiverWhateverWayTheFormationDroveIn() {
        val map = world()
        val bridge = place(map, bridgeEqid, 4, 3)
        // A river running north-south down column 3: the banks are east and west of it.
        repeat(map.rows) { row -> map.map!![row][3].terrain = TerrainType.IMPASSABLE_RIVER.value }
        bridge.facing = Direction.N.value

        val facing = PontoonBridges.spanFacing(map, bridge)
        assertTrue(facing == Direction.NW.value || facing == Direction.NE.value, "across the river, got $facing")

        // A river running west-east along row 4: the banks are north and south.
        repeat(map.cols) { col -> map.map!![4][col].terrain = TerrainType.IMPASSABLE_RIVER.value }
        repeat(map.rows) { row -> if (row != 4) map.map!![row][3].terrain = TerrainType.CLEAR.value }
        bridge.facing = Direction.W.value
        assertEquals(Direction.N.value, PontoonBridges.spanFacing(map, bridge))
    }

    @Test
    fun ordinaryTrackedUnitCannotEnterUnbridgedMajorRiver() {
        val map = world()
        val tank = place(map, tankEqid, 4, 4)
        map.map!![4][3].terrain = TerrainType.IMPASSABLE_RIVER.value

        assertFalse(MovementRules.getMoveRange(map, tank).any { it.row == 4 && it.col == 3 })
    }

    private fun trackedEquipment(bridge: Boolean): EquipmentData =
        EquipmentData().apply {
            name = if (bridge) "IT-28 BL" else "T-34"
            uclass = UnitClass.TANK.value
            movmethod = MovMethod.TRACKED.value
            movpoints = 4
            fuel = 30
            attr = if (bridge) 8 else 0
        }

    private fun world(): GameMap {
        val player =
            Player().apply {
                id = 0
                side = 0
            }
        return GameMap().apply {
            rows = 8
            cols = 8
            allocMap()
            addPlayer(player)
            currentPlayer = player
        }
    }

    private fun place(
        map: GameMap,
        eqid: Int,
        row: Int,
        col: Int,
    ): GameUnit {
        val unit =
            GameUnit(eqid).apply {
                id = row * 100 + col
                owner = 0
                player = map.currentPlayer
            }
        map.map!![row][col].setUnit(unit)
        map.addUnit(unit)
        return unit
    }
}
