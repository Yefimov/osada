package org.osada.hero

import org.osada.Game
import org.osada.current
import org.osada.save.CampaignRunMetadata

/**
 * Assembles the Hero Desk's read model from the two sources §2 of
 * `docs/design/hero-desk-and-profile-archive.md` names: every campaign run's own current snapshot,
 * and the profile archive. Split from [HeroArchiveService] so the archive's LIFETIME (minting,
 * upserting, deleting) and its READ side stay separately testable and separately small.
 *
 * Reads restore nothing: each run is projected out of the best generation
 * [org.osada.GameStatePersistence.readableGeneration] will hand over, with no `GameHolder`
 * mutation, no change of selected campaign and no settings load.
 */
internal object HeroDeskSource {
    data class DeskData(
        val records: List<HeroDeskRecord>,
        /** Campaign names whose every generation failed validation -- surfaced, never faked (§2). */
        val unreadableRuns: List<String>,
    )

    /**
     * Every card the desk can show, deduplicated by [HeroDeskModel.merge].
     *
     * [legacy] is supplied by the caller rather than read here: the migrated Hall of Fame summaries
     * live in a UI-package store, and the hero package must not depend on the UI package to answer
     * "who is in the archive?".
     */
    fun deskData(legacy: List<LegacyHeroRecord>): DeskData {
        val state = Game.current?.state
        val runs = state?.listCampaignRuns().orEmpty()
        // Each run's generation is read and parsed ONCE: the payload is ~130 KB, and the desk opens
        // on a menu the player may reach repeatedly.
        val projected = runs.map { run -> projectRun(run, state?.readableGeneration(run.campaignRunId)) }
        val liveRecords =
            projected.flatMap { (run, readable, projection) ->
                if (!readable || projection == null) {
                    emptyList()
                } else {
                    HeroSnapshotProjector.records(projection, HeroRecordSource.LIVE, resumableRun = !run.completed)
                }
            }
        val resumableByRun = runs.associate { it.campaignRunId to !it.completed }
        val archivedRecords =
            HeroArchiveService.archive().campaigns.values.flatMap { archived ->
                HeroSnapshotProjector.records(
                    archived,
                    HeroRecordSource.ARCHIVE,
                    resumableRun = resumableByRun[archived.campaignRunId] == true,
                )
            }
        return DeskData(
            records =
                HeroDeskModel.merge(
                    live = liveRecords,
                    archived = archivedRecords,
                    legacy = legacy.map(HeroDeskModel::legacyRecord),
                ),
            // A run with a readable generation but no hero block is NOT a failure -- it is a
            // campaign that has produced no officers yet -- so only a run with no readable
            // generation at all is reported.
            unreadableRuns =
                projected
                    .filterNot { it.readable }
                    .map { it.run.campaignName.ifBlank { it.run.campaignRunId } },
        )
    }

    private data class RunProjection(
        val run: CampaignRunMetadata,
        /** False when neither the current nor the recovery generation passed validation. */
        val readable: Boolean,
        val projection: CampaignHeroArchive?,
    )

    private fun projectRun(
        run: CampaignRunMetadata,
        snapshot: org.osada.save.SaveSnapshot?,
    ): RunProjection {
        if (snapshot == null) return RunProjection(run, readable = false, projection = null)
        return RunProjection(
            run = run,
            readable = true,
            projection =
                HeroSnapshotProjector.project(
                    payload = snapshot.payload,
                    campaignRunId = run.campaignRunId,
                    runEpoch = HeroArchiveService.epochFor(run.campaignRunId),
                    campaignFile = run.campaignFile,
                    campaignName = campaignDisplayName(run),
                    lastScenarioId = snapshot.scenarioName,
                    lastScenarioIndex = run.campaignScenario,
                    updatedAt = run.lastPlayedAt,
                    runStatus = HeroArchiveService.runStatusOf(run.completed, run.outcome),
                ),
        )
    }

    /**
     * The campaign's own title where the archive knows it.
     *
     * The save index row stores the campaign FILE in its `campaignName` field, so a live card read
     * straight from the register would be headed "camp6.json". The archive records the real title
     * at upsert time (when `game.campaign.name` is loaded), so prefer it and fall back to the file
     * only for a campaign that has never been archived.
     */
    private fun campaignDisplayName(run: CampaignRunMetadata): String =
        HeroArchiveService
            .archivedCampaign(run.campaignRunId)
            ?.campaignName
            ?.takeIf { it.isNotBlank() }
            ?: run.campaignName.ifBlank { run.campaignFile }
}
