package org.osada.multiplayer.coop

import org.osada.GameStateSerializer
import org.osada.multiplayer.model.MatchStatus
import org.osada.multiplayer.sync.CanonicalStateHasher
import org.osada.multiplayer.sync.MultiplayerSnapshot
import org.osada.multiplayer.sync.MultiplayerSnapshotJson
import org.osada.multiplayer.sync.MultiplayerSnapshotValidator
import org.osada.multiplayer.sync.canonicalizeJson
import org.osada.rules.ruleset.RulesetCompatibility
import kotlin.js.json

/** A portable checkpoint has no connection credentials. Seats are rebound explicitly in a NEW room. */
class CoopCheckpoint private constructor(
    val snapshot: MultiplayerSnapshot,
) {
    val state: CoopState = CoopStateJson.decode(requireNotNull(snapshot.multiplayerState.coopJson))
    private val game: dynamic = JSON.parse<dynamic>(snapshot.gameStateJson)
    val scenarioFile: String = game.scenario.file as String
    val campaignFile: String? = game.campaign?.file as? String
    val difficulty: Int = (game.campaign?.difficulty as? Number)?.toInt() ?: 0
    val turn: Int = (game.scenario.turn as Number).toInt()

    val canReplaceCommander: Boolean
        get() =
            campaignFile != null &&
                state.missionIndex > 0 &&
                state.preBattle() &&
                state.retiredCommanders.size < CoopState.MAX_RETIRED

    fun replaceCommander(
        id: String,
        name: String,
    ): CoopCheckpoint {
        require(canReplaceCommander) { "Commander replacement is only available between campaign missions" }
        val draft = CoopStateJson.decode(requireNotNull(snapshot.multiplayerState.coopJson))
        val old = requireNotNull(draft.commanderById(id))
        val replacement =
            old.copy(
                id = "c${old.seat + 1}-m${draft.missionIndex}-n${draft.retiredCommanders.size + 1}",
                participantId = null,
                name = CoopCommander.cleanName(name),
                portrait = CoopPortrait.generated(kotlin.random.Random.nextInt()),
                abilityIds = emptyList(),
            )
        require(replacement.name.isNotEmpty() && draft.replaceAtIntermission(id, replacement))
        return fromSnapshot(
            rehash(
                snapshot.copy(
                    multiplayerState = snapshot.multiplayerState.copy(coopJson = CoopStateJson.encode(draft)),
                ),
            ),
        )
    }

    fun encode(): String =
        JSON.stringify(
            json(
                "kind" to KIND,
                "version" to 1,
                "snapshot" to
                    JSON.parse<dynamic>(MultiplayerSnapshotJson.encode(snapshot)),
            ),
        )

    fun bind(
        roomCode: String,
        hostId: String,
        bindings: Map<String, String>,
        members: Set<String>,
    ): MultiplayerSnapshot {
        require(bindings.keys == state.commanders.map { it.id }.toSet()) { "Every saved commander must be assigned" }
        require(bindings.values.toSet() == members && bindings.size == members.size && hostId in members) {
            "Assign each connected participant exactly once"
        }
        val coop = JSON.parse<dynamic>(requireNotNull(snapshot.multiplayerState.coopJson))
        state.commanders.indices.forEach { index ->
            coop.commanders[index].participantId =
                bindings.getValue(state.commanders[index].id)
        }
        return rehash(
            snapshot.copy(
                authorityEpoch = 0,
                roomConfigHash = "room:$roomCode",
                multiplayerState =
                    snapshot.multiplayerState.copy(
                        status = MatchStatus.RUNNING,
                        authorityParticipantId = hostId,
                        authorityEpoch = 0,
                        readyParticipantIds = members,
                        pendingCommand = null,
                        coopJson = JSON.stringify(coop),
                    ),
            ),
        )
    }

    companion object {
        const val MAX_CHARS = 8 * 1024 * 1024
        private const val KIND = "osada-coop-checkpoint"
        private const val MAX_COMMANDERS = 4
        private val scenarioPattern = Regex("^[A-Za-z0-9_-]+\\.xml$")
        private val campaignPattern = Regex("^[A-Za-z0-9_-]+\\.json$")

        fun decode(source: String): CoopCheckpoint {
            require(source.length <= MAX_CHARS) { "Checkpoint is too large" }
            val value = JSON.parse<dynamic>(source)
            require(value.kind == KIND && value.version == 1) { "Unknown checkpoint format" }
            return fromSnapshot(MultiplayerSnapshotJson.decode(JSON.stringify(value.snapshot)))
        }

        fun fromSnapshot(snapshot: MultiplayerSnapshot): CoopCheckpoint {
            val verdict = MultiplayerSnapshotValidator().validate(snapshot)
            require(verdict.valid) { verdict.errors.joinToString() }
            require(
                snapshot.gameVersion == org.osada.VERSION &&
                    snapshot.gameSaveFormatVersion == GameStateSerializer.SAVE_FORMAT_VERSION,
            ) {
                "Checkpoint requires a different game build or save format"
            }
            val game = JSON.parse<dynamic>(snapshot.gameStateJson)
            require(game.ruleset == null || RulesetCompatibility.verdict(null, game.ruleset).allowed) {
                "Checkpoint has incompatible rules"
            }
            require(
                game.fmt == GameStateSerializer.SAVE_FORMAT_VERSION && game.scenario != null && game.players != null,
            ) {
                "Invalid checkpoint game state"
            }
            require(scenarioPattern.matches(game.scenario.file as? String ?: "")) { "Invalid scenario reference" }
            val campaign = game.campaign?.file as? String
            require(campaign == null || campaignPattern.matches(campaign)) { "Invalid campaign reference" }
            val coop = validatedCoop(snapshot)
            require(
                coop.commanders.size in 2..MAX_COMMANDERS &&
                    coop.commanders
                        .map { it.id }
                        .toSet()
                        .size == coop.commanders.size,
            ) {
                "Invalid commander roster"
            }
            require(
                coop.commanders.all {
                    CoopLoadout.validate(it.abilityIds, coop.slotCap()).isEmpty()
                },
            ) { "Invalid saved loadout" }
            // Serialize only recognized fields. In particular, never export reconnect tokens or old bindings.
            val cleanCoop = JSON.parse<dynamic>(CoopStateJson.encode(coop))
            coop.commanders.indices.forEach { cleanCoop.commanders[it].participantId = null }
            val clean =
                rehash(
                    snapshot.copy(
                        multiplayerState =
                            snapshot.multiplayerState.copy(
                                authorityParticipantId = "",
                                readyParticipantIds = emptySet(),
                                pendingCommand = null,
                                coopJson = JSON.stringify(cleanCoop),
                            ),
                    ),
                )
            return CoopCheckpoint(clean)
        }

        private fun rehash(snapshot: MultiplayerSnapshot): MultiplayerSnapshot =
            snapshot.copy(stateHash = CanonicalStateHasher().hash(snapshot))

        private fun validatedCoop(snapshot: MultiplayerSnapshot): CoopState {
            val source = requireNotNull(snapshot.multiplayerState.coopJson) { "This is not a cooperative checkpoint" }
            val raw = JSON.parse<dynamic>(source)
            require(
                raw.scoringVersion == CoopLedger.SCORING_VERSION &&
                    raw.ledger?.scoringVersion == CoopLedger.SCORING_VERSION,
            ) {
                "Checkpoint scoring version mismatch"
            }
            val coop = CoopStateJson.decode(source)
            require(coop.commanders.all { it.portrait.generatorVersion == CoopPortrait.GENERATOR_VERSION }) {
                "Checkpoint portrait version mismatch"
            }
            // Recovery must not silently discard an unknown assignment, Done flag or ledger field.
            require(
                canonicalizeJson(source) == canonicalizeJson(CoopStateJson.encode(coop)),
            ) { "Malformed cooperative state" }
            return coop
        }
    }
}
