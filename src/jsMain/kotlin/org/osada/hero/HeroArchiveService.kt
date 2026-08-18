package org.osada.hero

import kotlinx.browser.localStorage
import org.osada.VERSION
import org.osada.save.HeroArchiveStore
import org.osada.save.LocalStorageHeroArchiveStore
import org.osada.save.SaveResult
import kotlin.js.Date
import kotlin.js.json

/**
 * Owns the profile hero archive's LIFETIME
 * (`docs/design/hero-desk-and-profile-archive.md` §§4, 7). Its read side lives in [HeroDeskSource].
 *
 * Responsibilities, and equally what this is NOT:
 *
 * - it **snapshots** rosters. [HeroCampaign] remains the sole writer of a live roster, and nothing
 *   here mutates hero state -- §7's "never becomes another mutable hero model";
 * - it owns `runEpoch` minting, at the one moment a run is replaced ([beginRun]), so an epoch is
 *   never derived from hero names or timestamps during a restore (§3);
 * - it deliberately does **not** delete an archive when a campaign run is cleared. That is how
 *   fallen and completed careers survive an abandoned run (§4); [deleteCampaign] is the separate,
 *   confirmed action the desk offers instead.
 */
internal object HeroArchiveService {
    /** Swappable for tests; the browser adapter shares the save repository's key namespace. */
    var store: HeroArchiveStore = LocalStorageHeroArchiveStore()

    fun archive(): HeroArchive = store.read()

    fun archivedCampaign(campaignRunId: String): CampaignHeroArchive? = store.read().campaigns[campaignRunId]

    /** Officers in the archived career for [campaignRunId], for the replay confirmation's wording. */
    fun archivedHeroCount(campaignRunId: String): Int =
        archivedCampaign(campaignRunId)
            ?.let { HeroSnapshotProjector.records(it, HeroRecordSource.ARCHIVE, resumableRun = false).size }
            ?: 0

    /**
     * A new run of [campaignRunId] is starting and replaces whatever came before it: mint a fresh
     * epoch and drop the prior archived roster/history BEFORE the new run becomes active (§4).
     *
     * Called from the campaign-start path, after the player has confirmed the replacement. V1 keeps
     * one historic run per campaign, so this never accumulates duplicate universes for one campaign.
     */
    fun beginRun(campaignRunId: String) {
        if (campaignRunId.isBlank()) return
        store.deleteCampaign(campaignRunId)
        HeroRunEpochs.mintFor(campaignRunId)
    }

    /**
     * Upserts one campaign's complete roster.
     *
     * A COMPLETE replacement, never an append: retrying a scenario transition (the move-capture and
     * end-turn completion paths can both reach the campaign funnel for the same battle) must be
     * idempotent and must not duplicate medals or service events (§4).
     *
     * Returns null when there is nothing to archive (no run id, or a campaign that has produced no
     * formations or heroes). A failure never marks the mission incomplete -- the game save has
     * already committed by this point -- but the caller surfaces it as a persistence warning (§8).
     */
    @Suppress("LongParameterList")
    fun upsert(
        roster: HeroRoster,
        formationExperience: Map<String, Int>,
        campaignRunId: String,
        campaignFile: String,
        campaignName: String,
        lastScenarioId: String,
        lastScenarioIndex: Int,
        runStatus: ArchiveRunStatus,
    ): SaveResult? {
        val projection =
            if (campaignRunId.isBlank()) {
                null
            } else {
                HeroSnapshotProjector.projectRoster(
                    roster = roster,
                    formationExperience = formationExperience,
                    campaignRunId = campaignRunId,
                    runEpoch = epochFor(campaignRunId),
                    campaignFile = campaignFile,
                    campaignName = campaignName,
                    lastScenarioId = lastScenarioId,
                    lastScenarioIndex = lastScenarioIndex,
                    updatedAt = Date().getTime(),
                    runStatus = runStatus,
                )
            } ?: return null
        val result = store.replaceCampaign(projection)
        if (!result.isSuccess) {
            console.warn("[osada] hero archive upsert failed for '$campaignRunId': ${result.kind} ${result.message}")
        }
        return result
    }

    /** The desk's explicit "Delete archived career" action (§4) -- named and confirmed by the UI. */
    fun deleteCampaign(campaignRunId: String): SaveResult = store.deleteCampaign(campaignRunId)

    /** Whole-profile import (§4): the file's archive replaces the local one transactionally. */
    fun replaceAll(archive: HeroArchive): SaveResult = store.replaceAll(archive)

    fun runStatusOf(
        completed: Boolean,
        outcome: String,
    ): ArchiveRunStatus = if (!completed) ArchiveRunStatus.IN_PROGRESS else ArchiveRunStatus.forOutcome(outcome)

    /** The current epoch for [campaignRunId], minting and persisting one if the run has none. */
    fun epochFor(campaignRunId: String): String = HeroRunEpochs.forRun(campaignRunId)
}

/**
 * The `runEpoch` map: which universe of a campaign the archive and the live run currently belong to
 * (`docs/design/hero-desk-and-profile-archive.md` §3).
 *
 * Kept beside the archive rather than inside it because it has to survive the archive's own
 * deletion: [HeroArchiveService.beginRun] drops the previous roster and mints the next epoch in the
 * same breath, and a value stored inside the record it just removed could not do that.
 */
private object HeroRunEpochs {
    private val majorVersion = VERSION.split(".").take(2).joinToString(".")
    private val key = "osada-hero-run-epoch-$majorVersion"

    private const val RANDOM_SUFFIX_LENGTH = 6

    fun forRun(campaignRunId: String): String =
        read()[campaignRunId]?.takeIf { it.isNotBlank() } ?: mintFor(campaignRunId)

    fun mintFor(campaignRunId: String): String {
        val random = (js("Math.random()") as Double).toString().drop(2).take(RANDOM_SUFFIX_LENGTH)
        val minted = "${Date().getTime().toLong()}-$random"
        write(campaignRunId, minted)
        return minted
    }

    @Suppress("TooGenericExceptionCaught")
    private fun read(): Map<String, String> =
        try {
            val raw = localStorage.getItem(key)
            if (raw == null) {
                emptyMap()
            } else {
                val parsed = JSON.parse<dynamic>(raw)
                js("Object.keys")(parsed)
                    .unsafeCast<Array<String>>()
                    .mapNotNull { name -> (parsed[name] as? String)?.let { name to it } }
                    .toMap()
            }
        } catch (e: Throwable) {
            console.warn("[osada] hero run-epoch map unreadable", e)
            emptyMap()
        }

    @Suppress("TooGenericExceptionCaught")
    private fun write(
        campaignRunId: String,
        epoch: String,
    ) {
        try {
            val obj = json()
            (read() + (campaignRunId to epoch)).forEach { (name, value) -> obj[name] = value }
            localStorage.setItem(key, JSON.stringify(obj))
        } catch (e: Throwable) {
            // An unwritable epoch is not worth failing a campaign start over: the archive is keyed
            // by campaign run id, which is what v1 actually deduplicates on.
            console.warn("[osada] could not persist hero run epoch for '$campaignRunId'", e)
        }
    }
}
