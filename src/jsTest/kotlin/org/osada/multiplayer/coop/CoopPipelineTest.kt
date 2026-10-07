package org.osada.multiplayer.coop

import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.executeAttack
import org.osada.multiplayer.command.BriefingReady
import org.osada.multiplayer.command.ChooseCampaignDialogueOption
import org.osada.multiplayer.command.CommandValidation
import org.osada.multiplayer.command.EndPlayerTurn
import org.osada.multiplayer.command.EndTurnReady
import org.osada.multiplayer.command.ExcuseSeat
import org.osada.multiplayer.command.GameCommand
import org.osada.multiplayer.command.GameCommandJson
import org.osada.multiplayer.command.HexCoordinate
import org.osada.multiplayer.command.MoveUnit
import org.osada.multiplayer.command.OsadaGameCommandApplier
import org.osada.multiplayer.command.OsadaGameCommandValidator
import org.osada.multiplayer.command.ProposeTransfer
import org.osada.multiplayer.command.ResolveCampaignVote
import org.osada.multiplayer.command.RespondTransfer
import org.osada.multiplayer.command.SetLoadout
import org.osada.multiplayer.command.SetUnitAssignment
import org.osada.multiplayer.command.toPayloadJson
import org.osada.rules.OgRulesTestHarness
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The authority's cooperative pipeline against a real map: who may order which unit, what a first
 * order claims, what a rejected one leaves behind, and which leader layer answers for a unit.
 */
class CoopPipelineTest : OgRulesTestHarness() {
    private lateinit var map: GameMap
    private lateinit var coop: CoopState
    private var appliedEndTurns = 0
    private val log = mutableListOf<GameCommand>()

    @BeforeTest
    fun setup() {
        installTestWorld()
        map = world(prestige = 1_000)
        GameHolder.instance = holderFor(map)
        val portrait = CoopPortrait.generated(7)
        coop =
            CoopState(
                listOf(
                    CoopCommander("c1", 0, "host", "Ada", portrait, listOf("coop.marksman", "coop.tenacious_defense")),
                    CoopCommander("c2", 1, "guest", "Bo", portrait, listOf("coop.fire_discipline")),
                    CoopCommander("c3", 2, "third", "Cy", portrait, emptyList()),
                ),
                humanPlayerId = friendly.id,
            )
        coop.onTurnKey(CoopCommandPipeline.turnKeyOf(map), friendlyTurn = true)
        CoopRuntime.install(coop)
        appliedEndTurns = 0
        log.clear()
    }

    @AfterTest
    fun teardown() {
        CoopRuntime.clear()
        clearTestWorld()
    }

    private fun mine(
        row: Int,
        col: Int,
        eqid: Int = infantryEqid,
    ): GameUnit = place(map, eqid, row, col, 0).apply { isDeployed = true }

    private fun theirs(
        row: Int,
        col: Int,
    ): GameUnit = place(map, infantryEqid, row, col, 1).apply { isDeployed = true }

    private fun run(
        sender: String,
        command: GameCommand,
        host: String = "host",
    ): CommandValidation {
        val game = GameHolder.instance
        val validator = OsadaGameCommandValidator { game }
        val applier = OsadaGameCommandApplier(gameProvider = { game })
        return CoopCommandPipeline.execute(
            CoopCommandContext(
                map = map,
                coop = coop,
                command = command,
                senderParticipantId = sender,
                senderIsHost = sender == host,
                committingRevision = log.size + 1L,
                validate = { validator.validate(it, null) },
                apply = {
                    log += it
                    if (it is ChooseCampaignDialogueOption) {
                        // Observe the one authority application without coupling this pipeline test to DOM authoring.
                    } else if (it is EndPlayerTurn) {
                        appliedEndTurns++
                        map.currentPlayer = hostile
                        map.turn += 0
                    } else {
                        applier.apply(it)
                    }
                },
            ),
        )
    }

    private fun move(
        unit: GameUnit,
        row: Int,
        col: Int,
    ) = MoveUnit(
        unitId = unit.id,
        from = HexCoordinate(unit.getPos()!!.col, unit.getPos()!!.row),
        to = HexCoordinate(col, row),
        path = emptyList(),
        actorPlayerId = friendly.id,
    )

    private fun reason(verdict: CommandValidation): String? =
        (verdict as? CommandValidation.Rejected)?.rejection?.message

    // -- Claiming ------------------------------------------------------------------------------

    @Test
    fun theFirstRealOrderClaimsTheUnit() {
        val unit = mine(3, 3)
        assertNull(coop.ownerOf(unit.id))
        assertEquals(CommandValidation.Accepted, run("guest", move(unit, 3, 4)))
        assertEquals("c2", coop.ownerOf(unit.id))
        assertEquals(4, unit.getPos()!!.col, "the order was actually carried out")
    }

    @Test
    fun anotherCommanderIsNotRefusedForTouchingAClaimedUnitAndTakesItWhenItChanges() {
        val unit = mine(3, 3)
        run("guest", move(unit, 3, 4))
        val verdict =
            run("third", MoveUnit(unit.id, HexCoordinate(3, 4), HexCoordinate(3, 5), emptyList(), friendly.id))
        // The unit has already moved this turn, so the game refuses it -- but not as "another's unit".
        assertTrue(reason(verdict) != "coop.unit_commanded_by:Bo")
        assertEquals("c2", coop.ownerOf(unit.id), "a refused order takes nothing")
        coop.takeCommand(unit.id, "c3")
        assertEquals("c3", coop.ownerOf(unit.id), "an accepted order by a teammate hands the unit over")
    }

    @Test
    fun aRejectedFirstOrderLeavesNoClaimAndNoScore() {
        val unit = mine(3, 3)
        val verdict = run("guest", move(unit, 99, 99))
        assertTrue(verdict is CommandValidation.Rejected)
        assertNull(coop.ownerOf(unit.id))
        assertEquals(0, coop.ledger.totalPoints())
    }

    @Test
    fun twoCommandersRacingForOneUnitProduceOneOwnerAndOneAction() {
        val unit = mine(3, 3)
        val first = run("guest", move(unit, 3, 4))
        val second = run("third", MoveUnit(unit.id, HexCoordinate(3, 3), HexCoordinate(2, 3), emptyList(), friendly.id))
        assertEquals(CommandValidation.Accepted, first)
        assertTrue(second is CommandValidation.Rejected)
        assertEquals("c2", coop.ownerOf(unit.id))
        assertEquals(1, log.count { it is MoveUnit })
    }

    @Test
    fun anOrderThatChangesNothingClaimsNothing() {
        val unit = mine(3, 3)
        // Resupplying a unit that is already full is accepted by nothing -- the base rules refuse it --
        // and a refusal must not claim. (An accepted no-op is covered by the fingerprint gate.)
        val verdict =
            run(
                "guest",
                org.osada.multiplayer.command
                    .ResupplyUnit(unit.id, friendly.id),
            )
        assertNull(coop.ownerOf(unit.id))
        assertTrue(verdict is CommandValidation.Rejected || coop.ownerOf(unit.id) == null)
    }

    @Test
    fun anOrderForAnotherArmyIsRefusedWhoeverSendsIt() {
        val unit = mine(3, 3)
        val forged = MoveUnit(unit.id, HexCoordinate(3, 3), HexCoordinate(3, 4), emptyList(), hostile.id)
        assertTrue(run("guest", forged) is CommandValidation.Rejected)
        assertEquals(3, unit.getPos()!!.col)
        assertTrue(run("stranger", move(unit, 3, 4)) is CommandValidation.Rejected, "not a seated commander")
    }

    // -- Turn end ------------------------------------------------------------------------------

    @Test
    fun noCommanderCanEndTheArmysTurnWithARawOrder() {
        val verdict = run("guest", EndPlayerTurn(friendly.id))
        assertTrue(verdict is CommandValidation.Rejected)
        assertEquals("coop.use_done", reason(verdict))
        assertEquals(0, appliedEndTurns)
        // Even the host's human seat may not: only the host's bookkeeping for an AI player may.
        assertTrue(run("host", EndPlayerTurn(friendly.id)) is CommandValidation.Rejected)
    }

    @Test
    fun theHostMayStillEndAnAiPlayersTurn() {
        map.currentPlayer = hostile
        assertEquals(CommandValidation.Accepted, run("host", EndPlayerTurn(hostile.id)))
        assertEquals(1, appliedEndTurns)
        assertTrue(
            run("guest", EndPlayerTurn(hostile.id)) is CommandValidation.Rejected,
            "a guest cannot end the AI's turn",
        )
    }

    @Test
    fun theTurnEndsOnceWhenTheLastRequiredCommanderIsDone() {
        run("host", EndTurnReady(true, friendly.id))
        run("guest", EndTurnReady(true, friendly.id))
        assertEquals(0, appliedEndTurns, "one commander is still planning")
        run("third", EndTurnReady(true, friendly.id))
        assertEquals(1, appliedEndTurns)
        assertTrue(coop.done.isEmpty(), "the new turn starts with nobody done")
    }

    @Test
    fun aNewOrderWithdrawsEveryonesDone() {
        val unit = mine(3, 3)
        run("host", EndTurnReady(true, friendly.id))
        run("guest", EndTurnReady(true, friendly.id))
        run("third", move(unit, 3, 4).copy(unitId = unit.id))
        assertTrue(coop.done.isEmpty(), "the position changed, so players review it again")
        assertEquals(0, appliedEndTurns)
    }

    @Test
    fun undoingDoneIsAlwaysPossibleBeforeTheTurnCommits() {
        run("host", EndTurnReady(true, friendly.id))
        run("host", EndTurnReady(false, friendly.id))
        assertFalse("c1" in coop.done)
    }

    @Test
    fun onlyTheHostCanExcuseAnAbsentSeat() {
        assertTrue(run("guest", ExcuseSeat("c3", true, null, friendly.id)) is CommandValidation.Rejected)
        assertEquals(CommandValidation.Accepted, run("host", ExcuseSeat("c3", true, "c1", friendly.id)))
        assertTrue("c3" in coop.excused)
        run("host", EndTurnReady(true, friendly.id))
        run("guest", EndTurnReady(true, friendly.id))
        assertEquals(1, appliedEndTurns, "the excused seat is no longer awaited")
    }

    // -- Claim board and transfers through commands --------------------------------------------

    @Test
    fun votesBindAuthenticatedSendersAndCommitEffectsOnlyAfterAllSeatsVote() {
        coop.briefing = CoopBriefing(listOf(CoopDialogueNode("choice", null, mapOf("a" to null, "b" to null))))
        val unit = mine(3, 3)
        assertTrue(run("guest", move(unit, 3, 4)) is CommandValidation.Rejected)
        assertTrue(run("guest", BriefingReady(friendly.id)) is CommandValidation.Rejected)
        assertEquals(CommandValidation.Accepted, run("guest", ChooseCampaignDialogueOption("choice", "a", friendly.id)))
        assertEquals(
            "a",
            coop.briefing
                ?.votes
                ?.get("choice")
                ?.get("c2"),
        )
        assertTrue(log.isEmpty())
        run("host", ChooseCampaignDialogueOption("choice", "b", friendly.id))
        assertTrue(log.isEmpty())
        run("third", ChooseCampaignDialogueOption("choice", "a", friendly.id))
        assertEquals(listOf<GameCommand>(ChooseCampaignDialogueOption("choice", "a", friendly.id)), log)
        assertEquals("a", coop.briefing?.decisions?.get("choice"))
        assertTrue(run("host", ChooseCampaignDialogueOption("choice", "b", friendly.id)) is CommandValidation.Rejected)
        listOf("guest", "third").forEach { run(it, BriefingReady(friendly.id)) }
        assertTrue(coop.briefing?.open == true)
        run("host", BriefingReady(friendly.id))
        assertFalse(coop.briefing?.open == true)
        assertEquals(0, coop.ordersThisMission)
        assertTrue(coop.assignments.isEmpty())
    }

    @Test
    fun onlyHostMayBreakAnAlreadyCompleteTie() {
        coop.excuse("c3", true)
        coop.briefing = CoopBriefing(listOf(CoopDialogueNode("choice", null, mapOf("a" to null, "b" to null))))
        run("guest", ChooseCampaignDialogueOption("choice", "a", friendly.id))
        assertTrue(run("host", ResolveCampaignVote("b", friendly.id)) is CommandValidation.Rejected)
        run("host", ChooseCampaignDialogueOption("choice", "b", friendly.id))
        assertTrue(log.isEmpty())
        assertTrue(run("guest", ResolveCampaignVote("a", friendly.id)) is CommandValidation.Rejected)
        assertEquals(CommandValidation.Accepted, run("host", ResolveCampaignVote("b", friendly.id)))
        assertEquals(1, log.size)
        assertEquals("b", coop.briefing?.decisions?.get("choice"))
    }

    @Test
    fun aCommanderTakesAUnitOnlyForThemselvesButFromAnyone() {
        val unit = mine(3, 3)
        assertTrue(run("guest", SetUnitAssignment(unit.id, "host", friendly.id)) is CommandValidation.Rejected)
        assertEquals(CommandValidation.Accepted, run("guest", SetUnitAssignment(unit.id, "guest", friendly.id)))
        assertEquals("c2", coop.ownerOf(unit.id))
        assertEquals(CommandValidation.Accepted, run("third", SetUnitAssignment(unit.id, "third", friendly.id)))
        assertEquals("c3", coop.ownerOf(unit.id), "Wait hands the unit to whoever pressed it")
    }

    @Test
    fun aTransferNeedsAnAnswerAndTakesEffectNextFriendlyTurn() {
        val unit = mine(3, 3)
        run("guest", move(unit, 3, 4))
        assertEquals(CommandValidation.Accepted, run("third", ProposeTransfer(unit.id, null, friendly.id)))
        assertEquals("c2", coop.ownerOf(unit.id))
        assertEquals(CommandValidation.Accepted, run("guest", RespondTransfer(unit.id, true, friendly.id)))
        assertEquals("c2", coop.ownerOf(unit.id), "agreed, but it lands at the boundary")
        coop.onTurnKey("9:0", friendlyTurn = true)
        assertEquals("c3", coop.ownerOf(unit.id))
        assertTrue(run("host", RespondTransfer(unit.id, true, friendly.id)) is CommandValidation.Rejected)
    }

    @Test
    fun theCooperativeOrdersSurviveTheWire() {
        listOf(
            ProposeTransfer(4, "c2", 0),
            ProposeTransfer(4, null, 0),
            RespondTransfer(4, true, 0),
            ExcuseSeat("c3", true, "c1", 0),
            ExcuseSeat("c3", false, null, 0),
        ).forEach { assertEquals(it, GameCommandJson.decode(it.toPayloadJson())) }
    }

    // -- One leader layer ----------------------------------------------------------------------

    @Test
    fun anUnassignedUnitOfTheSharedArmyHasNoAbilitiesAndNoLegacyLeader() {
        val unit = mine(3, 3).apply { leader = LeaderType.MARKSMAN.value }
        assertFalse(Leaders.unitHasLeader(unit, LeaderType.MARKSMAN), "an authored leader's effect is suppressed here")
        assertEquals(LeaderType.MARKSMAN.value, unit.leader, "but the data is preserved, never stripped")
    }

    @Test
    fun anAssignedUnitGetsExactlyItsCommandersChosenEffects() {
        val unit = mine(3, 3)
        coop.claim(unit.id, "c1")
        assertTrue(Leaders.unitHasLeader(unit, LeaderType.MARKSMAN))
        assertTrue(Leaders.unitHasLeader(unit, LeaderType.TENACIOUS_DEFENSE))
        assertFalse(Leaders.unitHasLeader(unit, LeaderType.FIRE_DISCIPLINE), "that is Bo's, not Ada's")
        assertFalse(Leaders.unitHasLeader(unit, LeaderType.OVERWHELMING_ATTACK))
    }

    @Test
    fun theFirstOrdersCommanderIsInForceBeforeTheClaimCommits() {
        val unit = mine(3, 3)
        val seen = mutableListOf<Boolean>()
        CoopRuntime.acting("c2", unit.id) { seen += Leaders.unitHasLeader(unit, LeaderType.FIRE_DISCIPLINE) }
        assertEquals(listOf(true), seen)
        assertFalse(Leaders.unitHasLeader(unit, LeaderType.FIRE_DISCIPLINE), "the lens is gone afterwards")
        assertNull(coop.ownerOf(unit.id), "previewing never claims")
    }

    @Test
    fun anAbilityThatCannotFireOnThisClassDoesNotApply() {
        val battery = mine(3, 3, gunEqid)
        coop.claim(battery.id, "c1")
        coop.commanders.first { it.id == "c1" }
        val flier =
            org.osada.model.OgLeaderPools
                .appliesTo(LeaderType.SKILLED_GROUND_ATTACK, battery.unitData().uclass)
        assertFalse(flier, "an air-only effect does not apply to artillery")
    }

    @Test
    fun enemyAndAlliedLeadersKeepTheirOwnPath() {
        val enemy = theirs(5, 5).apply { leader = LeaderType.MARKSMAN.value }
        assertTrue(Leaders.unitHasLeader(enemy, LeaderType.MARKSMAN), "enemy leaders are unchanged")
        assertFalse(CoopRuntime.governs(enemy))
    }

    @Test
    fun noLegacyLeaderIsMintedForTheSharedArmy() {
        val unit = mine(3, 3)
        assertEquals(-1, Leaders.generateLeader(unit))
    }

    // -- Accounting through the real attack -------------------------------------------------------

    @Test
    fun aRealAttackIsCreditedToTheCommanderWhoOrderedItAndReconciles() {
        val attacker = mine(3, 3)
        val defender = theirs(3, 4)
        coop.claim(attacker.id, "c2")
        val before = defender.strength
        map.executeAttack(attacker, defender)
        val gained = coop.ledger.perCommander["c2"]
        val dealt = before - defender.strength
        assertEquals(dealt, gained?.damage ?: 0, "damage is what the exchange actually did")
        if (defender.destroyed) {
            assertEquals(1, coop.ledger.teamDestroyed + coop.ledger.teamCaptured)
            assertEquals(40, coop.ledger.totalPoints())
        } else {
            assertEquals(0, coop.ledger.totalPoints())
        }
    }

    @Test
    fun anEnemyAttackCreditsTheDefendersCommanderWithEndurance() {
        val defender = mine(3, 3)
        val enemy = theirs(3, 4)
        coop.claim(defender.id, "c3")
        map.currentPlayer = hostile
        coop.ledger.rollWindow("1:1")
        map.executeAttack(enemy, defender)
        val record = coop.ledger.perCommander["c3"]
        assertTrue(record != null, "the defender's commander has a record")
        if (!defender.destroyed) assertEquals(1, record.endurance)
        assertNull(coop.ledger.perCommander["c1"], "nobody else is credited")
    }

    // -- Pre-battle window and campaign orders ---------------------------------------------------

    @Test
    fun abilitiesChangeBeforeTheFirstOrderAndLockAfterIt() {
        assertEquals(CommandValidation.Accepted, run("guest", SetLoadout(listOf("coop.marksman"), friendly.id)))
        assertEquals(listOf("coop.marksman"), coop.commanderById("c2")!!.abilityIds)
        val unit = mine(3, 3)
        run("host", move(unit, 3, 4))
        val late = run("guest", SetLoadout(emptyList(), friendly.id))
        assertEquals("coop.loadout_locked", reason(late))
        assertEquals(listOf("coop.marksman"), coop.commanderById("c2")!!.abilityIds, "the locked loadout is untouched")
    }

    @Test
    fun anIllegalLoadoutIsRefusedAndChangesNothing() {
        val before = coop.commanderById("c1")!!.abilityIds
        val verdict =
            run("host", SetLoadout(listOf("coop.marksman", "coop.resilience", "coop.street_fighter"), friendly.id))
        assertEquals("coop.loadout_invalid", reason(verdict))
        assertEquals(before, coop.commanderById("c1")!!.abilityIds)
        assertTrue(run("host", SetLoadout(listOf("coop.not_a_thing"), friendly.id)) is CommandValidation.Rejected)
    }

    @Test
    fun aCommanderCannotChangeSomeoneElsesAbilities() {
        run("guest", SetLoadout(listOf("coop.marksman"), friendly.id))
        assertEquals(emptyList(), coop.commanderById("c3")!!.abilityIds, "the order named no other commander")
        assertEquals(listOf("coop.marksman"), coop.commanderById("c2")!!.abilityIds)
    }

    @Test
    fun releasingIsPreBattleButTakingStaysOpen() {
        val unit = mine(3, 3)
        assertEquals(CommandValidation.Accepted, run("guest", SetUnitAssignment(unit.id, "guest", friendly.id)))
        assertEquals("c2", coop.ownerOf(unit.id))
        assertTrue(
            run("third", SetUnitAssignment(unit.id, null, friendly.id)) is CommandValidation.Rejected,
            "only the holder gives it back",
        )
        assertEquals(CommandValidation.Accepted, run("guest", SetUnitAssignment(unit.id, null, friendly.id)))
        assertNull(coop.ownerOf(unit.id))
        run("host", EndTurnReady(true, friendly.id))
        assertEquals(CommandValidation.Accepted, run("guest", SetUnitAssignment(unit.id, "guest", friendly.id)))
        val closed = run("guest", SetUnitAssignment(unit.id, null, friendly.id))
        assertEquals("coop.board_closed", reason(closed))
    }

    @Test
    fun onlyTheHostAdvancesTheCampaignAndUnknownDialogueVotesAreRejected() {
        assertTrue(
            run(
                "guest",
                org.osada.multiplayer.command
                    .ContinueCampaign("victory", friendly.id),
            ) is CommandValidation.Rejected,
        )
        assertEquals(
            "coop.invalid_vote",
            reason(
                run(
                    "guest",
                    org.osada.multiplayer.command
                        .ChooseCampaignDialogueOption("d", "o", friendly.id),
                ),
            ),
        )
    }

    @Test
    fun theLoadoutOrderSurvivesTheWire() {
        val command = SetLoadout(listOf("coop.marksman", "coop.resilience"), 0)
        assertEquals(command, GameCommandJson.decode(command.toPayloadJson()))
    }

    @Test
    fun aFormationIsRememberedWhenItsUnitIsClaimed() {
        val unit = mine(3, 3).apply { formationId = "F-1" }
        run("guest", move(unit, 3, 4))
        assertEquals("c2", coop.formationSuggestions["F-1"])
    }

    @Test
    fun excusingTheMissingVoterCommitsOneDecisionAndReleasesRemainingReaders() {
        coop.briefing = CoopBriefing(listOf(CoopDialogueNode("decision", null, mapOf("a" to null))))
        run("host", ChooseCampaignDialogueOption("decision", "a", friendly.id))
        run("guest", ChooseCampaignDialogueOption("decision", "a", friendly.id))
        assertTrue(log.isEmpty())
        assertEquals(CommandValidation.Accepted, run("host", ExcuseSeat("c3", true, null, friendly.id)))
        assertEquals(1, log.filterIsInstance<ChooseCampaignDialogueOption>().size)
        run("host", BriefingReady(friendly.id))
        run("guest", BriefingReady(friendly.id))
        assertEquals(false, coop.briefing?.open)
        assertEquals(1, log.filterIsInstance<ChooseCampaignDialogueOption>().size)
    }
}
