package org.osada.rules

import org.osada.GameStateSerializer
import org.osada.UnitClass
import org.osada.hero.CoreFormation
import org.osada.hero.FormationId
import org.osada.hero.HeroCampaign
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.applySerializedScenarioProperties
import org.osada.model.resetEquipment
import kotlin.js.JSON
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Attachments the SCENARIO AUTHOR fitted — `.xscn` unit `@40`/`@41`, 12,555 corpus records.
 *
 * The property that made this its own storage rather than a write into `CoreFormation` is
 * [anAuxiliaryFormationWithNoCoreRecordStillCarriesItsAuthoredSlot]: most of the units that carry an
 * authored slot never join a campaign roster, and the purchased system has nowhere to put one.
 *
 * The two questions `docs/og-import-rules-backlog.md` §7 said had to be settled before deploying —
 * duplicates, and the two-slot cap — are [aDuplicateSlotCountsOnce] and
 * [theAuthorsFittingFillsTheCapAheadOfAPurchase].
 */
class AuthoredAttachmentsTest {
    private companion object {
        const val EQID = 520
    }

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            EQID,
            EquipmentData().apply {
                uclass = UnitClass.RECON.value
                spotrange = 3
                movpoints = 6
                ammo = 8
                cost = 20
            },
        )
        EfileConfig.setForTest(attachmentConfigValue = config())
    }

    @AfterTest
    fun cleanup() {
        HeroCampaign.reset()
        EfileConfig.resetForTest()
    }

    private fun slot(
        label: String,
        bonus: Int,
        disabled: Boolean = false,
    ) = EfileConfig.AttachmentSlot(
        name = label,
        disabled = disabled,
        bonus = bonus,
        penalty = -1,
        minCost = 30,
        factCost = 5,
        penaltyType = 1,
    )

    /** Recon (+2 spot), Anti-Tank (+3), Bridging disabled — the LXF shape, minus the parts unused
     *  here. Slot 6 is deliberately absent: it is a real `equip.cfg` id this engine does not
     *  implement, and the filter that drops it is what this fixture tests. */
    private fun config() =
        EfileConfig.AttachmentConfig(
            armyCost = true,
            minFuel = 0,
            minMove = 0,
            factorDefaultPct = 10,
            minCostDefault = 20,
            slots =
                mapOf(
                    Attachments.SLOT_RECON to slot("Recon", 2),
                    Attachments.SLOT_ANTI_TANK to slot("AntiTank", 3),
                    Attachments.SLOT_BRIDGING to slot("Bridging", 0, disabled = true),
                ),
        )

    private fun unit(
        vararg authored: Int,
        formationId: String? = null,
    ): GameUnit =
        GameUnit(EQID).apply {
            this.formationId = formationId
            authoredAttachmentIds = authored.toList()
            player = Player().apply { id = 0 }
        }

    private fun formation(id: String) =
        CoreFormation(
            id = FormationId(id),
            ownerId = 0,
            country = 19,
            displayName = "Formation $id",
            currentEquipmentId = EQID,
            unitClass = UnitClass.RECON.value,
        )

    // ---- Storage and effect ------------------------------------------------------------------------

    /**
     * The reason authored slots live on the unit. An auxiliary or scenario-only formation has no
     * `CoreFormation`, and that is where the purchased system keeps everything.
     */
    @Test
    fun anAuxiliaryFormationWithNoCoreRecordStillCarriesItsAuthoredSlot() {
        val auxiliary = unit(Attachments.SLOT_RECON)
        assertTrue(Attachments.has(auxiliary, Attachments.SLOT_RECON))
        assertEquals(2, Attachments.bonus(auxiliary, Attachments.SLOT_RECON))
        assertEquals(5, MovementRules.getUnitSpotRange(auxiliary), "base 3 plus the fitted +2")
    }

    /** A formation with no authored slot must behave exactly as it did before this existed. */
    @Test
    fun aFormationWithNoAuthoredSlotIsUnchanged() {
        val plain = unit()
        assertFalse(Attachments.has(plain, Attachments.SLOT_RECON))
        assertEquals(emptyList(), Attachments.fittedSlots(plain))
        assertEquals(3, MovementRules.getUnitSpotRange(plain))
    }

    /**
     * The ids are per EFILE. A slot the active `equip.cfg` does not define, or that this engine does
     * not implement, is dropped rather than approximated — the same filter `availableSlots` applies
     * before offering one for sale.
     */
    @Test
    fun aSlotTheEfileDoesNotDefineIsDropped() {
        // 6 is `equip.cfg`'s Forward Observer: a real OG slot with no mechanic in this engine.
        val fitted = unit(6, Attachments.SLOT_RECON)
        assertEquals(listOf(Attachments.SLOT_RECON), Attachments.fittedSlots(fitted).map { it.first })
    }

    /** An efile that disables a slot disables it for the author too. */
    @Test
    fun aDisabledSlotIsDropped() {
        assertFalse(Attachments.has(unit(Attachments.SLOT_BRIDGING), Attachments.SLOT_BRIDGING))
    }

    // ---- Duplicates and the cap ---------------------------------------------------------------------

    /** A second Recon package is not two Recon packages. */
    @Test
    fun aDuplicateSlotCountsOnce() {
        val fitted = unit(Attachments.SLOT_RECON, Attachments.SLOT_RECON)
        assertEquals(1, Attachments.fittedSlots(fitted).size)
        assertEquals(2, Attachments.bonus(fitted, Attachments.SLOT_RECON), "and grants its bonus once")
    }

    /**
     * The author's fitting is not something the player chose, so it is what occupies the cap: a
     * formation the author already filled has nothing left to buy, and the purchase list measures
     * against the union rather than against the formation's own list.
     */
    @Test
    fun theAuthorsFittingFillsTheCapAheadOfAPurchase() {
        HeroCampaign.roster().putFormation(formation("F-A"))
        val core = unit(Attachments.SLOT_RECON, Attachments.SLOT_ANTI_TANK, formationId = "F-A")

        assertEquals(Attachments.MAX_PER_UNIT, Attachments.fittedSlots(core).size)
        assertEquals(emptyList(), Attachments.availableSlots(core), "nothing left to buy")
        assertFalse(HeroCampaign.purchaseAttachment(core, Attachments.SLOT_BRIDGING))
    }

    /** With one authored slot the formation may still buy exactly one more, and not the same one. */
    @Test
    fun oneAuthoredSlotLeavesRoomForOnePurchaseButNotADuplicate() {
        HeroCampaign.roster().putFormation(formation("F-B"))
        val core = unit(Attachments.SLOT_RECON, formationId = "F-B")

        val offered = Attachments.availableSlots(core).map { it.first }
        assertEquals(listOf(Attachments.SLOT_ANTI_TANK), offered, "the authored slot is not re-offered")

        assertTrue(HeroCampaign.purchaseAttachment(core, Attachments.SLOT_ANTI_TANK))
        assertEquals(2, Attachments.fittedSlots(core).size)
        assertTrue(Attachments.has(core, Attachments.SLOT_RECON), "authored and purchased coexist")
        assertTrue(Attachments.has(core, Attachments.SLOT_ANTI_TANK))
    }

    /** An authored slot costs nothing, ever — it is part of the formation the author wrote. */
    @Test
    fun anAuthoredSlotIsNeverCharged() {
        val buyer =
            Player().apply {
                id = 0
                prestige = 500
            }
        val fitted = unit(Attachments.SLOT_RECON).apply { player = buyer }
        assertTrue(Attachments.has(fitted, Attachments.SLOT_RECON))
        assertEquals(500, buyer.prestige, "loading a scenario deducts nothing")
    }

    // ---- The author's veto ---------------------------------------------------------------------------

    /** `@50` bit 3, on two records corpus-wide: the author forbidding attachments outright. */
    @Test
    fun theAuthorsVetoRemovesEverySlotAndTheOfferWithIt() {
        HeroCampaign.roster().putFormation(formation("F-C"))
        val core =
            unit(Attachments.SLOT_RECON, formationId = "F-C").apply { attachmentsForbidden = true }

        assertEquals(emptyList(), Attachments.fittedSlots(core))
        assertEquals(emptyList(), Attachments.availableSlots(core))
        assertEquals(3, MovementRules.getUnitSpotRange(core), "and no bonus reaches the stat")
    }

    // ---- Persistence -----------------------------------------------------------------------------------

    /** A restore never re-reads the scenario XML, so the author's fitting has to be in the save. */
    @Test
    fun authoredSlotsSurviveASaveRoundTrip() {
        val fitted = unit(Attachments.SLOT_RECON, Attachments.SLOT_ANTI_TANK)
        fitted.attachmentsForbidden = false

        val restored = GameUnit(EQID)
        restored.applySerializedScenarioProperties(
            JSON.parse<dynamic>(JSON.stringify(GameStateSerializer.serializeUnit(fitted))),
        )

        assertEquals(
            listOf(Attachments.SLOT_RECON, Attachments.SLOT_ANTI_TANK),
            restored.authoredAttachmentIds,
        )
        assertFalse(restored.attachmentsForbidden)
    }

    /** A formation with none writes no key, so its save keeps the shape it had. */
    @Test
    fun anUnfittedFormationWritesNoKey() {
        val saved = JSON.parse<dynamic>(JSON.stringify(GameStateSerializer.serializeUnit(unit())))
        assertTrue(saved.authoredAttachments == undefined)
        assertTrue(saved.noAttachments == undefined)

        val restored = GameUnit(EQID)
        restored.applySerializedScenarioProperties(saved)
        assertEquals(emptyList(), restored.authoredAttachmentIds)
    }
}
