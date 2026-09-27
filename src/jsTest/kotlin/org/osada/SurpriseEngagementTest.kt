package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.allocMap
import org.osada.model.attackUnit
import org.osada.model.getPlayer
import org.osada.model.moveUnit
import org.osada.model.resetEquipment
import org.osada.model.setMoveRange
import org.osada.model.surpriseTarget
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A move that walks into an unspotted enemy is halted AND fights it, as a surprised attacker
 * (`GameMap.surpriseTarget`). Panzer Marshal ran that combat from its move-finish handler and the
 * port had dropped the call; OG lists it among the ways a Normal Combat starts.
 *
 * An ordinary formation reaches the hidden unit's hex: an UNSEEN zone of control does not stop a
 * move (OG's tutorial: an ordinary unit moved into unspotted ground risks that *"the hex could be
 * occupied by an enemy unit that would ambush our unit"*). A `Partizan` walks up too, but OG says
 * it "cannot be surprised" ([partisansCannotBeSurprised]).
 *
 * Same corridor as `ZoneOfControlMovementTest`: row 1 clear, rows 0 and 2 impassable.
 */
class SurpriseEngagementTest {
    private val partisanEqid = 410
    private val legEqid = 411
    private val columnEqid = 412
    private val rows = 3
    private val cols = 9

    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        movTable = movTableDry
        Equipment.resetEquipment()
        Equipment.putEquipment(partisanEqid, infantry(attrEx = 1024)) // Partizan
        Equipment.putEquipment(legEqid, infantry(attrEx = 0))
        // A formation that can hurt nothing -- what a column riding its trucks fights with.
        Equipment.putEquipment(columnEqid, infantry(attrEx = 0).apply { softatk = 0 })
    }

    private fun infantry(attrEx: Int) =
        EquipmentData().apply {
            uclass = UnitClass.INFANTRY.value
            movmethod = MovMethod.LEG.value
            movpoints = 6
            fuel = 0
            ammo = 6
            spotrange = 1
            softatk = 6
            grounddef = 4
            this.attrEx = attrEx
        }

    /** The corridor with the hidden enemy on (1,4); side 0 can see nothing from (1,3) on. */
    private fun corridorWithHiddenEnemy(): Pair<GameMap, GameUnit> {
        val map =
            GameMap().apply {
                rows = this@SurpriseEngagementTest.rows
                cols = this@SurpriseEngagementTest.cols
                allocMap()
            }
        for (playerId in 0..1) {
            map.addPlayer(
                Player().apply {
                    id = playerId
                    side = playerId
                    country = 0
                },
            )
        }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val hex = map.map!![r][c]
                hex.terrain = if (r == 1) TerrainType.CLEAR.value else TerrainType.IMPASSABLE_RIVER.value
                hex.setSpotted(0, c < 3)
            }
        }
        val enemy = place(map, 1, 4, 1, legEqid)
        return map to enemy
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
        return unit
    }

    @Test
    fun walkingIntoAHiddenEnemyNamesItAsTheSurpriseTarget() {
        val (map, enemy) = corridorWithHiddenEnemy()
        val mover = place(map, 1, 2, 0, legEqid, leader = LeaderType.SUPERIOR_MANEUVER.value)

        map.setMoveRange(mover)
        val result = map.moveUnit(mover, 1, 6)

        assertEquals(1 to 3, mover.getPos()!!.let { it.row to it.col }, "halted short of the occupied hex")
        assertTrue(mover.isSurprised)
        assertEquals(1 to 4, result.surpriseCell.first().let { it.row to it.col })
        assertSame(enemy, map.surpriseTarget(mover, result), "the ambusher is the unit it must now fight")
    }

    /** OG: a surprise is Rugged, and a Rugged combat is not aborted for a zero attack -- so a
     *  formation that cannot fire is still fired on, answers with nothing and spends no shot. */
    @Test
    fun aMoverThatCannotFireIsStillFiredOn() {
        val (map, enemy) = corridorWithHiddenEnemy()
        val mover = place(map, 1, 2, 0, columnEqid, leader = LeaderType.SUPERIOR_MANEUVER.value)

        map.setMoveRange(mover)
        val result = map.moveUnit(mover, 1, 6)
        val target = map.surpriseTarget(mover, result)
        assertSame(enemy, target, "the ambush happens even though the column cannot shoot")

        val combat = map.attackUnit(mover, target!!, supportFire = false)

        assertEquals(0, combat.kills, "nothing to answer with")
        assertTrue(combat.losses > 0, "the hidden unit gets its shot")
        assertEquals(6, mover.ammo, "no shot was fired, so no ammunition was spent")
        assertTrue(mover.hasFired, "but the turn's attack is gone")
    }

    /** `Battlefield Intelligence` -- "the unit cannot be surprised" -- still stops at the enemy but
     *  is not forced into the fight. */
    @Test
    fun battlefieldIntelligenceIsNeverForcedIntoTheFight() {
        val (map, _) = corridorWithHiddenEnemy()
        val mover = place(map, 1, 2, 0, legEqid, leader = LeaderType.BATTLEFIELD_INTELLIGENCE.value)

        map.setMoveRange(mover)
        val result = map.moveUnit(mover, 1, 6)

        assertFalse(mover.isSurprised)
        assertNull(map.surpriseTarget(mover, result))
    }

    /** OG: Partizan is *"not stopped by adjacent enemies. Same than Battlefield Intelligence"*, and
     *  of both *"cannot be surprised"*. So it walks on up to the hidden unit, stops there, and fights
     *  nothing it did not choose. */
    @Test
    fun partisansCannotBeSurprised() {
        val (map, _) = corridorWithHiddenEnemy()
        val mover = place(map, 1, 2, 0, partisanEqid)

        map.setMoveRange(mover)
        val result = map.moveUnit(mover, 1, 6)

        assertEquals(1 to 3, mover.getPos()!!.let { it.row to it.col }, "walked on to the hidden unit")
        assertFalse(mover.isSurprised)
        assertNull(map.surpriseTarget(mover, result))
    }

    /**
     * No commander, no special: an ordinary rifle company meets the hidden unit's zone of control on
     * the hex before it and stops there on CONTACT, without a fight (OG manual 6.13.1, restored
     * 2026-09-27). Surprise is for the movers that zone does not stop, and for a start next to it.
     */
    @Test
    fun anOrdinaryFormationStopsOnContactBeforeAHiddenEnemy() {
        val (map, _) = corridorWithHiddenEnemy()
        val mover = place(map, 1, 2, 0, legEqid)

        map.setMoveRange(mover)
        val result = map.moveUnit(mover, 1, 6)

        assertEquals(1 to 3, mover.getPos()!!.let { it.row to it.col }, "halted on the hex before it")
        assertTrue(result.stoppedByContact)
        assertFalse(mover.isSurprised)
        assertNull(map.surpriseTarget(mover, result))
    }

    /** Starting next to a hidden enemy and moving through its hex is still a surprise: there was no
     *  zone left to cross before the occupied hex. */
    @Test
    fun aMoveStartingBesideAHiddenEnemyIsStillSurprised() {
        val (map, enemy) = corridorWithHiddenEnemy()
        val mover = place(map, 1, 3, 0, legEqid)

        map.setMoveRange(mover)
        val result = map.moveUnit(mover, 1, 6)

        assertEquals(1 to 3, mover.getPos()!!.let { it.row to it.col }, "it never left its hex")
        assertTrue(mover.isSurprised)
        assertSame(enemy, map.surpriseTarget(mover, result))
    }
}
