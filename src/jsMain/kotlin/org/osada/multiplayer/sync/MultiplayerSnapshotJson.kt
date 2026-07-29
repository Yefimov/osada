@file:Suppress("TooManyFunctions", "UnusedParameter")

package org.osada.multiplayer.sync

import org.osada.multiplayer.model.MatchStatus
import org.osada.multiplayer.model.MultiplayerRuntimeState
import kotlin.js.json

object MultiplayerSnapshotJson {
    fun encode(snapshot: MultiplayerSnapshot): String {
        val runtime = snapshot.multiplayerState
        val value =
            json(
                "snapshotFormatVersion" to snapshot.snapshotFormatVersion,
                "gameSaveFormatVersion" to snapshot.gameSaveFormatVersion,
                "protocolVersion" to snapshot.protocolVersion,
                "gameVersion" to snapshot.gameVersion,
                "contentManifestHash" to snapshot.contentManifestHash,
                "roomConfigHash" to snapshot.roomConfigHash,
                "authorityEpoch" to snapshot.authorityEpoch.toDouble(),
                "revision" to snapshot.revision.toDouble(),
                "createdAt" to snapshot.createdAt.toDouble(),
                "gameState" to JSON.parse<dynamic>(snapshot.gameStateJson),
                "multiplayerState" to encodeRuntime(runtime),
                "stateHash" to snapshot.stateHash,
            )
        return JSON.stringify(value)
    }

    fun decode(source: String): MultiplayerSnapshot {
        val value = JSON.parse<dynamic>(source)
        val runtime = decodeRuntime(value.multiplayerState)
        return MultiplayerSnapshot(
            snapshotFormatVersion = requiredInt(value.snapshotFormatVersion, "snapshotFormatVersion"),
            gameSaveFormatVersion = requiredInt(value.gameSaveFormatVersion, "gameSaveFormatVersion"),
            protocolVersion = requiredInt(value.protocolVersion, "protocolVersion"),
            gameVersion = requiredString(value.gameVersion, "gameVersion"),
            contentManifestHash = requiredString(value.contentManifestHash, "contentManifestHash"),
            roomConfigHash = requiredString(value.roomConfigHash, "roomConfigHash"),
            authorityEpoch = requiredLong(value.authorityEpoch, "authorityEpoch"),
            revision = requiredLong(value.revision, "revision"),
            createdAt = requiredLong(value.createdAt, "createdAt"),
            gameStateJson = JSON.stringify(value.gameState),
            multiplayerState = runtime,
            stateHash = requiredString(value.stateHash, "stateHash"),
        )
    }

    private fun encodeRuntime(runtime: MultiplayerRuntimeState): dynamic =
        json(
            "status" to runtime.status.name,
            "revision" to runtime.revision.toDouble(),
            "authorityParticipantId" to runtime.authorityParticipantId,
            "authorityEpoch" to runtime.authorityEpoch.toDouble(),
            "readyParticipantIds" to runtime.readyParticipantIds.sorted().toTypedArray(),
            "sharedPrestigeAccounts" to stringIntMap(runtime.sharedPrestigeAccounts),
            "unitAssignments" to intStringMap(runtime.unitAssignments),
            "pendingCommand" to null,
        )

    private fun decodeRuntime(value: dynamic): MultiplayerRuntimeState =
        MultiplayerRuntimeState(
            status =
                MatchStatus.entries.firstOrNull { it.name == requiredString(value.status, "status") }
                    ?: error("Unknown match status"),
            revision = requiredLong(value.revision, "runtime.revision"),
            authorityParticipantId = requiredString(value.authorityParticipantId, "authorityParticipantId"),
            authorityEpoch = requiredLong(value.authorityEpoch, "runtime.authorityEpoch"),
            readyParticipantIds = stringSet(value.readyParticipantIds),
            sharedPrestigeAccounts = decodeStringIntMap(value.sharedPrestigeAccounts),
            unitAssignments = decodeIntStringMap(value.unitAssignments),
            pendingCommand = null,
        )

    private fun stringIntMap(values: Map<String, Int>): dynamic {
        val result = js("{}")
        values.forEach { (key, value) -> result[key] = value }
        return result
    }

    private fun intStringMap(values: Map<Int, String>): dynamic {
        val result = js("{}")
        values.forEach { (key, value) -> result[key.toString()] = value }
        return result
    }

    private fun stringSet(value: dynamic): Set<String> {
        require(js("Array.isArray(value)") as Boolean)
        return (0 until (value.length as Number).toInt()).map { requiredString(value[it], "set value") }.toSet()
    }

    private fun decodeStringIntMap(value: dynamic): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        objectKeys(value).forEach { key -> result[key] = requiredInt(value[key], key) }
        return result
    }

    private fun decodeIntStringMap(value: dynamic): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        objectKeys(value).forEach { key -> result[key.toInt()] = requiredString(value[key], key) }
        return result
    }

    private fun objectKeys(value: dynamic): Array<String> = js("Object.keys(value)") as Array<String>

    private fun requiredString(
        value: dynamic,
        field: String,
    ): String = value as? String ?: error("Missing $field")

    private fun requiredInt(
        value: dynamic,
        field: String,
    ): Int = (value as? Number)?.toInt() ?: error("Missing $field")

    private fun requiredLong(
        value: dynamic,
        field: String,
    ): Long = (value as? Number)?.toLong() ?: error("Missing $field")
}
