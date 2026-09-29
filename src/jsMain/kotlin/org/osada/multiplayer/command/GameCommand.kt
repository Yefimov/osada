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

/**
 * OG 9.9's two minefield actions (`docs/og-fidelity-plan.md` C.1).
 *
 * They belong in the command set for the same reason every other unit action does, and for one more:
 * [ClearMines] is the only unit action whose OUTCOME is rolled rather than derived, so it is the one
 * command that would visibly diverge between peers if it were left out of the replayed path. It does
 * not carry the outcome — the roll comes from the shared seeded stream
 * (`rules/GameRandomSource`), so both peers resolve the same attempt from the same cursor.
 */
data class LayMines(
    val unitId: Int,
    override val actorPlayerId: Int,
) : GameCommand

/**
 * OG 9.2's barrage: shell a hex nobody can see (`rules/Barrage`, added 2026-08-26).
 *
 * Carries the TARGET and not the outcome, for the same reason [LayMines] carries neither: the
 * success roll comes from the shared seeded stream, so both peers resolve the same shot from the
 * same cursor. Replaying it through `GameMap.fireBarrage` is what keeps a wrecked bridge, a razed
 * city and a rubbled hex identical on both sides.
 */
data class BarrageHex(
    val unitId: Int,
    val target: HexCoordinate,
    override val actorPlayerId: Int,
) : GameCommand

data class ClearMines(
    val unitId: Int,
    override val actorPlayerId: Int,
) : GameCommand

/**
 * OG 9.3's Build and Repair order (`rules/Engineering`).
 *
 * One command for all six chips, carrying the job by NAME rather than by ordinal: an
 * `EngineeringWork` ordinal would silently change meaning if a future job were inserted in the
 * middle of that enum, and a command in flight between two builds must not mean two things. An
 * unknown name is rejected at validation, so a peer running an older build refuses the order
 * instead of guessing at it.
 *
 * It carries no outcome. Construction is deterministic -- a fixed cost, a fixed duration and a
 * fixed terrain result -- so both peers reach the same hex state from the same command, and unlike
 * [ClearMines] there is no roll to keep in step.
 */
data class BeginEngineering(
    val unitId: Int,
    val work: String,
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

/** The unit orders added for multiplayer rooms on 2026-09-27, grouped so the codec and the
 *  validator can handle them in one branch each. */
sealed interface UnitOrder : GameCommand

/** Boarding a naval, air or rail transport from the transport pool (`GameMap.embarkUnit`). */
data class EmbarkUnit(
    val unitId: Int,
    override val actorPlayerId: Int,
) : UnitOrder

/** Stepping off a pooled transport (`GameMap.disembarkUnit`). */
data class DisembarkUnit(
    val unitId: Int,
    override val actorPlayerId: Int,
) : UnitOrder

/** Boarding a train from the rail transport pool (`GameMap.entrainUnit`). */
data class EntrainUnit(
    val unitId: Int,
    override val actorPlayerId: Int,
) : UnitOrder

/** Leaving a train and returning it to the pool (`GameMap.detrainUnit`). */
data class DetrainUnit(
    val unitId: Int,
    override val actorPlayerId: Int,
) : UnitOrder

/** Buying attachment slots for a formation (`Player.purchaseAttachment`), all of the picker's
 *  pending choices in one order -- a room accepts one outstanding command at a time. */
data class PurchaseAttachment(
    val unitId: Int,
    val slots: List<Int>,
    override val actorPlayerId: Int,
) : UnitOrder

/** OG's cheap replacement: strength for experience (`rules/GreenReplacements`). */
data class GreenReinforceUnit(
    val unitId: Int,
    override val actorPlayerId: Int,
) : UnitOrder

/** Sending a commander away (`rules/LeaderDismissal`, the efile's `rem_leader`). */
data class DismissLeader(
    val unitId: Int,
    override val actorPlayerId: Int,
) : UnitOrder

data class ContinueCampaign(
    val outcome: String,
    override val actorPlayerId: Int,
) : GameCommand

/** OG 9.2's barrage carries a unit and a target hex. Written here rather than inline so
 *  [toPayloadJson] stays inside detekt's length budget. */
private fun BarrageHex.writeBarrage(payload: dynamic) {
    payload["unitId"] = unitId
    payload["target"] = target.toJson()
}

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
        is LayMines -> payload["unitId"] = unitId
        is BarrageHex -> writeBarrage(payload)
        is ClearMines -> payload["unitId"] = unitId
        is BeginEngineering -> putEngineering(payload, unitId, work)
        is DeployUnit -> putDestination(payload, unitId, destination)

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
        is UnitOrder -> writeUnitOrder(payload)
    }
    return JSON.stringify(payload)
}

private fun HexCoordinate.toJson(): dynamic = json("x" to x, "y" to y)

/** [BeginEngineering]'s two fields, as one call so `toPayloadJson` stays inside its length
 *  budget -- it is one branch per command and adding a two-line block to it overflows. */
private fun putEngineering(
    payload: dynamic,
    unitId: Int,
    work: String,
) {
    payload["unitId"] = unitId
    payload["work"] = work
}

/** The unit orders added for rooms on 2026-09-27, written apart for `toPayloadJson`'s length budget. */
private fun UnitOrder.writeUnitOrder(payload: dynamic) {
    when (this) {
        is EmbarkUnit -> payload["unitId"] = unitId
        is DisembarkUnit -> payload["unitId"] = unitId
        is EntrainUnit -> payload["unitId"] = unitId
        is DetrainUnit -> payload["unitId"] = unitId
        is PurchaseAttachment -> putAttachment(payload, unitId, slots)
        is GreenReinforceUnit -> payload["unitId"] = unitId
        is DismissLeader -> payload["unitId"] = unitId
    }
}

/** A unit and a destination hex -- [DeployUnit]'s fields. */
private fun putDestination(
    payload: dynamic,
    unitId: Int,
    destination: HexCoordinate,
) {
    payload["unitId"] = unitId
    payload["destination"] = destination.toJson()
}

/** [PurchaseAttachment]'s two fields, out of `toPayloadJson` for its length budget. */
private fun putAttachment(
    payload: dynamic,
    unitId: Int,
    slots: List<Int>,
) {
    payload["unitId"] = unitId
    payload["slots"] = slots.toTypedArray()
}
