@file:Suppress("UnusedParameter")

package org.osada.multiplayer.sync

import org.osada.multiplayer.protocol.MultiplayerMessageType
import org.osada.multiplayer.protocol.ServerMessage
import org.osada.multiplayer.protocol.ServerProtocolMessage

class SnapshotSynchronizer(
    private val adapter: GameStateNetworkAdapter,
    private val validator: MultiplayerSnapshotValidator,
    private val hasher: CanonicalStateHasher,
    private val resyncRequester: (String) -> Unit = {},
) {
    private var revision = -1L
    private var stateHash = ""

    fun apply(snapshot: MultiplayerSnapshot) {
        val validation = validator.validate(snapshot)
        require(validation.valid) { validation.errors.joinToString() }
        require(snapshot.revision >= revision) { "Snapshot revision moved backwards" }
        adapter.restoreCanonicalNetworkState(snapshot.gameStateJson)
        val restored = adapter.validateRestoredNetworkState()
        require(restored.valid) { restored.errors.joinToString() }
        revision = snapshot.revision
        stateHash = hasher.hash(snapshot)
    }

    fun acceptCommit(message: ServerMessage) {
        val snapshotMessage =
            message.type == MultiplayerMessageType.COMMAND_COMMIT ||
                message.type == MultiplayerMessageType.SNAPSHOT
        require(snapshotMessage)
        require(message is ServerProtocolMessage)
        runCatching { apply(MultiplayerSnapshotJson.decode(message.payloadJson)) }
            .onFailure { requestResync(it.message ?: "Snapshot commit failed") }
    }

    fun requestResync(reason: String) {
        resyncRequester(reason)
    }

    fun currentRevision(): Long = revision

    fun currentStateHash(): String = stateHash
}
