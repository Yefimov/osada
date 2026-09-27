package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.allocMap
import org.osada.model.getPlayer
import org.osada.model.moveUnit
import org.osada.model.resetEquipment
import org.osada.model.setMoveRange
import org.osada.rules.GameRules
import org.osada.rules.HexGeometry
import org.osada.rules.MovementRules
import org.osada.rules.setZOCRange
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How an enemy Zone of Control shapes a move range, and the OG leader that ignores it.
 *
 * OG's Basic Manual §5.2 makes ZOC a movement-TERMINATION rule: a move ends when the unit becomes
 * adjacent to an enemy. This engine expresses that as a `254` cost floor on the ZOC hex, which
 * reproduces the observable outcome — the hex itself stays enterable, nothing beyond it is — but is
 * not the same model (see `DEFERRED.md` for what that costs us).
 *
 * The rule these tests pin down, because it is the one that explains the `Falciu 1` naval report:
 * **leaving** a ZOC hex is free, only **entering** one stops you. A unit that begins its turn
 * adjacent to an enemy may spend its whole allowance moving away.
 *
 * The map is a one-hex-wide corridor: row 1 is clear, rows 0 and 2 are IMPASSABLE_RIVER, which
 * `movTableDry[LEG][15]` marks 255. That removes every alternative route, so "unreachable" means
 * the ZOC stopped the unit rather than the pathfinder having found a detour.
 */
class ZoneOfControlMovementTest {
    private val legEqid = 400
    private val fighterEqid = 401
    private val partisanEqid = 402
    private val rows = 3
    private val cols = 9

    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        movTable = movTableDry
        Equipment.resetEquipment()
        Equipment.putEquipment(
            legEqid,
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                movpoints = 6
                fuel = 0
                ammo = 6
            },
        )
        Equipment.putEquipment(
            partisanEqid,
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                movpoints = 6
                fuel = 0
                ammo = 6
                attrEx = 1024 // Partizan: the equipment-level equivalent of Superior Maneuver.
            },
        )
    }

    @AfterTest
    fun cleanup() {
        movTable = movTableDry
    }

    /** Row 1 clear, rows 0 and 2 impassable, both sides present, everything spotted by side 0. */
    private fun corridor(): GameMap {
        val map =
            GameMap().apply {
                rows = this@ZoneOfControlMovementTest.rows
                cols = this@ZoneOfControlMovementTest.cols
                allocMap()
            }
        map.addPlayer(
            Player().apply {
                id = 0
                side = 0
                country = 0
            },
        )
        map.addPlayer(
            Player().apply {
                id = 1
                side = 1
                country = 0
            },
        )
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val hex = map.map!![r][c]
                hex.terrain = if (r == 1) TerrainType.CLEAR.value else TerrainType.IMPASSABLE_RIVER.value
                // ZOC only bites on hexes the moving side can see; give side 0 full vision so these
                // tests exercise the ZOC rule and not the fog-of-war gate on top of it.
                hex.setSpotted(0, true)
            }
        }
        return map
    }

    private fun place(
        map: GameMap,
        row: Int,
        col: Int,
        playerId: Int,
        leader: Int = -1,
    ): GameUnit {
        val unit =
            GameUnit(legEqid).apply {
                owner = playerId
                player = map.getPlayer(playerId)
                strength = 10
                this.leader = leader
            }
        map.map!![row][col].setUnit(unit)
        return unit
    }

    /** The enemy sits OFF the corridor at (0,4) — impassable to a leg unit, so it never blocks the
     *  corridor hex itself. Its ZOC still reaches (1,4), the corridor hex east of the mover. */
    private fun withEnemyProjectingZocInto(
        map: GameMap,
        row: Int,
        col: Int,
    ) {
        val enemy = place(map, row, col, 1)
        GameRules.setZOCRange(map, enemy, true)
    }

    private fun reach(
        map: GameMap,
        unit: GameUnit,
    ): Set<Pair<Int, Int>> = MovementRules.getMoveRange(map, unit).map { it.row to it.col }.toSet()

    @Test
    fun withNoEnemyTheCorridorIsOpenForTheWholeAllowance() {
        val map = corridor()
        val unit = place(map, 1, 2, 0)

        val got = reach(map, unit)

        assertTrue((1 to 8) in got, "6 clear hexes east of (1,2) at cost 1 each")
        assertTrue((1 to 0) in got, "and west")
    }

    @Test
    fun enteringAnEnemyZocHexEndsTheRangeThere() {
        val map = corridor()
        withEnemyProjectingZocInto(map, 0, 4)
        val unit = place(map, 1, 2, 0)

        val got = reach(map, unit)

        assertTrue((1 to 3) in got, "the hex before the ZOC is ordinary clear terrain")
        assertTrue((1 to 4) in got, "the ZOC hex itself stays enterable — you may always step in")
        assertFalse((1 to 5) in got, "but nothing beyond it: entering ZOC ends the move")
        assertFalse((1 to 8) in got)
    }

    /**
     * The rule behind the `Falciu 1` report. A unit that STARTS adjacent to an enemy is not pinned;
     * only entering a ZOC hex costs it the move. Here the mover begins on (1,4) — inside the
     * enemy's ZOC — and still crosses the corridor, because (1,5)..(1,8) are outside it.
     */
    @Test
    fun leavingAZocHexIsFree() {
        val map = corridor()
        withEnemyProjectingZocInto(map, 0, 4)
        val unit = place(map, 1, 4, 0)

        val got = reach(map, unit)

        assertTrue((1 to 8) in got, "starting inside ZOC must not cost the allowance")
    }

    /** OG: "Superior Maneuver — The unit may bypass enemy zones of control." Before this was wired
     *  up the leader was offered with that description and did nothing at all. */
    @Test
    fun superiorManeuverIgnoresEnemyZocEntirely() {
        val map = corridor()
        withEnemyProjectingZocInto(map, 0, 4)
        val unit = place(map, 1, 2, 0, leader = LeaderType.SUPERIOR_MANEUVER.value)

        val got = reach(map, unit)

        assertTrue((1 to 4) in got)
        assertTrue((1 to 5) in got, "the ZOC hex no longer terminates the move")
        assertTrue((1 to 8) in got, "full 6-point allowance straight through the ZOC")
    }

    @Test
    fun partizanEquipmentAbilityAlsoIgnoresEnemyZocInTheMoveOverlay() {
        val map = corridor()
        withEnemyProjectingZocInto(map, 0, 4)
        val unit =
            GameUnit(partisanEqid).apply {
                owner = 0
                player = map.getPlayer(0)
                strength = 10
            }
        map.map!![1][2].setUnit(unit)

        val got = reach(map, unit)

        assertTrue((1 to 4) in got)
        assertTrue((1 to 5) in got, "Partizan must agree with the move executor and bypass visible ZOC")
        assertTrue((1 to 8) in got, "equipment ability and hero trait must stack as equivalent permissions")
    }

    /** Control: a different leader must not bypass ZOC. Guards against the flag being wired to the
     *  presence of ANY leader rather than to this one. */
    @Test
    fun anUnrelatedLeaderDoesNotBypassZoc() {
        val map = corridor()
        withEnemyProjectingZocInto(map, 0, 4)
        val unit = place(map, 1, 2, 0, leader = LeaderType.DETERMINED_DEFENSE.value)

        val got = reach(map, unit)

        assertFalse((1 to 5) in got, "Determined Defense has nothing to do with movement")
    }

    // ---- A HIDDEN enemy's ZOC ----
    //
    // The move RANGE is blind to unseen ZOC: charging for it would draw a short reach around an
    // enemy the player has not found. The WALK is not: meeting the zone of an enemy the side had not
    // seen ends the move there, with no combat (OG manual 6.13.1, restored 2026-09-27,
    // `rules/ZoneOfControlContact`). "Unseen" is the enemy UNIT's visibility, not the terrain's.

    /** The overlay must keep offering the whole corridor even though an unseen enemy flanks it —
     *  if this ever fails, the move range has begun leaking hidden unit positions. */
    @Test
    fun theMoveRangeStaysOptimisticAboutAnUnseenEnemysZoc() {
        val map = corridor()
        withEnemyProjectingZocInto(map, 0, 4)
        unspot(map, 0, 4)
        val unit = place(map, 1, 2, 0)

        val got = reach(map, unit)

        assertTrue((1 to 5) in got, "an unseen enemy must not shorten the drawn range")
        assertTrue((1 to 8) in got, "nor cost the allowance — that would betray its position")
    }

    /** ...but walking into its zone is a contact: the move stops on the first hex of it. */
    @Test
    fun aMoveIntoAnUnseenEnemysZocStopsOnContact() {
        val map = corridor()
        withEnemyProjectingZocInto(map, 0, 4)
        unspot(map, 0, 4)
        val unit = place(map, 1, 2, 0)

        map.setMoveRange(unit)
        val result = map.moveUnit(unit, 1, 8)

        val stop = unit.getPos()!!.let { it.row to it.col }
        assertTrue(stop != (1 to 8), "the move does not reach where it was sent")
        assertTrue(result.stoppedByContact, "and the stop is reported as a contact")
        assertTrue(
            HexGeometry.getAdjacent(0, 4).any { it.row to it.col == stop },
            "it stops on the first hex of the enemy's zone",
        )
        assertFalse(unit.isSurprised, "a contact is not a surprise: no combat follows")
    }

    /** An enemy the side CAN see already ended the route in the overlay; seeing it is not "contact". */
    @Test
    fun aVisibleEnemysZoneIsARouteTerminalNotAContact() {
        val map = corridor()
        withEnemyProjectingZocInto(map, 0, 4)
        unspot(map, 1, 4)
        val unit = place(map, 1, 2, 0)

        val got = reach(map, unit)

        assertFalse((1 to 8) in got, "a visible enemy's zone ends the route, whatever the terrain's spotting")
    }

    /** Control: with the same enemy spotted, the range itself already ends the move, so the
     *  executor's stop must NOT be what is doing the work — otherwise the two rules would
     *  double-charge and the spotted case would silently change behaviour. */
    @Test
    fun aSpottedEnemyIsHandledByTheRangeNotTheWalkStop() {
        val map = corridor()
        withEnemyProjectingZocInto(map, 0, 4)
        val unit = place(map, 1, 2, 0)

        map.setMoveRange(unit)
        val result = map.moveUnit(unit, 1, 4)

        assertEquals(1 to 4, unit.getPos()!!.let { it.row to it.col })
    }

    /** Air units neither project nor feel ZOC (`MovementRules.setZOCRange` skips them), so a hidden
     *  ground enemy must not halt an aircraft either. */
    @Test
    fun anAircraftIsNotStoppedByAnUnseenGroundZoc() {
        val map = corridor()
        withEnemyProjectingZocInto(map, 0, 4)
        unspot(map, 1, 4)
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                uclass = UnitClass.FIGHTER.value
                movmethod = MovMethod.AIR.value
                movpoints = 6
                fuel = 40
                ammo = 6
            },
        )
        val plane =
            GameUnit(fighterEqid).apply {
                owner = 0
                player = map.getPlayer(0)
                strength = 10
                fuel = 40
            }
        map.map!![1][2].setUnit(plane)

        map.setMoveRange(plane)
        val result = map.moveUnit(plane, 1, 6)

        assertEquals(1 to 6, plane.getPos()!!.let { it.row to it.col }, "ground ZOC is nothing to an aircraft")
    }

    /** Hides the ZOC-covered corridor hex from side 0. The engine's gate is `isSpotted` on the hex
     *  BEING ENTERED (`MoveRangeCalculation.resolveNeighborCost`), so "an unseen enemy's ZOC" means
     *  precisely a ZOC hex the mover cannot see into — not an unseen enemy somewhere off to the side. */
    private fun unspot(
        map: GameMap,
        row: Int,
        col: Int,
    ) {
        map.map!![row][col].setSpotted(0, false)
    }

    /**
     * A move is charged what its hexes cost, not one point per hex plus one for the hex it started
     * on: the route used to come back from the pathfinder as plain cells, which the walk charges 1.
     */
    @Test
    fun aMoveSpendsTheTerrainCostOfTheHexesEnteredAndNothingForTheStart() {
        val tankEqid = 403
        Equipment.putEquipment(
            tankEqid,
            EquipmentData().apply {
                uclass = UnitClass.TANK.value
                movmethod = MovMethod.TRACKED.value
                movpoints = 6
                fuel = 40
            },
        )
        val map = corridor()
        val tank =
            GameUnit(tankEqid).apply {
                owner = 0
                player = map.getPlayer(0)
                fuel = 20
            }
        map.map!![1][2].setUnit(tank)
        val expected = (3..5).sumOf { col -> movTable[MovMethod.TRACKED.value][map.map!![1][col].terrain] }

        map.setMoveRange(tank)
        map.moveUnit(tank, 1, 5)

        assertEquals(20 - expected, tank.fuel, "three clear hexes cost their table cost, the start is free")
    }

    /**
     * A route may pass through a friendly formation's hex but never stop on it. A contact stop that
     * falls on such a hex steps back to the last free one, instead of putting two units on one hex.
     */
    @Test
    fun aContactStopNeverEndsOnAFriendlyHex() {
        val map = corridor()
        withEnemyProjectingZocInto(map, 0, 5)
        unspot(map, 0, 5)
        val firstZoneHex = (3..8).first { c -> HexGeometry.getAdjacent(0, 5).any { it.row == 1 && it.col == c } }
        val friend = place(map, 1, firstZoneHex, 0)
        val unit = place(map, 1, 2, 0)

        map.setMoveRange(unit)
        val result = map.moveUnit(unit, 1, 8)

        assertTrue(result.stoppedByContact)
        assertEquals(1 to firstZoneHex - 1, unit.getPos()!!.let { it.row to it.col }, "stepped back off the friend")
        assertTrue(map.map!![1][firstZoneHex].unit === friend, "the friend keeps its hex")
    }
}
