package org.osada.mpserver.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Lenient readers for client payloads: a missing or mistyped field is null, never an exception. */
fun JsonObject.stringOrNull(key: String): String? =
    runCatching { this[key]?.jsonPrimitive }
        .getOrNull()
        ?.takeIf { it.isString }
        ?.content

fun JsonObject.longOrNull(key: String): Long? = runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

fun JsonObject.booleanOrNull(key: String): Boolean? =
    runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

/** Copy of [payload] carrying the id of the participant the server received it from. */
fun withSender(
    payload: JsonObject,
    senderParticipantId: String,
): JsonObject =
    buildJsonObject {
        payload.forEach { (key, value) -> put(key, value) }
        put("senderParticipantId", JsonPrimitive(senderParticipantId))
    }
