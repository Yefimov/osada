package org.osada.rules

import org.osada.UnitClass
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Attachment prices (`docs/design/attachments.md` §8): an explicit 0 is a price, an omitted column
 * inherits `attach_mincost`/`attach_factor`, and a resource generated before the importer recorded
 * presence keeps its zeros meaning "inherit".
 *
 * Every case goes through [EfileConfig.parseAttachments], the same JSON the importer writes. The
 * unit costs 20, so `unitCostPerStrength = 20 * 12 / 10 = 24` and the percentage part of the
 * formula is `24 * 10 * factor / 100`.
 */
class AttachmentCostTest {
    private val eqid = 503

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            eqid,
            EquipmentData().apply {
                uclass = UnitClass.RECON.value
                spotrange = 3
                cost = 20
            },
        )
    }

    @AfterTest
    fun cleanup() = EfileConfig.resetForTest()

    /** [slot] is the JSON of slot 1's two cost fields; [globals] the block's own keys. */
    private fun reconCost(
        slot: String,
        globals: String = "",
        presence: Boolean = true,
    ): Int {
        val marker = if (presence) "\"costPresence\":true," else ""
        val json =
            "{\"attachments\":{\"on\":true,$marker$globals\"slots\":{\"1\":{\"name\":\"Recon\"," +
                "\"disabled\":false,\"bonus\":1,\"penalty\":0,$slot\"penaltyType\":0}}}}"
        EfileConfig.setForTest(attachmentConfigValue = assertNotNull(EfileConfig.parseAttachments(json)))
        return assertNotNull(Attachments.cost(GameUnit(eqid), Attachments.SLOT_RECON))
    }

    @Test
    fun explicitZeroMinimumAndFactorIsFree() {
        assertEquals(0, reconCost(costs(0, 0)))
    }

    /** ATOMIC's shape: no fixed part, but the percentage part still applies. */
    @Test
    fun explicitZeroMinimumKeepsThePercentagePart() {
        assertEquals(60, reconCost(costs(0, 25)))
    }

    @Test
    fun explicitZeroFactorLeavesTheFixedCost() {
        assertEquals(40, reconCost(costs(40, 0)))
    }

    @Test
    fun omittedColumnsInheritTheEfileGlobals() {
        assertEquals(44, reconCost(costs(null, null), globals(20, 10)))
    }

    @Test
    fun omittedEverywhereFallsBackToTheDocumentedDefaults() {
        assertEquals(90, reconCost(costs(null, null)))
    }

    @Test
    fun anExplicitZeroGlobalIsInheritedAsZero() {
        assertEquals(0, reconCost(costs(null, null), globals(0, 0)))
    }

    /** A resource written before 2026-10-06 cannot tell an omitted column from an explicit zero,
     *  so its zeros keep the old reading and the price does not change under the player. */
    @Test
    fun aLegacyResourceStillReadsZeroAsInherit() {
        assertEquals(44, reconCost(costs(0, 0), globals(20, 10), presence = false))
        assertEquals(90, reconCost(costs(0, 0), presence = false))
        assertEquals(90, reconCost(costs(0, 0), globals(0, 0), presence = false))
    }

    @Test
    fun aFreeAttachmentCanBeBoughtWithNoPrestige() {
        reconCost(costs(0, 0))
        val player = Player().apply { id = 0 }
        val unit = GameUnit(eqid).apply { this.player = player }

        assertTrue(player.purchaseAttachment(unit, Attachments.SLOT_RECON))
        assertEquals(0, player.prestige)
    }

    private fun costs(
        minCost: Int?,
        factCost: Int?,
    ) = "\"minCost\":$minCost,\"factCost\":$factCost,"

    private fun globals(
        minCost: Int,
        factor: Int,
    ) = "\"minCostDefault\":$minCost,\"factorDefaultPct\":$factor,"
}
