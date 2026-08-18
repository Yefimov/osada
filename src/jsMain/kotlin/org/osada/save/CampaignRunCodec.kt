package org.osada.save

import kotlin.js.json

/**
 * JSON codec for one campaign run's [CampaignRunBundle], shared by the per-campaign
 * `Export campaign` / `Import campaign` pair and by the whole-profile backup that wraps a list of
 * them (`docs/design/save-recovery.md` §§2, 8).
 *
 * Extracted rather than duplicated: a profile backup is a list of exactly these objects, so two
 * copies of this mapping would let the two file formats drift apart field by field and make an
 * older profile backup unreadable by the newer per-campaign importer.
 *
 * Deliberately DOM-free and `internal`, so the round trip is unit-testable without a browser.
 *
 * What is NOT here is as much the point as what is: a run carries no ruleset library and no hero
 * archive. Both are profile-level state and travel only with a whole-profile backup
 * (`docs/design/ruleset-profiles.md` §6, `docs/design/hero-desk-and-profile-archive.md` §4), so
 * importing one campaign can never silently rewrite the player's named rulesets or another
 * campaign's archived career.
 */
internal object CampaignRunCodec {
    /** Marks a single-run file so the importer can reject a whole-profile backup with a specific
     *  message instead of failing an unhelpfully generic shape check. */
    const val FILE_KIND = "osada-campaign-run"

    fun runToJson(run: CampaignRunBundle): dynamic =
        json(
            Pair("metadata", metadataToJson(run.metadata)),
            Pair("current", LocalStorageSaveSnapshotStore.snapshotToJson(run.current)),
            Pair("recovery", run.recovery?.let { LocalStorageSaveSnapshotStore.snapshotToJson(it) }),
        )

    fun metadataToJson(m: CampaignRunMetadata): dynamic =
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

    /**
     * Null when the object is not a readable run: no metadata, no `campaignRunId` to file it under,
     * or no restorable `current` generation. Reads an external file, so every other field falls
     * back to the same "unknown" defaults the store itself uses for rows written before a field
     * existed.
     */
    @Suppress("ReturnCount")
    fun jsonToRun(raw: dynamic): CampaignRunBundle? {
        val metaRaw = raw?.metadata ?: return null
        val metadata = jsonToMetadata(metaRaw) ?: return null
        val current = LocalStorageSaveSnapshotStore.jsonToSnapshot(JSON.stringify(raw.current)) ?: return null
        val recoveryRaw: dynamic = raw.recovery
        val recovery =
            if (recoveryRaw == null || recoveryRaw == undefined) {
                null
            } else {
                LocalStorageSaveSnapshotStore.jsonToSnapshot(JSON.stringify(recoveryRaw))
            }
        return CampaignRunBundle(metadata, current, recovery)
    }

    @Suppress("CyclomaticComplexMethod") // one field-by-field ?: default per CampaignRunMetadata property
    fun jsonToMetadata(d: dynamic): CampaignRunMetadata? =
        CampaignRunMetadata(
            campaignRunId = d.campaignRunId as? String ?: return null,
            campaignFile = d.campaignFile as? String ?: "",
            campaignName = d.campaignName as? String ?: "",
            scenarioName = d.scenarioName as? String ?: "",
            campaignScenario = d.campaignScenario as? Int ?: 0,
            phase = d.phase as? String ?: "",
            lastPlayedAt = d.lastPlayedAt as? Double ?: 0.0,
            completed = d.completed as? Boolean ?: false,
            turn = d.turn as? Int ?: 0,
            maxTurns = d.maxTurns as? Int ?: 0,
            outcome = d.outcome as? String ?: "",
        )
}
