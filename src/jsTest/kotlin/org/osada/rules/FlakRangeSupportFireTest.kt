package org.osada.rules

import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `flak_range` (`equip.cfg`) governs air-defense support fire flat, regardless of the AA unit's own
 * `gunrange` (DEFERRED.md §1.1, `docs/design/aa-interception.md` §1.1/§2). Ground support fire
 * (artillery) is untouched -- only covered here for the "no config present" baseline.
 */
class FlakRangeSupportFireTest {
    private class Scene(
        val map: GameMap,
        val plane: GameUnit,
        val ground: GameUnit,
        val aa: GameUnit,
    )

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            1,
            EquipmentData().apply {
                uclass = UnitClass.FIGHTER.value
                movmethod = MovMethod.AIR.value
                target = UnitType.AIR.value
                airatk = 4
                grounddef = 4
            },
        )
        Equipment.putEquipment(
            2,
            EquipmentData().apply {
                uclass = UnitClass.AIR_DEFENCE.value
                movmethod = MovMethod.WHEELED.value
                airatk = 6
                gunrange = 1
                ammo = 10
            },
        )
        Equipment.putEquipment(
            3,
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                target = UnitType.SOFT.value
            },
        )
    }

    @AfterTest
    fun cleanup() {
        EfileConfig.resetForTest()
    }

    @Test
    fun absentEquipCfgKeepsFlakSupportFireAtRangeOne() {
        // KAISER-shaped case: no equip.cfg at all -- flak_range defaults to 1.
        EfileConfig.setForTest()
        val scene = sceneWithAaAtDistance(4)

        val supporters = CombatResolver.getSupportFireUnits(scene.map.getUnits().toList(), scene.plane, scene.ground)

        assertEquals(emptyList(), supporters, "an AA unit 4 hexes away must not fire at the default range of 1")
    }

    @Test
    fun equipCfgFlakRangeExtendsAirDefenceSupportFireBeyondItsOwnGunrange() {
        // LXF-shaped case: flak_range=4, while this AA's own gunrange is 1.
        EfileConfig.setForTest(mapOf("flak_range" to 4))
        val scene = sceneWithAaAtDistance(4)

        val supporters = CombatResolver.getSupportFireUnits(scene.map.getUnits().toList(), scene.plane, scene.ground)

        assertTrue(scene.aa in supporters, "flak_range=4 must reach an AA unit 4 hexes from the attacker")
    }

    /** Builds a plane attacking a ground unit (so [CombatResolver.getSupportFireUnits]'s own
     *  adjacency gate passes), with a friendly AA unit [distance] hexes from the PLANE -- support
     *  fire range is measured from the attacker's position, not the defender's. */
    private fun sceneWithAaAtDistance(distance: Int): Scene {
        val map =
            GameMap().apply {
                rows = 10
                cols = 10
                allocMap()
            }
        val attackerSidePlayer =
            Player().apply {
                id = 0
                side = 0
                country = 0
            }
        val defenderSidePlayer =
            Player().apply {
                id = 1
                side = 1
                country = 1
            }
        map.addPlayer(attackerSidePlayer)
        map.addPlayer(defenderSidePlayer)

        val plane =
            GameUnit(1).apply {
                owner = attackerSidePlayer.id
                player = attackerSidePlayer
                strength = 10
                ammo = 10
            }
        val ground =
            GameUnit(3).apply {
                owner = defenderSidePlayer.id
                player = defenderSidePlayer
                strength = 10
                ammo = 10
            }
        val aa =
            GameUnit(2).apply {
                owner = defenderSidePlayer.id
                player = defenderSidePlayer
                strength = 10
                ammo = 10
            }

        map.map
            ?.get(5)
            ?.get(5)
            ?.setUnit(plane)
        map.map
            ?.get(5)
            ?.get(6)
            ?.setUnit(ground)
        map.map
            ?.get(5)
            ?.get(5 + distance)
            ?.setUnit(aa)
        map.addUnit(plane)
        map.addUnit(ground)
        map.addUnit(aa)
        return Scene(map, plane, ground, aa)
    }
}
