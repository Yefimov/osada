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
 * Who answers an aircraft's attack, and from how far -- OG's Air-Interception and Air Defense
 * procedures (`rules/AirDefenceSupport`, 2026-09-26). An AD battery answers at its own range of fire,
 * a FlaK at `flak_range`, a fighter only for the unit it stands beside. Until that date every kind of
 * air defence answered at one flat `flak_range`, which is what the first two tests of this file used
 * to assert for an AD unit.
 *
 * Geometry: everything sits on row 5, where hex distance is the column difference. The plane attacks
 * from (5,5) into the ground unit at (5,6).
 */
class FlakRangeSupportFireTest {
    private val fighterEqid = 1
    private val adEqid = 2
    private val infantryEqid = 3
    private val flakEqid = 4
    private val longAdEqid = 5

    private class Scene(
        val map: GameMap,
        val plane: GameUnit,
        val ground: GameUnit,
    )

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                uclass = UnitClass.FIGHTER.value
                movmethod = MovMethod.AIR.value
                target = UnitType.AIR.value
                airatk = 4
                grounddef = 4
                ammo = 10
            },
        )
        Equipment.putEquipment(adEqid, airDefence(UnitClass.AIR_DEFENCE.value, gun = 1))
        Equipment.putEquipment(longAdEqid, airDefence(UnitClass.AIR_DEFENCE.value, gun = 3))
        // A real FlaK record engages aircraft through OG's `CAN Air Atk` bit, not by class
        // (`EquipmentCombatEligibility.isDedicatedAntiAir` leaves the Flak class out).
        Equipment.putEquipment(
            flakEqid,
            airDefence(UnitClass.FLAK.value, gun = 1).apply { attr = ATTR_MASK_CAN_AIR_ATK },
        )
        Equipment.putEquipment(
            infantryEqid,
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                target = UnitType.SOFT.value
            },
        )
    }

    private fun airDefence(
        cls: Int,
        gun: Int,
    ) = EquipmentData().apply {
        uclass = cls
        movmethod = MovMethod.WHEELED.value
        airatk = 6
        gunrange = gun
        ammo = 10
    }

    @AfterTest
    fun cleanup() {
        EfileConfig.resetForTest()
    }

    @Test
    fun aFlakAnswersAtFlakRange() {
        EfileConfig.setForTest(mapOf("flak_range" to 4))
        val scene = scene()
        val flak = place(scene, flakEqid, 9, side = 1)

        assertTrue(flak in supporters(scene), "flak_range=4 carries a FlaK's air defence four hexes")
    }

    @Test
    fun withoutEquipCfgAFlakAnswersOnlyNextToThePlane() {
        EfileConfig.setForTest()
        val scene = scene()
        val far = place(scene, flakEqid, 7, side = 1)

        assertFalse(far in supporters(scene), "flak_range defaults to 1")
    }

    /** The case the flat rule got wrong in the other direction: ATOMIC and BASEKORP set
     *  `flak_range=1` and were cutting 3-6 hex AD batteries down to one. */
    @Test
    fun anAirDefenceBatteryAnswersAtItsOwnRangeOfFire() {
        EfileConfig.setForTest(mapOf("flak_range" to 1))
        val scene = scene()
        val battery = place(scene, longAdEqid, 8, side = 1)

        assertTrue(battery in supporters(scene), "gunrange 3 reaches the plane three hexes away")
    }

    /** ...and LXF's `flak_range=4` no longer lends a range-1 AD battery four hexes. */
    @Test
    fun flakRangeDoesNotStretchAnAirDefenceBattery() {
        EfileConfig.setForTest(mapOf("flak_range" to 4))
        val scene = scene()
        val battery = place(scene, adEqid, 9, side = 1)

        assertFalse(battery in supporters(scene), "an AD battery's reach is its own gunrange, not flak_range")
    }

    @Test
    fun aFighterCoversOnlyTheUnitItStandsBeside() {
        EfileConfig.setForTest(mapOf("flak_range" to 4))
        val scene = scene()
        val beside = place(scene, fighterEqid, 7, side = 1) // 1 from the defender, 2 from the plane
        val farther = place(scene, fighterEqid, 8, side = 1) // 3 from the plane: inside the old disc

        val answering = supporters(scene)

        assertTrue(beside in answering)
        assertFalse(farther in answering, "a fighter two hexes from the defender covers nothing here")
    }

    @Test
    fun aFighterInterceptsOnceATurnUnlessSkilled() {
        EfileConfig.setForTest()
        val scene = scene()
        val spent = place(scene, fighterEqid, 7, side = 1).apply { hasSupportedThisTurn = true }
        assertFalse(spent in supporters(scene), "one interception per enemy turn")

        spent.leader = LeaderType.SKILLED_INTERCEPTOR.value
        assertTrue(spent in supporters(scene), "Skilled Interceptor may intercept again")
    }

    @Test
    fun groundAirDefenceIgnoresAnAirToAirAttack() {
        EfileConfig.setForTest(mapOf("flak_range" to 4))
        val scene = scene(defenderEqid = fighterEqid)
        val flak = place(scene, flakEqid, 7, side = 1)

        assertFalse(flak in supporters(scene), "Air Defense answers attacks on GROUND units only")
    }

    /** `flak_ex2`: a FlaK beside the defender answers a plane beside the defender from any side --
     *  here two hexes from the plane, outside a `flak_range` of 1. */
    @Test
    fun flakEx2LetsAFlakGuardItsNeighbour() {
        EfileConfig.setForTest(mapOf("flak_range" to 1))
        val scene = scene()
        val flak = place(scene, flakEqid, 7, side = 1)
        assertFalse(flak in supporters(scene), "without flak_ex2 the FlaK is out of reach")

        EfileConfig.setForTest(mapOf("flak_range" to 1, "flak_ex2" to 1))
        assertTrue(flak in supporters(scene), "flak_ex2 lets it answer for the neighbour it guards")
    }

    private fun supporters(scene: Scene): List<GameUnit> =
        CombatResolver.getSupportFireUnits(scene.map.getUnits().toList(), scene.plane, scene.ground)

    private fun scene(defenderEqid: Int = infantryEqid): Scene {
        val map =
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
        val scene = Scene(map, GameUnit(fighterEqid), GameUnit(defenderEqid))
        put(scene, scene.plane, 5, side = 0)
        put(scene, scene.ground, 6, side = 1)
        return scene
    }

    private fun place(
        scene: Scene,
        eqid: Int,
        col: Int,
        side: Int,
    ): GameUnit = GameUnit(eqid).also { put(scene, it, col, side) }

    private fun put(
        scene: Scene,
        unit: GameUnit,
        col: Int,
        side: Int,
    ) {
        unit.owner = side
        unit.player = scene.map.players[side]
        unit.strength = 10
        unit.ammo = 10
        scene.map.map!![5][col].setUnit(unit)
        scene.map.addUnit(unit)
    }
}
