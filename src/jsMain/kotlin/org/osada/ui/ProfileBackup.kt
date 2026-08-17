package org.osada.ui

import kotlinx.browser.document
import org.osada.Game
import org.osada.current
import org.osada.i18n.I18n
import org.osada.rules.ruleset.RulesetProfileStore
import org.osada.save.CampaignRunBundle
import org.osada.save.CampaignRunMetadata
import org.osada.save.LocalStorageSaveSnapshotStore
import org.osada.save.ProfileBundle
import org.osada.save.SaveResult
import kotlin.js.Date
import kotlin.js.json

/**
 * "Export full profile backup" / "Import full profile backup" from
 * `docs/design/save-recovery.md` section 6: every campaign run plus completion metadata in one
 * file, distinct from the existing single-campaign disk save/load already built in
 * [GameStateMenuBuilder] (that one exports/imports the live in-memory game only). Reuses the
 * existing hidden-anchor download technique from [org.osada.OSGlue.disksave] rather than adding a
 * second download mechanism.
 *
 * Scoping note: the hero archive is not yet part of the exported bundle -- the hero desk
 * workstream (`docs/player-comfort-roadmap.md` workstream 10) that would produce a profile-level
 * archive distinct from each campaign's own save has not been built. This exports every campaign
 * run's own saved state, which already includes that run's in-campaign hero roster.
 */
internal object ProfileBackup {
    fun exportToFile() {
        val bundle = Game.current?.state?.exportProfile() ?: return
        val json = bundleToJson(bundle)
        val fileName = "osada-profile-backup-${Date().getTime().toLong()}.json"
        val anchor = document.createElement("a").asDynamic()
        anchor.download = fileName
        anchor.href = "data:application/force-download," + encodeURIComponentSafe(JSON.stringify(json))
        anchor.click()
    }

    /**
     * Reads the file, shows what it would replace, and only then applies it.
     *
     * A whole-profile import is the single most destructive action in the game -- it drops EVERY
     * existing campaign run and writes the file's set in their place, with no partial merge in this
     * release. It previously committed the moment the file finished loading, with the file picker
     * as the only confirmation, so a mis-picked backup silently destroyed live progress. The
     * preview names both sides: the runs about to be replaced and the runs arriving.
     *
     * Reads an external, untrusted JSON file, so any parse/shape error must fail gracefully through
     * [onError] rather than crash -- hence the broad catch.
     */
    @Suppress("TooGenericExceptionCaught")
    fun importFromFile(
        file: dynamic,
        onSuccess: (Int) -> Unit,
        onError: () -> Unit,
    ) {
        val reader = js("new FileReader()")
        reader.onloadend = {
            try {
                val bundle = jsonToBundle(JSON.parse<dynamic>(reader.result as String))
                if (bundle == null) onError() else confirmThenApply(bundle, onSuccess, onError)
            } catch (e: Throwable) {
                console.error("[osada] profile backup import failed", e)
                onError()
            }
        }
        reader.readAsText(file)
    }

    private fun confirmThenApply(
        bundle: ProfileBundle,
        onSuccess: (Int) -> Unit,
        onError: () -> Unit,
    ) {
        val existing =
            Game.current
                ?.state
                ?.listCampaignRuns()
                .orEmpty()
        ConfirmCard.open(
            I18n.t("save_load.profile_import.confirm.title"),
            buildPreview(bundle, existing),
            I18n.t("save_load.profile_import.confirm.confirm_button"),
        ) {
            val result: SaveResult? = Game.current?.state?.importProfile(bundle)
            if (result?.isSuccess == true) onSuccess(bundle.runs.size) else onError()
        }
    }

    /** Names every run on both sides of the replacement. Campaign titles come from the file's own
     *  metadata, which is all an offline backup carries -- falling back to the run id. */
    private fun buildPreview(
        bundle: ProfileBundle,
        existing: List<CampaignRunMetadata>,
    ): String {
        val incoming = bundle.runs.map { describe(it.metadata) }
        val outgoing = existing.map { describe(it) }
        val incomingBlock =
            I18n.t("save_load.profile_import.confirm.incoming", mapOf("count" to incoming.size)) +
                "<br>" + incoming.joinToString("<br>") { "&nbsp;&nbsp;• $it" }
        val outgoingBlock =
            if (outgoing.isEmpty()) {
                I18n.t("save_load.profile_import.confirm.replaces_nothing")
            } else {
                I18n.t("save_load.profile_import.confirm.replaces", mapOf("count" to outgoing.size)) +
                    "<br>" + outgoing.joinToString("<br>") { "&nbsp;&nbsp;• $it" }
            }
        return "$incomingBlock<br><br>$outgoingBlock"
    }

    private fun describe(m: CampaignRunMetadata): String {
        val name = m.campaignName.ifBlank { m.campaignRunId }
        val where = m.scenarioName.ifBlank { null }
        return escapeHtml(if (where == null) name else "$name — $where")
    }

    /** The preview is injected as HTML (the list needs line breaks), and campaign/scenario names
     *  in an imported file are attacker-controlled text, so they are escaped rather than trusted. */
    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun bundleToJson(bundle: ProfileBundle): dynamic =
        json(
            Pair("exportedAt", bundle.exportedAt),
            Pair("gameVersion", bundle.gameVersion),
            Pair(
                "runs",
                bundle.runs
                    .map { runToJson(it) }
                    .toTypedArray(),
            ),
            // Additive: the named ruleset library travels with a WHOLE-profile backup, never with a
            // campaign-only export (`docs/design/ruleset-profiles.md` §6). A backup written before
            // this field existed simply has no `rulesetProfiles`, and imports unchanged.
            Pair("rulesetProfiles", JSON.parse<dynamic>(RulesetProfileStore.serialize(RulesetProfileStore.custom()))),
        )

    private fun runToJson(run: CampaignRunBundle): dynamic =
        json(
            Pair("metadata", metadataToJson(run.metadata)),
            Pair("current", LocalStorageSaveSnapshotStore.snapshotToJson(run.current)),
            Pair("recovery", run.recovery?.let { LocalStorageSaveSnapshotStore.snapshotToJson(it) }),
        )

    private fun metadataToJson(m: CampaignRunMetadata): dynamic =
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

    @Suppress("TooGenericExceptionCaught", "ReturnCount", "CyclomaticComplexMethod")
    private fun jsonToBundle(d: dynamic): ProfileBundle? {
        val rawRuns = d?.runs as? Array<dynamic> ?: return null
        val runs =
            rawRuns.mapNotNull { r ->
                val metaRaw = r?.metadata ?: return@mapNotNull null
                val metadata =
                    CampaignRunMetadata(
                        campaignRunId = metaRaw.campaignRunId as? String ?: return@mapNotNull null,
                        campaignFile = metaRaw.campaignFile as? String ?: "",
                        campaignName = metaRaw.campaignName as? String ?: "",
                        scenarioName = metaRaw.scenarioName as? String ?: "",
                        campaignScenario = metaRaw.campaignScenario as? Int ?: 0,
                        phase = metaRaw.phase as? String ?: "",
                        lastPlayedAt = metaRaw.lastPlayedAt as? Double ?: 0.0,
                        completed = metaRaw.completed as? Boolean ?: false,
                        turn = metaRaw.turn as? Int ?: 0,
                        maxTurns = metaRaw.maxTurns as? Int ?: 0,
                        outcome = metaRaw.outcome as? String ?: "",
                    )
                val currentRaw: dynamic = r.current
                val current =
                    LocalStorageSaveSnapshotStore.jsonToSnapshot(JSON.stringify(currentRaw))
                        ?: return@mapNotNull null
                val recoveryRaw: dynamic = r.recovery
                val recovery =
                    if (recoveryRaw == null || recoveryRaw == undefined) {
                        null
                    } else {
                        LocalStorageSaveSnapshotStore.jsonToSnapshot(JSON.stringify(recoveryRaw))
                    }
                CampaignRunBundle(metadata, current, recovery)
            }
        if (runs.isEmpty()) return null
        restoreRulesetProfiles(d.rulesetProfiles)
        return ProfileBundle(runs, d.exportedAt as? Double ?: 0.0, d.gameVersion as? String ?: "")
    }
}

/**
 * Replaces the ruleset library from a whole-profile backup, transactionally with the runs
 * (`docs/design/ruleset-profiles.md` §6). Two profiles are never merged merely because their
 * display names match; the backup's library replaces the local one outright.
 *
 * A backup with no `rulesetProfiles` field (any written before rulesets shipped) leaves the local
 * library untouched rather than clearing it.
 */
private fun restoreRulesetProfiles(raw: dynamic) {
    if (raw == null || raw == undefined) return
    RulesetProfileStore.replaceAll(RulesetProfileStore.parse(JSON.stringify(raw)))
}

private fun encodeURIComponentSafe(value: String): String = js("encodeURIComponent")(value) as String
