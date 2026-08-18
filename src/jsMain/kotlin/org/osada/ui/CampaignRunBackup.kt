package org.osada.ui

import org.osada.Game
import org.osada.current
import org.osada.i18n.I18n
import org.osada.save.CampaignRunBundle
import org.osada.save.CampaignRunCodec
import org.osada.save.CampaignRunMetadata
import org.osada.scenario.Campaign
import kotlin.js.Date
import kotlin.js.json

/**
 * `Export campaign…` / `Import campaign…` for ONE campaign run
 * (`docs/design/save-recovery.md` §2 "Campaigns" and §8).
 *
 * Three file-level operations now exist and are deliberately different sizes:
 *
 * - [GameStateMenuBuilder]'s disk save/load moves the LIVE in-memory game;
 * - this one moves one campaign's stored run, current and previous-good generations included,
 *   and touches no other campaign;
 * - [ProfileBackup] moves every run plus the ruleset library and hero archive.
 *
 * The narrow one exists because the broad one is the most destructive action in the game: a player
 * who wants to move a single campaign between browsers should not have to overwrite every other
 * campaign to do it.
 */
internal object CampaignRunBackup {
    /** The parse outcomes the Import button has distinct text for. A player who picked the wrong
     *  kind of backup file is told which kind they picked, rather than just "invalid". */
    internal enum class ImportError {
        UNREADABLE,
        WHOLE_PROFILE_FILE,
        WRITE_FAILED,
    }

    /** False when the campaign has no stored run to write, which the caller surfaces as a disabled
     *  button rather than a download that produces an empty file. */
    fun exportToFile(campaignRunId: String): Boolean {
        val bundle = Game.current?.state?.exportCampaignRun(campaignRunId) ?: return false
        val payload =
            json(
                Pair("kind", CampaignRunCodec.FILE_KIND),
                Pair("exportedAt", Date().getTime()),
                Pair("gameVersion", bundle.current.gameVersion),
                Pair("run", CampaignRunCodec.runToJson(bundle)),
            )
        downloadJson(fileNameFor(bundle.metadata), payload)
        return true
    }

    /**
     * Reads the file, identifies its campaign, previews what would be replaced, and only then
     * writes (§8). Reads untrusted external JSON, so every shape error has to land on [onError]
     * instead of propagating -- hence the broad catch.
     */
    @Suppress("TooGenericExceptionCaught")
    fun importFromFile(
        file: dynamic,
        onSuccess: (String) -> Unit,
        onError: (ImportError) -> Unit,
    ) {
        val reader = js("new FileReader()")
        reader.onloadend = {
            try {
                val parsed = JSON.parse<dynamic>(reader.result as String)
                val run = CampaignRunCodec.jsonToRun(parsed?.run)
                when {
                    run != null -> confirmThenApply(run, onSuccess, onError)
                    // A whole-profile backup is a valid file that belongs in the other importer.
                    parsed?.runs != null -> onError(ImportError.WHOLE_PROFILE_FILE)
                    else -> onError(ImportError.UNREADABLE)
                }
            } catch (e: Throwable) {
                console.error("[osada] campaign run import failed", e)
                onError(ImportError.UNREADABLE)
            }
        }
        reader.readAsText(file)
    }

    private fun confirmThenApply(
        run: CampaignRunBundle,
        onSuccess: (String) -> Unit,
        onError: (ImportError) -> Unit,
    ) {
        val name = displayName(run.metadata)
        // The run this file would replace, matched on `campaignRunId` -- the campaign file name,
        // which is the store's own stable key. Absent when the player has never played it here, and
        // then the import adds a campaign instead of replacing one.
        val existing =
            Game.current
                ?.state
                ?.listCampaignRuns()
                .orEmpty()
                .firstOrNull { it.campaignRunId == run.metadata.campaignRunId }
        ConfirmCard.open(
            // Not escaped: [ConfirmCard] sets its title with `textContent` and only the body with
            // `innerHTML`, so escaping here would print a literal `&amp;` in a campaign's name.
            I18n.t("campaign.run_import.confirm.title", mapOf("campaign" to name)),
            buildPreview(run.metadata, existing),
            I18n.t("campaign.run_import.confirm.confirm_button"),
        ) {
            val result = Game.current?.state?.importCampaignRun(run)
            if (result?.isSuccess == true) onSuccess(name) else onError(ImportError.WRITE_FAILED)
        }
    }

    /**
     * Names the affected campaign, both operations and both timestamps (§8), plus the explicit
     * boundary: one campaign's run is replaced and nothing else is.
     */
    private fun buildPreview(
        incoming: CampaignRunMetadata,
        existing: CampaignRunMetadata?,
    ): String {
        val incomingBlock =
            I18n.t("campaign.run_import.confirm.incoming") + "<br>&nbsp;&nbsp;• " + describe(incoming)
        val outgoingBlock =
            if (existing == null) {
                I18n.t("campaign.run_import.confirm.replaces_nothing")
            } else {
                I18n.t("campaign.run_import.confirm.replaces") + "<br>&nbsp;&nbsp;• " + describe(existing)
            }
        val scope = I18n.t("campaign.run_import.confirm.scope")
        return "$incomingBlock<br><br>$outgoingBlock<br><br>$scope"
    }

    /** "Operation 4 — Kiel, turn 12/20 — 17.08.2026 22:13", degrading field by field for a run
     *  saved before the index carried turn counts or a timestamp. */
    private fun describe(m: CampaignRunMetadata): String {
        val operation = I18n.t("campaign.run_import.operation", mapOf("index" to m.campaignScenario + 1))
        val where =
            m.scenarioName.ifBlank { null }?.let { scenario ->
                when {
                    m.turn > 0 && m.maxTurns > 0 ->
                        I18n.t(
                            "campaign.run_import.scenario_turns",
                            mapOf("scenario" to scenario, "turn" to m.turn, "total" to m.maxTurns),
                        )
                    m.turn > 0 ->
                        I18n.t("campaign.run_import.scenario_turn", mapOf("scenario" to scenario, "turn" to m.turn))
                    else -> scenario
                }
            }
        val stamp = if (m.lastPlayedAt > 0.0) I18n.formatDateTime(m.lastPlayedAt) else null
        return listOfNotNull(operation, where, stamp).joinToString(" — ") { escapeBackupHtml(it) }
    }

    /**
     * The campaign's real title, preferred over what the save index recorded.
     *
     * `CampaignRunMetadata.campaignName` is the campaign FILE name for runs the index wrote that way
     * (`forward.json`), which is not a name to show a player in a destructive confirmation. The
     * installed campaign list is asked first, by the same file lookup the restore path uses; the
     * file's own metadata is the fallback for a campaign this browser does not have installed, and
     * the run id the last resort.
     */
    private fun displayName(m: CampaignRunMetadata): String {
        val index = Campaign.findCampaignByFile(m.campaignRunId)
        val title = StartMenuBuilder.campaignList().getOrNull(index)?.title as? String
        return title?.takeIf { it.isNotBlank() } ?: m.campaignName.ifBlank { m.campaignRunId }
    }

    /** `osada-campaign-camp6.json-1755470000000.json`: the campaign it belongs to is visible in the
     *  file name, because a folder of exports is otherwise indistinguishable timestamps. */
    private fun fileNameFor(m: CampaignRunMetadata): String {
        val slug =
            m.campaignRunId
                .removeSuffix(".json")
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "campaign" }
        return "osada-campaign-$slug-${Date().getTime().toLong()}.json"
    }
}
