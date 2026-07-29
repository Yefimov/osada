package org.osada.multiplayer.sync

import org.osada.GameStateSerializer
import org.osada.multiplayer.model.MultiplayerRuntimeState
import kotlin.js.Date

class MultiplayerSnapshotFactory(
    private val adapter: GameStateNetworkAdapter,
    private val hasher: CanonicalStateHasher,
    private val gameVersion: String,
    private val contentManifestHash: String,
    private val roomConfigHash: String,
) {
    fun create(runtimeState: MultiplayerRuntimeState): MultiplayerSnapshot {
        val unhashed =
            MultiplayerSnapshot(
                snapshotFormatVersion = SNAPSHOT_FORMAT_VERSION,
                gameSaveFormatVersion = GameStateSerializer.SAVE_FORMAT_VERSION,
                protocolVersion = PROTOCOL_VERSION,
                gameVersion = gameVersion,
                contentManifestHash = contentManifestHash,
                roomConfigHash = roomConfigHash,
                authorityEpoch = runtimeState.authorityEpoch,
                revision = runtimeState.revision,
                createdAt = Date.now().toLong(),
                gameStateJson = adapter.exportCanonicalNetworkState(),
                multiplayerState = runtimeState,
                stateHash = "",
            )
        return unhashed.copy(stateHash = hasher.hash(unhashed))
    }

    private companion object {
        const val SNAPSHOT_FORMAT_VERSION = 1
        const val PROTOCOL_VERSION = 1
    }
}
