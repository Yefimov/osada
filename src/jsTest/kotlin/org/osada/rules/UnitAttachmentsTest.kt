package org.osada.rules

import org.osada.UnitClass
import org.osada.enterCampaignForTests
import org.osada.hero.CoreFormation
import org.osada.hero.FormationId
import org.osada.hero.HeroCampaign
import org.osada.leaveCampaignForTests
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.purchaseAttachment
import org.osada.model.resetEquipment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [UnitAttachments]: who buys onto the unit itself, and that a roster formation still does not. */
class UnitAttachmentsTest {
    private val eqid = 502

    private val recon =
        EfileConfig.AttachmentSlot(
            "Recon",
            disabled = false,
            bonus = 2,
            penalty = -1,
            minCost = 30,
            factCost = 25,
            penaltyType = 1,
        )

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            eqid,
            EquipmentData().apply {
                uclass = UnitClass.RECON.value
                spotrange = 3
                movpoints = 6
                cost = 20
            },
        )
        EfileConfig.setForTest(
            attachmentConfigValue =
                EfileConfig.AttachmentConfig(
                    armyCost = false,
                    minFuel = 0,
                    minMove = 0,
                    factorDefaultPct = 25,
                    minCostDefault = 30,
                    slots = mapOf(Attachments.SLOT_RECON to recon),
                ),
        )
    }

    @AfterTest
    fun cleanup() {
        leaveCampaignForTests()
        HeroCampaign.reset()
        EfileConfig.resetForTest()
    }

    private fun unitOf(player: Player): GameUnit = GameUnit(eqid).apply { this.player = player }

    @Test
    fun standaloneScenarioBuysOntoTheUnitAndSpendsPrestige() {
        val player = Player().apply { id = 0 }
        val unit = unitOf(player)
        val cost = assertNotNull(Attachments.cost(unit, Attachments.SLOT_RECON))
        player.prestige = cost + 7

        assertTrue(Attachments.availableSlots(unit).any { it.first == Attachments.SLOT_RECON })
        assertTrue(player.purchaseAttachment(unit, Attachments.SLOT_RECON))
        assertEquals(7, player.prestige)
        assertEquals(listOf(Attachments.SLOT_RECON), unit.purchasedAttachmentIds)
        assertTrue(Attachments.has(unit, Attachments.SLOT_RECON))
        assertFalse(player.purchaseAttachment(unit, Attachments.SLOT_RECON), "an owned slot is not sold twice")
        assertEquals(7, player.prestige)
    }

    /** A borrowed auxiliary in a campaign has no roster record; it buys for this battle only. */
    @Test
    fun aCampaignAuxiliaryBuysOntoItself() {
        enterCampaignForTests()
        val player = Player().apply { id = 0 }
        val auxiliary = unitOf(player).apply { isTemporaryBorrowed = true }
        player.prestige = 10_000

        assertTrue(player.purchaseAttachment(auxiliary, Attachments.SLOT_RECON))
        assertEquals(listOf(Attachments.SLOT_RECON), auxiliary.purchasedAttachmentIds)
        assertTrue(player.prestige < 10_000, "the auxiliary's attachment is paid for")
    }

    /** A roster formation still buys into the FORMATION, so the purchase carries to the next battle. */
    @Test
    fun aCampaignCoreFormationStillBuysIntoTheRoster() {
        enterCampaignForTests()
        val player = Player().apply { id = 0 }
        HeroCampaign.roster().putFormation(
            CoreFormation(
                id = FormationId("F-A"),
                ownerId = 0,
                country = 19,
                displayName = "Test Formation",
                currentEquipmentId = eqid,
                unitClass = UnitClass.RECON.value,
            ),
        )
        val core = unitOf(player).apply { formationId = "F-A" }
        player.prestige = 10_000

        assertTrue(player.purchaseAttachment(core, Attachments.SLOT_RECON))
        assertEquals(emptyList(), core.purchasedAttachmentIds)
        assertEquals(
            listOf("1"),
            HeroCampaign
                .roster()
                .formation(FormationId("F-A"))
                ?.attachmentIds,
        )
    }
}
