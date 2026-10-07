package org.osada.ui

import org.osada.i18n.I18n
import org.osada.save.CampaignRunMetadata

/** The import confirmation's body: what the file brings, what it replaces, and the scope note. */
internal object ProfileBackupPreview {
    internal fun preview(
        plan: ProfileBackupCodec.ImportPlan,
        existing: List<CampaignRunMetadata>,
    ): String {
        val incoming = plan.bundle.runs.map { describe(it.metadata) }
        val replaced =
            if (plan.replaceAll) {
                existing
            } else {
                existing.filter { old ->
                    plan.bundle.runs.any { it.metadata.campaignRunId == old.campaignRunId }
                }
            }
        val standalone = standaloneLine(plan)
        val incomingText =
            if (incoming.isEmpty()) {
                I18n.t("save_load.profile_import.confirm.incoming_none")
            } else {
                I18n.t("save_load.profile_import.confirm.incoming", mapOf("count" to incoming.size)) +
                    "<br>" + incoming.joinToString("<br>")
            }
        val replacedText =
            if (replaced.isEmpty()) {
                I18n.t("save_load.profile_import.confirm.replaces_nothing")
            } else {
                I18n.t("save_load.profile_import.confirm.replaces", mapOf("count" to replaced.size)) +
                    "<br>" + replaced.joinToString("<br>") { describe(it) }
            }
        return incomingText + (standalone?.let { "<br><br>$it" } ?: "") + "<br><br>" + replacedText + "<br><br>" +
            I18n.t(if (plan.replaceAll) "save_load.backup.scope" else "save_load.backup.legacy.scope")
    }

    /** The standalone battle a file carries, so a standalone-only file does not preview as empty. */
    private fun standaloneLine(plan: ProfileBackupCodec.ImportPlan): String? {
        val raw = plan.extras[ProfileBackupCodec.standaloneKey] ?: return null
        val session = JSON.parse<dynamic>(raw)
        val scenario = JSON.parse<dynamic>(session.payload as String).scenario
        val turn = scenario.turn as? Int ?: scenario.map?.turn as? Int ?: 0
        val savedAt = session.savedAt as? Double ?: 0.0
        return escapeBackupHtml(
            I18n.t("save_load.profile_import.confirm.standalone") + " " +
                "${scenario.name as? String ?: ""} · " +
                I18n.t(
                    "save_load.profile_import.turn",
                    mapOf("turn" to turn),
                ) +
                " · " + if (savedAt > 0.0) I18n.formatDateTime(savedAt) else I18n.t("common.none"),
        )
    }

    private fun describe(metadata: CampaignRunMetadata): String =
        escapeBackupHtml(
            "${metadata.campaignName.ifBlank { metadata.campaignRunId }} — ${metadata.scenarioName} · " +
                I18n.t("save_load.profile_import.turn", mapOf("turn" to metadata.turn)) + " · " +
                if (metadata.lastPlayedAt > 0.0) I18n.formatDateTime(metadata.lastPlayedAt) else I18n.t("common.none"),
        )
}
