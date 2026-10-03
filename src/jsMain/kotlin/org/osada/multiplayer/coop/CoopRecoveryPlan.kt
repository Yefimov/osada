package org.osada.multiplayer.coop

import kotlin.js.json

/** Small, public lobby description. The game snapshot stays on the restoring host until Start. */
class CoopRecoveryPlan(
    val value: dynamic,
) {
    val commanderIds: List<String> = (value.commanders as Array<dynamic>).map { it.id as String }
    val bindings: Map<String, String> =
        commanderIds
            .mapNotNull { id ->
                (value.bindings[id] as? String)?.let { id to it }
            }.toMap()

    fun name(id: String): String = (value.commanders as Array<dynamic>).first { it.id == id }.name as String

    companion object {
        fun create(
            checkpoint: CoopCheckpoint,
            bindings: Map<String, String>,
        ): CoopRecoveryPlan {
            val assignments = json()
            bindings.forEach { (id, participant) -> assignments[id] = participant }
            return CoopRecoveryPlan(
                json(
                    "snapshotHash" to checkpoint.snapshot.stateHash,
                    "scenarioFile" to checkpoint.scenarioFile,
                    "campaignFile" to checkpoint.campaignFile,
                    "difficulty" to checkpoint.difficulty,
                    "turn" to checkpoint.turn,
                    "revision" to checkpoint.snapshot.revision.toDouble(),
                    "canReplaceCommander" to checkpoint.canReplaceCommander,
                    "commanders" to
                        checkpoint.state.commanders
                            .map { json("id" to it.id, "name" to it.name) }
                            .toTypedArray(),
                    "bindings" to assignments,
                ),
            )
        }
    }
}
