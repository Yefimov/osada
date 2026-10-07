package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.ATTR_MASK_COMBAT_SUPPORT
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Combat Support learns from what it supports (`rules/CombatSupportExperience`, 2026-10-07), and a
 * staff inside a bunker lends its bars to the bunker itself.
 */
class CombatSupportExperienceTest : OgRulesTestHarness() {
    private val hqEqid = 985
    private val bunkerEqid = 986

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            hqEqid,
            EquipmentData().apply {
                eqid = hqEqid
                name = "Headquarters"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                attr = ATTR_MASK_COMBAT_SUPPORT
            },
        )
        Equipment.putEquipment(
            bunkerEqid,
            EquipmentData().apply {
                eqid = bunkerEqid
                name = "Bunker"
                uclass = UnitClass.FORTIFICATION.value
                target = UnitType.HARD.value
                hangarCap = 2
            },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun liveWorld(): GameMap {
        val map = world()
        GameHolder.instance = holderFor(map)
        return map
    }

    @Test
    fun theShareIsAQuarterRoundedHalfUp() {
        assertEquals(3, CombatSupportExperience.share(10))
        assertEquals(1, CombatSupportExperience.share(2))
        assertEquals(0, CombatSupportExperience.share(1))
        assertEquals(0, CombatSupportExperience.share(0))
    }

    @Test
    fun anAdjacentHeadquartersGainsAQuarterOfWhatItsNeighbourGains() {
        val map = liveWorld()
        val hq = place(map, hqEqid, 2, 2, side = 0).apply { experience = 100 }
        val infantry = place(map, infantryEqid, 2, 3, side = 0)
        val far = place(map, hqEqid, 6, 6, side = 0).apply { experience = 100 }

        CombatSupportExperience.award(map.units.toList(), infantry, 10)

        assertEquals(103, hq.experience)
        assertEquals(100, far.experience, "only the supporters lending bars to it")
    }

    @Test
    fun aSupporterStopsAtTheExperienceCap() {
        val map = liveWorld()
        val hq = place(map, hqEqid, 2, 2, side = 0).apply { experience = UnitExperience.cap() - 1 }
        val infantry = place(map, infantryEqid, 2, 3, side = 0)

        CombatSupportExperience.award(map.units.toList(), infantry, 40)

        assertEquals(UnitExperience.cap(), hq.experience)
    }

    @Test
    fun aStaffInsideABunkerLendsItsBarsToTheBunker() {
        EfileConfig.setForTest(mapOf("ground_carrier" to 3))
        val map = liveWorld()
        val bunker = place(map, bunkerEqid, 3, 3, side = 0)
        val hq = place(map, hqEqid, 3, 4, side = 0).apply { experience = 200 }
        assertTrue(CarrierHangars.board(map, hq, bunker))

        assertEquals(2, UnitCapabilities.combatSupportBars(map.units.toList(), bunker))
        assertEquals(2, UnitCapabilities.combatSupportBars(map.units.toList(), place(map, infantryEqid, 3, 2, 0)))
    }
}
