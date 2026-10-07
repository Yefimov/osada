@file:Suppress("TooManyFunctions", "ReturnCount")

package org.osada.multiplayer.coop

import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.getPlayer
import org.osada.model.getPlayers
import org.osada.model.getUnits
import org.osada.multiplayer.command.BriefingReady
import org.osada.multiplayer.command.ChooseCampaignDialogueOption
import org.osada.multiplayer.command.CommandRejection
import org.osada.multiplayer.command.CommandValidation
import org.osada.multiplayer.command.ContinueCampaign
import org.osada.multiplayer.command.CoopBriefingOrder
import org.osada.multiplayer.command.CoopEconomyOrder
import org.osada.multiplayer.command.CoopOrder
import org.osada.multiplayer.command.EndPlayerTurn
import org.osada.multiplayer.command.EndTurnReady
import org.osada.multiplayer.command.ExcuseSeat
import org.osada.multiplayer.command.GameCommand
import org.osada.multiplayer.command.ProposeTransfer
import org.osada.multiplayer.command.PurchaseUnit
import org.osada.multiplayer.command.ResolveCampaignVote
import org.osada.multiplayer.command.RespondTransfer
import org.osada.multiplayer.command.SetLoadout
import org.osada.multiplayer.command.SetSeatSymbol
import org.osada.multiplayer.command.SetUnitAssignment
import org.osada.multiplayer.command.findCommandUnit
import org.osada.multiplayer.command.kind
import org.osada.multiplayer.command.unitIdOrNull
import org.osada.multiplayer.protocol.MultiplayerErrorCode
import org.osada.rules.UnitConcealment

/**
 * Who sent a command, and the callbacks that run it. The pipeline never touches the game directly:
 * the host's validator and applier are passed in, so the same code serves the live room and tests.
 */
class CoopCommandContext(
    val map: GameMap,
    val coop: CoopState,
    val command: GameCommand,
    val senderParticipantId: String,
    val senderIsHost: Boolean,
    /** The revision the commit WILL carry; stamps ledger evidence. */
    val committingRevision: Long,
    val validate: (GameCommand) -> CommandValidation,
    val apply: (GameCommand) -> Unit,
    val quotePurchase: (PurchaseUnit) -> Int? = { null },
)

/**
 * The authority's pipeline for every command in a cooperative room (§6, §11): bind the sender to a
 * commander, check unit ownership, run the ordinary validation under that commander's lens, apply,
 * and only then commit the claim, the readiness reset and the score -- one commit, so a rejected
 * first order leaves no assignment, no ledger entry and no readiness change behind.
 *
 * The sender comes from the room server's connection, never from the payload: a client-supplied
 * `actorPlayerId` names the SHARED army (it must equal [CoopState.humanPlayerId]) and proves nothing
 * about who is acting.
 */
object CoopCommandPipeline {
    @Suppress("ReturnCount")
    fun execute(ctx: CoopCommandContext): CommandValidation {
        val command = ctx.command
        authorize(ctx, command)?.let { return CommandValidation.Rejected(it) }
        val commander = ctx.coop.commanderOfParticipant(ctx.senderParticipantId)
        return when (command) {
            is ChooseCampaignDialogueOption -> executeVote(ctx, command, commander)
            is CoopOrder -> executeCoopOrder(ctx, command, commander)
            is EndTurnReady -> executeReadiness(ctx, command, commander)
            else -> executeGameplay(ctx, command, commander)
        }
    }

    // -- Authorization -------------------------------------------------------------------------

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    internal fun authorize(
        ctx: CoopCommandContext,
        command: GameCommand,
    ): CommandRejection? {
        val coop = ctx.coop
        if (command.actorPlayerId != coop.humanPlayerId) {
            // Another player's orders are the AI's, and only the host runs the AI: its moves, attacks
            // and turn endings reach the pipeline as ordinary commands from the host's own seat. A
            // guest naming an AI player as the actor is forging an order for an army it does not command.
            return if (ctx.senderIsHost) null else deny(MultiplayerErrorCode.NOT_YOUR_CONTROL_SCOPE, "not_your_army")
        }
        val commander =
            coop.commanderOfParticipant(ctx.senderParticipantId)
                ?: return deny(MultiplayerErrorCode.NOT_YOUR_CONTROL_SCOPE, "not_a_commander")
        val battleOrder =
            (command !is CoopOrder || command is CoopEconomyOrder) && command !is ChooseCampaignDialogueOption
        return when (command) {
            // Ending the army's turn is a decision of ALL commanders (Done), never one player's hotkey.
            is EndPlayerTurn -> deny(MultiplayerErrorCode.NOT_YOUR_CONTROL_SCOPE, "use_done")
            is ExcuseSeat, is ContinueCampaign, is ResolveCampaignVote ->
                if (ctx.senderIsHost) null else deny(MultiplayerErrorCode.NOT_ROOM_HOST, "host_only")
            is SetUnitAssignment -> claimBoardCheck(coop, commander, ctx.senderParticipantId, command)
            else ->
                if (coop.briefing?.open == true &&
                    battleOrder
                ) {
                    deny(MultiplayerErrorCode.NOT_YOUR_CONTROL_SCOPE, "briefing_pending")
                } else {
                    null
                }
        }
    }

    /**
     * A commander may take any unit for themselves at any time -- the "Wait" button does exactly
     * that. Giving a unit back to the pool is a pre-battle decision only.
     */
    private fun claimBoardCheck(
        coop: CoopState,
        commander: CoopCommander,
        sender: String,
        command: SetUnitAssignment,
    ): CommandRejection? {
        val owner = coop.ownerOf(command.unitId)
        val releasing = command.assignedParticipantId == null
        return when {
            releasing && !coop.preBattle() -> deny(MultiplayerErrorCode.NOT_YOUR_CONTROL_SCOPE, "board_closed")
            releasing && owner != commander.id ->
                deny(
                    MultiplayerErrorCode.NOT_YOUR_CONTROL_SCOPE,
                    "claim_for_self_only",
                )
            releasing -> null
            command.assignedParticipantId != sender ->
                deny(MultiplayerErrorCode.NOT_YOUR_CONTROL_SCOPE, "claim_for_self_only")

            else -> null
        }
    }

    // -- Execution -----------------------------------------------------------------------------

    private fun executeCoopOrder(
        ctx: CoopCommandContext,
        command: CoopOrder,
        commander: CoopCommander?,
    ): CommandValidation {
        val base = ctx.validate(command)
        if (base is CommandValidation.Rejected) return base
        val coop = ctx.coop
        val refusal =
            when (command) {
                is CoopEconomyOrder -> CoopEconomyPipeline.execute(ctx, command, commander)
                is CoopBriefingOrder -> executeBriefingOrder(ctx, command, commander)
                is ProposeTransfer, is RespondTransfer -> transferOrder(coop, command, commander)
                is ExcuseSeat -> excuse(ctx, command)
                is SetLoadout -> commander?.let { coop.setLoadout(it.id, command.abilityIds) }
                is SetSeatSymbol -> commander?.let { coop.setSymbol(it.id, command.symbol) }
            }
        if (refusal !=
            null
        ) {
            return CommandValidation.Rejected(deny(MultiplayerErrorCode.INVALID_MESSAGE, refusal.name.lowercase()))
        }
        return CommandValidation.Accepted
    }

    private fun transferOrder(
        coop: CoopState,
        command: CoopOrder,
        commander: CoopCommander?,
    ): CoopRefusal? {
        val by = commander ?: return CoopRefusal.UNKNOWN_COMMANDER
        return when (command) {
            is ProposeTransfer -> coop.requestTransfer(command.unitId, by.id, command.toCommanderId)
            is RespondTransfer -> coop.respondTransfer(command.unitId, by.id, command.accept)
            else -> null
        }
    }

    private fun executeVote(
        ctx: CoopCommandContext,
        command: ChooseCampaignDialogueOption,
        commander: CoopCommander?,
    ): CommandValidation {
        val validation = ctx.validate(command)
        if (validation is CommandValidation.Rejected) return validation
        val shared = ctx.coop.briefing
        val required = ctx.coop.requiredCommanderIds()
        if (commander == null || shared?.vote(command.dialogueId, command.optionId, commander.id, required) != true) {
            return CommandValidation.Rejected(deny(MultiplayerErrorCode.INVALID_MESSAGE, "invalid_vote"))
        }
        val winners = shared.winners(required)
        if (winners.size == 1) resolveVote(ctx, winners.single())
        return CommandValidation.Accepted
    }

    private fun executeBriefingOrder(
        ctx: CoopCommandContext,
        command: CoopBriefingOrder,
        commander: CoopCommander?,
    ): CoopRefusal? =
        when (command) {
            is ResolveCampaignVote -> resolveVote(ctx, command.optionId)
            is BriefingReady ->
                if (commander != null &&
                    ctx.coop.briefing?.markReady(commander.id, ctx.coop.requiredCommanderIds()) == true
                ) {
                    null
                } else {
                    CoopRefusal.NOT_PRE_BATTLE
                }
        }

    private fun resolveVote(
        ctx: CoopCommandContext,
        option: String,
    ): CoopRefusal? {
        val shared = ctx.coop.briefing ?: return CoopRefusal.NOT_PRE_BATTLE
        val pending = shared.pending() ?: return CoopRefusal.NOT_PRE_BATTLE
        val required = ctx.coop.requiredCommanderIds()
        if (option !in shared.winners(required)) return CoopRefusal.NOT_PRE_BATTLE
        ctx.apply(ChooseCampaignDialogueOption(pending.id, option, ctx.coop.humanPlayerId))
        check(shared.decide(option, required))
        return null
    }

    private fun excuse(
        ctx: CoopCommandContext,
        command: ExcuseSeat,
    ): CoopRefusal? {
        val coop = ctx.coop
        if (coop.commanderById(command.commanderId) == null) return CoopRefusal.UNKNOWN_COMMANDER
        val delegate = command.delegateToCommanderId
        if (delegate != null && coop.commanderById(delegate) == null) return CoopRefusal.UNKNOWN_COMMANDER
        coop.excuse(command.commanderId, command.excused)
        if (command.excused && delegate != null) {
            coop.delegateSeat(command.commanderId, delegate)
        }
        coop.briefing?.takeIf { it.open }?.let { shared ->
            val required = coop.requiredCommanderIds()
            val winners = shared.winners(required)
            if (winners.size == 1) resolveVote(ctx, winners.first())
            if (required.isNotEmpty() &&
                required.all { it in shared.ready }
            ) {
                shared.markReady(required.first(), required)
            }
        }
        return null
    }

    private fun executeReadiness(
        ctx: CoopCommandContext,
        command: EndTurnReady,
        commander: CoopCommander?,
    ): CommandValidation {
        val base = ctx.validate(command)
        if (base is CommandValidation.Rejected) return base
        val coop = ctx.coop
        val who =
            commander
                ?: return CommandValidation.Rejected(
                    deny(MultiplayerErrorCode.NOT_YOUR_CONTROL_SCOPE, "not_a_commander"),
                )
        coop.setDone(who.id, command.ready)
        if (command.ready) coop.ordersThisMission++
        if (command.ready && coop.allDone()) {
            // Everyone required is done and nothing is pending (commands are strictly serial): the
            // army's turn ends exactly once, here.
            ctx.coop.ledger.beginRevision(ctx.committingRevision)
            ctx.apply(EndPlayerTurn(coop.humanPlayerId))
            refreshTurn(ctx)
        }
        return CommandValidation.Accepted
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun executeGameplay(
        ctx: CoopCommandContext,
        command: GameCommand,
        commander: CoopCommander?,
    ): CommandValidation {
        val coop = ctx.coop
        val map = ctx.map
        val humanSide = map.getPlayers().firstOrNull { it.id == coop.humanPlayerId }?.side ?: -1
        val actsForArmy = command.actorPlayerId == coop.humanPlayerId
        val prestigeBefore = map.getPlayer(coop.humanPlayerId).prestige
        val unitId = command.unitIdOrNull()
        val subject = unitId?.let { map.findCommandUnit(it) }
        val before = subject?.let(::fingerprint)
        val worldBefore = if (actsForArmy) World.capture(map, humanSide) else null
        coop.ledger.beginRevision(ctx.committingRevision)

        val verdict =
            try {
                CoopRuntime.acting(commander?.id.takeIf { actsForArmy }, unitId) {
                    val validation = ctx.validate(command)
                    if (validation is CommandValidation.Accepted) ctx.apply(command)
                    validation
                }
            } catch (e: Throwable) {
                console.warn("[coop] command could not be applied", e)
                return CommandValidation.Rejected(deny(MultiplayerErrorCode.INVALID_MESSAGE, "could_not_apply"))
            }
        if (verdict is CommandValidation.Rejected) return verdict

        if (actsForArmy && commander != null) {
            commit(ctx, command, commander, Observed(subject, before, worldBefore, humanSide))
            val prestige = map.getPlayer(coop.humanPlayerId).prestige
            if (prestige != prestigeBefore || command is PurchaseUnit) {
                coop.economy.record(
                    CoopSpending(
                        ctx.committingRevision,
                        coop.missionFile,
                        commander.id,
                        null,
                        command.kind(),
                        prestige - prestigeBefore,
                        prestige,
                    ),
                )
            }
        }
        pruneAssignments(ctx)
        refreshTurn(ctx)
        return verdict
    }

    /** What was seen BEFORE the order ran, to diff against what it did. */
    private class Observed(
        val subject: GameUnit?,
        val fingerprintBefore: String?,
        val world: World?,
        val humanSide: Int,
    )

    /**
     * The one commit: claim (only for a real change to the unit), readiness reset and ledger diff
     * happen together, after the order was accepted and applied -- never for a refused one.
     */
    private fun commit(
        ctx: CoopCommandContext,
        command: GameCommand,
        commander: CoopCommander,
        seen: Observed,
    ) {
        val coop = ctx.coop
        // The claim board's order is a bare assignment: the applier has nothing to do for it.
        if (command is SetUnitAssignment) {
            if (command.assignedParticipantId ==
                null
            ) {
                coop.release(command.unitId, commander.id)
            } else {
                coop.takeCommand(command.unitId, commander.id)
            }
        }
        val subject = seen.subject
        // Whoever really acts on a unit commands it from then on, taking it from a teammate if need be.
        if (subject != null && fingerprint(subject) != seen.fingerprintBefore) {
            coop.takeCommand(subject.id, commander.id)
        }
        if (clearsReadiness(command)) {
            coop.clearDone()
            coop.ordersThisMission++
        }
        rememberFormations(ctx)
        seen.world?.let { World.capture(ctx.map, seen.humanSide).creditSince(it, ctx.map, coop, commander.id) }
    }

    private fun clearsReadiness(command: GameCommand): Boolean =
        command !is SetUnitAssignment &&
            command !is EndPlayerTurn &&
            command !is org.osada.multiplayer.command.ReorderReserve &&
            command !is org.osada.multiplayer.command.ChooseCampaignDialogueOption

    private fun pruneAssignments(ctx: CoopCommandContext) {
        ctx.coop.assignments.keys.toList().forEach { id ->
            val unit = ctx.map.findCommandUnit(id)
            if (unit == null || unit.destroyed) ctx.coop.forget(id)
        }
    }

    /** Keeps "whose formation is this" up to date, so the next mission can offer the allocation back. */
    private fun rememberFormations(ctx: CoopCommandContext) {
        ctx.coop.assignments.forEach { (unitId, commanderId) ->
            ctx.map
                .findCommandUnit(unitId)
                ?.formationId
                ?.let { ctx.coop.rememberFormation(it, commanderId) }
        }
    }

    /** Rolls the turn-scoped coop state if the turn or the player on the move changed. */
    fun refreshTurn(ctx: CoopCommandContext) {
        val map = ctx.map
        val key = turnKeyOf(map)
        val friendly = map.currentPlayer?.id == ctx.coop.humanPlayerId
        if (ctx.coop.onTurnKey(key, friendly)) rememberFormations(ctx)
    }

    fun turnKeyOf(map: GameMap): String = "${map.turn}:${map.currentPlayer?.id}"

    // -- Facts ---------------------------------------------------------------------------------

    /** Enough of a unit's state to tell a real action from an accepted no-op (§11). */
    internal fun fingerprint(unit: GameUnit): String =
        listOf(
            unit.getPos()?.let { "${it.row},${it.col}" },
            unit.strength,
            unit.moveLeft,
            unit.fuel,
            unit.ammo,
            unit.hasMoved,
            unit.hasFired,
            unit.hasResupplied,
            unit.isDeployed,
            unit.isMounted,
            unit.eqid,
            unit.entrenchment,
            unit.destroyed,
            unit.containedIn?.id,
            unit.shotsThisTurn,
        ).joinToString("|")

    /** The slice of the map the ledger diffs around a command: objective owners and visible enemies. */
    private class World(
        val objectiveOwners: Map<String, Int>,
        val visibleEnemies: Set<Int>,
    ) {
        fun creditSince(
            before: World,
            map: GameMap,
            coop: CoopState,
            commanderId: String,
        ) {
            val sideOf = map.getPlayers().associate { it.id to it.side }
            val humanSide = sideOf[coop.humanPlayerId]
            objectiveOwners.forEach { (key, owner) ->
                val was = before.objectiveOwners[key]
                if (was != owner && sideOf[owner] == humanSide && sideOf[was] != humanSide) {
                    coop.ledger.recordObjective(key, commanderId)
                }
            }
            (visibleEnemies - before.visibleEnemies).forEach { coop.ledger.recordReveal(it, commanderId) }
        }

        companion object {
            fun capture(
                map: GameMap,
                humanSide: Int,
            ): World {
                val owners = linkedMapOf<String, Int>()
                for (row in 0 until map.rows) {
                    for (col in 0 until map.cols) {
                        val hex = map.map?.getOrNull(row)?.getOrNull(col) ?: continue
                        if (hex.victorySide >= 0) owners["$row,$col"] = hex.owner
                    }
                }
                val visible =
                    map
                        .getUnits()
                        .filter { !it.destroyed && it.player?.side != humanSide }
                        .filter { UnitConcealment.isVisibleTo(it, humanSide) }
                        .map { it.id }
                        .toSet()
                return World(owners, visible)
            }
        }
    }

    // -- Rejections ----------------------------------------------------------------------------

    /** A coded refusal: [REASON_PREFIX] + key (+ ":" + argument), localized by the client. */
    private fun deny(
        code: MultiplayerErrorCode,
        key: String,
        arg: String? = null,
    ): CommandRejection = CommandRejection(code, REASON_PREFIX + key + (arg?.let { ":$it" } ?: ""))

    const val REASON_PREFIX = "coop."
}
