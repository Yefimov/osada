@file:Suppress("UnusedParameter")

package org.osada.multiplayer.diagnostics

import org.osada.multiplayer.transport.TransportState
import kotlin.js.json

data class MultiplayerDiagnostics(
    val gameBuild: String,
    val protocolVersion: Int,
    val roomCode: String?,
    val revision: Long?,
    val stateHash: String?,
    val transportState: TransportState,
    val recentMessageMetadata: List<String>,
    val recentErrorCodes: List<String>,
)

class MultiplayerDiagnosticsService(
    private val gameBuild: String,
    private val protocolVersion: Int,
    private val transportState: () -> TransportState,
    private val roomCode: () -> String? = { null },
    private val revision: () -> Long? = { null },
    private val stateHash: () -> String? = { null },
) {
    private val messages = ArrayDeque<String>()
    private val errors = ArrayDeque<String>()

    fun capture(): MultiplayerDiagnostics =
        MultiplayerDiagnostics(
            gameBuild = gameBuild,
            protocolVersion = protocolVersion,
            roomCode = roomCode(),
            revision = revision(),
            stateHash = stateHash(),
            transportState = transportState(),
            recentMessageMetadata = messages.toList(),
            recentErrorCodes = errors.toList(),
        )

    fun exportJson(): String {
        val value = capture()
        return JSON.stringify(
            json(
                "gameBuild" to value.gameBuild,
                "protocolVersion" to value.protocolVersion,
                "roomCode" to value.roomCode,
                "revision" to value.revision?.toDouble(),
                "stateHash" to value.stateHash,
                "transportState" to value.transportState.name,
                "recentMessageMetadata" to value.recentMessageMetadata.toTypedArray(),
                "recentErrorCodes" to value.recentErrorCodes.toTypedArray(),
            ),
        )
    }

    fun recordMessageMetadata(metadata: String) {
        appendBounded(messages, sanitize(metadata))
    }

    fun recordError(
        code: String,
        correlationId: String?,
    ) {
        val value = if (correlationId.isNullOrBlank()) code else "$code:$correlationId"
        appendBounded(errors, sanitize(value))
    }

    private fun appendBounded(
        target: ArrayDeque<String>,
        value: String,
    ) {
        if (target.size == MAX_ENTRIES) target.removeFirst()
        target.addLast(value)
    }

    private fun sanitize(value: String): String =
        value
            .replace(Regex("(?i)(invite|reconnect|token)=\\S+"), "$1=[redacted]")
            .take(MAX_ENTRY_LENGTH)

    private companion object {
        const val MAX_ENTRIES = 50
        const val MAX_ENTRY_LENGTH = 256
    }
}
