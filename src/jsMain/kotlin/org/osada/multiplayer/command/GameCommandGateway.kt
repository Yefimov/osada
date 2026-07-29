package org.osada.multiplayer.command

import org.osada.multiplayer.model.MultiplayerSession
import org.osada.multiplayer.protocol.ClientProtocolMessage
import org.osada.multiplayer.protocol.MultiplayerErrorCode
import org.osada.multiplayer.protocol.MultiplayerMessageType
import org.osada.multiplayer.transport.MultiplayerTransport
import kotlin.js.Date
import kotlin.js.json
import kotlin.random.Random

enum class CommandSubmissionStatus {
    APPLIED,
    PENDING,
    REJECTED,
}

data class CommandSubmission(
    val clientMessageId: String,
    val status: CommandSubmissionStatus,
    val rejection: CommandRejection? = null,
)

data class CommandRejection(
    val code: MultiplayerErrorCode,
    val message: String? = null,
)

sealed interface CommandValidation {
    data object Accepted : CommandValidation

    data class Rejected(
        val rejection: CommandRejection,
    ) : CommandValidation
}

interface GameCommandGateway {
    fun submit(command: GameCommand): CommandSubmission
}

interface GameCommandValidator {
    fun validate(
        command: GameCommand,
        session: MultiplayerSession?,
    ): CommandValidation
}

interface GameCommandApplier {
    fun apply(command: GameCommand)
}

class OfflineGameCommandGateway(
    private val validator: GameCommandValidator,
    private val applier: GameCommandApplier,
) : GameCommandGateway {
    override fun submit(command: GameCommand): CommandSubmission {
        val messageId = newClientMessageId()
        return when (val validation = validator.validate(command, null)) {
            CommandValidation.Accepted -> {
                applier.apply(command)
                CommandSubmission(messageId, CommandSubmissionStatus.APPLIED)
            }
            is CommandValidation.Rejected ->
                CommandSubmission(messageId, CommandSubmissionStatus.REJECTED, validation.rejection)
        }
    }
}

class MultiplayerGameCommandGateway(
    private val session: MultiplayerSession,
    private val transport: MultiplayerTransport,
) : GameCommandGateway {
    @Suppress("ReturnCount")
    override fun submit(command: GameCommand): CommandSubmission {
        val messageId = newClientMessageId()
        if (!session.controlsPlayer(command.actorPlayerId)) {
            return CommandSubmission(
                messageId,
                CommandSubmissionStatus.REJECTED,
                CommandRejection(MultiplayerErrorCode.NOT_YOUR_CONTROL_SCOPE),
            )
        }
        if (session.runtimeState?.pendingCommand != null) {
            return CommandSubmission(
                messageId,
                CommandSubmissionStatus.REJECTED,
                CommandRejection(MultiplayerErrorCode.STALE_STATE, "Another command is pending"),
            )
        }
        val runtime = requireNotNull(session.runtimeState) { "Multiplayer session has no runtime state" }
        val payload =
            json(
                "clientMessageId" to messageId,
                "expectedRevision" to runtime.revision.toDouble(),
                "authorityEpoch" to runtime.authorityEpoch.toDouble(),
                "command" to JSON.parse<dynamic>(command.toPayloadJson()),
            )
        transport.send(
            ClientProtocolMessage(
                MultiplayerMessageType.COMMAND_PROPOSE,
                JSON.stringify(payload),
            ),
        )
        return CommandSubmission(messageId, CommandSubmissionStatus.PENDING)
    }
}

private fun newClientMessageId(): String =
    "cmd-${Date.now().toLong().toString(ID_RADIX)}-${Random.nextInt().toUInt().toString(ID_RADIX)}"

private const val ID_RADIX = 36
