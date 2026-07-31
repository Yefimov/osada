package org.osada.multiplayer.transport

import org.osada.multiplayer.protocol.ClientMessage
import org.osada.multiplayer.protocol.ClientProtocolMessage
import org.osada.multiplayer.protocol.MultiplayerProtocolCodec
import org.osada.multiplayer.protocol.ServerMessage
import kotlin.random.Random

class InMemoryTransport(
    private val faultProfile: NetworkFaultProfile? = null,
) : MultiplayerTransport {
    private val listeners = mutableSetOf<(ServerMessage) -> Unit>()
    internal var connectRequest: ConnectRequest? = null
    private var currentState = TransportState.DISCONNECTED

    override val state: TransportState
        get() = currentState

    override fun connect(request: ConnectRequest) {
        check(currentState == TransportState.DISCONNECTED)
        currentState = TransportState.CONNECTING
        connectRequest = request
        InMemoryRoomServer.connect(this)
        currentState = TransportState.CONNECTED
    }

    override fun send(message: ClientMessage) {
        check(currentState == TransportState.CONNECTED)
        val protocolMessage = message as? ClientProtocolMessage ?: error("Unsupported in-memory message")
        InMemoryRoomServer.receive(this, protocolMessage)
    }

    override fun close(reason: String?) {
        if (currentState == TransportState.DISCONNECTED) return
        currentState = TransportState.CLOSING
        InMemoryRoomServer.disconnect(this)
        connectRequest = null
        currentState = TransportState.DISCONNECTED
    }

    override fun onMessage(listener: (ServerMessage) -> Unit): Subscription {
        listeners += listener
        return Subscription { listeners -= listener }
    }

    internal fun deliver(message: ServerMessage) {
        val profile = faultProfile
        if (profile != null && Random.nextDouble() < profile.dropProbability) return
        val deliveries = if (profile != null && Random.nextDouble() < profile.duplicateProbability) 2 else 1
        repeat(deliveries) {
            val delay =
                profile?.latencyMs?.let { range ->
                    if (range.isEmpty()) 0 else Random.nextInt(range.first, range.last + 1)
                } ?: 0
            if (delay == 0) {
                listeners.toList().forEach { it(message) }
            } else {
                js("setTimeout")({ listeners.toList().forEach { it(message) } }, delay)
            }
        }
    }
}

class BroadcastChannelTransport(
    private val channelPrefix: String = "osada-mp",
) : MultiplayerTransport {
    private val codec = MultiplayerProtocolCodec()
    private val listeners = mutableSetOf<(ServerMessage) -> Unit>()
    private var channel: dynamic = null
    private var currentState = TransportState.DISCONNECTED

    override val state: TransportState
        get() = currentState

    override fun connect(request: ConnectRequest) {
        check(currentState == TransportState.DISCONNECTED)
        val room = request.roomCode ?: error("BroadcastChannel transport requires a room code")
        currentState = TransportState.CONNECTING
        channel = newBroadcastChannel("$channelPrefix-$room")
        channel.onmessage = { event: dynamic ->
            val raw = event.data as? String
            if (raw != null) {
                runCatching { codec.decodeServer(raw) }
                    .onSuccess { message -> listeners.toList().forEach { it(message) } }
            }
        }
        currentState = TransportState.CONNECTED
    }

    override fun send(message: ClientMessage) {
        check(currentState == TransportState.CONNECTED)
        channel.postMessage(codec.encode(message))
    }

    override fun close(reason: String?) {
        if (currentState == TransportState.DISCONNECTED) return
        currentState = TransportState.CLOSING
        channel?.close()
        channel = null
        currentState = TransportState.DISCONNECTED
    }

    override fun onMessage(listener: (ServerMessage) -> Unit): Subscription {
        listeners += listener
        return Subscription { listeners -= listener }
    }
}

class WebSocketMultiplayerTransport : MultiplayerTransport {
    private val codec = MultiplayerProtocolCodec()
    private val listeners = mutableSetOf<(ServerMessage) -> Unit>()
    private var socket: dynamic = null
    private var currentState = TransportState.DISCONNECTED

    override val state: TransportState
        get() = currentState

    override fun connect(request: ConnectRequest) {
        check(currentState == TransportState.DISCONNECTED)
        currentState = TransportState.CONNECTING
        val parameters =
            listOfNotNull(
                request.roomCode?.let { "room=${encodeURIComponent(it)}" },
                request.inviteToken?.let { "invite=${encodeURIComponent(it)}" },
                request.reconnectToken?.let { "reconnect=${encodeURIComponent(it)}" },
                request.displayName.takeIf { it.isNotBlank() }?.let { "displayName=${encodeURIComponent(it)}" },
            )
        val separator = if ("?" in request.endpoint.webSocketBaseUrl) "&" else "?"
        val url = request.endpoint.webSocketBaseUrl + separator + parameters.joinToString("&")
        socket = newWebSocket(url)
        socket.onopen = { currentState = TransportState.CONNECTED }
        socket.onclose = { currentState = TransportState.DISCONNECTED }
        socket.onerror = {
            currentState =
                if (currentState == TransportState.CONNECTED) {
                    TransportState.RECONNECTING
                } else {
                    TransportState.DISCONNECTED
                }
        }
        socket.onmessage = { event: dynamic ->
            val raw = event.data as? String
            if (raw != null) {
                runCatching { codec.decodeServer(raw) }
                    .onSuccess { message -> listeners.toList().forEach { it(message) } }
            }
        }
    }

    override fun send(message: ClientMessage) {
        check(currentState == TransportState.CONNECTED)
        socket.send(codec.encode(message))
    }

    override fun close(reason: String?) {
        if (currentState == TransportState.DISCONNECTED) return
        currentState = TransportState.CLOSING
        socket?.close(NORMAL_CLOSE_CODE, reason ?: "")
        socket = null
        currentState = TransportState.DISCONNECTED
    }

    override fun onMessage(listener: (ServerMessage) -> Unit): Subscription {
        listeners += listener
        return Subscription { listeners -= listener }
    }
}

@Suppress("UnusedParameter")
private fun encodeURIComponent(value: String): String = js("encodeURIComponent(value)") as String

@Suppress("UnusedParameter")
private fun newBroadcastChannel(name: String): dynamic = js("new BroadcastChannel(name)")

@Suppress("UnusedParameter")
private fun newWebSocket(url: String): dynamic = js("new WebSocket(url)")

private const val NORMAL_CLOSE_CODE = 1000
