package org.osada.multiplayer.command

import org.osada.EndGameType
import org.osada.Game
import org.osada.GameHolder
import org.osada.continueCampaign
import org.osada.handleMoveVictory
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.attackUnit
import org.osada.model.buyUnit
import org.osada.model.deployPlayerUnit
import org.osada.model.disbandUnit
import org.osada.model.getPlayer
import org.osada.model.getUnitById
import org.osada.model.mountUnit
import org.osada.model.moveUnit
import org.osada.model.reinforceUnit
import org.osada.model.resupplyUnit
import org.osada.model.undeployUnit
import org.osada.model.unmountUnit
import org.osada.model.upgradeUnit
import org.osada.multiplayer.model.MultiplayerSession
import org.osada.multiplayer.protocol.MultiplayerErrorCode
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
        val unit = command.unitIdOrNull()?.let(map::getUnitById)
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
                        unit.player?.side != defender.player?.side
                }
                is ResupplyUnit -> unit != null && SupplyRules.canResupply(map, unit)
                is ReinforceUnit -> unit != null && SupplyRules.canReinforce(map, unit, command.strengthPoints != null)
                is MountUnit -> unit != null && UnitPredicates.canMount(unit)
                is UnmountUnit -> unit != null && UnitPredicates.canUnmount(unit)
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
            }
        return if (legal) CommandValidation.Accepted else rejected(MultiplayerErrorCode.UNIT_ALREADY_ACTED)
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
            is MoveUnit -> {
                val result = map.moveUnit(requireUnit(map, command.unitId), command.to.y, command.to.x)
                if (result.isVictorySide >= 0) game.handleMoveVictory(result.isVictorySide)
            }
            is AttackUnit ->
                map.attackUnit(
                    requireUnit(map, command.attackerUnitId),
                    requireUnit(map, command.defenderUnitId),
                    supportFire = false,
                )
            is ResupplyUnit -> map.resupplyUnit(requireUnit(map, command.unitId))
            is ReinforceUnit -> map.reinforceUnit(requireUnit(map, command.unitId), command.strengthPoints != null)
            is MountUnit -> map.mountUnit(requireUnit(map, command.unitId))
            is UnmountUnit -> map.unmountUnit(requireUnit(map, command.unitId))
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
            is UpgradeUnit ->
                check(
                    map.upgradeUnit(
                        command.unitId,
                        command.equipmentId,
                        command.transportEquipmentId ?: -1,
                    ),
                )
            is DisbandUnit -> check(map.disbandUnit(command.unitId))
            is ReorderReserve -> reorderReserve(map, command)
            is SetUnitAssignment -> assignmentHandler(command.unitId, command.assignedParticipantId)
            is EndTurnReady -> readinessHandler(command.actorPlayerId, command.ready)
            is EndPlayerTurn -> game.endTurn()
            is ChooseCampaignDialogueOption ->
                check(dialogueChoiceHandler(command.dialogueId, command.optionId)) {
                    "Dialogue choice was not accepted"
                }
            is ContinueCampaign -> game.continueCampaign(command.outcome, EndGameType.MOVE_CAPTURE)
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
    ): GameUnit = requireNotNull(map.getUnitById(unitId)) { "Unknown unit $unitId" }
}

private fun GameCommand.unitIdOrNull(): Int? =
    when (this) {
        is MoveUnit -> unitId
        is AttackUnit -> attackerUnitId
        is ResupplyUnit -> unitId
        is ReinforceUnit -> unitId
        is MountUnit -> unitId
        is UnmountUnit -> unitId
        is DeployUnit -> unitId
        is UndeployUnit -> unitId
        is UpgradeUnit -> unitId
        is DisbandUnit -> unitId
        is ReorderReserve -> unitId
        is SetUnitAssignment -> unitId
        is PurchaseUnit, is EndTurnReady, is EndPlayerTurn, is ChooseCampaignDialogueOption, is ContinueCampaign -> null
    }
