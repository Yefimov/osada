package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.attackUnit
import org.osada.model.getPlayer
import org.osada.model.moveUnit
import org.osada.model.resetEquipment
import org.osada.model.setMoveRange
import org.osada.model.surpriseTarget
import org.osada.rules.AttackEligibility
import org.osada.rules.HexGeometry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * OG's two ambushing leaders (`rules/MoveAmbush`): an `Overwatch` gun surprises the first enemy that
 * enters its range, a Camouflage Expert the first that enters its zone of control on its own ground.
 * Either way the move STOPS, the mover is surprised, and the ambusher is who it then fights.
 *
 * Corridor: row 1 clear, rows 0 and 2 impassable to the leg movers, so the path is fixed.
 */
class MoveAmbushTest {
    private val legEqid = 420
    private val gunEqid = 421
    private val rows = 3
    private val cols = 10

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
                target = UnitType.SOFT.value
                movpoints = 8
                ammo = 6
                gunrange = 1
                spotrange = 1
                softatk = 6
                grounddef = 4
                closedef = 5
            },
        )
        Equipment.putEquipment(
            gunEqid,
            EquipmentData().apply {
                uclass = UnitClass.ARTILLERY.value
                movmethod = MovMethod.TOWED.value
                target = UnitType.SOFT.value
                ammo = 6
                gunrange = 3
                softatk = 9
                grounddef = 3
            },
        )
    }

    private fun corridor(): GameMap {
        val map =
            GameMap().apply {
                rows = this@MoveAmbushTest.rows
                cols = this@MoveAmbushTest.cols
                allocMap()
            }
        for (id in 0..1) {
            map.addPlayer(
                Player().apply {
                    this.id = id
                    side = id
                    country = 0
                },
            )
        }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val hex = map.map!![r][c]
                hex.terrain = if (r == 1) TerrainType.CLEAR.value else TerrainType.IMPASSABLE_RIVER.value
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
        eqid: Int,
        leader: Int = -1,
    ): GameUnit {
        val unit =
            GameUnit(eqid).apply {
                owner = playerId
                player = map.getPlayer(playerId)
                strength = 10
                ammo = 6
                this.leader = leader
            }
        map.map!![row][col].setUnit(unit)
        map.addUnit(unit)
        return unit
    }

    private fun walk(
        map: GameMap,
        mover: GameUnit,
        toCol: Int,
    ) = map.run {
        setMoveRange(mover)
        moveUnit(mover, 1, toCol)
    }

    /** The gun at (1,9) reaches three hexes: the column is stopped on the FIRST hex of the
     *  corridor that is in its range, not walked on to its destination. */
    @Test
    fun anOverwatchGunStopsAndSurprisesTheFirstEnemyInRange() {
        val map = corridor()
        val gun = place(map, 1, 9, 1, gunEqid, LeaderType.OVERWATCH.value)
        val mover = place(map, 1, 1, 0, legEqid)

        val result = walk(map, mover, 7)

        // Read, not assumed: a commander also brings its class's signature trait, and an artillery
        // commander's reaches one hex further (`Leaders.getUnitClassLeader`).
        val range = AttackEligibility.getUnitAttackRange(gun)
        val stop = mover.getPos()!!
        assertTrue(HexGeometry.distance(1, 9, stop.row, stop.col) <= range, "stopped inside the gun's range")
        assertTrue(HexGeometry.distance(1, 9, 1, stop.col - 1) > range, "and on the first such hex")
        assertTrue(mover.isSurprised)
        assertSame(gun, map.surpriseTarget(mover, result))
        assertTrue(gun.hasAmbushedThisTurn)
    }

    /** "Each turn ... the first enemy unit": the second column through walks on. */
    @Test
    fun anOverwatchGunAmbushesOnceATurn() {
        val map = corridor()
        place(map, 1, 9, 1, gunEqid, LeaderType.OVERWATCH.value).hasAmbushedThisTurn = true
        val mover = place(map, 1, 1, 0, legEqid)

        val result = walk(map, mover, 6)

        assertFalse(mover.isSurprised)
        assertNull(result.ambusher)
    }

    @Test
    fun battlefieldIntelligenceCannotBeAmbushed() {
        val map = corridor()
        place(map, 1, 9, 1, gunEqid, LeaderType.OVERWATCH.value)
        val mover = place(map, 1, 1, 0, legEqid, LeaderType.BATTLEFIELD_INTELLIGENCE.value)

        val result = walk(map, mover, 6)

        assertNull(result.ambusher)
        assertEquals(1 to 6, mover.getPos()!!.let { it.row to it.col })
    }

    /** Fought at range: the gun fires from three hexes, the rifle company cannot answer. */
    @Test
    fun anAmbushAtRangeIsOneWayWhenTheMoverCannotReach() {
        val map = corridor()
        val gun = place(map, 1, 9, 1, gunEqid, LeaderType.OVERWATCH.value)
        val mover = place(map, 1, 1, 0, legEqid)
        val result = walk(map, mover, 7)

        val combat = map.attackUnit(mover, map.surpriseTarget(mover, result)!!, supportFire = false)

        assertTrue(combat.losses > 0, "the ambusher fires at its own reach")
        assertEquals(0, combat.kills, "a range-1 company cannot reach a gun that far off")
        assertEquals(10, gun.strength)
    }

    /** Camouflage Expert in a forest beside the corridor springs on the first hex of its zone. */
    @Test
    fun aCamouflageExpertAmbushesFromItsOwnGround() {
        val map = corridor()
        map.map!![0][5].terrain = TerrainType.FOREST.value
        hideScout(map)
        val scout = place(map, 0, 5, 1, legEqid, LeaderType.FOREST_CAMOUFLAGE.value)
        val mover = place(map, 1, 1, 0, legEqid)

        val result = walk(map, mover, 8)

        assertSame(scout, result.ambusher)
        assertTrue(mover.isSurprised)
    }

    /** ...and not from open ground, where it is an ordinary formation. */
    @Test
    fun aCamouflageExpertInTheOpenAmbushesNobody() {
        val map = corridor()
        map.map!![0][5].terrain = TerrainType.CLEAR.value
        hideScout(map)
        place(map, 0, 5, 1, legEqid, LeaderType.FOREST_CAMOUFLAGE.value)
        val mover = place(map, 1, 1, 0, legEqid)

        val result = walk(map, mover, 8)

        assertNull(result.ambusher)
    }

    /**
     * Unseen, as a camouflaged scout is -- its hex and the corridor hexes of its zone of control.
     * The overlay only prices a zone of control the mover can SEE (`MoveRangeCalculation`), so a
     * seen one would stop the range short and the walk would never be offered past it.
     */
    private fun hideScout(map: GameMap) {
        map.map!![0][5].setSpotted(0, false)
        (1 until cols)
            .filter { HexGeometry.distance(0, 5, 1, it) <= 1 }
            .forEach { map.map!![1][it].setSpotted(0, false) }
    }
}
