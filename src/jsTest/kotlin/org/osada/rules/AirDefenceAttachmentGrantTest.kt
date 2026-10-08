package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Air Defense attachment lets a formation with no air attack engage aircraft, on OG's
 * `CAN Air Atk` terms (owner, 2026-10-08): only when it has not moved, with the bonus as its attack.
 */
class AirDefenceAttachmentGrantTest : OgRulesTestHarness() {
    private val fighterEqid = 943_001

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                uclass = UnitClass.FIGHTER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                movpoints = 8
                airatk = 8
                airdef = 6
                ammo = 6
            },
        )
        EfileConfig.setForTest(
            attachmentConfigValue =
                EfileConfig.AttachmentConfig(
                    armyCost = true,
                    minFuel = 0,
                    minMove = 0,
                    factorDefaultPct = 25,
                    minCostDefault = 30,
                    slots =
                        mapOf(
                            Attachments.SLOT_AIR_DEFENSE to
                                EfileConfig.AttachmentSlot(
                                    name = "Air Defense",
                                    disabled = false,
                                    bonus = 2,
                                    penalty = -1,
                                    minCost = 30,
                                    factCost = 25,
                                    penaltyType = 2,
                                ),
                        ),
                ),
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    @Test
    fun aFittedRifleBattalionMayShootAtAircraftOnlyBeforeMoving() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val rifles = place(map, infantryEqid, 3, 3, 0)
        val fighter = place(map, fighterEqid, 3, 4, 1)

        assertFalse(AttackEligibility.canInitiateAttack(rifles, fighter), "no air attack without the attachment")

        rifles.authoredAttachmentIds = listOf(Attachments.SLOT_AIR_DEFENSE)
        assertEquals(2, AirDefenceAttachmentGrant.effectiveAirAttack(rifles))
        assertTrue(AttackEligibility.canInitiateAttack(rifles, fighter))

        rifles.hasMoved = true
        assertFalse(AttackEligibility.canInitiateAttack(rifles, fighter), "CAN Air Atk terms: not after moving")
        assertTrue(AttackEligibility.canFire(rifles, fighter), "it still returns fire when attacked")
    }
}
