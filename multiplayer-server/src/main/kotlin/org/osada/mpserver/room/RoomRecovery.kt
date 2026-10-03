@file:Suppress("ReturnCount") // Explicit shape guards reject partial restores before room mutation.

package org.osada.mpserver.room

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.osada.mpserver.protocol.longOrNull
import org.osada.mpserver.protocol.stringOrNull

/** Checks authenticated seat bindings, not game rules. Runs under the room mutex before any mutation. */
internal object RoomRecovery {
    private const val MAX_PLAN_CHARS = 16384
    private const val MAX_COMMANDERS = 4
    private const val MAX_IDENTIFIER_LENGTH = 64

    fun validPlan(
        room: Room,
        plan: JsonObject,
    ): Boolean {
        val commanders = plan["commanders"] as? JsonArray ?: return false
        val bindings = plan["bindings"] as? JsonObject ?: return false
        val ids = commanders.map { (it as? JsonObject)?.stringOrNull("id") }
        return room.isCoop &&
            plan.toString().length <= MAX_PLAN_CHARS &&
            validHeader(plan) &&
            validRoster(commanders, ids) &&
            validBindings(room, bindings, ids)
    }

    private fun validHeader(plan: JsonObject): Boolean {
        val campaign = plan.stringOrNull("campaignFile")
        return Identifiers.isScenarioFile(plan.stringOrNull("scenarioFile").orEmpty()) &&
            (campaign == null || Identifiers.isCampaignFile(campaign)) &&
            plan.longOrNull("difficulty") in 0L..2L &&
            (plan.longOrNull("revision") ?: -1) >= 0 &&
            plan.stringOrNull("snapshotHash")?.startsWith("sha256:") == true
    }

    private fun validRoster(
        commanders: JsonArray,
        ids: List<String?>,
    ): Boolean =
        commanders.size in 2..MAX_COMMANDERS &&
            ids.all { it != null && it.length in 1..MAX_IDENTIFIER_LENGTH } &&
            ids.toSet().size == ids.size &&
            commanders.all {
                (it as? JsonObject)?.stringOrNull("name")?.length in 1..MAX_IDENTIFIER_LENGTH
            }

    private fun validBindings(
        room: Room,
        bindings: JsonObject,
        ids: List<String?>,
    ): Boolean =
        bindings.keys.all { it in ids } &&
            bindings.values.all {
                room.member((it as? JsonPrimitive)?.content)?.connected == true
            } &&
            bindings.values.toSet().size == bindings.size

    fun validSnapshot(
        room: Room,
        snapshot: JsonObject,
    ): Boolean {
        val plan = room.recovery ?: return false
        if (!validPlan(room, plan)) return false
        val runtime = snapshot["multiplayerState"] as? JsonObject ?: return false
        val coop = runtime["coop"] as? JsonObject ?: return false
        val commanders = coop["commanders"] as? JsonArray ?: return false
        val bindings = plan["bindings"] as? JsonObject ?: return false
        val ready = runtime["readyParticipantIds"] as? JsonArray ?: return false
        return validSeats(room, commanders, bindings, ready) &&
            validAuthority(room, runtime) &&
            validEnvelope(room, plan, runtime, snapshot)
    }

    private fun validSeats(
        room: Room,
        commanders: JsonArray,
        bindings: JsonObject,
        ready: JsonArray,
    ): Boolean {
        val actual =
            commanders.mapNotNull { entry ->
                val commander = entry as? JsonObject ?: return@mapNotNull null
                val id = commander.stringOrNull("id") ?: return@mapNotNull null
                val participant = commander["participantId"] ?: return@mapNotNull null
                id to participant
            }
        val members = room.members.map { it.participantId }.toSet()
        return bindings.size == room.members.size &&
            commanders.size == bindings.size &&
            actual.size == commanders.size &&
            actual.toMap() == bindings &&
            bindings.values.map { (it as? JsonPrimitive)?.content }.toSet() == members &&
            ready.map { (it as? JsonPrimitive)?.content }.toSet() == members
    }

    private fun validAuthority(
        room: Room,
        runtime: JsonObject,
    ): Boolean =
        runtime.stringOrNull("authorityParticipantId") == room.hostParticipantId &&
            runtime.stringOrNull("status") == "RUNNING" &&
            runtime.longOrNull("authorityEpoch") == 0L

    private fun validEnvelope(
        room: Room,
        plan: JsonObject,
        runtime: JsonObject,
        snapshot: JsonObject,
    ): Boolean =
        snapshot.longOrNull("authorityEpoch") == 0L &&
            snapshot.longOrNull("revision") == plan.longOrNull("revision") &&
            runtime.longOrNull("revision") == snapshot.longOrNull("revision") &&
            snapshot.longOrNull("protocolVersion") == 1L &&
            snapshot.longOrNull("snapshotFormatVersion") == 1L &&
            snapshot.stringOrNull("roomConfigHash") == "room:${room.code}" &&
            snapshot["gameState"] is JsonObject &&
            snapshot.stringOrNull("stateHash")?.startsWith("sha256:") == true

    fun invalidBootstrap(
        room: Room,
        payload: JsonObject,
    ): Boolean {
        if (room.recovery == null && !payload.containsKey("recoverySnapshot")) return false
        val snapshot = payload["recoverySnapshot"] as? JsonObject ?: return true
        return !validSnapshot(room, snapshot)
    }
}
