package org.osada.multiplayer.coop

import org.osada.LeaderType
import org.osada.hero.HeroPortraitArt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure halves of the cooperative mode: the catalogue, loadout legality, the ledger and the state. */
class CoopRulesTest {
    // -- Catalogue and loadout -----------------------------------------------------------------

    @Test
    fun theCatalogueHasOneStableIdPerRuleEffect() {
        val leaders = CoopAbilityCatalogue.ALL.map { it.leader }
        assertEquals(leaders.size, leaders.toSet().size, "one entry per effect, however many heroes share it")
        assertEquals(
            CoopAbilityCatalogue.ALL.size,
            CoopAbilityCatalogue.ALL
                .map { it.id }
                .toSet()
                .size,
        )
        assertEquals(LeaderType.TENACIOUS_DEFENSE, CoopAbilityCatalogue.leaderFor("coop.tenacious_defense"))
    }

    @Test
    fun economyEffectsAreNotOffered() {
        // One shared prestige account: Liberator / Influence would be a per-player income stream.
        assertNull(CoopAbilityCatalogue.leaderFor("coop.liberator"))
        assertNull(CoopAbilityCatalogue.leaderFor("coop.influence"))
    }

    @Test
    fun aNegativeOrUnknownCardIsRefused() {
        val issues = CoopLoadout.validate(listOf("coop.tenacious_defense", "coop.negative_card"), 2)
        assertTrue(CoopLoadout.Issue.UNKNOWN_ABILITY in issues)
    }

    @Test
    fun slotsGrowFromTwoToThreeToFourAndNoFurther() {
        assertEquals(2, CoopLoadout.slotCap(0))
        assertEquals(3, CoopLoadout.slotCap(1))
        assertEquals(4, CoopLoadout.slotCap(2))
        assertEquals(4, CoopLoadout.slotCap(9), "the ceiling is four, however many unlocks are replayed")
        assertEquals(2, CoopLoadout.slotCap(-3))
    }

    @Test
    fun theThirdAbilityNeedsTheFirstUnlockAndTheFifthIsNeverAllowed() {
        val three = listOf("coop.tenacious_defense", "coop.marksman", "coop.fire_discipline")
        assertTrue(CoopLoadout.Issue.TOO_MANY in CoopLoadout.validate(three, CoopLoadout.slotCap(0)))
        assertTrue(CoopLoadout.validate(three, CoopLoadout.slotCap(1)).isEmpty())
        val five = three + listOf("coop.street_fighter", "coop.resilience")
        assertTrue(CoopLoadout.Issue.TOO_MANY in CoopLoadout.validate(five, CoopLoadout.slotCap(2)))
    }

    @Test
    fun emptySlotsAndDuplicatesAndRestrictedLimits() {
        assertTrue(CoopLoadout.validate(emptyList(), 2).isEmpty(), "leaving slots empty is allowed")
        assertTrue(
            CoopLoadout.Issue.DUPLICATE_ABILITY in
                CoopLoadout.validate(listOf("coop.marksman", "coop.marksman"), 4),
        )
        val twoRestricted = listOf("coop.first_strike", "coop.recon_movement")
        assertTrue(CoopLoadout.Issue.TOO_MANY_RESTRICTED in CoopLoadout.validate(twoRestricted, 4))
        assertTrue(CoopLoadout.validate(listOf("coop.first_strike", "coop.marksman"), 2).isEmpty())
    }

    // -- Portraits -----------------------------------------------------------------------------

    @Test
    fun portraitsAreAllowlistedIdentifiers() {
        val art = HeroPortraitArt.ALL.first().id
        assertTrue(CoopPortrait.legendary(art)!!.isValid())
        assertNull(CoopPortrait.legendary("../../etc/passwd"))
        assertFalse(CoopPortrait(artId = "not-a-portrait").isValid())
        assertFalse(CoopPortrait(layerIds = listOf("javascript:alert(1)")).isValid())
        assertFalse(CoopPortrait().isValid(), "an empty portrait is not a portrait")
    }

    @Test
    fun aGeneratedPortraitIsStoredAndReproducible() {
        val first = CoopPortrait.generated(1234)
        assertTrue(first.isValid())
        assertEquals(first, CoopPortrait.generated(1234), "the same seed draws the same face")
        assertTrue(first.layerIds.isNotEmpty())
    }

    // -- Terminal pool -------------------------------------------------------------------------

    @Test
    fun theTerminalPoolAlwaysSumsToForty() {
        val cases =
            listOf(
                emptyMap(),
                mapOf("c1" to 3),
                mapOf("c1" to 1, "c2" to 1, "c3" to 1),
                mapOf("c2" to 7, "c3" to 5, "c4" to 1),
                mapOf("c1" to 100, "c2" to 1),
            )
        cases.forEach { contributors ->
            listOf("c1", "c2", null).forEach { finisher ->
                val allocation = CoopLedger.allocateTerminal(finisher, contributors)
                assertEquals(CoopLedger.TERMINAL_POOL, allocation.values.sum(), "$finisher $contributors")
                assertTrue(allocation.values.all { it >= 0 })
            }
        }
    }

    @Test
    fun theFinisherKeepsAFlatHalfAndEarlierDamageSharesTheRest() {
        val shared = CoopLedger.allocateTerminal("c1", mapOf("c2" to 6, "c3" to 2))
        assertEquals(20 + 0, shared["c1"])
        assertEquals(15, shared["c2"])
        assertEquals(5, shared["c3"])
        val alone = CoopLedger.allocateTerminal("c1", emptyMap())
        assertEquals(mapOf("c1" to 40), alone)
    }

    @Test
    fun anUnassignedFinisherKeepsItsHalfInTheBucketNotWithAPlayer() {
        val allocation = CoopLedger.allocateTerminal(null, mapOf("c1" to 4))
        assertEquals(20, allocation[CoopLedger.UNASSIGNED])
        assertEquals(20, allocation["c1"])
    }

    // -- Ledger guards -------------------------------------------------------------------------

    @Test
    fun anEnemyEndsOnlyOnceAndCapturedExcludesDestroyed() {
        val ledger = CoopLedger()
        ledger.recordTerminal(900, "c1", captured = true)
        assertTrue(ledger.recordTerminal(900, "c2", captured = false).isEmpty(), "the same unit cannot end twice")
        assertEquals(1, ledger.teamCaptured)
        assertEquals(0, ledger.teamDestroyed)
        assertEquals(40, ledger.totalPoints())
    }

    @Test
    fun objectivesAndRevealsScoreOncePerMission() {
        val ledger = CoopLedger()
        assertTrue(ledger.recordObjective("3,4", "c1"))
        assertFalse(ledger.recordObjective("3,4", "c2"), "a recapture earns nothing")
        assertTrue(ledger.recordReveal(77, "c1"))
        assertFalse(ledger.recordReveal(77, "c1"), "hide and reveal again earns nothing")
        assertEquals(105, ledger.perCommander["c1"]!!.points)
        assertNull(ledger.perCommander["c2"], "the second capturer was never credited")
    }

    @Test
    fun enduranceIsCappedPerUnitPerWindow() {
        val ledger = CoopLedger()
        ledger.rollWindow("1:1")
        repeat(6) { ledger.recordEndurance(5, "c1") }
        assertEquals(15, ledger.perCommander["c1"]!!.points)
        assertEquals(6, ledger.perCommander["c1"]!!.endurance, "the statistic still counts every endured attack")
        ledger.rollWindow("2:1")
        ledger.recordEndurance(5, "c1")
        assertEquals(20, ledger.perCommander["c1"]!!.points, "a new enemy turn is a new window")
    }

    @Test
    fun theFinishingBlowIsNotItsOwnEarlierContribution() {
        val ledger = CoopLedger()
        ledger.rollWindow("1:0")
        ledger.recordDamage(900, "c2", 4)
        CoopAttackAccounting.record(
            ledger,
            CoopAttackFacts(
                attackerId = 1,
                defenderId = 900,
                attackerFriendly = true,
                defenderFriendly = false,
                attackerCommanderId = "c1",
                defenderCommanderId = null,
                attackerLost = 0,
                attackerDestroyed = false,
                defenderLost = 6,
                defenderDestroyed = true,
                defenderCaptured = false,
                defenderRetreated = false,
                attackStopped = false,
            ),
        )
        assertEquals(20, ledger.perCommander["c1"]!!.points, "finisher: flat half, no earlier damage of their own")
        assertEquals(20, ledger.perCommander["c2"]!!.points, "the earlier damage dealer takes the shared half")
        assertEquals(40, ledger.totalPoints())
        assertEquals(1, ledger.teamDestroyed)
    }

    @Test
    fun anEnemyAttackOnAnUnassignedDefenderFabricatesNoPlayerActor() {
        val ledger = CoopLedger()
        CoopAttackAccounting.record(
            ledger,
            CoopAttackFacts(
                attackerId = 900,
                defenderId = 1,
                attackerFriendly = false,
                defenderFriendly = true,
                attackerCommanderId = null,
                defenderCommanderId = null,
                attackerLost = 0,
                attackerDestroyed = false,
                defenderLost = 2,
                defenderDestroyed = false,
                defenderCaptured = false,
                defenderRetreated = false,
                attackStopped = false,
            ),
        )
        assertEquals(0, ledger.totalPoints(), "no endurance credit without a commander")
        assertEquals(2, ledger.perCommander[CoopLedger.UNASSIGNED]!!.strengthLost)
    }

    // -- State ---------------------------------------------------------------------------------

    private fun twoCommanders(): CoopState {
        val portrait = CoopPortrait.generated(1)
        return CoopState(
            listOf(
                CoopCommander("c1", 0, "pa", "Ada", portrait, listOf("coop.marksman")),
                CoopCommander("c2", 1, "pb", "Bo", portrait, emptyList()),
            ),
            humanPlayerId = 0,
        )
    }

    @Test
    fun theFirstClaimWinsAndAnOwnedUnitIsNotReclaimed() {
        val state = twoCommanders()
        assertTrue(state.claim(10, "c1"))
        assertFalse(state.claim(10, "c2"))
        assertEquals("c1", state.ownerOf(10))
        assertTrue(state.claim(10, "c1"), "re-claiming your own unit is harmless")
        assertFalse(state.claim(11, "nobody"))
    }

    @Test
    fun aTransferNeedsBothPartiesAndLandsOnlyAtTheFriendlyBoundary() {
        val state = twoCommanders()
        state.onTurnKey("1:0", friendlyTurn = true)
        state.claim(10, "c1")
        assertNull(state.requestTransfer(10, "c1", "c2"))
        assertEquals(CoopRefusal.ALREADY_PENDING, state.requestTransfer(10, "c2", null))
        assertEquals(
            CoopRefusal.NOT_THE_ADDRESSEE,
            state.respondTransfer(10, "c1", accept = true),
            "asking is not agreeing",
        )
        assertNull(state.respondTransfer(10, "c2", accept = true))
        assertEquals("c1", state.ownerOf(10), "agreed, but not yet effective mid-turn")
        state.onTurnKey("1:1", friendlyTurn = false)
        assertEquals("c1", state.ownerOf(10), "the enemy turn leaves defenders and bonuses where they were")
        state.onTurnKey("2:0", friendlyTurn = true)
        assertEquals("c2", state.ownerOf(10))
        assertTrue(state.transfers.isEmpty())
    }

    @Test
    fun aDeclinedOrUnfulfilledTransferChangesNothing() {
        val state = twoCommanders()
        state.claim(10, "c1")
        assertNull(state.requestTransfer(10, "c2", null))
        assertNull(state.respondTransfer(10, "c1", accept = false))
        state.onTurnKey("2:0", friendlyTurn = true)
        assertEquals("c1", state.ownerOf(10))
        assertEquals(CoopRefusal.UNIT_UNASSIGNED, state.requestTransfer(99, "c1", "c2"))
        assertEquals(CoopRefusal.SAME_COMMANDER, state.requestTransfer(10, "c1", "c1"))
    }

    @Test
    fun doneIsClearedByANewTurnAndExcusedSeatsAreNotAwaited() {
        val state = twoCommanders()
        assertFalse(state.allDone())
        state.setDone("c1", true)
        assertFalse(state.allDone())
        state.excuse("c2", true)
        assertTrue(state.allDone(), "the host chose to continue without the absent seat")
        state.excuse("c2", false)
        assertFalse(state.allDone())
        state.setDone("c2", true)
        assertTrue(state.allDone())
        state.onTurnKey("2:0", friendlyTurn = true)
        assertTrue(state.done.isEmpty())
    }

    @Test
    fun delegatingASeatTakesEffectAtTheNextBoundary() {
        val state = twoCommanders()
        state.claim(10, "c1")
        state.claim(11, "c1")
        assertEquals(2, state.delegateSeat("c1", "c2"))
        assertEquals("c1", state.ownerOf(10), "the old effective abilities stay through this cycle")
        state.onTurnKey("3:0", friendlyTurn = true)
        assertEquals(listOf(10, 11), state.unitsOf("c2"))
    }

    // -- Codec ---------------------------------------------------------------------------------

    @Test
    fun theStateSurvivesTheWireIncludingTheLedger() {
        val state = twoCommanders()
        state.unlocksEarned = 1
        state.claim(10, "c1")
        state.requestTransfer(10, "c2", null)
        state.setDone("c2", true)
        state.onTurnKey("4:0", friendlyTurn = true)
        state.ledger.recordObjective("1,1", "c1")
        state.ledger.recordTerminal(900, "c2", captured = false)
        val text = CoopStateJson.encode(state)
        val back = CoopStateJson.decode(text)
        assertEquals(text, CoopStateJson.encode(back), "encode(decode(x)) is stable")
        assertEquals(1, back.unlocksEarned)
        assertEquals("c1", back.ownerOf(10))
        assertEquals(state.ledger.totalPoints(), back.ledger.totalPoints())
        assertEquals(setOf(900), back.ledger.terminalEnemies)
        assertEquals("4:0", back.turnKey)
    }

    @Test
    fun anIncompatibleOrTamperedStateIsRefusedNotSilentlyDowngraded() {
        val text = CoopStateJson.encode(twoCommanders())
        val wrongPolicy = text.replace("\"policyVersion\":1", "\"policyVersion\":99")
        assertFailsWithMessage { CoopStateJson.decode(wrongPolicy) }
        val wrongCatalogue = text.replace("\"catalogueVersion\":1", "\"catalogueVersion\":99")
        assertFailsWithMessage { CoopStateJson.decode(wrongCatalogue) }
        val unknownAbility = text.replace("coop.marksman", "coop.invented")
        assertFailsWithMessage { CoopStateJson.decode(unknownAbility) }
    }

    @Test
    fun aLobbyProfileIsValidatedOnTheWayIn() {
        val profile = CoopCommanderProfile("  Ada\u0007  ", CoopPortrait.generated(5), listOf("coop.marksman"))
        val decoded =
            CoopStateJson.decodeProfile(
                JSON.parse<dynamic>(JSON.stringify(CoopStateJson.encodeProfile(profile))),
            )
        assertNotNull(decoded)
        assertEquals("Ada", decoded.name, "control characters are stripped and the name trimmed")
        val forged =
            JSON.parse<dynamic>(
                "{\"name\":\"x\",\"portrait\":{\"artId\":\"nope\"},\"abilityIds\":[],\"catalogueVersion\":1}",
            )
        assertNull(CoopStateJson.decodeProfile(forged), "an unknown portrait id is refused")
    }

    private fun assertFailsWithMessage(block: () -> Unit) {
        val failed = runCatching(block).isFailure
        assertTrue(failed, "expected the incompatible state to be refused")
    }

    // -- Campaign progress ---------------------------------------------------------------------

    @Test
    fun aLongCampaignUnlocksTheThirdSlotAfterTwoMissionsAndTheFourthAfterFive() {
        assertEquals(CoopProgress.Milestones(2, 5), CoopProgress.milestones(21))
        assertEquals(0, CoopProgress.unlocksAfter(1, 21))
        assertEquals(1, CoopProgress.unlocksAfter(2, 21))
        assertEquals(1, CoopProgress.unlocksAfter(4, 21))
        assertEquals(2, CoopProgress.unlocksAfter(5, 21))
        assertEquals(2, CoopProgress.unlocksAfter(40, 21), "two unlocks is the most there are")
    }

    @Test
    fun aShortCampaignCompressesTheMilestonesSoTheSlotsAreStillUsable() {
        assertEquals(CoopProgress.Milestones(1, 2), CoopProgress.milestones(3))
        assertEquals(
            CoopProgress.Milestones(1, null),
            CoopProgress.milestones(2),
            "a fourth slot after the last battle would be no slot",
        )
        assertEquals(CoopProgress.Milestones(null, null), CoopProgress.milestones(1))
        (2..30).forEach { total ->
            val m = CoopProgress.milestones(total)
            listOfNotNull(m.first, m.second).forEach {
                assertTrue(it <= total - 1, "$total: milestone $it leaves a mission to play")
            }
        }
    }

    // -- Mission rollover ----------------------------------------------------------------------

    private fun campaignState(): CoopState =
        twoCommanders().also {
            it.campaignTotal = 21
            it.beginNextMission("m1.xml")
        }

    @Test
    fun theFirstMissionHasNoUnlocksAndAnOpenWindow() {
        val state = campaignState()
        assertEquals("m1.xml", state.missionFile)
        assertEquals(0, state.missionIndex)
        assertEquals(2, state.slotCap())
        assertTrue(state.preBattle())
    }

    @Test
    fun finishingAMissionArchivesItAndStartsTheNextCleanButKeepsTheCommanders() {
        val state = campaignState()
        state.claim(10, "c1")
        state.setDone("c2", true)
        state.ledger.recordObjective("1,1", "c1")
        state.ordersThisMission = 3
        state.beginNextMission("m2.xml")
        assertEquals(1, state.missionIndex)
        assertEquals(1, state.summaries.size)
        assertEquals(100, state.summaries[0].perCommander["c1"]!!.points)
        assertEquals(0, state.ledger.totalPoints(), "the new mission scores from zero")
        assertTrue(state.assignments.isEmpty(), "unit ids are recycled by every scenario")
        assertTrue(state.done.isEmpty())
        assertTrue(state.preBattle(), "abilities can be revisited before the new mission starts")
        assertEquals(2, state.commanders.size)
        assertEquals(setOf("m1.xml"), state.completedMissions)
    }

    @Test
    fun retryingAMissionOrReplayingATransitionGrantsNothingTwice() {
        val state = campaignState()
        state.beginNextMission("m1.xml")
        assertTrue(state.completedMissions.isEmpty(), "a retry of the same mission is not a completion")
        state.beginNextMission("m2.xml")
        state.beginNextMission("m1.xml")
        state.beginNextMission("m2.xml")
        assertEquals(setOf("m1.xml", "m2.xml"), state.completedMissions)
        assertEquals(1, state.unlocksEarned, "two distinct missions earn one slot, however often they are replayed")
        assertEquals(3, state.slotCap())
    }

    @Test
    fun theSecondUnlockArrivesAtTheFifthDistinctMissionAndNeverAgain() {
        val state = campaignState()
        (2..8).forEach { state.beginNextMission("m$it.xml") }
        assertEquals(2, state.unlocksEarned)
        assertEquals(4, state.slotCap())
    }

    @Test
    fun aStandaloneBattleNeverUnlocksAnything() {
        val state = twoCommanders()
        state.beginNextMission("one.xml")
        state.beginNextMission("two.xml")
        assertEquals(2, state.slotCap())
    }

    @Test
    fun aNewlyEarnedSlotIsFilledByItsPlayersChoiceAndOnlyBeforeTheMission() {
        val state = campaignState()
        assertEquals(
            CoopRefusal.LOADOUT_INVALID,
            state.setLoadout("c1", listOf("coop.marksman", "coop.resilience", "coop.street_fighter")),
        )
        state.beginNextMission("m2.xml")
        state.beginNextMission("m3.xml")
        assertNull(state.setLoadout("c1", listOf("coop.marksman", "coop.resilience", "coop.street_fighter")))
        assertEquals(3, state.commanderById("c1")!!.abilityIds.size)
        state.ordersThisMission = 1
        assertEquals(CoopRefusal.LOADOUT_LOCKED, state.setLoadout("c1", emptyList()))
        assertEquals(CoopRefusal.UNKNOWN_COMMANDER, state.setLoadout("nobody", emptyList()))
    }

    @Test
    fun lastMissionsAllocationReturnsByFormationAsASuggestion() {
        val state = campaignState()
        state.claim(10, "c1")
        state.claim(11, "c2")
        state.rememberFormation("F-armour", "c1")
        state.rememberFormation("F-guns", "c2")
        state.beginNextMission("m2.xml")
        val formations = mapOf(5 to "F-guns", 6 to "F-armour", 7 to "F-new", 8 to null)
        state.applySuggestions(formations.keys.toList()) { formations[it] }
        assertEquals("c2", state.ownerOf(5))
        assertEquals("c1", state.ownerOf(6))
        assertNull(state.ownerOf(7), "a formation nobody held is unclaimed")
        assertNull(state.ownerOf(8))
        assertTrue(state.release(6, "c1"), "the board can hand it back")
        assertNull(state.ownerOf(6))
        assertFalse(state.release(5, "c1"), "only the holder can release")
    }

    @Test
    fun theCampaignStateSurvivesTheWireWithItsHistory() {
        val state = campaignState()
        state.claim(10, "c1")
        state.rememberFormation("F-armour", "c1")
        state.ledger.recordObjective("2,2", "c2")
        state.ordersThisMission = 2
        state.beginNextMission("m2.xml")
        state.beginNextMission("m3.xml")
        val text = CoopStateJson.encode(state)
        val back = CoopStateJson.decode(text)
        assertEquals(text, CoopStateJson.encode(back))
        assertEquals(2, back.missionIndex)
        assertEquals("m3.xml", back.missionFile)
        assertEquals(21, back.campaignTotal)
        assertEquals(setOf("m1.xml", "m2.xml"), back.completedMissions)
        assertEquals(2, back.summaries.size)
        assertEquals(100, back.summaries[0].perCommander["c2"]!!.points)
        assertEquals("c1", back.formationSuggestions["F-armour"])
        assertEquals(1, back.unlocksEarned)
    }

    @Test
    fun theSummariesStayBounded() {
        val state = campaignState()
        repeat(60) { state.beginNextMission("m${it + 2}.xml") }
        assertTrue(state.summaries.size <= 40)
    }
}
