package org.osada.multiplayer.command

import org.osada.EndGameType
import org.osada.Game
import org.osada.GameHolder
import org.osada.continueCampaign
import org.osada.evaluateScenarioEvents
import org.osada.handleMoveVictory
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.UndoInvalidation
import org.osada.model.beginEngineering
import org.osada.model.buyUnit
import org.osada.model.clearMinefield
import org.osada.model.deployPlayerUnit
import org.osada.model.disbandUnit
import org.osada.model.disembarkUnit
import org.osada.model.embarkUnit
import org.osada.model.executeAttack
import org.osada.model.fireBarrage
import org.osada.model.getAttackableUnit
import org.osada.model.getEliminationWinner
import org.osada.model.getPlayer
import org.osada.model.getPlayers
import org.osada.model.getUnitById
import org.osada.model.layMinefield
import org.osada.model.mountUnit
import org.osada.model.moveByRail
import org.osada.model.moveUnit
import org.osada.model.openRailDestinations
import org.osada.model.purchaseAttachment
import org.osada.model.reinforceUnit
import org.osada.model.resupplyUnit
import org.osada.model.sellUnit
import org.osada.model.surpriseTarget
import org.osada.model.undeployUnit
import org.osada.model.unmountUnit
import org.osada.model.updateUnitList
import org.osada.model.upgradeUnit
import org.osada.multiplayer.model.MultiplayerSession
import org.osada.multiplayer.protocol.MultiplayerErrorCode
import org.osada.rules.Barrage
import org.osada.rules.CombatPositioning
import org.osada.rules.Engineering
import org.osada.rules.EngineeringWork
import org.osada.rules.GreenReplacements
import org.osada.rules.LeaderDismissal
import org.osada.rules.Minefields
import org.osada.rules.SupplyRules
import org.osada.rules.UnitPredicates

class OsadaGameCommandValidator(
    private val gameProvider: () -> Game? = { GameHolder.instance },
) : GameCommandValidator {
    @Suppress("ReturnCount")
    override fun validate(
        command: GameCommand,
        session: MultiplayerSession?,
    ): CommandValidation {
        val map =
            gameProvider()?.scenario?.map
                ?: return rejected(MultiplayerErrorCode.INVALID_MESSAGE, "No active scenario")
        if (map.currentPlayer?.id != command.actorPlayerId) {
            return rejected(MultiplayerErrorCode.NOT_ACTIVE_PLAYER)
        }
        if (session != null && !session.controlsPlayer(command.actorPlayerId)) {
            return rejected(MultiplayerErrorCode.NOT_YOUR_CONTROL_SCOPE)
        }
        return validateCommand(map, command, session)
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun validateCommand(
        map: GameMap,
        command: GameCommand,
        session: MultiplayerSession?,
    ): CommandValidation {
        val unit = command.unitIdOrNull()?.let { map.findCommandUnit(it) }
        if (command.unitIdOrNull() != null && unit == null) {
            return rejected(MultiplayerErrorCode.INVALID_MESSAGE, "Unknown unit")
        }
        if (unit != null && unit.owner != command.actorPlayerId) {
            return rejected(MultiplayerErrorCode.NOT_YOUR_CONTROL_SCOPE)
        }
        if (unit != null && session != null && !session.controlsUnit(unit.owner, unit.id)) {
            return rejected(MultiplayerErrorCode.NOT_YOUR_CONTROL_SCOPE)
        }
        val legal =
            when (command) {
                is MoveUnit ->
                    unit != null &&
                        unit.isDeployed &&
                        !unit.destroyed &&
                        !unit.hasMoved &&
                        map.map?.getOrNull(command.to.y)?.getOrNull(command.to.x) != null

                is AttackUnit -> {
                    val defender = map.getUnitById(command.defenderUnitId)
                    unit != null &&
                        defender != null &&
                        !unit.hasFired &&
                        !unit.destroyed &&
                        unit.player?.side != defender.player?.side &&
                        isOfferedTarget(map, unit, defender)
                }

                is ResupplyUnit -> unit != null && SupplyRules.canResupply(map, unit)
                is ReinforceUnit -> unit != null && SupplyRules.canReinforce(map, unit, command.strengthPoints != null)
                is MountUnit -> unit != null && UnitPredicates.canMount(unit)
                is UnmountUnit -> unit != null && UnitPredicates.canUnmount(unit)
                // Eligibility is not re-derived here: `layMinefield`/`clearMinefield` re-check every
                // fact they would be unsafe to assume, precisely because a command handler can reach
                // them with no action chip ever having been drawn. Both return NOT_ALLOWED rather
                // than mutating anything, so a rejected attempt costs no random draw either.
                is LayMines, is ClearMines -> unit != null && Minefields.enabled()
                // The target is NOT re-derived here: `fireBarrage` re-checks range, ammunition and
                // the unspotted condition itself and refuses without spending anything, which is
                // the same contract `layMinefield` has. What is checked is that the hex exists.
                is BarrageHex ->
                    unit != null &&
                        Barrage.enabled() &&
                        map.map?.getOrNull(command.target.y)?.getOrNull(command.target.x) != null
                // Same reasoning, plus one of its own: the job NAME is validated here rather than
                // in `beginEngineering`, because a name this build does not know is a protocol
                // mismatch and must be refused outright, not silently treated as "no work".
                is BeginEngineering ->
                    unit != null &&
                        Engineering.enabled() &&
                        EngineeringWork.entries.any { it.name == command.work }
                is DeployUnit ->
                    map
                        .getPlayer(command.actorPlayerId)
                        .getCoreUnitList()
                        .any { it.id == command.unitId && !it.isDeployed }

                is UndeployUnit -> unit?.isCore == true && unit.isDeployed
                is PurchaseUnit -> command.equipmentId > 0
                is UpgradeUnit -> unit != null && command.equipmentId > 0
                is DisbandUnit -> unit != null
                is ReorderReserve -> command.destinationIndex >= 0
                is SetUnitAssignment -> unit != null
                is EndTurnReady, is EndPlayerTurn, is ChooseCampaignDialogueOption, is ContinueCampaign -> true
                is UnitOrder -> unitOrderLegal(map, command, unit)
            }
        return if (legal) CommandValidation.Accepted else rejected(MultiplayerErrorCode.UNIT_ALREADY_ACTED)
    }

    /**
     * The unit orders added for rooms on 2026-09-27. The model functions re-check their own
     * preconditions and refuse without mutating anything, so these confirm the unit exists (and is
     * not destroyed), the efile allows leader dismissal, and a rail destination is a real hex.
     */
    private fun unitOrderLegal(
        map: GameMap,
        command: UnitOrder,
        unit: GameUnit?,
    ): Boolean =
        unit != null &&
            !unit.destroyed &&
            when (command) {
                is DismissLeader -> LeaderDismissal.enabled()
                is RailMove -> map.map?.getOrNull(command.destination.y)?.getOrNull(command.destination.x) != null
                else -> true
            }

    /**
     * The same answer the attacking player's own screen gives: [defender] stands on a hex of
     * [attacker]'s attack overlay (`CombatPositioning.getUnitAttackCells` -- ammunition, weather,
     * range, line of fire, the air-unit own-hex rule) and is the unit that hex offers as the target
     * (`Hex.getAttackableUnit` -- visibility, concealment, target type). A command that the UI could
     * never have produced -- out of range, at a hidden unit, a grounded aircraft -- is refused rather
     * than resolved.
     */
    private fun isOfferedTarget(
        map: GameMap,
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean {
        val pos = defender.getPos()
        val inOverlay =
            pos != null &&
                CombatPositioning
                    .getUnitAttackCells(map.map, attacker, map.rows, map.cols)
                    .any { it.row == pos.row && it.col == pos.col }
        val offered =
            pos
                ?.let { map.map?.getOrNull(it.row)?.getOrNull(it.col) }
                ?.getAttackableUnit(attacker, UnitPredicates.isAir(defender))
        return inOverlay && offered === defender
    }

    private fun rejected(
        code: MultiplayerErrorCode,
        message: String? = null,
    ): CommandValidation.Rejected = CommandValidation.Rejected(CommandRejection(code, message))
}

class OsadaGameCommandApplier(
    private val gameProvider: () -> Game? = { GameHolder.instance },
    private val assignmentHandler: (Int, String?) -> Unit = { _, _ -> },
    private val readinessHandler: (Int, Boolean) -> Unit = { _, _ -> },
    private val dialogueChoiceHandler: (String, String) -> Boolean = { _, _ -> false },
) : GameCommandApplier {
    @Suppress("CyclomaticComplexMethod")
    override fun apply(command: GameCommand) {
        val game = requireNotNull(gameProvider()) { "No active game" }
        val map = requireNotNull(game.scenario?.map) { "No active scenario" }
        when (command) {
            is MoveUnit -> applyMove(game, map, command)

            // The resulting state reaches every peer in the host's snapshot; no protocol change.
            is AttackUnit -> applyAttack(game, map, command)

            is ResupplyUnit -> map.resupplyUnit(requireUnit(map, command.unitId))
            is ReinforceUnit -> map.reinforceUnit(requireUnit(map, command.unitId), command.strengthPoints != null)
            is MountUnit -> map.mountUnit(requireUnit(map, command.unitId))
            is UnmountUnit -> map.unmountUnit(requireUnit(map, command.unitId))
            is LayMines -> map.layMinefield(requireUnit(map, command.unitId))
            is BarrageHex ->
                map.fireBarrage(requireUnit(map, command.unitId), command.target.y, command.target.x)
            is BeginEngineering ->
                map.beginEngineering(
                    requireUnit(map, command.unitId),
                    EngineeringWork.valueOf(command.work),
                )

            is ClearMines -> map.clearMinefield(requireUnit(map, command.unitId))
            is DeployUnit -> {
                val player = map.getPlayer(command.actorPlayerId)
                val unit = player.getCoreUnitList().first { it.id == command.unitId }
                check(map.deployPlayerUnit(player, unit, command.destination.y, command.destination.x))
            }

            is UndeployUnit -> check(map.undeployUnit(requireUnit(map, command.unitId)))
            is PurchaseUnit ->
                check(
                    map.getPlayer(command.actorPlayerId).buyUnit(
                        command.equipmentId,
                        command.transportEquipmentId ?: -1,
                    ),
                )

            is UpgradeUnit -> check(upgrade(map, command))

            is DisbandUnit -> check(disband(map, command))
            is ReorderReserve -> reorderReserve(map, command)
            is SetUnitAssignment -> assignmentHandler(command.unitId, command.assignedParticipantId)
            is EndTurnReady -> readinessHandler(command.actorPlayerId, command.ready)
            is EndPlayerTurn -> game.endTurn()
            is ChooseCampaignDialogueOption ->
                check(dialogueChoiceHandler(command.dialogueId, command.optionId)) {
                    "Dialogue choice was not accepted"
                }

            is ContinueCampaign -> game.continueCampaign(command.outcome, EndGameType.MOVE_CAPTURE)
            is EmbarkUnit -> map.embarkUnit(requireUnit(map, command.unitId))
            is DisembarkUnit -> map.disembarkUnit(requireUnit(map, command.unitId))
            is RailMove -> railMove(map, command)
            is GreenReinforceUnit -> greenReinforce(map, command)
            is DismissLeader -> LeaderDismissal.dismiss(requireUnit(map, command.unitId))
            is PurchaseAttachment -> {
                val unit = requireUnit(map, command.unitId)
                command.slots.forEach { map.getPlayer(command.actorPlayerId).purchaseAttachment(unit, it) }
            }
        }
    }

    /**
     * A move, and the surprise engagement the single-player path runs after its move animation
     * (`AnimationOrchestrator.finishMoveAnimation`) -- here without the animation, because the host
     * resolves the command and every peer receives the snapshot. The engagement is the same attack
     * transaction (`model/executeAttack`), which clears the surprise once it has been fought.
     */
    private fun applyMove(
        game: Game,
        map: GameMap,
        command: MoveUnit,
    ) {
        val unit = requireUnit(map, command.unitId)
        val result = map.moveUnit(unit, command.to.y, command.to.x)
        if (result.isVictorySide >= 0) game.handleMoveVictory(result.isVictorySide)
        map.surpriseTarget(unit, result)?.let { target ->
            if (!game.gameEnded) {
                map.executeAttack(unit, target)
                map.updateUnitList()
                map.getEliminationWinner()?.let(game::handleMoveVictory)
            }
        }
        unit.isSurprised = false
        // Every arrival can satisfy an authored proximity condition. The local path evaluates them
        // after each move (`AnimationOrchestrator.finishMoveAnimation`); a room used to reach them
        // only at the end of the turn.
        if (!game.gameEnded) game.evaluateScenarioEvents()
    }

    /**
     * The complete attack order, exactly as a local click resolves it. The host then does what the
     * local presenter does after the animation: ends the battle when a side has nothing left, and
     * re-evaluates the scenario's authored conditions when a retreat or an evasion moved a unit.
     */
    private fun applyAttack(
        game: Game,
        map: GameMap,
        command: AttackUnit,
    ) {
        val outcome =
            map.executeAttack(
                requireUnit(map, command.attackerUnitId),
                requireUnit(map, command.defenderUnitId),
            )
        map.updateUnitList()
        map.getEliminationWinner()?.let(game::handleMoveVictory)
        if (!game.gameEnded && (outcome.retreat != null || outcome.result.evasion != null)) {
            game.evaluateScenarioEvents()
        }
    }

    private fun reorderReserve(
        map: GameMap,
        command: ReorderReserve,
    ) {
        val player = map.getPlayer(command.actorPlayerId)
        val units = player.getCoreUnitList().toMutableList()
        val source = units.indexOfFirst { it.id == command.unitId }
        require(source >= 0)
        val unit = units.removeAt(source)
        units.add(command.destinationIndex.coerceIn(0, units.size), unit)
        player.setCoreUnitList(units)
    }

    private fun requireUnit(
        map: GameMap,
        unitId: Int,
    ): GameUnit = requireNotNull(map.findCommandUnit(unitId)) { "Unknown unit $unitId" }

    /** A rail move to an offered station: the destinations are opened first, exactly as the
     *  player's chip opens them, so `moveByRail` validates the hex against the same list. */
    private fun railMove(
        map: GameMap,
        command: RailMove,
    ) {
        val unit = requireUnit(map, command.unitId)
        map.openRailDestinations(unit)
        map.moveByRail(unit, command.destination.y, command.destination.x)
    }

    /** OG's cheap replacement, exactly as the local chip applies it (`UnitContextMenu`). */
    private fun greenReinforce(
        map: GameMap,
        command: GreenReinforceUnit,
    ) {
        val unit = requireUnit(map, command.unitId)
        GreenReplacements.apply(unit, SupplyRules.getReinforceValue(map, unit, false))
        map.undoState.invalidate(unit, UndoInvalidation.IRREVERSIBLE_ACTION)
    }

    /** An upgrade on the map or, for a formation still in the reserve tray, through its player --
     *  the two branches `EquipmentWindowButtons.onUpgrade` takes. */
    private fun upgrade(
        map: GameMap,
        command: UpgradeUnit,
    ): Boolean {
        val transport = command.transportEquipmentId ?: -1
        val reserve = reserveUnit(map, command.actorPlayerId, command.unitId)
        return if (reserve != null) {
            map.getPlayer(command.actorPlayerId).upgradeUnit(reserve, command.equipmentId, transport)
        } else {
            map.upgradeUnit(command.unitId, command.equipmentId, transport)
        }
    }

    /** Disbanding on the map, or SELLING a formation from the reserve tray, as
     *  `EquipmentWindowButtons.onSell` does. */
    private fun disband(
        map: GameMap,
        command: DisbandUnit,
    ): Boolean {
        val player = map.getPlayer(command.actorPlayerId)
        val reserve = reserveUnit(map, command.actorPlayerId, command.unitId)
        return if (reserve != null) {
            val index = player.getCoreUnitList().indexOf(reserve)
            player.sellUnit(reserve).also { sold -> if (sold) player.removeUndeployedCoreUnit(index) }
        } else {
            map.disbandUnit(command.unitId)
        }
    }

    private fun reserveUnit(
        map: GameMap,
        playerId: Int,
        unitId: Int,
    ): GameUnit? = map.getPlayer(playerId).getCoreUnitList().firstOrNull { it.id == unitId && !it.isDeployed }
}

/**
 * The unit a command acts on, or `null` for the commands that act on a player or the run.
 *
 * One exhaustive branch per command, and deliberately not collapsed. The complexity score counts
 * arms rather than decisions -- there is no logic here to simplify -- and exhaustiveness over the
 * sealed hierarchy is the point: adding a command must fail to compile here until someone has said
 * which unit, if any, it belongs to. `GameCommandJson.decode` carries the same suppression for the
 * same reason.
 */
@Suppress("CyclomaticComplexMethod")
private fun GameCommand.unitIdOrNull(): Int? =
    when (this) {
        is AttackUnit -> attackerUnitId
        is MoveUnit -> unitId
        is ResupplyUnit -> unitId
        is ReinforceUnit -> unitId
        is MountUnit -> unitId
        is UnmountUnit -> unitId
        is LayMines -> unitId
        is BarrageHex -> unitId
        is ClearMines -> unitId
        is BeginEngineering -> unitId
        is DeployUnit -> unitId
        is UndeployUnit -> unitId
        is UpgradeUnit -> unitId
        is DisbandUnit -> unitId
        is ReorderReserve -> unitId
        is SetUnitAssignment -> unitId
        is EmbarkUnit -> unitId
        is DisembarkUnit -> unitId
        is RailMove -> unitId
        is PurchaseAttachment -> unitId
        is GreenReinforceUnit -> unitId
        is DismissLeader -> unitId
        is PurchaseUnit, is EndTurnReady, is EndPlayerTurn, is ChooseCampaignDialogueOption, is ContinueCampaign -> null
    }

/**
 * The unit a command names: on the map, or a formation in a player's reserve tray. Deploying,
 * upgrading or selling a reserve formation names a unit that is not on the map, and a lookup that
 * only searched the map refused every one of those commands as "Unknown unit".
 */
internal fun GameMap.findCommandUnit(unitId: Int): GameUnit? =
    getUnitById(unitId) ?: getPlayers().firstNotNullOfOrNull { player ->
        player.getCoreUnitList().firstOrNull { it.id == unitId }
    }
