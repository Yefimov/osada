package org.osada

/**
 * Save/load entry point exposed to JavaScript (`window.game.state`).
 *
 * This used to be a ~650-line god-class mixing serialization, deserialization, graph
 * rebuilding and storage I/O. It has been split (Single Responsibility) into:
 * - [GameStateSerializer] — model → JSON,
 * - [GameStateDeserializer] — JSON → leaf models,
 * - [GameStateRestore] — rebuild the live game graph + apply settings,
 * - [GameStatePersistence] — localStorage / file I/O.
 *
 * This class is now a thin `@JsExport` facade preserving the exact public surface (and
 * save-file format) the HTML/JS and tests depend on, delegating to those collaborators.
 */
@JsExport
@JsName("GameState")
@Suppress("TooManyFunctions") // thin facade preserving the exported JS surface, see class doc
class GameState(
    private val game: Game,
) {
    private val restorer = GameStateRestore(game)
    private val persistence = GameStatePersistence(game, restorer)

    fun save() = persistence.save()

    fun saveCampaign() = persistence.saveCampaign()

    fun saveSettings() = persistence.saveSettings()

    fun restore(
        onSuccess: () -> Unit,
        onFail: () -> Unit,
    ) = persistence.restore(onSuccess, onFail)

    fun restoreFromString(
        data: String,
        onReady: () -> Unit = {},
    ): Boolean = persistence.restoreFromString(data, onReady)

    fun restoreFromFile(
        file: dynamic,
        onSuccess: () -> Unit,
        onError: () -> Unit,
    ) = persistence.restoreFromFile(file, onSuccess, onError)

    fun clear() = persistence.clear()

    fun restoreSettings() = persistence.restoreSettings()

    fun exportGameState(): String = GameStateSerializer.exportGameState(game)

    // ---- per-campaign-run repository (docs/design/save-recovery.md) ---------------------------

    fun listCampaignRuns() = persistence.listCampaignRuns()

    /** Drives the main menu's Continue button; null = nothing to continue. */
    fun savedGameSummary() = persistence.savedGameSummary()

    fun restoreCampaignRun(
        campaignRunId: String,
        onSuccess: () -> Unit,
        onFail: () -> Unit,
    ) = persistence.restoreCampaignRun(campaignRunId, onSuccess, onFail)

    fun clearCampaignRun(campaignRunId: String) = persistence.clearCampaignRun(campaignRunId)

    /** Records that a campaign run reached its end; see [GameStatePersistence.markCampaignRunCompleted]. */
    fun markCampaignRunCompleted(
        campaignRunId: String,
        outcome: String,
    ) = persistence.markCampaignRunCompleted(campaignRunId, outcome)

    fun exportCampaignRun(campaignRunId: String) = persistence.exportCampaignRun(campaignRunId)

    fun importCampaignRun(bundle: org.osada.save.CampaignRunBundle) = persistence.importCampaignRun(bundle)

    fun exportProfile() = persistence.exportProfile()

    fun importProfile(bundle: org.osada.save.ProfileBundle) = persistence.importProfile(bundle)
}
