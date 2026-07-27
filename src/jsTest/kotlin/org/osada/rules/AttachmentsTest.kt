package org.osada.rules

import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.hero.CoreFormation
import org.osada.hero.FormationId
import org.osada.hero.HeroCampaign
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Player
import org.osada.model.entrench
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
                fuel = 40
                cost = 20
            },
        )
    }

    /** Adds Fast Speed (12) and Fuel Pods (11) to [base] with the given magnitudes, plus the
     *  efile-level `attach_minmove`/`attach_minfuel` that decide whether those two read as flat
     *  amounts or as percentages of the unit's base stat. */
    private fun withScaledSlots(
        base: EfileConfig.AttachmentConfig,
        fastSpeedBonus: Int,
        fuelPodsBonus: Int,
        minMove: Int,
        minFuel: Int,
    ) = base.copy(
        minMove = minMove,
        minFuel = minFuel,
        slots =
            base.slots +
                mapOf(
                    Attachments.SLOT_FAST_SPEED to
                        EfileConfig.AttachmentSlot(
                            "Fast Speed",
                            disabled = false,
                            bonus = fastSpeedBonus,
                            penalty = -1,
                            minCost = 30,
                            factCost = 15,
                            penaltyType = 2,
                        ),
                    Attachments.SLOT_FUEL_PODS to
                        EfileConfig.AttachmentSlot(
                            "Fuel Pods",
                            disabled = false,
                            bonus = fuelPodsBonus,
                            penalty = -1,
                            minCost = 30,
                            factCost = 10,
                            penaltyType = 1,
                        ),
                ),
    )

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

    /** ATOMIC-shaped: neither `attach_minmove` nor `attach_minfuel` is set, so slots 11/12 read as
     *  flat amounts exactly like slots 1-10. */
    @Test
    fun fastSpeedAndFuelPodsAreFlatWhenTheMinKeysAreZero() {
        val config =
            withScaledSlots(
                lxfShapedConfig(),
                fastSpeedBonus = 1,
                fuelPodsBonus = 15,
                minMove = 0,
                minFuel = 0,
            )
        EfileConfig.setForTest(attachmentConfigValue = config)
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_FAST_SPEED))
        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_FUEL_PODS))

        val fast = Attachments.bonus(unit, Attachments.SLOT_FAST_SPEED)
        val pods = Attachments.bonus(unit, Attachments.SLOT_FUEL_PODS)
        assertEquals(1, fast, "ATOMIC Fast Speed 1 with no minmove is a flat +1 MP")
        assertEquals(15, pods, "ATOMIC Fuel Pods 15 with no minfuel is a flat +15 fuel")
    }

    /** LXF-shaped: `attach_minmove`/`attach_minfuel` are set, so slots 11/12's columns are
     *  PERCENTAGES of the unit's base stat, floored at the key -- DEFERRED.md §1.17. The old flat
     *  reading gave a 6-MP unit +20 MP. */
    @Test
    fun fastSpeedAndFuelPodsArePercentagesWhenTheMinKeysAreSet() {
        val config =
            withScaledSlots(
                lxfShapedConfig(),
                fastSpeedBonus = 20,
                fuelPodsBonus = 25,
                minMove = 1,
                minFuel = 8,
            )
        EfileConfig.setForTest(attachmentConfigValue = config)
        HeroCampaign.roster().putFormation(formation("F-A"))
        HeroCampaign.roster().putFormation(formation("F-B"))
        val unit = coreUnit("F-A")

        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_FAST_SPEED))
        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_FUEL_PODS))

        // movpoints 6: 6 * 20 / 100 = 1 (integer division), floored at minMove 1 -> +1, NOT +20.
        val fast = Attachments.bonus(unit, Attachments.SLOT_FAST_SPEED)
        assertEquals(1, fast, "LXF Fast Speed 20 is 20% of 6 MP, not a flat +20")
        // fuel 40: 40 * 25 / 100 = 10, above minFuel 8 -> +10.
        assertEquals(10, Attachments.bonus(unit, Attachments.SLOT_FUEL_PODS), "LXF Fuel Pods = 25% of 40")

        // Fast Speed alone, so Fuel Pods' own -1 movement malus doesn't mask the delta.
        val fastOnly = coreUnit("F-B")
        assertTrue(HeroCampaign.purchaseAttachment(fastOnly, Attachments.SLOT_FAST_SPEED))
        assertEquals(7, MovementRules.getUnitMoveRange(fastOnly), "base 6 + 1; the flat reading gave 26")
    }

    /** The min key floors THE BONUS, never the resulting stat. A percentage that rounds below it
     *  is raised to it -- BASEKORP's 30% of a 5-MP unit is 1, floored to `attach_minmove` 2. */
    @Test
    fun theMinKeyFloorsTheComputedPercentage() {
        val config =
            withScaledSlots(
                lxfShapedConfig(),
                fastSpeedBonus = 30,
                fuelPodsBonus = 50,
                minMove = 20,
                minFuel = 25,
            )
        EfileConfig.setForTest(attachmentConfigValue = config)
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_FAST_SPEED))
        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_FUEL_PODS))

        val fast = Attachments.bonus(unit, Attachments.SLOT_FAST_SPEED)
        val pods = Attachments.bonus(unit, Attachments.SLOT_FUEL_PODS)
        assertEquals(20, fast, "6 * 30 / 100 = 1, floored to minMove 20")
        assertEquals(25, pods, "40 * 50 / 100 = 20, floored to minFuel 25")
    }

    /** DEFERRED.md §1.16: `attach_minmove` used to cap the whole post-attachment range at the
     *  pre-attachment one, so Fast Speed's bonus was discarded whenever the unit also carried a
     *  movement malus. It is not a cap on the range at all. */
    @Test
    fun aMovementMalusDoesNotDiscardTheFastSpeedBonus() {
        val config =
            withScaledSlots(
                lxfShapedConfig(),
                fastSpeedBonus = 50,
                fuelPodsBonus = 25,
                minMove = 1,
                minFuel = 8,
            )
        EfileConfig.setForTest(attachmentConfigValue = config)
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        // Recon carries the malus-type-1 penalty (-1 MP); Fast Speed gives 6 * 50 / 100 = +3.
        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_FAST_SPEED))
        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_RECON))

        assertEquals(-1, Attachments.movementPenalty(unit))
        assertEquals(8, MovementRules.getUnitMoveRange(unit), "6 + 3 - 1; the old cap clamped this back to 6")
    }

    /** DEFERRED.md §1.14: an out-of-fuel unit carrying a movement-malus attachment must stay at 0.
     *  It used to be handed `attach_minmove` movement points it had no fuel for. */
    @Test
    fun anOutOfFuelUnitWithAMovementMalusStaysAtZero() {
        val config =
            withScaledSlots(
                lxfShapedConfig(),
                fastSpeedBonus = 20,
                fuelPodsBonus = 25,
                minMove = 1,
                minFuel = 8,
            )
        EfileConfig.setForTest(attachmentConfigValue = config)
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A").apply { fuel = 0 }

        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_RECON))

        assertEquals(0, MovementRules.getUnitMoveRange(unit), "no fuel means no movement, attachment or not")
    }

    /** GCE ships `penalty = -1` with the malus-type column OMITTED on all eight of its slots.
     *  `EFILE_NOKORP/equip.cfg`: `0 = Default/Omitted (current penalty)` -- so the slot's built-in
     *  default applies, and Recon's default is Movement. These penalties used to be dropped whole.
     *  DEFERRED.md §1.19. */
    @Test
    fun anOmittedMalusTypeFallsBackToTheSlotsDocumentedDefault() {
        val base = lxfShapedConfig()
        val gceShaped =
            base.copy(
                slots =
                    base.slots +
                        mapOf(
                            Attachments.SLOT_RECON to
                                base.slots.getValue(Attachments.SLOT_RECON).copy(penaltyType = 0),
                        ),
            )
        EfileConfig.setForTest(attachmentConfigValue = gceShaped)
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_RECON))

        assertEquals(-1, Attachments.movementPenalty(unit), "omitted malus-type means DEFAULT, not none")
        assertEquals(5, MovementRules.getUnitMoveRange(unit), "base 6 - 1")
    }

    /** A slot whose mechanic this engine does not implement must never be offered -- the player
     *  would pay real prestige for a no-op. Forward Observer (6) is Tier 3. */
    @Test
    fun slotsWithNoImplementedMechanicAreNotOffered() {
        val base = lxfShapedConfig()
        val withTier3 =
            base.copy(
                slots =
                    base.slots +
                        mapOf(
                            6 to
                                EfileConfig.AttachmentSlot(
                                    "Forward Observer",
                                    disabled = false,
                                    bonus = 2,
                                    penalty = -1,
                                    minCost = 30,
                                    factCost = 15,
                                    penaltyType = 2,
                                ),
                        ),
            )
        EfileConfig.setForTest(attachmentConfigValue = withTier3)
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        val offered = Attachments.availableSlots(unit).map { it.first }

        assertFalse(6 in offered, "Forward Observer has no mechanic yet and must not be sold")
        assertTrue(Attachments.SLOT_RECON in offered, "Tier 1 slots are still offered")
        assertTrue(offered.all { it in Attachments.IMPLEMENTED_SLOTS })
    }

    /** `penaltyType 4 = Fuel` (`EFILE_NOKORP/equip.cfg`). No shipped efile uses it, but OG defines
     *  it, so a malus-type-4 attachment must reduce max fuel rather than be silently ignored. */
    @Test
    fun aFuelMalusReducesMaxFuel() {
        val base = lxfShapedConfig()
        val withFuelMalus =
            base.copy(
                slots =
                    base.slots +
                        mapOf(
                            Attachments.SLOT_RECON to
                                base.slots.getValue(Attachments.SLOT_RECON).copy(penalty = -3, penaltyType = 4),
                        ),
            )
        EfileConfig.setForTest(attachmentConfigValue = withFuelMalus)
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_RECON))

        assertEquals(-3, Attachments.fuelPenalty(unit))
        assertEquals(0, Attachments.movementPenalty(unit), "a fuel malus is not a movement malus")
    }

    /** The purchase UI describes slots the unit does NOT own yet, so it cannot use the
     *  ownership-gated [Attachments.bonus] -- that read "+0" on every purchasable tile. Both go
     *  through the same scaling, so a preview can never disagree with what is delivered. */
    @Test
    fun previewBonusReportsAnUnownedSlotWhileBonusReportsZero() {
        val config =
            withScaledSlots(
                lxfShapedConfig(),
                fastSpeedBonus = 50,
                fuelPodsBonus = 25,
                minMove = 1,
                minFuel = 8,
            )
        EfileConfig.setForTest(attachmentConfigValue = config)
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        assertEquals(0, Attachments.bonus(unit, Attachments.SLOT_RECON), "not fitted: contributes nothing")
        assertEquals(2, Attachments.previewBonus(unit, Attachments.SLOT_RECON), "but the tile must show +2")
        // The percentage slots must preview through the SAME scaling, not the raw column.
        assertEquals(3, Attachments.previewBonus(unit, Attachments.SLOT_FAST_SPEED), "6 * 50 / 100, not 50")

        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_FAST_SPEED))
        assertEquals(
            Attachments.previewBonus(unit, Attachments.SLOT_FAST_SPEED),
            Attachments.bonus(unit, Attachments.SLOT_FAST_SPEED),
            "once fitted, the promise and the delivery must be identical",
        )
    }

    @Test
    fun formationlessUnitHasNoAttachmentsToOffer() {
        EfileConfig.setForTest(attachmentConfigValue = lxfShapedConfig())
        val unit = GameUnit(eqid).apply { player = Player().apply { id = 0 } }

        assertEquals(emptyList(), Attachments.availableSlots(unit))
        assertFalse(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_RECON))
    }

    // ---------------------------------------------------------------- Tier 2 (DEFERRED.md §1.4)

    private fun withTier2Slots(base: EfileConfig.AttachmentConfig) =
        base.copy(
            slots =
                base.slots +
                    mapOf(
                        Attachments.SLOT_BRIDGING to
                            EfileConfig.AttachmentSlot(
                                "Bridging",
                                disabled = false,
                                bonus = 0,
                                penalty = -1,
                                minCost = 30,
                                factCost = 50,
                                penaltyType = 1,
                            ),
                        Attachments.SLOT_FAST_ENTRENCH to
                            EfileConfig.AttachmentSlot(
                                "Fast Entrench",
                                disabled = false,
                                bonus = 3,
                                penalty = -1,
                                minCost = 30,
                                factCost = 25,
                                penaltyType = 2,
                            ),
                        Attachments.SLOT_BUNKER_BUSTER to
                            EfileConfig.AttachmentSlot(
                                "Bunker Buster",
                                disabled = false,
                                bonus = 3,
                                penalty = -1,
                                minCost = 30,
                                factCost = 25,
                                penaltyType = 3,
                            ),
                    ),
        )

    @Test
    fun bridgingLetsAUnitActAsABridge() {
        EfileConfig.setForTest(attachmentConfigValue = withTier2Slots(lxfShapedConfig()))
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit =
            coreUnit("F-A").apply {
                player =
                    Player().apply {
                        id = 0
                        side = 0
                    }
            }
        val riverHex =
            Hex(0, 0).apply {
                terrain = TerrainType.RIVER.value
                this.unit = unit
            }

        assertFalse(MovementRules.isBridgeForSide(riverHex, 0), "not a bridge before purchase")
        assertTrue(HeroCampaign.purchaseAttachment(unit, Attachments.SLOT_BRIDGING))
        assertTrue(MovementRules.isBridgeForSide(riverHex, 0), "Bridging attachment grants the same capability")
    }

    @Test
    fun bridgingIsNotOfferedToAUnitThatAlreadyHasTheBridgeSpecial() {
        Equipment.equipmentMap[eqid]?.attr = 8 // ATTR_MASK_BRIDGE
        EfileConfig.setForTest(attachmentConfigValue = withTier2Slots(lxfShapedConfig()))
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        val offered = Attachments.availableSlots(unit).map { it.first }

        assertFalse(Attachments.SLOT_BRIDGING in offered, "already a bridge -- buying a second would waste prestige")
    }

    @Test
    fun bridgingIsNotOfferedToAirOrNavalUnits() {
        Equipment.putEquipment(
            eqid,
            EquipmentData().apply {
                uclass = UnitClass.FIGHTER.value
                movmethod = MovMethod.AIR.value
            },
        )
        EfileConfig.setForTest(attachmentConfigValue = withTier2Slots(lxfShapedConfig()))
        HeroCampaign.roster().putFormation(formation("F-A"))
        val unit = coreUnit("F-A")

        val offered = Attachments.availableSlots(unit).map { it.first }

        assertFalse(Attachments.SLOT_BRIDGING in offered, "OG's pre-v6 fallback rule (A) excludes air units")
    }

    @Test
    fun fastEntrenchRaisesTheTerrainEntrenchmentCeiling() {
        EfileConfig.setForTest(attachmentConfigValue = withTier2Slots(lxfShapedConfig()))
        HeroCampaign.roster().putFormation(formation("F-A"))
        HeroCampaign.roster().putFormation(formation("F-B"))
        // CITY (terrainEntrenchment index 1) has a nonzero base -- starting entrenchment 0 is below
        // it, so entrench() snaps straight to the ceiling in one call rather than needing several
        // ticks, which CLEAR's base-0 ceiling would.
        val bare = coreUnit("F-A").apply { hex = Hex(0, 0).apply { terrain = TerrainType.CITY.value } }
        val fitted = coreUnit("F-B").apply { hex = Hex(0, 1).apply { terrain = TerrainType.CITY.value } }
        assertTrue(HeroCampaign.purchaseAttachment(fitted, Attachments.SLOT_FAST_ENTRENCH))

        bare.entrench()
        fitted.entrench()

        val plainCeiling = bare.entrenchment
        assertEquals(
            plainCeiling + 3,
            fitted.entrenchment,
            "Fast Entrench's +3 raises the terrain's own ceiling this unit snaps to",
        )
    }

    @Test
    fun bunkerBusterBypassesTheDefendersEntrenchment() {
        EfileConfig.setForTest(attachmentConfigValue = withTier2Slots(lxfShapedConfig()))
        HeroCampaign.roster().putFormation(formation("F-A"))
        val attacker = coreUnit("F-A")
        val defender = GameUnit(eqid).apply { player = Player().apply { id = 1 } }

        assertTrue(
            CombatResolver.isEntrenchmentIntact(attacker, defender, TerrainType.CLEAR.value),
            "entrenchment applies normally before the attacker buys Bunker Buster",
        )
        assertTrue(HeroCampaign.purchaseAttachment(attacker, Attachments.SLOT_BUNKER_BUSTER))
        assertFalse(
            CombatResolver.isEntrenchmentIntact(attacker, defender, TerrainType.CLEAR.value),
            "Bunker Buster bypasses entrenchment exactly like the Ignore-trench attr bit",
        )
    }
}
