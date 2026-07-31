package org.osada.multiplayer.command

import org.osada.multiplayer.model.PendingCommand
import org.osada.multiplayer.protocol.ClientProtocolMessage
import org.osada.multiplayer.protocol.MultiplayerMessageType
import org.osada.multiplayer.protocol.ServerMessage
import org.osada.multiplayer.protocol.ServerProtocolMessage
import org.osada.multiplayer.sync.MultiplayerSnapshotJson
import org.osada.multiplayer.sync.SnapshotSynchronizer
import org.osada.multiplayer.transport.MultiplayerTransport
import org.osada.multiplayer.transport.Subscription

// Command transport wiring is the reusable online-multiplayer path. The current local two-tab
// mode uses its in-process channel directly, so this stays dormant until remote transport lands.
@Suppress("unused")
class MultiplayerCommandRuntime(
    private val transport: MultiplayerTransport,
    private val authorityProcessor: AuthorityCommandProcessor,
    private val synchronizer: SnapshotSynchronizer,
    private val mayExecuteAuthority: () -> Boolean,
    private val rejectionHandler: (CommandRejection) -> Unit = {},
) {
    private var subscription: Subscription? = null

    fun start() {
        if (subscription != null) return
        subscription = transport.onMessage(::handleMessage)
    }

    fun stop() {
        subscription?.unsubscribe()
        subscription = null
    }

    private fun handleMessage(message: ServerMessage) {
        when (message.type) {
            MultiplayerMessageType.COMMAND_FOR_AUTHORITY -> processAuthorityCommand(message)
            MultiplayerMessageType.COMMAND_COMMIT, MultiplayerMessageType.SNAPSHOT ->
                synchronizer.acceptCommit(message)

            MultiplayerMessageType.COMMAND_REJECT -> handleRejection(message)
            else -> Unit
        }
    }

    private fun processAuthorityCommand(message: ServerMessage) {
        if (!mayExecuteAuthority() || message !is ServerProtocolMessage) return
        val pending = decodePending(message.payloadJson)
        when (val decision = authorityProcessor.process(pending)) {
            is AuthorityDecision.Commit ->
                transport.send(
                    ClientProtocolMessage(
                        MultiplayerMessageType.COMMAND_COMMIT,
                        MultiplayerSnapshotJson.encode(decision.snapshot),
                    ),
                )

            is AuthorityDecision.Reject ->
                transport.send(
                    ClientProtocolMessage(
                        MultiplayerMessageType.COMMAND_REJECT,
                        rejectionJson(decision.rejection),
                    ),
                )
        }
    }

    @Suppress("ReturnCount")
    private fun handleRejection(message: ServerMessage) {
        if (message !is ServerProtocolMessage) return
        val value = JSON.parse<dynamic>(message.payloadJson)
        val codeName = value.code as? String ?: return
        val code =
            org.osada.multiplayer.protocol.MultiplayerErrorCode.entries
                .firstOrNull { it.name == codeName }
                ?: return
        rejectionHandler(CommandRejection(code, value.message as? String))
    }

    private fun decodePending(source: String): PendingCommand {
        val value = JSON.parse<dynamic>(source)
        val sequence = (value.serverSequence as Number).toLong()
        return PendingCommand(
            clientMessageId = value.clientMessageId as? String ?: "server-$sequence",
            participantId = value.participantId as String,
            serverSequence = sequence,
            expectedRevision = (value.expectedRevision as Number).toLong(),
            authorityEpoch = (value.authorityEpoch as Number).toLong(),
            command = GameCommandJson.decode(JSON.stringify(value.command)),
        )
    }

    private fun rejectionJson(rejection: CommandRejection): String =
        JSON.stringify(
            kotlin.js.json(
                "code" to rejection.code.name,
                "message" to rejection.message,
            ),
        )
}
