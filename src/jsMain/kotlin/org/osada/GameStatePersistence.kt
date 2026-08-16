package org.osada

import kotlinx.browser.localStorage
import org.osada.save.CampaignRunBundle
import org.osada.save.CampaignRunMetadata
import org.osada.save.LocalStorageSaveSnapshotStore
import org.osada.save.SaveResult
import org.osada.save.SaveResultKind
import org.osada.save.SaveSnapshot
import org.osada.save.SaveSnapshotStore
import org.osada.save.SaveStatus
import org.osada.save.SaveStatusBus
import org.osada.save.SavedGameSummary
import kotlin.js.Date
import kotlin.js.json

/**
 * All save-state I/O: the per-campaign-run repository, the standalone/tutorial session snapshot,
 * settings, and file import/export. See `docs/design/save-recovery.md`.
 *
 * OSADA: cloud save/load was removed — it used a hardcoded GitHub gist token belonging to the
 * original Panzer Marshal author's account, not something a fork should keep using without its
 * own server/credentials. Reintroduce only against a project-owned backend/token if ever needed.
 *
 * Extracted from the former `GameState` god-class. This collaborator owns the storage keys and
 * the asynchronous restore orchestration; it produces save payloads via [GameStateSerializer] and
 * rebuilds the game via the injected [GameStateRestore]. It holds no serialization or graph-
 * building logic of its own.
 *
 * `save()`/`saveCampaign()` used to write scenario, players and campaign to three independent
 * `localStorage` keys — a quota/storage exception partway through could leave one key from the
 * new save and another from the old one. Both now commit ONE validated [SaveSnapshot] through
 * [store], which is the actual fix for that failure mode; `saveCampaign()` is kept only so the
 * existing call sites (turn end, scenario load, disk-import setup) do not need to change, since a
 * campaign-only write was exactly the split-write bug this replaces.
 */
@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
class GameStatePersistence(
    private val game: Game,
    private val restorer: GameStateRestore,
    private val store: SaveSnapshotStore = LocalStorageSaveSnapshotStore(),
) {
    private val majorVersion: String = VERSION.split(".").take(2).joinToString(".")
    private val settingsKey = "osada-settings-$majorVersion"
    private val standaloneSessionKey = "osada-standalone-session-$majorVersion"

    // Legacy shared keys from before the per-campaign-run repository (pre-2026-08-15 build).
    // Migration-only: read once at startup if the new repository is empty, then folded into a
    // real campaign run. See restore()/migrateLegacyIfNeeded().
    private val legacyScenarioKey = "osada-scenario-$majorVersion"
    private val legacyPlayersKey = "osada-players-$majorVersion"
    private val legacyCampaignKey = "osada-campaign-$majorVersion"

    private val loadingState = mutableMapOf<String, dynamic>()

    /** The campaign-run id currently open in this session, if any -- kept so quota eviction never
     *  drops the run actually being played (design doc section 11). */
    private var activeCampaignRunId: String? = null

    fun save() {
        val scenario = game.scenario ?: return
        val campaign = game.campaign
        SaveStatusBus.update(SaveStatus.Saving)
        if (campaign != null) {
            activeCampaignRunId = campaign.file
            val result = commitCampaignSnapshot(campaign.file)
            reportResult(result)
        } else {
            saveStandaloneSession(scenario)
            SaveStatusBus.update(SaveStatus.Saved(Date().getTime()))
        }
    }

    /** Kept for existing call sites (turn end, campaign scenario load, disk-import setup): a
     *  campaign-only write is now just the same unified commit, never a second independent key. */
    fun saveCampaign() = save()

    fun saveSettings() {
        localStorageSet(settingsKey, uiSettings)
    }

    private fun commitCampaignSnapshot(campaignRunId: String): SaveResult {
        val payload = GameStateSerializer.exportGameState(game)
        val parsedPayload = JSON.parse<dynamic>(payload)
        val scenario = game.scenario
        val phase =
            SavePhaseValidation.derivePhase(
                parsedPayload.scenario,
                deployPhaseActive = game.campaign?.deployPhase == true && !(game.gameStarted),
            )
        if (!SavePhaseValidation.isValidForPhase(phase, parsedPayload.scenario, parsedPayload.players)) {
            return SaveResult(SaveResultKind.CORRUPT_READBACK, "generated snapshot failed phase validation ($phase)")
        }
        val snapshot =
            SaveSnapshot(
                id = generateId(),
                campaignRunId = campaignRunId,
                kind = "autosave",
                createdAt = Date().getTime(),
                gameVersion = VERSION,
                saveFormat = GameStateSerializer.SAVE_FORMAT_VERSION,
                scenarioFile = scenario?.file ?: "",
                scenarioName = scenario?.name ?: "",
                turn = scenario?.map?.turn ?: 0,
                maxTurns = scenario?.maxTurns ?: 0,
                phase = phase,
                campaignFile = game.campaign?.file ?: "",
                campaignScenario = game.campaign?.currentScenarioIndex ?: 0,
                payload = payload,
            )
        var result = store.commitAutosave(campaignRunId, snapshot)
        if (result.kind == SaveResultKind.QUOTA_EXCEEDED) {
            (store as? LocalStorageSaveSnapshotStore)?.let {
                it.pruneOrphans()
                it.evictOldestRecovery(activeCampaignRunId)
            }
            result = store.commitAutosave(campaignRunId, snapshot)
        }
        return result
    }

    private fun reportResult(result: SaveResult) {
        if (result.isSuccess) {
            SaveStatusBus.update(SaveStatus.Saved(Date().getTime()))
        } else {
            console.error("[osada] campaign save FAILED (${result.kind}): ${result.message}")
            SaveStatusBus.update(SaveStatus.Failed(result.message ?: result.kind.name))
        }
    }

    /** Wrapped in a `{savedAt, payload}` envelope so [restore] can tell whether this session or a
     *  campaign run was played more recently. A bare pre-envelope payload is still readable --
     *  see [readStandaloneSession]. */
    private fun saveStandaloneSession(scenario: org.osada.scenario.Scenario) {
        try {
            val envelope =
                json(
                    Pair("savedAt", Date().getTime()),
                    Pair("payload", GameStateSerializer.exportGameState(game)),
                )
            localStorage.setItem(standaloneSessionKey, JSON.stringify(envelope))
        } catch (e: Throwable) {
            console.error("[osada] standalone session snapshot FAILED", e)
            SaveStatusBus.update(SaveStatus.Failed(e.message ?: "storage error"))
        }
    }

    /**
     * Reads the standalone/tutorial session snapshot in either shape: the current
     * `{savedAt, payload}` envelope, or a bare payload written before the envelope existed. An
     * unknown timestamp becomes 0.0, so a dated campaign run wins the recency comparison in
     * [restore] -- the conservative direction, since the campaign run is the one with real
     * progress behind it.
     */
    private fun readStandaloneSession(): StandaloneSession? {
        val raw = localStorage.getItem(standaloneSessionKey) ?: return null
        val parsed = parseOrNull(raw)
        val payload = parsed?.payload as? String
        // No `payload` field means either the bare pre-envelope shape or something unreadable;
        // both are handed on as-is, and an unreadable one simply fails the restore attempt and
        // lets [restore] move to its next candidate.
        return if (payload == null) {
            StandaloneSession(0.0, raw)
        } else {
            StandaloneSession(parsed.savedAt as? Double ?: 0.0, payload)
        }
    }

    /**
     * Main-menu Continue / boot restore: brings back whatever was played most recently.
     *
     * Every source is tried in recency order and a failure falls through to the next. Both halves
     * of that matter, and neither used to hold: this returned unconditionally after the campaign
     * branch, so (1) refreshing during a standalone scenario restored an unrelated CAMPAIGN as soon
     * as any campaign run existed, and (2) a campaign run whose generations were both unloadable
     * took the whole restore down with it, never reaching the standalone or legacy snapshots
     * written directly below it -- the chain only looked like a chain.
     *
     * The campaign register resumes a SPECIFIC row through [restoreCampaignRun] instead; this is
     * the only caller that picks one implicitly.
     */
    fun restore(
        onSuccess: () -> Unit,
        onFail: () -> Unit,
    ) {
        (store as? LocalStorageSaveSnapshotStore)?.pruneOrphans()
        val attempts = mutableListOf<Pair<Double, () -> Boolean>>()
        store.listCampaignRuns().firstOrNull()?.let { run ->
            attempts += run.lastPlayedAt to { openCampaignRun(run.campaignRunId, onSuccess) }
        }
        readStandaloneSession()?.let { session ->
            attempts += session.savedAt to { restoreFromString(session.payload, onSuccess) }
        }
        attempts.sortByDescending { it.first }
        for ((_, attempt) in attempts) {
            if (attempt()) return
        }
        restoreFromLegacyKeys(onSuccess, onFail)
    }

    private data class StandaloneSession(
        val savedAt: Double,
        val payload: String,
    )

    /**
     * What the main menu's Continue button needs: null when nothing is restorable (hide it), else
     * the scenario label to annotate it with. Deliberately mirrors [restore]'s own fallback order
     * -- repository run, then standalone session, then a not-yet-migrated legacy save -- so the
     * button appears exactly when pressing it would find something.
     *
     * A run's turn position is read from the index row, never from a generation, so this stays a
     * single small `localStorage` read rather than parsing a ~130 KB payload on every menu show.
     */
    fun savedGameSummary(): SavedGameSummary? =
        store
            .listCampaignRuns()
            .firstOrNull()
            ?.let { SavedGameSummary(it.scenarioName, it.turn, it.maxTurns) }
            ?: readStandaloneSession()?.let { session ->
                summaryOf(parseOrNull(session.payload)?.scenario) ?: SavedGameSummary.UNREADABLE
            }
            // The legacy key stored the scenario object directly, not a whole exported game state.
            ?: localStorage.getItem(legacyScenarioKey)?.let { raw ->
                summaryOf(parseOrNull(raw)) ?: SavedGameSummary.UNREADABLE
            }

    private fun summaryOf(scenario: dynamic): SavedGameSummary? {
        val name = (scenario?.name as? String)?.takeIf { it.isNotBlank() } ?: return null
        return SavedGameSummary(name, scenario.turn as? Int ?: 0, scenario.maxTurns as? Int ?: 0)
    }

    // Save data is an external, untrusted blob: an unparseable one means "present but unreadable",
    // which still has to show Continue, not crash the menu build.
    private fun parseOrNull(raw: String): dynamic =
        try {
            JSON.parse<dynamic>(raw)
        } catch (e: Throwable) {
            console.warn("[osada] saved-game summary unreadable", e)
            null
        }

    /** Resumes one specific campaign run (current generation, falling back to recovery), used by
     *  the campaign register's per-row resume action. */
    fun restoreCampaignRun(
        campaignRunId: String,
        onSuccess: () -> Unit,
        onFail: () -> Unit,
    ) {
        if (!openCampaignRun(campaignRunId, onSuccess)) onFail()
    }

    /** The generation-selection half of [restoreCampaignRun], reporting success rather than
     *  invoking a failure callback, so [restore] can fall through to its next candidate. */
    private fun openCampaignRun(
        campaignRunId: String,
        onSuccess: () -> Unit,
    ): Boolean {
        activeCampaignRunId = campaignRunId
        val current = store.readCurrent(campaignRunId)
        if (current != null && restoreSnapshot(current, onSuccess)) return true
        console.warn("[osada] campaign run '$campaignRunId' current generation invalid, trying recovery")
        val recovery = store.readRecovery(campaignRunId)
        val recovered = recovery != null && restoreSnapshot(recovery, onSuccess)
        if (!recovered) console.error("[osada] campaign run '$campaignRunId' has no loadable generation")
        return recovered
    }

    private fun restoreSnapshot(
        snapshot: SaveSnapshot,
        onReady: () -> Unit,
    ): Boolean = isRestorableGeneration(snapshot) && restoreFromString(snapshot.payload, onReady)

    /**
     * Whether a generation still describes a legal state for its OWN declared phase -- the same
     * check [commitCampaignSnapshot] runs before writing.
     *
     * Validating on read as well is not redundant. A generation can reach the store without ever
     * passing the write path: `replaceCampaignRun` (per-campaign import and whole-profile import)
     * only shape-checks the JSON, and a legacy-key migration inherits whatever the pre-repository
     * build left behind. Those are exactly the "latest generation contains no units" cases the
     * recovery fallback is supposed to catch, and without this they were restored instead.
     */
    private fun isRestorableGeneration(snapshot: SaveSnapshot): Boolean =
        try {
            val parsed = JSON.parse<dynamic>(snapshot.payload)
            val valid = SavePhaseValidation.isValidForPhase(snapshot.phase, parsed.scenario, parsed.players)
            if (!valid) {
                console.warn(
                    "[osada] generation '${snapshot.id}' of '${snapshot.campaignRunId}' " +
                        "failed phase validation (${snapshot.phase}) -- rejecting",
                )
            }
            valid
        } catch (e: Throwable) {
            console.warn("[osada] generation '${snapshot.id}' payload unreadable -- rejecting", e)
            false
        }

    /** Records that this campaign run reached its end, with the outcome it ended on. Called from
     *  the single campaign-end funnel (`Game.continueCampaign`), because no save is written after
     *  a campaign finishes and the state therefore cannot be inferred from any generation. */
    fun markCampaignRunCompleted(
        campaignRunId: String,
        outcome: String,
    ) {
        val result = store.markCompleted(campaignRunId, outcome)
        if (!result.isSuccess) {
            console.warn("[osada] could not mark '$campaignRunId' completed: ${result.kind} ${result.message}")
        }
    }

    private fun restoreFromLegacyKeys(
        onSuccess: () -> Unit,
        onFail: () -> Unit,
    ) {
        val scenarioRaw = localStorage.getItem(legacyScenarioKey)
        val playersRaw = localStorage.getItem(legacyPlayersKey)
        val campaignRaw = localStorage.getItem(legacyCampaignKey)
        if (scenarioRaw == null || playersRaw == null) {
            onFail()
            return
        }
        try {
            val scenarioData = JSON.parse<dynamic>(scenarioRaw)
            val playersData = JSON.parse<dynamic>(playersRaw)
            val campaignData = if (campaignRaw != null) JSON.parse<dynamic>(campaignRaw) else null
            if (!hasAnyUnits(scenarioData, playersData)) {
                clearLegacyKeys()
                onFail()
                return
            }
            restorer.restoreGame(scenarioData, playersData, campaignData) {
                // Fold the migrated state into the new repository immediately so it only ever
                // needs to happen once, then retire the legacy keys.
                onSuccess()
                save()
                clearLegacyKeys()
            }
        } catch (e: Throwable) {
            console.error("[osada] legacy save migration failed", e)
            onFail()
        }
    }

    private fun clearLegacyKeys() {
        localStorage.removeItem(legacyScenarioKey)
        localStorage.removeItem(legacyPlayersKey)
        localStorage.removeItem(legacyCampaignKey)
    }

    fun restoreFromString(
        data: String,
        onReady: () -> Unit = {},
    ): Boolean =
        try {
            val parsed = JSON.parse<dynamic>(data)
            if (isLoadableSave(parsed)) {
                game.cleanup()
                restorer.restoreGame(parsed.scenario, parsed.players, parsed.campaign) {
                    game.setupGameState()
                    onReady()
                }
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            console.error("restoreFromString failed: " + e.message, e)
            false
        }

    /** "Clear campaign" from the register: removes one campaign's browser run. Downloaded files
     *  and other campaigns' runs are untouched. */
    fun clearCampaignRun(campaignRunId: String): SaveResult = store.deleteCampaignRun(campaignRunId)

    /** Called by `Game.cleanup()` at the START of every restore/teardown, campaign or standalone.
     *  Clears legacy migration keys (harmless: the repository is the source of truth once it has
     *  anything in it) and the disposable standalone/tutorial session snapshot -- per design doc
     *  section 8, that snapshot is meant to survive only an accidental refresh of the SAME
     *  standalone scenario; wiping it here and letting the very next `save()` rewrite it if that
     *  scenario is in fact what gets restored is the same "clear-then-immediately-rebuild" pattern
     *  the campaign-run repository already relies on (see restoreGame -> cleanup ordering). It
     *  never touches campaign runs: those live in [store], keyed by campaign, and are replaced
     *  only through an explicit action (starting/replaying that campaign, or an import). */
    fun clear() {
        clearLegacyKeys()
        localStorage.removeItem(standaloneSessionKey)
    }

    fun listCampaignRuns(): List<CampaignRunMetadata> = store.listCampaignRuns()

    fun exportCampaignRun(campaignRunId: String): CampaignRunBundle? = store.readCampaignRunBundle(campaignRunId)

    fun importCampaignRun(bundle: CampaignRunBundle): SaveResult = store.replaceCampaignRun(bundle)

    fun exportProfile() = store.exportProfile()

    fun importProfile(bundle: org.osada.save.ProfileBundle): SaveResult = store.replaceProfile(bundle)

    fun restoreSettings() {
        localStorageGet(settingsKey) { restorer.applySettings(it) }
    }

    private fun generateId(): String {
        val randomSuffixLength = 8
        val random = (js("Math.random()") as Double).toString().drop(2).take(randomSuffixLength)
        return "${Date().getTime().toLong()}-$random"
    }

    // Save data is an external, untrusted JSON blob -- any parse/shape error must fail this
    // call gracefully (return false) rather than crash, so a broad catch is intentional here.
    private fun localStorageSet(
        key: String,
        value: dynamic,
    ) {
        try {
            localStorage.setItem(key, JSON.stringify(value))
        } catch (e: Throwable) {
            console.error("[osada] localStorageSet FAILED for", key, "-- the game is no longer saving", e)
        }
    }

    private fun localStorageGet(
        key: String,
        callback: (dynamic) -> Unit,
    ) {
        val item = localStorage.getItem(key)
        val parsed = if (item != null) JSON.parse(item) else null
        console.log("[osada] localStorageGet $key present=${item != null}")
        callback(parsed)
    }
}

// Top-level (not a class member) so it doesn't count against GameStatePersistence's
// TooManyFunctions budget -- only restoreFromString (same file) calls it.
private fun isLoadableSave(parsed: dynamic): Boolean {
    if (parsed.scenario == undefined) return false
    val fmt = parsed.fmt as? Int ?: 0
    return when {
        fmt < GameStateSerializer.SAVE_FORMAT_VERSION -> {
            // Pre-eqp-united save: its eqids/country codes are from the old per-efile
            // numbering and no longer resolve against the merged equipment DB. Reject
            // rather than load and silently show wrong/missing units.
            console.error(
                "[osada] refusing to load save with fmt=$fmt " +
                    "(need >= ${GameStateSerializer.SAVE_FORMAT_VERSION}): saved before the " +
                    "equipment merge, its unit ids are no longer valid",
            )
            false
        }

        else -> true
    }
}

/** True if the saved state represents a real game in progress: at least one unit on the map,
 *  one pending reinforcement, or one core unit in a player's roster (a deploy-phase game keeps
 *  its core in reserve, so map-only counting would wrongly reject it). A save with none of these
 *  is stale/empty and should not be auto-restored. Kept for the legacy-key migration path; the
 *  repository path uses the richer [SavePhaseValidation] instead. */
private fun hasAnyUnits(
    scenarioData: dynamic,
    playersData: dynamic,
): Boolean =
    SavePhaseValidation.isValidForPhase(SavePhaseValidation.PHASE_PLAYER_TURN, scenarioData, playersData) ||
        SavePhaseValidation.isValidForPhase(SavePhaseValidation.PHASE_PREPARATION, scenarioData, playersData)
