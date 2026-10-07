package org.osada.ui

import kotlinx.browser.window
import org.osada.Game
import org.osada.GameStateSerializer
import org.osada.PlayerType
import org.osada.current
import org.osada.hero.HeroArchiveCodec
import org.osada.hero.HeroArchiveService
import org.osada.i18n.I18n
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.rules.ruleset.RulesetProfileStore
import org.osada.save.CampaignRunCodec
import org.osada.save.LocalStorageBatch
import org.osada.save.LocalStorageSaveSnapshotStore
import org.osada.save.ResumeTarget
import org.osada.save.SaveResult
import org.osada.save.SaveResultKind
import org.osada.storage.BrowserStorage
import org.osada.storage.localStorage
import kotlin.js.Date
import kotlin.js.json

/** One file surface for all campaigns; older campaign and battle files remain readable. */
@Suppress("TooGenericExceptionCaught", "TooManyFunctions") // Export, import and the cloud copy share one journal.
internal object ProfileBackup {
    /** The game is mid-action (animation, computer turn, online session): retry later, not a fault. */
    internal class BackupBusy : IllegalStateException("backup refused while the game is busy")

    fun exportToFile() {
        try {
            downloadJson("osada-backup-${Date().getTime().toLong()}.json", prepareExport())
            status("save_load.backup.exported")
        } catch (error: BackupBusy) {
            console.warn("[osada] backup export deferred", error)
            status("save_load.backup.busy")
        } catch (error: Throwable) {
            console.warn("[osada] backup export refused", error)
            status("save_load.backup.failed")
        }
    }

    /**
     * The whole profile as one backup document. [includeLiveBattle] false is the cloud-sync view:
     * only what is already committed to this browser's store (autosaves at their own boundaries),
     * never a half-played turn, and dated by the latest save rather than "now" so unchanged
     * content serializes to the same bytes.
     */
    internal fun prepareExport(includeLiveBattle: Boolean = true): dynamic {
        if (includeLiveBattle && !canImport()) throw BackupBusy()
        val game = Game.current
        val liveGame = game?.takeIf { includeLiveBattle && it.gameStarted && !it.gameEnded }
        val stored = LocalStorageSaveSnapshotStore().exportProfile()
        val now =
            if (includeLiveBattle) {
                Date().getTime()
            } else {
                stored.runs.maxOfOrNull { it.metadata.lastPlayedAt }
                    ?: 0.0
            }
        var runs = if (includeLiveBattle) stored.runs.map(CampaignRunBackup::withLiveBattle) else stored.runs
        if (liveGame?.campaign != null &&
            runs.none { it.metadata.campaignRunId == liveGame.campaign?.file }
        ) {
            runs = runs + ProfileBackupCodec.runFromSave(GameStateSerializer.exportGameState(liveGame), now)
        }
        val checkpoints = js("Object.create(null)")
        runs.forEach { run ->
            checkpoints[run.metadata.campaignRunId] =
                game?.missionRestartCheckpoint?.exportFor(
                    run.current.campaignFile,
                    run.current.campaignScenario,
                    run.current.scenarioFile,
                )
        }
        val result =
            json(
                "kind" to ProfileBackupCodec.FILE_KIND,
                "backupVersion" to ProfileBackupCodec.FILE_VERSION,
                "exportedAt" to now,
                "gameVersion" to stored.gameVersion,
                "runs" to runs.map(CampaignRunCodec::runToJson).toTypedArray(),
                "rulesetProfiles" to
                    JSON.parse<dynamic>(
                        localStorage.getItem(ProfileBackupCodec.RULESETS_KEY)
                            ?: RulesetProfileStore.serialize(RulesetProfileStore.custom()),
                    ),
                "heroArchive" to (
                    localStorage.getItem(ProfileBackupCodec.archiveKey)?.let { JSON.parse<dynamic>(it) }
                        ?: HeroArchiveCodec.serialize(HeroArchiveService.archive())
                ),
                "missionStarts" to checkpoints,
                "standalone" to exportStandalone(game, liveGame, now),
            )
        ProfileBackupCodec.decode(result)
        return result
    }

    private fun exportStandalone(
        game: Game?,
        liveGame: Game?,
        now: Double,
    ): dynamic {
        val rawSession = localStorage.getItem(ProfileBackupCodec.standaloneKey)
        var standalone: dynamic = rawSession?.let { JSON.parse<dynamic>(it) }
        if (standalone != null && standalone.payload == null) {
            standalone = json("savedAt" to 0.0, "payload" to rawSession)
        }
        if (liveGame != null && liveGame.campaign == null) {
            standalone = json("savedAt" to now, "payload" to GameStateSerializer.exportGameState(liveGame))
        }
        if (standalone != null) {
            val saved = JSON.parse<dynamic>(standalone.payload as String)
            standalone.missionStart =
                game?.missionRestartCheckpoint?.exportFor(null, null, saved.scenario.file as? String)
        }
        return standalone
    }

    /** [onError] receives the i18n key of the message to show; each names a different remedy. */
    fun importFromFile(
        file: dynamic,
        onSuccess: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        importRefusalKey()?.let {
            onError(it)
            return
        }
        if ((file.size as? Double ?: 0.0) > MAX_FILE_BYTES) {
            onError("save_load.backup.import_invalid")
            return
        }
        val reader = js("new FileReader()")
        reader.onload = {
            try {
                val plan = ProfileBackupCodec.decode(JSON.parse<dynamic>(reader.result as String))
                confirmThenApply(plan, onSuccess, onError)
            } catch (error: Throwable) {
                console.warn("[osada] backup rejected", error)
                onError("save_load.backup.import_invalid")
            }
        }
        reader.onerror = { onError("save_load.backup.import_invalid") }
        reader.readAsText(file)
    }

    /** Which message a failed [apply] deserves. Only a pending journal means "reload now". */
    internal fun importFailureKey(result: SaveResult): String =
        when {
            localStorage.getItem(LocalStorageBatch.JOURNAL_KEY) != null -> "save_load.backup.import_failed"
            result.kind == SaveResultKind.QUOTA_EXCEEDED -> "save_load.backup.import_quota"
            else -> "save_load.backup.import_unchanged"
        }

    /**
     * Replaces the whole profile with [document] (a downloaded online copy) through the same
     * journaled path as a file import, then reloads. [beforeReload] runs once the import is
     * durable, so the caller can record what this device now holds. [confirm] false skips the
     * preview: only for a copy that descends from this device's own acknowledged state.
     */
    internal fun applyDocument(
        document: dynamic,
        confirm: Boolean,
        beforeReload: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val plan =
            try {
                ProfileBackupCodec.decode(document)
            } catch (error: Throwable) {
                console.warn("[osada] online copy rejected", error)
                onError("save_load.backup.import_invalid")
                return
            }
        if (confirm) {
            confirmThenApply(plan, { beforeReload() }, onError)
        } else {
            commit(plan, { beforeReload() }, onError)
        }
    }

    private fun confirmThenApply(
        plan: ProfileBackupCodec.ImportPlan,
        onSuccess: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        val existing = LocalStorageSaveSnapshotStore().listCampaignRuns()
        ConfirmCard.open(
            I18n.t(if (plan.replaceAll) "save_load.profile_import.confirm.title" else "save_load.backup.legacy.title"),
            ProfileBackupPreview.preview(plan, existing),
            I18n.t(
                if (plan.replaceAll) {
                    "save_load.profile_import.confirm.confirm_button"
                } else {
                    "save_load.backup.legacy.confirm"
                },
            ),
        ) { commit(plan, onSuccess, onError) }
    }

    private fun commit(
        plan: ProfileBackupCodec.ImportPlan,
        onSuccess: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        val refusal = importRefusalKey()
        val result = if (refusal == null) apply(plan) else null
        if (result == null) {
            onError(refusal ?: "save_load.backup.busy")
        } else if (result.isSuccess) {
            // Reload only once the whole import is on disk: an unload with the IndexedDB
            // transaction still open would abort it after the player was told it succeeded.
            BrowserStorage.whenDurable { durable ->
                if (durable) {
                    onSuccess(plan.bundle.runs.size)
                    // The old in-memory game must not autosave over the imported profile.
                    window.location.reload()
                } else {
                    // The transaction aborted as a whole: disk still holds the old profile. Stop
                    // this page from retrying the import later, and ask for the reload.
                    BrowserStorage.suspendWrites()
                    onError("save_load.backup.import_failed")
                }
            }
        } else {
            onError(importFailureKey(result))
            if (localStorage.getItem(LocalStorageBatch.JOURNAL_KEY) != null) {
                BrowserStorage.whenDurable { window.location.reload() }
            }
        }
    }

    internal fun apply(plan: ProfileBackupCodec.ImportPlan): SaveResult =
        try {
            val store = LocalStorageSaveSnapshotStore()
            val bundle =
                if (plan.replaceAll) {
                    plan.bundle
                } else {
                    val ids =
                        plan.bundle.runs
                            .map { it.metadata.campaignRunId }
                            .toSet()
                    plan.bundle.copy(
                        runs =
                            store.exportProfile().runs.filter { it.metadata.campaignRunId !in ids } + plan.bundle.runs,
                    )
                }
            val changes = store.profileChanges(bundle).toMutableMap()
            changes.putAll(plan.extras)
            changes[ResumeTarget.key] = resumeTargetFor(plan)?.let(ResumeTarget::encode)
            if (plan.replaceAll) {
                store
                    .listCampaignRuns()
                    .filter { old ->
                        bundle.runs.none {
                            it.metadata.campaignRunId ==
                                old.campaignRunId
                        }
                    }.forEach { changes["${ProfileBackupCodec.checkpointPrefix}:${it.campaignFile}"] = null }
            }
            if (plan.extras.keys.any { it.startsWith(ProfileBackupCodec.checkpointPrefix + ":") }) {
                changes[ProfileBackupCodec.checkpointPrefix] = null
            }
            val checkpointIndex = "${ProfileBackupCodec.checkpointPrefix}:index"
            val existingCheckpoints =
                localStorage
                    .getItem(checkpointIndex)
                    ?.let {
                        JSON.parse<Array<String>>(it).toList()
                    }.orEmpty()
            val checkpointChanges = changes.filterKeys { it.startsWith(ProfileBackupCodec.checkpointPrefix + ":") }
            if (checkpointChanges.isNotEmpty()) {
                changes[checkpointIndex] =
                    JSON.stringify(
                        (
                            existingCheckpoints.filter { it !in checkpointChanges } +
                                checkpointChanges.filterValues { it != null }.keys
                        ).toTypedArray(),
                    )
            }
            LocalStorageBatch().apply(changes)
        } catch (error: Throwable) {
            SaveResult(SaveResultKind.CORRUPT_READBACK, error.message)
        }

    /**
     * A single-campaign or raw-battle file opens what it imported, even though its honest
     * timestamp (often unknown) is older than other local progress. A full profile replaces
     * everything and keeps ordinary recency, so it clears any older pointer instead.
     */
    internal fun resumeTargetFor(plan: ProfileBackupCodec.ImportPlan): ResumeTarget.Target? =
        when {
            plan.replaceAll -> null
            plan.bundle.runs.isNotEmpty() ->
                ResumeTarget.Campaign(
                    plan.bundle.runs
                        .first()
                        .metadata.campaignRunId,
                )
            plan.extras[ProfileBackupCodec.standaloneKey] != null -> ResumeTarget.Standalone
            else -> null
        }

    private fun status(key: String) {
        byId("diskloaderror")?.textContent = I18n.t(key)
    }

    /**
     * Why an import cannot start now, before anything is read or written; null when it can.
     *
     * An import ends in a reload that rebuilds the game from the imported profile. With site
     * storage blocked the profile lives in this page's memory, so that reload would erase the import
     * the moment it succeeded: refuse instead of pretending.
     */
    internal fun importRefusalKey(): String? =
        when {
            !BrowserStorage.persistent -> "save_load.backup.import_session_only"
            !canImport() -> "save_load.backup.busy"
            else -> null
        }

    private fun canImport(): Boolean {
        val game = Game.current
        return !OsadaMultiplayer.active &&
            game?.waitUIAnimation != true &&
            (
                game?.gameStarted != true ||
                    game.gameEnded ||
                    game.scenario
                        ?.map
                        ?.currentPlayer
                        ?.type == PlayerType.HUMAN_LOCAL
            )
    }

    private const val MAX_FILE_BYTES = 64 * 1024 * 1024
}
