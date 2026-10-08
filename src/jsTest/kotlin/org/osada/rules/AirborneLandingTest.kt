package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.WeatherCondition
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.canUndoMove
import org.osada.model.disembarkUnit
import org.osada.model.moveUnit
import org.osada.scenario.Scenario
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OG's random airborne misdrop (`rules/AirborneLanding`): the author's published chances and the
 * owner's policies for a hidden enemy, no room to land and a drop over the sea (2026-10-08).
 */
class AirborneLandingTest : OgRulesTestHarness() {
    private val transportEqid = 942_001
    private val paratroopEqid = 942_002
    private val navalTransportEqid = 942_003

    /** Rolls the landing to fail and then picks the first candidate. */
    private val missThenFirst: (Int) -> Int = { bound -> if (bound == 100) 99 else 0 }

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            transportEqid,
            EquipmentData().apply {
                uclass = UnitClass.AIR_TRANSPORT.value
                movmethod = MovMethod.AIR.value
                movpoints = 8
            },
        )
        Equipment.putEquipment(
            navalTransportEqid,
            EquipmentData().apply {
                uclass = UnitClass.NAVAL_TRANSPORT.value
                movmethod = MovMethod.NAVAL.value
                movpoints = 8
            },
        )
        Equipment.putEquipment(
            paratroopEqid,
            EquipmentData().apply {
                name = "Fallschirmjager"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                movpoints = 3
                softatk = 6
                grounddef = 5
            },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun scenario(
        map: GameMap,
        misdrop: Boolean? = true,
        weather: Int = WeatherCondition.FAIR.value,
    ) {
        val scenario =
            Scenario(null).apply {
                this.map = map
                airborneMisdrop = misdrop
                atmosferic = weather
            }
        GameHolder.instance = GameHolder.instance ?: org.osada.Game()
        GameHolder.instance?.scenario = scenario
    }

    /** A paratroop battalion aboard an air transport over (3,3), already ordered off. */
    private fun dropping(map: GameMap): GameUnit =
        place(map, paratroopEqid, 3, 3, 0).apply {
            carrier = -transportEqid
            strength = 10
        }

    @Test
    fun chanceFollowsTheAuthorsTable() {
        val map = world()
        val unit = dropping(map)
        listOf(70, 40, 30, 20).forEachIndexed { weather, expected ->
            scenario(map, weather = weather)
            assertEquals(expected, AirborneLanding.chancePercent(unit), "weather $weather")
        }
        scenario(map, weather = WeatherCondition.SNOW.value)
        unit.experience = 300
        unit.leader = 1
        assertEquals(20 + 15 + 5, AirborneLanding.chancePercent(unit), "+5 per bar, +5 with a leader")
    }

    @Test
    fun onlyAnAuthoredAirDropAwayFromAnAirfieldRolls() {
        val map = world()
        val unit = dropping(map)
        val target = Cell(3, 4)

        scenario(map, misdrop = null)
        assertFalse(AirborneLanding.applies(map, unit, target), "unauthored")
        scenario(map)
        assertTrue(AirborneLanding.applies(map, unit, target))
        map.map!![3][4].terrain = TerrainType.AIRFIELD.value
        assertFalse(AirborneLanding.applies(map, unit, target), "an airfield landing is not a drop")
        map.map!![3][4].terrain = TerrainType.CLEAR.value
        unit.carrier = -navalTransportEqid
        assertFalse(AirborneLanding.applies(map, unit, target), "a ship is not a paradrop")
    }

    @Test
    fun aMissLandsOnAFreeNeighbourAndNeverOnAVisibleOrFriendlyUnit() {
        val map = world()
        scenario(map)
        val unit = dropping(map)
        val target = Cell(3, 4)
        val neighbours = HexGeometry.getAdjacent(target.row, target.col).filterNot { it.row == 3 && it.col == 3 }
        // Fill every neighbour but one: friends and a SPOTTED enemy are not landing places.
        neighbours.dropLast(1).forEachIndexed { i, cell ->
            val occupant = place(map, infantryEqid, cell.row, cell.col, i % 2)
            if (occupant.player?.side == 1) map.map!![cell.row][cell.col].setSpotted(0, true)
        }

        val outcome = AirborneLanding.resolve(map, unit, target, missThenFirst)

        assertEquals(AirborneLanding.Kind.SCATTERED, outcome.kind)
        assertEquals(neighbours.last().row, outcome.landing?.row)
        assertEquals(neighbours.last().col, outcome.landing?.col)
    }

    @Test
    fun scatteringOntoAHiddenEnemyMeansCombatFromTheTarget() {
        val map = world()
        scenario(map)
        val unit = dropping(map)
        val target = Cell(3, 4)
        HexGeometry.getAdjacent(target.row, target.col).forEach { cell ->
            if (cell.row != 3 || cell.col != 3) place(map, infantryEqid, cell.row, cell.col, 1)
        }

        val outcome = AirborneLanding.resolve(map, unit, target, missThenFirst)

        assertEquals(AirborneLanding.Kind.HIDDEN_ENEMY, outcome.kind)
        assertEquals(target.row, outcome.landing?.row, "comes down on the target, beside the enemy")
        assertNotNull(outcome.enemyCell)
    }

    @Test
    fun withNoRoomBesideTheTargetItDriftsAndIsBadlyHurt() {
        val map = world()
        scenario(map)
        val unit = dropping(map)
        val target = Cell(4, 4)
        HexGeometry.getAdjacent(4, 4).forEach { map.map!![it.row][it.col].terrain = TerrainType.OCEAN.value }

        val outcome = AirborneLanding.resolve(map, unit, target, missThenFirst)

        assertEquals(AirborneLanding.Kind.DRIFTED, outcome.kind)
        val landing = outcome.landing!!
        assertEquals(2, HexGeometry.distance(4, 4, landing.row, landing.col))
        assertEquals(5, outcome.losses, "half of 10")
    }

    @Test
    fun overTheSeaTheFormationIsLost() {
        val map = world()
        scenario(map)
        for (r in 0 until map.rows) {
            for (c in 0 until map.cols) map.map!![r][c].terrain = TerrainType.OCEAN.value
        }
        val unit = dropping(map)

        val outcome = AirborneLanding.resolve(map, unit, Cell(3, 4), missThenFirst)

        assertEquals(AirborneLanding.Kind.LOST, outcome.kind)
        assertNull(outcome.landing)
    }

    /** End to end through `GameMap.moveUnit`: a drop that rolled is final and lands on the roll. */
    @Test
    fun aRealDropRollsAndCannotBeUndone() {
        val map = world()
        scenario(map)
        org.osada.rules.GameRandomSource
            .start(1L)
        val unit =
            place(map, paratroopEqid, 3, 3, 0).apply {
                carrier = transportEqid
                experience = 600
            }
        assertTrue(map.disembarkUnit(unit))

        val result = map.moveUnit(unit, 3, 4)

        assertNotNull(result.airborneLanding, "the drop rolled")
        assertEquals(AirborneLanding.Kind.ON_TARGET, result.airborneLanding!!.kind, "70 + 30 = 100 %")
        assertEquals(3, unit.getPos()?.row)
        assertEquals(4, unit.getPos()?.col)
        assertFalse(map.canUndoMove(unit), "undo would re-roll the drop")
    }
}
