package org.osada.multiplayer.command

import kotlin.js.json

data class HexCoordinate(
    val x: Int,
    val y: Int,
)

sealed interface GameCommand {
    val actorPlayerId: Int
}

data class MoveUnit(
    val unitId: Int,
    val from: HexCoordinate,
    val to: HexCoordinate,
    val path: List<HexCoordinate>,
    override val actorPlayerId: Int,
) : GameCommand

data class AttackUnit(
    val attackerUnitId: Int,
    val defenderUnitId: Int,
    override val actorPlayerId: Int,
) : GameCommand

data class ResupplyUnit(
    val unitId: Int,
    override val actorPlayerId: Int,
) : GameCommand

data class ReinforceUnit(
    val unitId: Int,
    val strengthPoints: Int?,
    override val actorPlayerId: Int,
) : GameCommand

data class MountUnit(
    val unitId: Int,
    val transportEquipmentId: Int?,
    override val actorPlayerId: Int,
) : GameCommand

data class UnmountUnit(
    val unitId: Int,
    override val actorPlayerId: Int,
) : GameCommand

data class DeployUnit(
    val unitId: Int,
    val destination: HexCoordinate,
    override val actorPlayerId: Int,
) : GameCommand

data class UndeployUnit(
    val unitId: Int,
    override val actorPlayerId: Int,
) : GameCommand

data class PurchaseUnit(
    val equipmentId: Int,
    val transportEquipmentId: Int?,
    override val actorPlayerId: Int,
) : GameCommand

data class UpgradeUnit(
    val unitId: Int,
    val equipmentId: Int,
    val transportEquipmentId: Int?,
    override val actorPlayerId: Int,
) : GameCommand

data class DisbandUnit(
    val unitId: Int,
    override val actorPlayerId: Int,
) : GameCommand

data class ReorderReserve(
    val unitId: Int,
    val destinationIndex: Int,
    override val actorPlayerId: Int,
) : GameCommand

data class SetUnitAssignment(
    val unitId: Int,
    val assignedParticipantId: String?,
    override val actorPlayerId: Int,
) : GameCommand

data class EndTurnReady(
    val ready: Boolean,
    override val actorPlayerId: Int,
) : GameCommand

data class EndPlayerTurn(
    override val actorPlayerId: Int,
) : GameCommand

data class ChooseCampaignDialogueOption(
    val dialogueId: String,
    val optionId: String,
    override val actorPlayerId: Int,
) : GameCommand

data class ContinueCampaign(
    val outcome: String,
    override val actorPlayerId: Int,
) : GameCommand

fun GameCommand.kind(): String = this::class.simpleName ?: error("Anonymous game command")

@Suppress("CyclomaticComplexMethod")
fun GameCommand.toPayloadJson(): String {
    val payload = json("kind" to kind(), "actorPlayerId" to actorPlayerId)
    when (this) {
        is MoveUnit -> {
            payload["unitId"] = unitId
            payload["from"] = from.toJson()
            payload["to"] = to.toJson()
            payload["path"] = path.map { it.toJson() }.toTypedArray()
        }
        is AttackUnit -> {
            payload["attackerUnitId"] = attackerUnitId
            payload["defenderUnitId"] = defenderUnitId
        }
        is ResupplyUnit -> payload["unitId"] = unitId
        is ReinforceUnit -> {
            payload["unitId"] = unitId
            payload["strengthPoints"] = strengthPoints
        }
        is MountUnit -> {
            payload["unitId"] = unitId
            payload["transportEquipmentId"] = transportEquipmentId
        }
        is UnmountUnit -> payload["unitId"] = unitId
        is DeployUnit -> {
            payload["unitId"] = unitId
            payload["destination"] = destination.toJson()
        }
        is UndeployUnit -> payload["unitId"] = unitId
        is PurchaseUnit -> {
            payload["equipmentId"] = equipmentId
            payload["transportEquipmentId"] = transportEquipmentId
        }
        is UpgradeUnit -> {
            payload["unitId"] = unitId
            payload["equipmentId"] = equipmentId
            payload["transportEquipmentId"] = transportEquipmentId
        }
        is DisbandUnit -> payload["unitId"] = unitId
        is ReorderReserve -> {
            payload["unitId"] = unitId
            payload["destinationIndex"] = destinationIndex
        }
        is SetUnitAssignment -> {
            payload["unitId"] = unitId
            payload["assignedParticipantId"] = assignedParticipantId
        }
        is EndTurnReady -> payload["ready"] = ready
        is EndPlayerTurn -> Unit
        is ChooseCampaignDialogueOption -> {
            payload["dialogueId"] = dialogueId
            payload["optionId"] = optionId
        }
        is ContinueCampaign -> payload["outcome"] = outcome
    }
    return JSON.stringify(payload)
}

private fun HexCoordinate.toJson(): dynamic = json("x" to x, "y" to y)
