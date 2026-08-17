package org.osada.save

import kotlinx.browser.localStorage
import org.osada.VERSION
import kotlin.js.Date
import kotlin.js.json

/**
 * `localStorage`-backed [SaveSnapshotStore].
 *
 * `docs/design/save-recovery.md` section 4 specifies IndexedDB as the eventual backing store to
 * avoid a shared-quota ceiling across many campaigns. Measured 2026-08-15: a full snapshot is
 * ~120-135 KB and the first-release MVP keeps exactly one run per campaign (one autosave + one
 * recovery generation each), so a realistic multi-campaign catalogue stays in the low single-digit
 * megabytes -- comfortably inside the ~5-10 MB `localStorage` quota most browsers grant per origin.
 * This adapter therefore uses `localStorage`, matching every other synchronous browser-persistence
 * subsystem already in this codebase (settings, Hall of Fame, the mission-start checkpoint,
 * multiplayer recovery) instead of introducing this codebase's first async-IndexedDB code path.
 * [SaveSnapshotStore] is the seam: swapping to IndexedDB later only requires a new adapter here,
 * exactly as the design doc allows.
 */
@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
class LocalStorageSaveSnapshotStore : SaveSnapshotStore {
    private val majorVersion = VERSION.split(".").take(2).joinToString(".")
    private val indexKey = "osada-save-index-$majorVersion"

    private fun currentKey(campaignRunId: String) = "osada-save-run-$majorVersion-$campaignRunId$CURRENT_SUFFIX"

    private fun recoveryKey(campaignRunId: String) = "osada-save-run-$majorVersion-$campaignRunId$RECOVERY_SUFFIX"

    @Suppress("ReturnCount")
    override fun commitAutosave(
        campaignRunId: String,
        snapshot: SaveSnapshot,
    ): SaveResult {
        require(snapshot.campaignRunId == campaignRunId) { "snapshot campaignRunId mismatch" }
        return try {
            val serialized = JSON.stringify(snapshotToJson(snapshot))
            // Step 2 (design doc sec 4): the same minimum shape check import uses.
            if (!SaveValidation.isWellFormedSnapshotJson(serialized)) {
                return SaveResult(SaveResultKind.CORRUPT_READBACK, "generated snapshot failed its own shape check")
            }
            // Promote the existing "current" to "recovery" only if it currently validates -- a
            // corrupt current generation must not overwrite a still-good recovery generation.
            val existingCurrent = localStorage.getItem(currentKey(campaignRunId))
            if (existingCurrent != null && SaveValidation.isWellFormedSnapshotJson(existingCurrent)) {
                localStorage.setItem(recoveryKey(campaignRunId), existingCurrent)
            }
            localStorage.setItem(currentKey(campaignRunId), serialized)
            val readBack =
                localStorage.getItem(currentKey(campaignRunId))
                    ?: return SaveResult(SaveResultKind.CORRUPT_READBACK, "readback returned null")
            if (readBack != serialized) {
                return SaveResult(SaveResultKind.CORRUPT_READBACK, "readback did not match what was written")
            }
            writeIndexRow(
                CampaignRunMetadata(
                    campaignRunId = campaignRunId,
                    campaignFile = snapshot.campaignFile,
                    campaignName = snapshot.campaignFile,
                    scenarioName = snapshot.scenarioName,
                    campaignScenario = snapshot.campaignScenario,
                    phase = snapshot.phase,
                    lastPlayedAt = snapshot.createdAt,
                    // A fresh autosave means this run is being played, so it is by definition not
                    // finished. Completion is set only by markCompleted, from the campaign-end
                    // funnel -- it was previously derived from `phase == "scenarioEnd"`, a value
                    // `SavePhaseValidation.derivePhase` never produces, so it was always false.
                    completed = false,
                    turn = snapshot.turn,
                    maxTurns = snapshot.maxTurns,
                    outcome = "",
                ),
            )
            SaveResult.success()
        } catch (e: Throwable) {
            SaveResult(classifyFailure(e), e.message)
        }
    }

    override fun listCampaignRuns(): List<CampaignRunMetadata> =
        readIndex().values.sortedByDescending { it.lastPlayedAt }

    override fun readCurrent(campaignRunId: String): SaveSnapshot? =
        localStorage.getItem(currentKey(campaignRunId))?.let(::jsonToSnapshot)

    override fun readRecovery(campaignRunId: String): SaveSnapshot? =
        localStorage.getItem(recoveryKey(campaignRunId))?.let(::jsonToSnapshot)

    override fun replaceCampaignRun(bundle: CampaignRunBundle): SaveResult =
        try {
            val currentJson = JSON.stringify(snapshotToJson(bundle.current))
            localStorage.setItem(currentKey(bundle.metadata.campaignRunId), currentJson)
            val recovery = bundle.recovery
            if (recovery != null) {
                localStorage.setItem(
                    recoveryKey(bundle.metadata.campaignRunId),
                    JSON.stringify(snapshotToJson(recovery)),
                )
            } else {
                localStorage.removeItem(recoveryKey(bundle.metadata.campaignRunId))
            }
            writeIndexRow(bundle.metadata)
            SaveResult.success()
        } catch (e: Throwable) {
            SaveResult(classifyFailure(e), e.message)
        }

    @Suppress("ReturnCount")
    override fun readCampaignRunBundle(campaignRunId: String): CampaignRunBundle? {
        val meta = readIndex()[campaignRunId] ?: return null
        val current = readCurrent(campaignRunId) ?: return null
        return CampaignRunBundle(meta, current, readRecovery(campaignRunId))
    }

    override fun exportProfile(): ProfileBundle =
        ProfileBundle(
            runs = readIndex().keys.mapNotNull { readCampaignRunBundle(it) },
            exportedAt = Date().getTime(),
            gameVersion = org.osada.VERSION,
        )

    override fun replaceProfile(bundle: ProfileBundle): SaveResult =
        try {
            // Transactional in intent: validate every run's shape before writing any of them, so a
            // malformed file in the middle of a large profile does not leave a half-applied import.
            for (run in bundle.runs) {
                if (!SaveValidation.isWellFormedSnapshotJson(JSON.stringify(snapshotToJson(run.current)))) {
                    return SaveResult(
                        SaveResultKind.CORRUPT_READBACK,
                        "profile contains an invalid run: ${run.metadata.campaignRunId}",
                    )
                }
            }
            // Whole-profile replace: drop every existing run first, then write the imported set.
            for (existingId in readIndex().keys.toList()) deleteCampaignRun(existingId)
            for (run in bundle.runs) replaceCampaignRun(run)
            SaveResult.success()
        } catch (e: Throwable) {
            SaveResult(classifyFailure(e), e.message)
        }

    override fun deleteCampaignRun(campaignRunId: String): SaveResult =
        try {
            localStorage.removeItem(currentKey(campaignRunId))
            localStorage.removeItem(recoveryKey(campaignRunId))
            val idx = readIndex().toMutableMap()
            idx.remove(campaignRunId)
            persistIndex(idx)
            SaveResult.success()
        } catch (e: Throwable) {
            SaveResult(classifyFailure(e), e.message)
        }

    override fun markCompleted(
        campaignRunId: String,
        outcome: String,
    ): SaveResult =
        try {
            val existing = readIndex()[campaignRunId]
            if (existing == null) {
                SaveResult(SaveResultKind.NOT_FOUND, "no index row for '$campaignRunId'")
            } else {
                writeIndexRow(existing.copy(completed = true, outcome = outcome, lastPlayedAt = Date().getTime()))
                SaveResult.success()
            }
        } catch (e: Throwable) {
            SaveResult(classifyFailure(e), e.message)
        }

    /** Startup audit: an index row whose generation keys are both missing is a leftover from a
     *  failed/interrupted write (design doc sec 4/6) and is dropped. A generation key with no
     *  index row (the reverse failure) is also removed, since nothing can list or restore it and
     *  it would otherwise hold ~130 KB of quota forever. */
    override fun pruneOrphans() {
        val idx = readIndex().toMutableMap()
        var changed = false
        for ((id, _) in idx.toMap()) {
            if (localStorage.getItem(currentKey(id)) == null) {
                idx.remove(id)
                localStorage.removeItem(recoveryKey(id))
                changed = true
            }
        }
        if (changed) persistIndex(idx)
        removeUnreferencedGenerations(idx.keys)
    }

    /**
     * The reverse orphan: a generation key whose run has no index row. Nothing can list or restore
     * it, so it is dead weight against the shared origin quota -- which matters here because quota
     * pressure is what [evictOldestRecovery] exists to relieve. The previous implementation only
     * handled the forward case despite documenting both.
     */
    private fun removeUnreferencedGenerations(knownRunIds: Set<String>) {
        val prefix = "osada-save-run-$majorVersion-"
        // Collected before removing: deleting while iterating localStorage by index re-indexes the
        // remaining keys underneath the cursor and would skip every other match.
        val doomed =
            (0 until localStorage.length)
                .mapNotNull { localStorage.key(it) }
                .filter { key -> generationRunId(key, prefix)?.let { it !in knownRunIds } == true }
        doomed.forEach { localStorage.removeItem(it) }
        if (doomed.isNotEmpty()) {
            console.warn("[osada] pruned ${doomed.size} unreferenced save generation(s)")
        }
    }

    /** The campaign-run id inside a generation key, or null when [key] is not a generation key. */
    private fun generationRunId(
        key: String,
        prefix: String,
    ): String? =
        when {
            !key.startsWith(prefix) -> null
            key.endsWith(CURRENT_SUFFIX) -> key.removePrefix(prefix).removeSuffix(CURRENT_SUFFIX)
            key.endsWith(RECOVERY_SUFFIX) -> key.removePrefix(prefix).removeSuffix(RECOVERY_SUFFIX)
            else -> null
        }

    // ---- quota-driven eviction (design doc sec 11) -------------------------------------------

    /** Drops the oldest recovery generation belonging to the least-recently-played run OTHER than
     *  [activeCampaignRunId]. Returns true if something was evicted. Never touches the active run,
     *  never touches a `current` generation, never touches anything outside this repository. */
    fun evictOldestRecovery(activeCampaignRunId: String?): Boolean {
        val candidate =
            readIndex()
                .values
                .filter { it.campaignRunId != activeCampaignRunId }
                .filter { localStorage.getItem(recoveryKey(it.campaignRunId)) != null }
                .minByOrNull { it.lastPlayedAt }
                ?: return false
        localStorage.removeItem(recoveryKey(candidate.campaignRunId))
        return true
    }

    // ---- index -------------------------------------------------------------------------------

    private fun readIndex(): Map<String, CampaignRunMetadata> {
        val raw = localStorage.getItem(indexKey) ?: return emptyMap()
        return try {
            val parsed = JSON.parse<dynamic>(raw)
            val rows = parsed.rows.unsafeCast<Array<dynamic>>()
            rows
                .mapNotNull { row -> rowToMetadata(row) }
                .associateBy { it.campaignRunId }
        } catch (e: Throwable) {
            console.warn("[osada] save index unreadable, treating as empty", e)
            emptyMap()
        }
    }

    private fun writeIndexRow(metadata: CampaignRunMetadata) {
        val idx = readIndex().toMutableMap()
        idx[metadata.campaignRunId] = metadata
        persistIndex(idx)
    }

    private fun persistIndex(map: Map<String, CampaignRunMetadata>) {
        val rows =
            map.values
                .map { m ->
                    json(
                        Pair("campaignRunId", m.campaignRunId),
                        Pair("campaignFile", m.campaignFile),
                        Pair("campaignName", m.campaignName),
                        Pair("scenarioName", m.scenarioName),
                        Pair("campaignScenario", m.campaignScenario),
                        Pair("phase", m.phase),
                        Pair("lastPlayedAt", m.lastPlayedAt),
                        Pair("completed", m.completed),
                        Pair("turn", m.turn),
                        Pair("maxTurns", m.maxTurns),
                        Pair("outcome", m.outcome),
                    )
                }.toTypedArray()
        localStorage.setItem(indexKey, JSON.stringify(json(Pair("rows", rows))))
    }

    private fun rowToMetadata(row: dynamic): CampaignRunMetadata? {
        val campaignRunId = row.campaignRunId as? String ?: return null
        return CampaignRunMetadata(
            campaignRunId = campaignRunId,
            campaignFile = row.campaignFile as? String ?: "",
            campaignName = row.campaignName as? String ?: campaignRunId,
            scenarioName = row.scenarioName as? String ?: "",
            campaignScenario = row.campaignScenario as? Int ?: 0,
            phase = row.phase as? String ?: "",
            lastPlayedAt = row.lastPlayedAt as? Double ?: 0.0,
            completed = row.completed as? Boolean ?: false,
            turn = row.turn as? Int ?: 0,
            maxTurns = row.maxTurns as? Int ?: 0,
            outcome = row.outcome as? String ?: "",
        )
    }

    private fun classifyFailure(e: Throwable): SaveResultKind {
        val name = e.asDynamic()?.name as? String
        return if (name == "QuotaExceededError") SaveResultKind.QUOTA_EXCEEDED else SaveResultKind.STORAGE_UNAVAILABLE
    }

    companion object {
        private const val CURRENT_SUFFIX = "-current"
        private const val RECOVERY_SUFFIX = "-recovery"

        fun snapshotToJson(s: SaveSnapshot): dynamic =
            json(
                Pair("id", s.id),
                Pair("campaignRunId", s.campaignRunId),
                Pair("kind", s.kind),
                Pair("createdAt", s.createdAt),
                Pair("gameVersion", s.gameVersion),
                Pair("saveFormat", s.saveFormat),
                Pair("scenarioFile", s.scenarioFile),
                Pair("scenarioName", s.scenarioName),
                Pair("turn", s.turn),
                Pair("maxTurns", s.maxTurns),
                Pair("phase", s.phase),
                Pair("campaignFile", s.campaignFile),
                Pair("campaignScenario", s.campaignScenario),
                Pair("payload", s.payload),
            )

        @Suppress("CyclomaticComplexMethod") // one field-by-field ?: default per SaveSnapshot property
        fun jsonToSnapshot(raw: String): SaveSnapshot? =
            try {
                val d = JSON.parse<dynamic>(raw)
                SaveSnapshot(
                    id = d.id as? String ?: return null,
                    campaignRunId = d.campaignRunId as? String ?: return null,
                    kind = d.kind as? String ?: "autosave",
                    createdAt = d.createdAt as? Double ?: 0.0,
                    gameVersion = d.gameVersion as? String ?: "",
                    saveFormat = d.saveFormat as? Int ?: 0,
                    scenarioFile = d.scenarioFile as? String ?: "",
                    scenarioName = d.scenarioName as? String ?: "",
                    turn = d.turn as? Int ?: 0,
                    maxTurns = d.maxTurns as? Int ?: 0,
                    phase = d.phase as? String ?: "",
                    campaignFile = d.campaignFile as? String ?: "",
                    campaignScenario = d.campaignScenario as? Int ?: 0,
                    payload = d.payload as? String ?: return null,
                )
            } catch (e: Throwable) {
                console.warn("[osada] snapshot JSON unreadable", e)
                null
            }
    }
}
