package org.osada.cloud

/**
 * The profile's online copy as the server's manifest describes it. The first sync release keeps the
 * whole profile (every campaign, the hero archive, custom rules and the standalone battle) as one
 * revisioned object, so hero careers never split between devices; see the online-saves contract.
 */
data class CloudCopy(
    val runInstanceId: String,
    val revision: Int,
    val contentHash: String,
    val deleted: Boolean,
    val summary: CopySummary,
    val serverSavedAt: Double,
    val clientPlayedAt: Double?,
)

/** Display facts for a copy, from the upload's own summary. All optional: old or odd rows degrade. */
data class CopySummary(
    val campaigns: Int = 0,
    val campaignName: String = "",
    val scenarioName: String = "",
    val turn: Int = 0,
    val device: String = "",
)

/**
 * What this browser remembers about its link to the online copy. Device-local (never in a backup)
 * and bound to one account and data epoch: another account's sign-in never uploads this profile.
 */
data class SyncBinding(
    val accountId: String,
    val dataEpoch: Int,
    val runInstanceId: String,
    /** The last revision this device uploaded or downloaded and saw acknowledged. */
    val ackRevision: Int,
    /** Content hash of that acknowledged revision. */
    val ackHash: String,
    /** Its [LocalCopy.contentKey]; empty for a link stored before the key existed. */
    val ackContentKey: String = "",
    /** "Decide later" on a conflict: no automatic upload or download until the player chooses. */
    val paused: Boolean = false,
)

/**
 * This device's committed profile. [hash] covers the exact upload bytes; [contentKey] the same
 * document without pure timestamps (`exportedAt`, the standalone battle's `savedAt`), which a
 * restore rewrites with identical game content on every page load. "Has this device saved anything
 * new?" is asked of the content key, so opening the game is not mistaken for playing it.
 */
data class LocalCopy(
    val hash: String,
    val empty: Boolean,
    val contentKey: String = hash,
)

sealed interface SyncDecision {
    /** Nothing to do: both sides hold the acknowledged content. */
    data object InSync : SyncDecision

    /** Identical bytes on both sides without a recorded link: record it, show no dialog (§8 case 4). */
    data class Adopt(
        val copy: CloudCopy,
    ) : SyncDecision

    /** This device moved on from the acknowledged base and the cloud did not: conditional upload. */
    data class Upload(
        val runInstanceId: String,
        val baseRevision: Int,
    ) : SyncDecision

    /** The cloud descends from what this device acknowledged and this device has no new saves. */
    data class Download(
        val copy: CloudCopy,
    ) : SyncDecision

    /** Signed in, nothing online yet and saves here: offer the first upload; never automatic. */
    data object OfferFirstUpload : SyncDecision

    /** Nothing on this device and a copy online: offer to bring it here. */
    data class OfferDownload(
        val copy: CloudCopy,
    ) : SyncDecision

    /**
     * Two different versions and no proof either descends from the other: either both diverged
     * after a shared base ([sharedBase]) or this device's saves were never linked to the account
     * (guest progress). The player chooses; nothing is merged or overwritten automatically.
     */
    data class Choose(
        val copy: CloudCopy,
        val sharedBase: Boolean,
        val paused: Boolean,
    ) : SyncDecision

    /** The linked copy was deleted or expired online; uploading again starts a new copy, by choice. */
    data class CloudGone(
        val reason: String,
    ) : SyncDecision

    /** Nothing anywhere. */
    data object Empty : SyncDecision
}

/**
 * The reconciliation table of online-saves-and-player-profile.md §4, for the whole-profile copy.
 *
 * Ancestry decides, never clocks: "newer" means the cloud revision descends from the revision this
 * device acknowledged, and a dirty device is one whose content differs from what it acknowledged.
 * Timestamps only ever label the two cards of a choice.
 */
object CloudSyncRules {
    const val PROFILE_FILE = "osada-profile"

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // One branch per row of the §4 table.
    fun decide(
        accountId: String,
        dataEpoch: Int,
        binding: SyncBinding?,
        local: LocalCopy,
        cloud: CloudCopy?,
    ): SyncDecision {
        val linked = binding?.takeIf { it.accountId == accountId }
        if (linked != null && linked.dataEpoch != dataEpoch) return SyncDecision.CloudGone("epoch-expired")
        val live = cloud?.takeIf { !it.deleted }
        val linkedCopyGone = cloud == null || cloud.deleted || cloud.runInstanceId != linked?.runInstanceId
        if (linked != null && linkedCopyGone) {
            // The copy this device was linked to is no longer offered. A different live copy (another
            // device started afresh) is a choice, not a silent replacement.
            return when {
                live != null && live.contentHash == local.hash -> SyncDecision.Adopt(live)
                live != null -> SyncDecision.Choose(live, sharedBase = false, paused = linked.paused)
                else -> SyncDecision.CloudGone(if (cloud?.deleted == true) "run-deleted" else "missing")
            }
        }
        if (live == null) return if (local.empty) SyncDecision.Empty else SyncDecision.OfferFirstUpload
        if (live.contentHash == local.hash) {
            return if (linked != null &&
                linked.ackRevision == live.revision
            ) {
                SyncDecision.InSync
            } else {
                SyncDecision.Adopt(live)
            }
        }
        if (linked == null) {
            return if (local.empty) {
                SyncDecision.OfferDownload(
                    live,
                )
            } else {
                SyncDecision.Choose(live, sharedBase = false, paused = false)
            }
        }
        val dirty =
            if (linked.ackContentKey.isEmpty()) {
                local.hash != linked.ackHash
            } else {
                local.contentKey !=
                    linked.ackContentKey
            }
        val cloudMoved = live.revision != linked.ackRevision
        return when {
            linked.paused -> SyncDecision.Choose(live, sharedBase = true, paused = true)
            dirty && !cloudMoved -> SyncDecision.Upload(live.runInstanceId, live.revision)
            !dirty && cloudMoved -> SyncDecision.Download(live)
            dirty && cloudMoved -> SyncDecision.Choose(live, sharedBase = true, paused = false)
            else -> SyncDecision.InSync
        }
    }
}
