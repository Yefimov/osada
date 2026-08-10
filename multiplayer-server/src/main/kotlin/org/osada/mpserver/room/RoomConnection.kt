package org.osada.mpserver.room

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import org.osada.mpserver.protocol.ErrorCode
import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.Wire

/**
 * One WebSocket connection.
 *
 * Frames are never written from the handler coroutine directly: everything goes through [outbound],
 * which a single writer coroutine drains. That keeps concurrent room broadcasts off the same
 * session object and lets a stalled client be detected instead of blocking the room.
 */
class RoomConnection(
    val id: String,
) {
    val outbound: Channel<String> = Channel(OUTBOUND_CAPACITY)

    /** Set once the connection has created or joined a room. */
    @Volatile
    var room: Room? = null

    @Volatile
    var participantId: String? = null

    private val rateLimiter = RateLimiter(MESSAGES_PER_SECOND, BURST)

    @Volatile
    var overloaded: Boolean = false
        private set

    fun allowMessage(nowMillis: Long): Boolean {
        val allowed = rateLimiter.allow(nowMillis)
        if (!allowed) overloaded = true
        return allowed
    }

    fun send(raw: String) {
        val result = outbound.trySend(raw)
        if (result.isFailure) {
            // A client that cannot keep up with snapshots is disconnected rather than allowed to
            // grow an unbounded queue; it will reconnect and resync from the stored snapshot.
            overloaded = true
            outbound.close()
        }
    }

    fun send(
        type: MessageType,
        payload: JsonObject,
    ) {
        send(Wire.encode(type, payload))
    }

    fun sendError(
        code: ErrorCode,
        message: String,
    ) {
        send(Wire.error(code, message))
    }

    fun close() {
        outbound.close()
    }

    private companion object {
        const val OUTBOUND_CAPACITY = 64
        const val MESSAGES_PER_SECOND = 40.0
        const val BURST = 120.0
    }
}

/** Token bucket guarding a single connection against message floods. */
class RateLimiter(
    private val ratePerSecond: Double,
    private val burst: Double,
) {
    private var tokens = burst
    private var lastRefillMillis = 0L

    fun allow(nowMillis: Long): Boolean {
        if (lastRefillMillis == 0L) lastRefillMillis = nowMillis
        val elapsedSeconds = (nowMillis - lastRefillMillis).coerceAtLeast(0) / MILLIS_PER_SECOND
        tokens = (tokens + elapsedSeconds * ratePerSecond).coerceAtMost(burst)
        lastRefillMillis = nowMillis
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
