package org.osada.mpserver

import java.io.File

/**
 * Runtime configuration of the self-hosted room server.
 *
 * Every value is read from the environment so the same jar runs unchanged on a laptop and on the
 * VPS; `scripts/deploy/osada.service` is the only place that pins production values.
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val webRoot: File?,
    val allowedOrigins: Set<String>,
    val maxRooms: Int,
    val maxParticipantsPerRoom: Int,
    val roomIdleTimeoutMillis: Long,
    val disconnectGraceMillis: Long,
    val authorityTimeoutMillis: Long,
    val maxFrameBytes: Long,
) {
    companion object {
        const val DEFAULT_PORT = 8090

        fun fromEnvironment(env: (String) -> String? = System::getenv): ServerConfig =
            ServerConfig(
                host = env("OSADA_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
                port = env("OSADA_PORT")?.toIntOrNull() ?: DEFAULT_PORT,
                webRoot =
                    env("OSADA_WEB_ROOT")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::File),
                // Empty means "accept any Origin". Fill it in once the game has a domain, so a
                // random page cannot open sockets against this server from a visitor's browser.
                allowedOrigins =
                    env("OSADA_ALLOWED_ORIGINS")
                        ?.split(',')
                        ?.map { it.trim().trimEnd('/') }
                        ?.filter { it.isNotEmpty() }
                        ?.toSet()
                        .orEmpty(),
                maxRooms = env("OSADA_MAX_ROOMS")?.toIntOrNull() ?: DEFAULT_MAX_ROOMS,
                maxParticipantsPerRoom =
                    env("OSADA_MAX_PARTICIPANTS")?.toIntOrNull()
                        ?: DEFAULT_MAX_PARTICIPANTS,
                roomIdleTimeoutMillis =
                    env("OSADA_ROOM_IDLE_MINUTES")?.toLongOrNull()?.times(MILLIS_PER_MINUTE)
                        ?: DEFAULT_ROOM_IDLE_MILLIS,
                disconnectGraceMillis =
                    env("OSADA_DISCONNECT_GRACE_SECONDS")?.toLongOrNull()?.times(MILLIS_PER_SECOND)
                        ?: DEFAULT_DISCONNECT_GRACE_MILLIS,
                authorityTimeoutMillis =
                    env("OSADA_AUTHORITY_TIMEOUT_SECONDS")?.toLongOrNull()?.times(MILLIS_PER_SECOND)
                        ?: DEFAULT_AUTHORITY_TIMEOUT_MILLIS,
                maxFrameBytes = env("OSADA_MAX_FRAME_BYTES")?.toLongOrNull() ?: DEFAULT_MAX_FRAME_BYTES,
            )

        private const val MILLIS_PER_SECOND = 1_000L
        private const val MILLIS_PER_MINUTE = 60_000L

        // Each room retains one committed snapshot of a whole game state. With the 192 MB heap this
        // host can spare, a few dozen rooms is the honest ceiling; raise it together with -Xmx.
        private const val DEFAULT_MAX_ROOMS = 24
        private const val DEFAULT_MAX_PARTICIPANTS = 2
        private const val DEFAULT_ROOM_IDLE_MILLIS = 180 * MILLIS_PER_MINUTE
        private const val DEFAULT_DISCONNECT_GRACE_MILLIS = 120 * MILLIS_PER_SECOND
        private const val DEFAULT_AUTHORITY_TIMEOUT_MILLIS = 45 * MILLIS_PER_SECOND

        // A full game-state snapshot travels as one text frame. 8 MiB leaves generous head-room
        // over the largest scenario measured so far and still bounds a hostile client.
        private const val DEFAULT_MAX_FRAME_BYTES = 8L * 1024 * 1024
    }
}
