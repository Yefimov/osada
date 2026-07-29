@file:Suppress("UnusedParameter")

package org.osada.multiplayer.sync

import org.osada.Game
import org.osada.GameHolder
import org.osada.GameStateSerializer
import org.osada.multiplayer.model.MultiplayerRuntimeState

data class MultiplayerSnapshot(
    val snapshotFormatVersion: Int,
    val gameSaveFormatVersion: Int,
    val protocolVersion: Int,
    val gameVersion: String,
    val contentManifestHash: String,
    val roomConfigHash: String,
    val authorityEpoch: Long,
    val revision: Long,
    val createdAt: Long,
    val gameStateJson: String,
    val multiplayerState: MultiplayerRuntimeState,
    val stateHash: String,
)

data class SnapshotValidationResult(
    val valid: Boolean,
    val errors: List<String>,
)

class GameStateNetworkAdapter(
    private val gameProvider: () -> Game? = { GameHolder.instance },
) {
    fun exportCanonicalNetworkState(): String {
        val game = requireNotNull(gameProvider()) { "No active game" }
        return canonicalizeJson(GameStateSerializer.exportGameState(game))
    }

    fun restoreCanonicalNetworkState(gameStateJson: String) {
        require(canonicalizeJson(gameStateJson).isNotEmpty())
        val state = requireNotNull(gameProvider()?.state) { "No active game state" }
        require(state.restoreFromString(gameStateJson)) { "Network game state could not be restored" }
    }

    fun validateRestoredNetworkState(): SnapshotValidationResult {
        val game = gameProvider()
        val errors = mutableListOf<String>()
        if (game == null) errors += "No active game"
        if (game?.scenario == null) errors += "No restored scenario"
        if (game?.scenario?.map?.currentPlayer == null) errors += "No restored current player"
        return SnapshotValidationResult(errors.isEmpty(), errors)
    }
}

class CanonicalStateHasher {
    fun hash(snapshot: MultiplayerSnapshot): String {
        val runtime = snapshot.multiplayerState
        val canonicalPayload =
            buildString {
                append(snapshot.snapshotFormatVersion).append('|')
                append(snapshot.gameSaveFormatVersion).append('|')
                append(snapshot.protocolVersion).append('|')
                append(snapshot.gameVersion).append('|')
                append(snapshot.contentManifestHash).append('|')
                append(snapshot.roomConfigHash).append('|')
                append(snapshot.authorityEpoch).append('|')
                append(snapshot.revision).append('|')
                append(canonicalizeJson(snapshot.gameStateJson)).append('|')
                append(runtime.status.name).append('|')
                append(runtime.revision).append('|')
                append(runtime.authorityParticipantId).append('|')
                append(runtime.authorityEpoch).append('|')
                append(runtime.readyParticipantIds.sorted().joinToString(",")).append('|')
                append(
                    runtime.sharedPrestigeAccounts.entries
                        .sortedBy { it.key }
                        .joinToString(","),
                ).append('|')
                append(
                    runtime.unitAssignments.entries
                        .sortedBy { it.key }
                        .joinToString(","),
                )
            }
        return "sha256:${Sha256.digest(canonicalPayload)}"
    }
}

class MultiplayerSnapshotValidator(
    private val expectedProtocolVersion: Int = 1,
    private val expectedSnapshotFormatVersion: Int = 1,
    private val hasher: CanonicalStateHasher = CanonicalStateHasher(),
) {
    fun validate(snapshot: MultiplayerSnapshot): SnapshotValidationResult {
        val errors = mutableListOf<String>()
        if (snapshot.protocolVersion != expectedProtocolVersion) errors += "Protocol version mismatch"
        if (snapshot.snapshotFormatVersion != expectedSnapshotFormatVersion) errors += "Snapshot format mismatch"
        if (snapshot.revision < 0) errors += "Negative revision"
        if (snapshot.authorityEpoch < 0) errors += "Negative authority epoch"
        if (snapshot.multiplayerState.revision != snapshot.revision) errors += "Runtime revision mismatch"
        if (snapshot.multiplayerState.authorityEpoch != snapshot.authorityEpoch) errors += "Authority epoch mismatch"
        runCatching { canonicalizeJson(snapshot.gameStateJson) }
            .onFailure { errors += "Invalid game state JSON" }
        if (snapshot.stateHash != hasher.hash(snapshot)) errors += "State hash mismatch"
        return SnapshotValidationResult(errors.isEmpty(), errors)
    }
}

internal fun canonicalizeJson(source: String): String = canonicalizeValue(JSON.parse<dynamic>(source))

private fun canonicalizeValue(value: dynamic): String =
    when {
        value == null -> "null"
        js("Array.isArray(value)") as Boolean ->
            (0 until (value.length as Number).toInt())
                .joinToString(prefix = "[", postfix = "]") { canonicalizeValue(value[it]) }
        jsTypeOf(value) == "object" -> {
            val keys = (js("Object.keys(value)") as Array<String>).sorted()
            keys.joinToString(prefix = "{", postfix = "}") { key ->
                "${JSON.stringify(key)}:${canonicalizeValue(value[key])}"
            }
        }
        else -> JSON.stringify(value)
    }
