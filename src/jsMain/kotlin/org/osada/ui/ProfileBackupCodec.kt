package org.osada.ui

import org.osada.GameStateSerializer
import org.osada.SavePhaseValidation
import org.osada.VERSION
import org.osada.hero.HeroArchive
import org.osada.hero.HeroArchiveCodec
import org.osada.rules.ruleset.RulesetProfileStore
import org.osada.save.CampaignRunBundle
import org.osada.save.CampaignRunCodec
import org.osada.save.CampaignRunMetadata
import org.osada.save.ProfileBundle
import org.osada.save.SaveSnapshot
import kotlin.js.json

/** Strict file boundary; forgiving browser-store readers must never silently trim an import. */
internal object ProfileBackupCodec {
    const val FILE_KIND = "osada-profile"
    const val FILE_VERSION = 1

    internal data class ImportPlan(
        val bundle: ProfileBundle,
        val replaceAll: Boolean,
        val extras: Map<String, String?>,
    )

    private val version = VERSION.split('.').take(2).joinToString(".")
    internal val standaloneKey = "osada-standalone-session-$version"
    internal val checkpointPrefix = "osada-mission-start-$version"
    internal val archiveKey = "osada-hero-archive-$version"
    internal const val RULESETS_KEY = "osada-ruleset-profiles"

    fun decode(raw: dynamic): ImportPlan {
        require(raw != null)
        return when {
            raw.runs != null -> decodeProfile(raw)
            raw.run != null -> {
                val run = readRun(raw.run)
                val extras = checkpoint(run.current, raw.missionStart)
                ImportPlan(ProfileBundle(listOf(run), 0.0, ""), false, extras)
            }
            raw.scenario != null -> decodeLegacySave(raw)
            else -> error("Unrecognised backup")
        }
    }

    private fun decodeProfile(raw: dynamic): ImportPlan {
        require(raw.kind == null || raw.kind == FILE_KIND)
        require(raw.backupVersion == null || raw.backupVersion == FILE_VERSION)
        require(js("Array.isArray")(raw.runs) as Boolean)
        val runs = (raw.runs as Array<dynamic>).map(::readRun)
        require(runs.map { it.metadata.campaignRunId }.distinct().size == runs.size)
        val extras = mutableMapOf<String, String?>()
        if (raw.rulesetProfiles != null) {
            require(js("Array.isArray")(raw.rulesetProfiles) as Boolean)
            val entries = raw.rulesetProfiles as Array<dynamic>
            val profiles = RulesetProfileStore.parse(JSON.stringify(entries))
            require(profiles.size == entries.size && profiles.map { it.id }.distinct().size == profiles.size)
            extras[RULESETS_KEY] = JSON.stringify(entries)
        }
        if (raw.heroArchive != null) {
            val archive = raw.heroArchive
            require(archive.schemaVersion == HeroArchive.SCHEMA_VERSION)
            require(js("Array.isArray")(archive.campaigns) as Boolean)
            require(js("Array.isArray")(archive.legacy) as Boolean)
            val decoded = HeroArchiveCodec.parse(archive)
            require(decoded.campaigns.size == (archive.campaigns as Array<dynamic>).size)
            require(decoded.legacy.size == (archive.legacy as Array<dynamic>).size)
            extras[archiveKey] = JSON.stringify(archive)
        }
        // A missing opening checkpoint cannot reuse a different playthrough's local opening.
        runs.forEach { run ->
            val opening: dynamic =
                if (raw.missionStarts ==
                    null
                ) {
                    null
                } else {
                    raw.missionStarts[run.metadata.campaignRunId]
                }
            extras.putAll(checkpoint(run.current, opening))
        }
        if (js("Object.prototype.hasOwnProperty.call")(raw, "standalone") as Boolean) {
            val session = raw.standalone
            if (session != null) {
                val save = readSave(session.payload as String)
                require(save.campaign == null)
                extras[standaloneKey] = JSON.stringify(session)
                extras["$checkpointPrefix:standalone"] =
                    validCheckpoint(session.missionStart, null, null, save.scenario.file as String)
            } else {
                extras[standaloneKey] = null
                extras["$checkpointPrefix:standalone"] = null
            }
        }
        return ImportPlan(
            ProfileBundle(runs, raw.exportedAt as? Double ?: 0.0, raw.gameVersion as? String ?: ""),
            true,
            extras,
        )
    }

    private fun readRun(raw: dynamic): CampaignRunBundle {
        val run = requireNotNull(CampaignRunCodec.jsonToRun(raw))
        require(run.metadata.campaignRunId.isNotBlank())
        require(run.metadata.campaignRunId == run.metadata.campaignFile)
        require(run.metadata.campaignRunId == run.current.campaignRunId)
        require(run.metadata.campaignFile == run.current.campaignFile)
        require(raw.recovery == null || run.recovery != null)
        listOfNotNull(run.current, run.recovery).forEach { snapshot ->
            val save = readSave(snapshot.payload, snapshot.phase)
            require(snapshot.campaignRunId == run.metadata.campaignRunId)
            require(snapshot.campaignFile == run.metadata.campaignFile)
            require(save.campaign?.file == snapshot.campaignFile)
            require(save.campaign?.scenario == snapshot.campaignScenario)
            require(save.scenario.file == snapshot.scenarioFile)
            require(snapshot.saveFormat == save.fmt)
        }
        return run
    }

    /**
     * A stored generation is checked against its OWN recorded [phase] -- the same predicate the
     * local store applies before writing and before restoring -- so a run the game would resume is
     * never refused merely because it was exported. Raw files carry no phase; for them, a state
     * valid as a player turn or as preparation is accepted.
     */
    internal fun readSave(
        payload: String,
        phase: String? = null,
    ): dynamic {
        val save = JSON.parse<dynamic>(payload)
        require((save.fmt as? Int ?: 0) >= GameStateSerializer.SAVE_FORMAT_VERSION)
        require((save.scenario?.file as? String)?.isNotBlank() == true)
        require(js("Array.isArray")(save.players) as Boolean)
        val phases =
            if (phase.isNullOrBlank()) {
                listOf(SavePhaseValidation.PHASE_PLAYER_TURN, SavePhaseValidation.PHASE_PREPARATION)
            } else {
                listOf(phase)
            }
        require(phases.any { SavePhaseValidation.isValidForPhase(it, save.scenario, save.players) })
        return save
    }

    private fun decodeLegacySave(raw: dynamic): ImportPlan {
        val payload = JSON.stringify(raw)
        readSave(payload)
        val file = raw.campaign?.file as? String
        if (file == null) {
            val session = json("savedAt" to 0.0, "payload" to payload, "missionStart" to raw.missionStart)
            return ImportPlan(
                ProfileBundle(emptyList(), 0.0, ""),
                false,
                mapOf(
                    standaloneKey to JSON.stringify(session),
                    "$checkpointPrefix:standalone" to
                        validCheckpoint(raw.missionStart, null, null, raw.scenario.file as String),
                ),
            )
        }
        val run = runFromSave(payload, 0.0)
        return ImportPlan(ProfileBundle(listOf(run), 0.0, ""), false, checkpoint(run.current, raw.missionStart))
    }

    internal fun runFromSave(
        payload: String,
        savedAt: Double,
    ): CampaignRunBundle {
        val raw = readSave(payload)
        val file = raw.campaign.file as String
        val scenario = raw.scenario
        val snapshot =
            SaveSnapshot(
                id = "${savedAt.toLong()}-file",
                campaignRunId = file,
                kind = "manual",
                createdAt = savedAt,
                gameVersion = VERSION,
                saveFormat = raw.fmt as Int,
                scenarioFile = scenario.file as String,
                scenarioName = scenario.name as? String ?: "",
                turn =
                    scenario.turn as? Int ?: scenario.map?.turn as? Int ?: 0,
                maxTurns = scenario.maxTurns as? Int ?: 0,
                phase = SavePhaseValidation.PHASE_PLAYER_TURN,
                campaignFile = file,
                campaignScenario = raw.campaign.scenario as? Int ?: 0,
                payload = payload,
            )
        val metadata =
            CampaignRunMetadata(
                file,
                file,
                file,
                snapshot.scenarioName,
                snapshot.campaignScenario,
                snapshot.phase,
                savedAt,
                false,
                snapshot.turn,
                snapshot.maxTurns,
            )
        return CampaignRunBundle(metadata, snapshot, null)
    }

    private fun checkpoint(
        snapshot: SaveSnapshot,
        raw: dynamic,
    ): Map<String, String?> =
        mapOf(
            "$checkpointPrefix:${snapshot.campaignFile}" to
                validCheckpoint(
                    raw,
                    snapshot.campaignFile,
                    snapshot.campaignScenario,
                    snapshot.scenarioFile,
                ),
        )

    private fun validCheckpoint(
        raw: dynamic,
        campaign: String?,
        operation: Int?,
        scenario: String,
    ): String? {
        if (raw == null) return null
        val payload = raw as String
        val saved = readSave(payload)
        require(saved.scenario.file == scenario)
        require(
            if (campaign ==
                null
            ) {
                saved.campaign == null
            } else {
                saved.campaign?.file == campaign && saved.campaign?.scenario == operation
            },
        )
        return payload
    }
}
