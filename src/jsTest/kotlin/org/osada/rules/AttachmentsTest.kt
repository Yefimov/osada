package org.osada.rules

import org.osada.UnitClass
import org.osada.hero.CoreFormation
import org.osada.hero.FormationId
import org.osada.hero.HeroCampaign
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.resetEquipment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Attachments (DEFERRED.md §1.4, `docs/design/attachments.md`), Tier 1: pure stat deltas over
 * existing read sites. A per-unit query layer over `CoreFormation.attachmentIds` -- never a
 * mutation of the shared `EquipmentData` (§3.1's trap), which
 * [aBonusDoesNotLeakToOtherUnitsOfTheSameEquipment] exists specifically to catch.
 */
class AttachmentsTest {
    private val eqid = 501

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            eqid,
            EquipmentData().apply {
                uclass = UnitClass.RECON.value
                spotrange = 3
                movpoints = 6
                ammo = 8
                cost = 20
            },
        )
    }

    @AfterTest
    fun cleanup() {
        HeroCampaign.reset()
        EfileConfig.resetForTest()
    }

    private fun coreUnit(formationId: String): GameUnit =
        GameUnit(eqid).apply {
            this.formationId = formationId
            player = Player().apply { id = 0 }
        }

    private fun formation(id: String) =
        CoreFormation(
            id = FormationId(id),
            ownerId = 0,
            country = 19,
            displayName = "Test Formation $id",
            currentEquipmentId = eqid,
            unitClass = UnitClass.RECON.value,
        )

    /** LXF-shaped config: slot 1 Recon (+2 spot, -1 movement penalty), slot 3 Bridging disabled,
     *  slot 5 Support (+3 ammo, -2 ammo penalty, zero cost columns to force the global default). */
    private fun lxfShapedConfig() =
        EfileConfig.AttachmentConfig(
            armyCost = true,
            minFuel = 0,
            minMove = 0,
            factorDefaultPct = 10,
            minCostDefault = 20,
            slots =
                mapOf(
                    Attachments.SLOT_RECON to
                        EfileConfig.AttachmentSlot(
                            "Recon",
                            disabled = false,
                            bonus = 2,
                            penalty = -1,
                            minCost = 30,
                            factCost = 5,
                            penaltyType = 1,
                        ),
                    Attachments.SLOT_BRIDGING to
                        EfileConfig.AttachmentSlot(
                            "Bridging",
                            disabled = true,
                            bonus = 0,
                            penalty = 0,
                            minCost = 0,
                            factCost = 0,
                            penaltyType = 0,
                        ),
                    Attachments.SLOT_SUPPORT_AMMO to
                        EfileConfig.AttachmentSlot(
                            "Support",
                            disabled = false,
                            bonus = 3,
                            penalty = -2,
                            minCost = 0,
                            factCost = 0,
                            penaltyType = 3,
                        ),
                ),
        )

    @Test
    fun aBonusDoesNotLeakToOtherUnitsOfTheSameEquipment() {
        EfileConfig.setForTest(attachmentConfigValue = lxfShapedConfig())
        HeroCampaign.roster().putFormation(formation("F-A"))
        HeroCampaign.roster().putFormation(formation("F-B"))
        val unitA = coreUnit("F-A")
        val unitB = coreUnit("F-B")

        assertTrue(HeroCampaign.purchaseAttachment(unitA, Attachments.SLOT_RECON))

        assertEquals(5, MovementRules.getUnitSpotRange(unitA), "unit A bought Recon: base 3 + 2")
        assertEquals(3, MovementRules.getUnitSpotRange(unitB), "unit B must see none of unit A's bonus")
    }

    @Test
    fun penaltyIsAppliedAlongsideTheBonus() {
        EfileConfig.setForTest(attachmentConfigValue = lxfShapedConfig())
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_RECON)

        assertEquals(2, Attachments.bonus(unit, Attachments.SLOT_RECON))
        assertEquals(-1, Attachments.movementPenalty(unit), "Recon's malus-type-1 penalty must also apply")
    }

    @Test
    fun atMostTwoAttachmentsPerUnit() {
        val base = lxfShapedConfig()
        val slots =
            mapOf(
                Attachments.SLOT_RECON to base.slots.getValue(Attachments.SLOT_RECON),
                Attachments.SLOT_SUPPORT_AMMO to base.slots.getValue(Attachments.SLOT_SUPPORT_AMMO),
                4 to
                    EfileConfig.AttachmentSlot(
                        "AntiTank",
                        disabled = false,
                        bonus = 3,
                        penalty = -1,
                        minCost = 30,
                        factCost = 25,
                        penaltyType = 1,
                    ),
            )
        val config = base.copy(slots = slots)
        EfileConfig.setForTest(attachmentConfigValue = config)
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_RECON))
        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_SUPPORT_AMMO))
        assertFalse(HeroCampaign.purchaseAttachment(unit, 4), "a third attachment must be refused")
        assertEquals(
            2,
            HeroCampaign
                .roster()
                .formation(FormationId("F-A"))
                ?.attachmentIds
                ?.size,
        )
    }

    @Test
    fun disabledSlotIsNotOffered() {
        EfileConfig.setForTest(attachmentConfigValue = lxfShapedConfig())
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        val available = Attachments.availableSlots(unit).map { it.first }

        assertFalse(Attachments.SLOT_BRIDGING in available, "LXF disables Bridging")
        assertTrue(Attachments.SLOT_RECON in available)
    }

    @Test
    fun efileWithoutAttachOnOffersNothing() {
        // KAISER-shaped case: no equip.cfg at all -- attach_on absent means off.
        EfileConfig.setForTest()
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        assertEquals(emptyList(), Attachments.availableSlots(unit))
        assertFalse(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_RECON))
    }

    @Test
    fun costUsesGlobalDefaultsWhenSlotColumnsAreZero() {
        // GCE-shaped: Support's own minCost/factCost are 0 (omitted), so the global
        // attach_mincost=20 / attach_factor=10 must be used instead of the hardcoded 30/25.
        EfileConfig.setForTest(attachmentConfigValue = lxfShapedConfig())
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        val cost = assertNotNull(Attachments.cost(unit, Attachments.SLOT_SUPPORT_AMMO))
        // unitCostPerStrength = 20 * 12 / 10 = 24; cost = 20 + (24 * 10 * 10) / 100 = 44.
        assertEquals(44, cost)
    }

    @Test
    fun formationlessUnitHasNoAttachmentsToOffer() {
        EfileConfig.setForTest(attachmentConfigValue = lxfShapedConfig())
        val unit = GameUnit(eqid).apply { player = Player().apply { id = 0 } }

        assertEquals(emptyList(), Attachments.availableSlots(unit))
        assertFalse(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_RECON))
    }
}
