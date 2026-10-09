package org.osada.ui

import org.osada.i18n.I18n
import org.osada.save.SaveStatus

/**
 * Tells the player that this mission's opening checkpoint is not stored by the browser
 * (`MissionRestartCheckpoint.capture`). Restart Mission still works until the page is closed or
 * reloaded, because the checkpoint is kept in memory, and a backup made now still carries it; after
 * a reload the mission has no restart. Until 2026-10-08 this was only a console line, which is how
 * whole campaigns were played without anyone knowing Restart would be missing.
 */
internal object MissionCheckpointNotice {
    fun show(reason: String) {
        val detail =
            if (reason == SaveStatus.SESSION_ONLY) {
                I18n.t("menu.main.restart_mission.not_saved.session_only")
            } else {
                reason
            }
        UIBuilder.messageDynamic(
            I18n.t("menu.main.restart_mission.not_saved.title"),
            I18n.t("menu.main.restart_mission.not_saved.body", mapOf("reason" to escapeHtml(detail))),
        )
    }

    private fun escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
