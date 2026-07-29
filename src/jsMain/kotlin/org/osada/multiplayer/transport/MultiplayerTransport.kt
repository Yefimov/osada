package org.osada.multiplayer.transport

import org.osada.multiplayer.model.MultiplayerEndpointConfig
import org.osada.multiplayer.protocol.ClientMessage
import org.osada.multiplayer.protocol.ServerMessage

enum class TransportState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    CLOSING,
}

data class ConnectRequest(
    val endpoint: MultiplayerEndpointConfig,
    val roomCode: String?,
    val inviteToken: String?,
    val reconnectToken: String?,
    val displayName: String,
)

fun interface Subscription {
    fun unsubscribe()
}

interface MultiplayerTransport {
    val state: TransportState

    fun connect(request: ConnectRequest)

    fun send(message: ClientMessage)

    fun close(reason: String? = null)

    fun onMessage(listener: (ServerMessage) -> Unit): Subscription
}

data class NetworkFaultProfile(
    val latencyMs: IntRange,
    val dropProbability: Double,
    val duplicateProbability: Double,
    val reorderWindow: Int,
)
