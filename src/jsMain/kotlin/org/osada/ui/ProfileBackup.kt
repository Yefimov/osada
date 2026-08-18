package org.osada.ui

import org.osada.Game
import org.osada.current
import org.osada.hero.HeroArchive
import org.osada.hero.HeroArchiveCodec
import org.osada.hero.HeroArchiveService
import org.osada.i18n.I18n
import org.osada.rules.ruleset.RulesetProfileStore
import org.osada.save.CampaignRunCodec
import org.osada.save.CampaignRunMetadata
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
 * The bundle carries three things: every campaign run's saved state, the named ruleset library, and
 * the profile-level hero archive (`docs/design/hero-desk-and-profile-archive.md` §4). The last two
 * are additive fields -- a backup written before either existed simply lacks them and imports with
 * the local copy left alone rather than cleared.
 */
internal object ProfileBackup {
    fun exportToFile() {
        val bundle = Game.current?.state?.exportProfile() ?: return
        val json = bundleToJson(bundle)
        downloadJson("osada-profile-backup-${Date().getTime().toLong()}.json", json)
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
                val parsed = jsonToBundle(JSON.parse<dynamic>(reader.result as String))
                if (parsed == null) onError() else confirmThenApply(parsed, onSuccess, onError)
            } catch (e: Throwable) {
                console.error("[osada] profile backup import failed", e)
                onError()
            }
        }
        reader.readAsText(file)
    }

    /**
     * A parsed backup file, before anything has been applied.
     *
     * The whole file is decoded and validated first and written only after the player confirms.
     * That ordering is the point: the ruleset library used to be replaced inside the PARSER, so
     * picking a backup and then cancelling the confirmation still overwrote every named profile the
     * player had -- a destructive side effect of reading a file.
     */
    private data class ParsedProfile(
        val bundle: ProfileBundle,
        val rulesetProfiles: dynamic,
        val heroArchive: HeroArchive?,
    )

    private fun confirmThenApply(
        parsed: ParsedProfile,
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
            buildPreview(parsed.bundle, existing),
            I18n.t("save_load.profile_import.confirm.confirm_button"),
        ) {
            val result: SaveResult? = Game.current?.state?.importProfile(parsed.bundle)
            if (result?.isSuccess == true) {
                // Applied together with the runs, and only once they are in: a file whose runs were
                // rejected must leave the local rulesets and hero archive exactly as they were.
                restoreRulesetProfiles(parsed.rulesetProfiles)
                parsed.heroArchive?.let(HeroArchiveService::replaceAll)
                onSuccess(parsed.bundle.runs.size)
            } else {
                onError()
            }
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
        return escapeBackupHtml(if (where == null) name else "$name — $where")
    }

    private fun bundleToJson(bundle: ProfileBundle): dynamic =
        json(
            Pair("exportedAt", bundle.exportedAt),
            Pair("gameVersion", bundle.gameVersion),
            Pair(
                "runs",
                bundle.runs
                    .map { CampaignRunCodec.runToJson(it) }
                    .toTypedArray(),
            ),
            // Additive: the named ruleset library travels with a WHOLE-profile backup, never with a
            // campaign-only export (`docs/design/ruleset-profiles.md` §6). A backup written before
            // this field existed simply has no `rulesetProfiles`, and imports unchanged.
            Pair("rulesetProfiles", JSON.parse<dynamic>(RulesetProfileStore.serialize(RulesetProfileStore.custom()))),
            // Same rule for the complete versioned hero archive: it is profile-level state, not
            // per-campaign, so it belongs to the whole-profile backup and to nothing smaller.
            Pair("heroArchive", HeroArchiveCodec.serialize(HeroArchiveService.archive())),
        )

    @Suppress("ReturnCount") // two early rejections (no runs array, no readable run) plus the result
    private fun jsonToBundle(d: dynamic): ParsedProfile? {
        val rawRuns = d?.runs as? Array<dynamic> ?: return null
        val runs = rawRuns.mapNotNull { CampaignRunCodec.jsonToRun(it) }
        if (runs.isEmpty()) return null
        return ParsedProfile(
            bundle = ProfileBundle(runs, d.exportedAt as? Double ?: 0.0, d.gameVersion as? String ?: ""),
            rulesetProfiles = d.rulesetProfiles,
            // An older profile file without `heroArchive` imports with the local archive left
            // alone; the desk then rebuilds live cards from the imported runs' own hero blocks,
            // which is exactly what §4 asks for.
            heroArchive = readHeroArchive(d.heroArchive),
        )
    }
}

/** Null for a backup written before the hero archive existed, which leaves the local archive alone
 *  rather than clearing it (`docs/design/hero-desk-and-profile-archive.md` §4). */
private fun readHeroArchive(raw: dynamic): HeroArchive? =
    if (raw == null || raw == undefined) null else HeroArchiveCodec.parse(raw)

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
