package org.osada.ui

import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement

/**
 * The campaign register's `Export campaign` / `Import campaign` footer controls, driving
 * [CampaignRunBackup].
 *
 * They live on the register rather than on the Save/Load screen because the design's wording is
 * "the SELECTED campaign's run" (`docs/design/save-recovery.md` §2) and the register is the only
 * surface with a selection, a per-campaign progress note and the neighbouring `Start over` action.
 * The Save/Load screen keeps the two operations that need no campaign selection: the live-game disk
 * save/load and the whole-profile backup.
 *
 * A separate object rather than more functions on [StartMenuCampaignScreen], which is already over
 * that file's function budget and suppressing the warning.
 */
internal object CampaignBackupButtons {
    private const val EXPORT_ID = "campaignRunExport"
    private const val IMPORT_ID = "campaignRunImport"
    private const val FILE_ID = "campaignRunImportFile"
    private const val STATUS_ID = "campaignRunBackupStatus"

    fun install(host: HTMLElement?) {
        val parent = host ?: return
        byId(EXPORT_ID)?.let { delTag(it) }
        byId(IMPORT_ID)?.let { delTag(it) }
        byId(FILE_ID)?.let { delTag(it) }
        byId(STATUS_ID)?.let { delTag(it) }

        val export = addTag(parent, "div")
        export.id = EXPORT_ID
        export.className = "osada-button osadaCampBackupButton osadaCampBackupButton--export"
        export.asButton(onActivate = { onExport() })

        val importBut = addTag(parent, "div")
        importBut.id = IMPORT_ID
        importBut.className = "osada-button osadaCampBackupButton osadaCampBackupButton--import"

        // A real file input, kept out of the layout and opened by the button, so the control stays
        // keyboard-activatable through `asButton` instead of relying on a click landing on a
        // transparent input stretched over the label.
        val picker = addTag(parent, "input")
        picker.id = FILE_ID
        picker.setAttribute("type", "file")
        picker.setAttribute("accept", ".json")
        picker.style.display = "none"
        importBut.asButton(onActivate = { picker.asDynamic().click() })
        picker.addEventListener("change", { onFilePicked(picker) })

        val status = addTag(parent, "div")
        status.id = STATUS_ID
        status.className = "osadaCampBackupStatus"

        refresh()
    }

    /**
     * Applies labels and the export button's enabled state for the currently selected campaign.
     *
     * Called on install, on every campaign selection change and on a language switch: export is
     * offered only for a campaign that actually has a stored run, and the disabled tooltip says
     * which of the two it is rather than leaving a dead control on the screen.
     */
    fun refresh() {
        val exportable = selectedCampaignFile()?.let { it in StartMenuCampaignData.campaignRunsByFile() } == true
        byId(EXPORT_ID)?.apply {
            val label = I18n.t("campaign.run_export.label")
            textContent = label
            setAttribute("aria-label", label)
            title = I18n.t(if (exportable) "campaign.run_export.help" else "campaign.run_export.unavailable")
            classList.toggle("osadaCampBackupButton--disabled", !exportable)
            setAttribute("aria-disabled", (!exportable).toString())
        }
        byId(IMPORT_ID)?.apply {
            val label = I18n.t("campaign.run_import.label")
            textContent = label
            setAttribute("aria-label", label)
            title = I18n.t("campaign.run_import.help")
        }
        status(null)
    }

    private fun onExport() {
        val file = selectedCampaignFile()
        // Mirrors the disabled state instead of trusting it: the click handler is the only thing
        // between a keyboard Enter and a download of nothing.
        if (file == null || file !in StartMenuCampaignData.campaignRunsByFile()) {
            status(I18n.t("campaign.run_export.unavailable"))
            return
        }
        if (CampaignRunBackup.exportToFile(file)) {
            status(I18n.t("campaign.run_export.done"))
        } else {
            status(I18n.t("campaign.run_export.failed"))
        }
    }

    private fun onFilePicked(picker: HTMLElement) {
        val files = picker.asDynamic().files
        if ((files?.length as? Int ?: 0) == 0) return
        CampaignRunBackup.importFromFile(
            files[0],
            onSuccess = { name ->
                // The imported campaign's row still shows the operation/turn of the run that was
                // just replaced, so re-render the register's notes from the new state.
                LiveLocalization.refreshCampaignRows()
                // Before the message, not after: [refresh] resets the status line, so reporting the
                // import first would immediately wipe what it reported.
                refresh()
                status(I18n.t("campaign.run_import.success", mapOf("campaign" to name)))
            },
            onError = { error ->
                status(
                    I18n.t(
                        when (error) {
                            CampaignRunBackup.ImportError.WHOLE_PROFILE_FILE -> "campaign.run_import.error.profile_file"
                            CampaignRunBackup.ImportError.WRITE_FAILED -> "campaign.run_import.error.write"
                            CampaignRunBackup.ImportError.UNREADABLE -> "campaign.run_import.error.unreadable"
                        },
                    ),
                )
            },
        )
        // Clear it so re-picking the same file fires `change` again.
        picker.asDynamic().value = ""
    }

    private fun status(text: String?) {
        byId(STATUS_ID)?.textContent = text ?: ""
    }

    private fun selectedCampaignFile(): String? {
        val index = byId("smCamp")?.asDynamic()?.selectedCampaign as? Int ?: return null
        return StartMenuBuilder.campaignList().getOrNull(index)?.file as? String
    }
}
