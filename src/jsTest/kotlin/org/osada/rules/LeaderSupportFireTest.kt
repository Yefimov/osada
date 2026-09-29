package org.osada.rules

import org.osada.LeaderType
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.ATTR_MASK_CAN_AIR_ATK
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.getUnits
import org.osada.model.resetEquipment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OG's two support-fire leaders ([LeaderSupportFire]): Extended Front (*"Gives support fire to
 * adjacent friend units"*) and Flak Support (*"Support Fire and Multiple intercept"*).
 *
 * Geometry: everything sits on row 5, where hex distance is the column difference. The enemy tank
 * attacks from (5,5) into our infantry at (5,6).
 */
class LeaderSupportFireTest {
    private val tankEqid = 1
    private val infantryEqid = 2
    private val flakEqid = 3
    private val planeEqid = 4

    private lateinit var map: GameMap
    private lateinit var attacker: GameUnit
    private lateinit var defender: GameUnit

    @BeforeTest
    fun setup() {
        EfileConfig.setForTest()
        Equipment.resetEquipment()
        putEquipment()
        map =
            GameMap().apply {
                rows = 10
                cols = 10
                allocMap()
            }
        for (id in 0..1) {
            map.addPlayer(
                Player().apply {
                    this.id = id
                    side = id
                    country = id
                },
            )
        }
        attacker = put(tankEqid, 5, side = 0)
        defender = put(infantryEqid, 6, side = 1)
    }

    private fun putEquipment() {
        Equipment.putEquipment(
            tankEqid,
            EquipmentData().apply {
                uclass = UnitClass.TANK.value
                movmethod = MovMethod.TRACKED.value
                target = UnitType.HARD.value
                hardatk = 8
                softatk = 6
                gunrange = 2
                ammo = 10
            },
        )
        Equipment.putEquipment(
            infantryEqid,
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                target = UnitType.SOFT.value
                softatk = 6
                hardatk = 2
                ammo = 10
            },
        )
        Equipment.putEquipment(
            flakEqid,
            EquipmentData().apply {
                uclass = UnitClass.FLAK.value
                movmethod = MovMethod.TOWED.value
                target = UnitType.SOFT.value
                airatk = 8
                hardatk = 4
                softatk = 6
                gunrange = 2
                ammo = 10
                attr = ATTR_MASK_CAN_AIR_ATK
            },
        )
        Equipment.putEquipment(
            planeEqid,
            EquipmentData().apply {
                uclass = UnitClass.TACTICAL_BOMBER.value
                movmethod = MovMethod.AIR.value
                target = UnitType.AIR.value
                airdef = 4
                ammo = 10
            },
        )
    }

    @AfterTest
    fun cleanup() {
        EfileConfig.resetForTest()
    }

    @Test
    fun aTankDoesNotSupportByItself() {
        val tank = put(tankEqid, 7, side = 1)
        assertFalse(tank in supporters())
    }

    @Test
    fun anExtendedFrontTankSupportsTheNeighbourItStandsBeside() {
        val tank = put(tankEqid, 7, side = 1, leader = LeaderType.EXTENDED_FRONT)
        assertTrue(tank in supporters(), "beside the defender, and its gun reaches the attacker")
    }

    @Test
    fun extendedFrontReachesOnlyAnAdjacentFriend() {
        Equipment.getEquipment(tankEqid)!!.gunrange = 3
        val tank = put(tankEqid, 8, side = 1, leader = LeaderType.EXTENDED_FRONT)
        assertFalse(tank in supporters(), "two hexes from the defender is not 'adjacent friend units'")
    }

    @Test
    fun aFlakSupportBatteryAnswersAGroundAttack() {
        val plain = put(flakEqid, 7, side = 1)
        assertFalse(plain in supporters(), "a FlaK has no ground support fire of its own")

        plain.leader = LeaderType.MARKSMAN.value // any leader: Flak Support is the Flak class attribute
        assertTrue(plain in supporters(), "a led FlaK gets OG's 'FlaK Support' class attribute")
    }

    @Test
    fun flakSupportLiftsTheOneInterceptionBudget() {
        val plane = put(planeEqid, 3, side = 0, row = 2)
        val plain = put(flakEqid, 3, side = 1, row = 3)
        val led = put(flakEqid, 4, side = 1, row = 3, leader = LeaderType.MARKSMAN)

        AAInterception.applyInterception(map, plane, listOf(plain, led))

        assertTrue(plain.hasInterceptedMovingAirThisTurn, "an ordinary FlaK has spent its interception")
        assertFalse(led.hasInterceptedMovingAirThisTurn, "Flak Support: 'Multiple intercept'")
    }

    private fun supporters(): List<GameUnit> =
        CombatResolver.getSupportFireUnits(map.getUnits().toList(), attacker, defender)

    private fun put(
        eqid: Int,
        col: Int,
        side: Int,
        leader: LeaderType? = null,
        row: Int = 5,
    ): GameUnit =
        GameUnit(eqid).also { unit ->
            unit.id = row * 100 + col
            unit.owner = side
            unit.player = map.players[side]
            unit.strength = 10
            unit.ammo = 10
            unit.leader = leader?.value ?: -1
            map.map!![row][col].setUnit(unit)
            map.addUnit(unit)
        }
}
