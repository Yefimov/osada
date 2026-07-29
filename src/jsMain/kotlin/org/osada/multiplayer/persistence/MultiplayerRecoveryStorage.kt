package org.osada.multiplayer.persistence

import kotlinx.browser.window
import org.osada.multiplayer.sync.MultiplayerSnapshot
import org.osada.multiplayer.sync.MultiplayerSnapshotJson
import kotlin.js.json

data class MultiplayerRecoveryRecord(
    val roomId: String,
    val roomCode: String,
    val reconnectToken: String,
    val contentManifestHash: String,
    val acknowledgedSnapshot: MultiplayerSnapshot,
)

interface MultiplayerRecoveryStorage {
    fun save(record: MultiplayerRecoveryRecord)

    fun load(roomId: String): MultiplayerRecoveryRecord?

    fun loadLatest(): MultiplayerRecoveryRecord?

    fun remove(roomId: String)

    fun saveDisplayName(displayName: String)

    fun loadDisplayName(): String?
}

class LocalMultiplayerRecoveryStorage : MultiplayerRecoveryStorage {
    override fun save(record: MultiplayerRecoveryRecord) {
        require(record.roomId.isNotBlank())
        val value =
            json(
                "roomId" to record.roomId,
                "roomCode" to record.roomCode,
                "reconnectToken" to record.reconnectToken,
                "contentManifestHash" to record.contentManifestHash,
                "acknowledgedSnapshot" to
                    JSON.parse<dynamic>(
                        MultiplayerSnapshotJson.encode(record.acknowledgedSnapshot),
                    ),
            )
        window.localStorage.setItem(recoveryKey(record.roomId), JSON.stringify(value))
        window.localStorage.setItem(LATEST_ROOM_KEY, record.roomId)
    }

    override fun load(roomId: String): MultiplayerRecoveryRecord? {
        val source = window.localStorage.getItem(recoveryKey(roomId)) ?: return null
        return runCatching {
            val value = JSON.parse<dynamic>(source)
            MultiplayerRecoveryRecord(
                roomId = value.roomId as String,
                roomCode = value.roomCode as String,
                reconnectToken = value.reconnectToken as String,
                contentManifestHash = value.contentManifestHash as String,
                acknowledgedSnapshot = MultiplayerSnapshotJson.decode(JSON.stringify(value.acknowledgedSnapshot)),
            )
        }.getOrNull()
    }

    override fun loadLatest(): MultiplayerRecoveryRecord? = window.localStorage.getItem(LATEST_ROOM_KEY)?.let(::load)

    override fun remove(roomId: String) {
        window.localStorage.removeItem(recoveryKey(roomId))
        if (window.localStorage.getItem(LATEST_ROOM_KEY) == roomId) {
            window.localStorage.removeItem(LATEST_ROOM_KEY)
        }
    }

    override fun saveDisplayName(displayName: String) {
        val sanitized = displayName.trim().take(MAX_DISPLAY_NAME_LENGTH)
        if (sanitized.isEmpty()) {
            window.localStorage.removeItem(DISPLAY_NAME_KEY)
        } else {
            window.localStorage.setItem(DISPLAY_NAME_KEY, sanitized)
        }
    }

    override fun loadDisplayName(): String? = window.localStorage.getItem(DISPLAY_NAME_KEY)

    private fun recoveryKey(roomId: String): String = "osada-mp-recovery-$roomId-v1"

    private companion object {
        const val LATEST_ROOM_KEY = "osada-mp-session-v1"
        const val DISPLAY_NAME_KEY = "osada-mp-display-name-v1"
        const val MAX_DISPLAY_NAME_LENGTH = 40
    }
}
