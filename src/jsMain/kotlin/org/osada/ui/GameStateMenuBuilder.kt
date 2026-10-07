package org.osada.ui

import org.osada.i18n.I18n
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.storage.BrowserStorage
import org.w3c.dom.HTMLElement

/** A single backup/export and import surface, including legacy save files. */
internal object GameStateMenuBuilder {
    fun buildGameStateMenu() {
        val root = byId("smState") ?: return
        val header = addTag(root, "div")
        header.id = "smStateHeader"
        header.className = "osadaScreenHeader osada-sl-header"
        val body = addTag(root, "div")
        body.className = "osada-sl-body"
        val export = backupButton(body, "profileBackupExport")
        export.asButton(onActivate = {
            if (!OsadaMultiplayer.active) ProfileBackup.exportToFile()
        })
        val import = backupButton(body, "profileBackupImport")
        val picker = addTag(body, "input")
        picker.id = "profileBackupImportFile"
        picker.setAttribute("type", "file")
        picker.setAttribute("accept", ".json")
        picker.style.display = "none"
        import.asButton(onActivate = { if (!OsadaMultiplayer.active) picker.asDynamic().click() })
        picker.addEventListener("change", {
            val files = picker.asDynamic().files
            if ((files?.length as? Int ?: 0) > 0) {
                ProfileBackup.importFromFile(
                    files[0],
                    onSuccess = {
                        byId("diskloaderror")?.textContent =
                            I18n.t("save_load.profile_import.success", mapOf("count" to it))
                    },
                    onError = { key -> byId("diskloaderror")?.textContent = I18n.t(key) },
                )
            }
            picker.asDynamic().value = ""
        })
        val info = addTag(body, "div")
        info.id = "backupScope"
        info.className = "osada-sl-info"
        val status = addTag(body, "div")
        status.id = "diskloaderror"
        status.className = "osada-sl-error"
        status.setAttribute("role", "status")
        status.setAttribute("aria-live", "polite")
        OnlineSavesPanel.buildEntry(body)
        val footer = addTag(root, "div")
        footer.className = "osada-sl-footer"
        byId("smStOkBut")?.let { footer.appendChild(it) }
        byId("smStOkBut")?.onclick = {
            makeHidden("smState")
            makeVisible("smMain")
            status.textContent = ""
        }
        refreshLocalization()
    }

    private fun backupButton(
        body: HTMLElement,
        id: String,
    ): HTMLElement {
        val button = addTag(body, "div")
        button.id = id
        button.className = "osada-sl-btn"
        addTag(button, "span").className = "osada-sl-btn__label"
        addTag(button, "span").className = "osada-sl-btn__sub"
        return button
    }

    fun applySaveLoadContext() {
        byId("smStateHeader")?.textContent = I18n.t("save_load.title")
        listOf("profileBackupExport", "profileBackupImport").forEach { id ->
            byId(id)?.apply {
                classList.toggle("osada-sl-btn--disabled", OsadaMultiplayer.active)
                setAttribute("aria-disabled", OsadaMultiplayer.active.toString())
            }
        }
        byId("backupScope")?.textContent =
            I18n.t(
                when {
                    OsadaMultiplayer.active -> "save_load.backup.multiplayer"
                    !BrowserStorage.persistent -> "save_load.backup.session_only"
                    else -> "save_load.backup.description"
                },
            )
    }

    fun refreshLocalization() {
        val buttons = listOf("profileBackupExport" to "profile_export", "profileBackupImport" to "profile_import")
        buttons.forEach { (id, key) ->
            byId(id)?.apply {
                title = I18n.t("save_load.$key.help")
                querySelector(".osada-sl-btn__label")?.textContent = I18n.t("save_load.$key.label")
                querySelector(".osada-sl-btn__sub")?.textContent = I18n.t("save_load.$key.subtitle")
            }
        }
        byId("smStOkBut")?.apply {
            title = I18n.t("save_load.close.help")
            setAttribute("data-label", I18n.t("common.close.label"))
        }
        applySaveLoadContext()
    }

    /** Preserve the old command entry points without offering a second file format in the UI. */
    fun gameStateButton(id: String) {
        when (id) {
            "disksave" -> ProfileBackup.exportToFile()
            "diskload" -> byId("profileBackupImportFile")?.asDynamic()?.click()
        }
    }
}
