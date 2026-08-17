package org.osada.save

/**
 * Repository contract for per-campaign-run browser saves. See `docs/design/save-recovery.md`.
 *
 * This interface is domain-agnostic: it stores/rotates opaque JSON strings keyed by campaign run
 * id and never inspects game state itself. Shape/phase-aware validation (does this payload really
 * contain a live roster for its declared phase?) is the caller's job -- see [SaveValidation] --
 * so the store can be unit-tested with an in-memory adapter without any game-model dependency.
 *
 * `campaignRunId` is the stable value already used elsewhere in the codebase to identify a
 * campaign across saves/restores: `Campaign.file` (e.g. "camp6.json"), not the numeric list
 * index `Campaign.id`, which is not guaranteed stable if the campaign list is ever reordered.
 */
interface SaveSnapshotStore {
    /** Serializes, validates, writes a new "current" generation, demoting the previous valid
     *  "current" to "recovery", per the commit protocol in save-recovery.md section 4. */
    fun commitAutosave(
        campaignRunId: String,
        snapshot: SaveSnapshot,
    ): SaveResult

    /** One row per campaign run, for the campaign register. Ordered by most-recently-played first. */
    fun listCampaignRuns(): List<CampaignRunMetadata>

    fun readCurrent(campaignRunId: String): SaveSnapshot?

    fun readRecovery(campaignRunId: String): SaveSnapshot?

    /** Used by "Import this campaign": replaces exactly one run after the caller has already
     *  shown a preview/confirmation. Never touches any other campaign's run. */
    fun replaceCampaignRun(bundle: CampaignRunBundle): SaveResult

    /** Used by "Export this campaign": current + recovery generations plus metadata, ready to be
     *  written to a file with `kind` overridden to "manual" by the caller. */
    fun readCampaignRunBundle(campaignRunId: String): CampaignRunBundle?

    /** Used by "Export full profile backup". */
    fun exportProfile(): ProfileBundle

    /** Used by "Import full profile backup": transactional whole-profile replace, no per-campaign
     *  merge in the first release. */
    fun replaceProfile(bundle: ProfileBundle): SaveResult

    /** "Clear campaign". Removes both generations and the index row for one campaign run. */
    fun deleteCampaignRun(campaignRunId: String): SaveResult

    /**
     * Marks a run as finished, with the outcome its final scenario ended on.
     *
     * Completion cannot be derived from a snapshot: OSADA writes no save at all once a campaign
     * ends (`gameEnded = true; gameStarted = false`), so the last generation on disk is always a
     * mid-campaign one. It is therefore recorded explicitly, from the single funnel that knows
     * -- `Game.continueCampaign` when the campaign has no next scenario.
     */
    fun markCompleted(
        campaignRunId: String,
        outcome: String,
    ): SaveResult

    /** Startup audit: drop any generation key no longer referenced by the index. */
    fun pruneOrphans()
}

enum class SaveResultKind {
    SUCCESS,
    QUOTA_EXCEEDED,
    STORAGE_UNAVAILABLE,
    CORRUPT_READBACK,
    NOT_FOUND,
}

data class SaveResult(
    val kind: SaveResultKind,
    val message: String? = null,
) {
    val isSuccess: Boolean get() = kind == SaveResultKind.SUCCESS

    companion object {
        fun success() = SaveResult(SaveResultKind.SUCCESS)
    }
}

/** One generation of one campaign run. `payload` is the existing
 *  `GameStateSerializer.exportGameState(game)` JSON string, unchanged. */
data class SaveSnapshot(
    val id: String,
    val campaignRunId: String,
    val kind: String, // "autosave" | "recovery" | "manual"
    val createdAt: Double,
    val gameVersion: String,
    val saveFormat: Int,
    val scenarioFile: String,
    val scenarioName: String,
    val turn: Int,
    /** 0 when unknown (a snapshot written before this field existed); callers must treat it as
     *  "no turn budget to show" rather than "a scenario of zero turns". */
    val maxTurns: Int = 0,
    val phase: String,
    val campaignFile: String,
    val campaignScenario: Int,
    val payload: String,
)

data class CampaignRunMetadata(
    val campaignRunId: String,
    val campaignFile: String,
    val campaignName: String,
    val scenarioName: String,
    val campaignScenario: Int,
    val phase: String,
    val lastPlayedAt: Double,
    val completed: Boolean,
    /** Turn position within the run's current scenario, carried on the index row so the main
     *  menu's Continue summary never has to parse a ~130 KB payload. Both default to 0 =
     *  "unknown" for index rows written before these fields existed. */
    val turn: Int = 0,
    val maxTurns: Int = 0,
    /** The campaign's final outcome once [completed] -- `"lose"` for a campaign that ended in
     *  defeat, otherwise the winning outcome key. Empty while the run is still in progress. A
     *  finished run is NOT necessarily a won one, and the register must not say it was. */
    val outcome: String = "",
)

/**
 * The main menu's Continue annotation. [name] blank means "a save exists but its metadata could
 * not be read" -- the button must still be shown, just without a fresh subtitle. [maxTurns] 0
 * means the turn budget is unknown, not that the scenario has zero turns.
 */
data class SavedGameSummary(
    val name: String,
    val turn: Int,
    val maxTurns: Int,
) {
    companion object {
        val UNREADABLE = SavedGameSummary(name = "", turn = 0, maxTurns = 0)
    }
}

data class CampaignRunBundle(
    val metadata: CampaignRunMetadata,
    val current: SaveSnapshot,
    val recovery: SaveSnapshot?,
)

data class ProfileBundle(
    val runs: List<CampaignRunBundle>,
    val exportedAt: Double,
    val gameVersion: String,
)
