package org.osada.ui

import org.osada.GameHolder
import org.osada.diskSavePayload
import org.osada.i18n.I18n
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.storage.BrowserStorage
import org.w3c.dom.HTMLElement
import kotlin.js.Date

/**
 * The Save / Load window: the current battle alone as a file (save/load scenario), then the
 * whole-profile backup export/import. Loading a battle file replaces only the game in play.
 */
internal object GameStateMenuBuilder {
    fun buildGameStateMenu() {
        val root = byId("smState") ?: return
        val header = addTag(root, "div")
        header.id = "smStateHeader"
        header.className = "osadaScreenHeader osada-sl-header"
        val body = addTag(root, "div")
        body.className = "osada-sl-body"
        buildScenarioRow(body)
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

    /** Save/load of the battle in play only: no other campaign run or profile data is touched. */
    private fun buildScenarioRow(body: HTMLElement) {
        backupButton(body, "disksave").asButton(onActivate = { gameStateButton("disksave") })
        val load = backupButton(body, "diskload")
        val picker = addTag(body, "input")
        picker.id = "scenarioLoadFile"
        picker.setAttribute("type", "file")
        picker.setAttribute("accept", ".json")
        picker.style.display = "none"
        load.asButton(onActivate = { gameStateButton("diskload") })
        picker.addEventListener("change", {
            val files = picker.asDynamic().files
            if ((files?.length as? Int ?: 0) > 0) loadScenarioFile(files[0])
            picker.asDynamic().value = ""
        })
    }

    private fun scenarioSaveAvailable(): Boolean = !OsadaMultiplayer.active && GameHolder.instance?.gameStarted == true

    /** [ProfileBackup.canImport] also refuses online sessions, animations and computer turns. */
    private fun saveScenarioFile() {
        val game = GameHolder.instance?.takeIf { scenarioSaveAvailable() }
        val payload = if (game != null && ProfileBackup.canImport()) diskSavePayload(game) else null
        when {
            game == null -> Unit
            payload == null -> setStatus(I18n.t("save_load.backup.busy"))
            payload.isEmpty() -> setStatus(I18n.t("save_load.backup.failed"))
            else -> {
                val fileName = scenarioFileName(game)
                downloadJson(fileName, JSON.parse<dynamic>(payload))
                setStatus("${I18n.t("save_load.last_saved.label")} $fileName")
            }
        }
    }

    private fun scenarioFileName(game: org.osada.Game): String {
        val now = Date()
        return I18n.t(
            "save_load.filename",
            mapOf(
                "mode" to
                    I18n.t(if (game.campaign != null) "save_load.filename.campaign" else "save_load.filename.scenario"),
                "name" to (game.scenario?.name ?: ""),
                "turn" to (game.scenario?.map?.turn ?: 0),
                "date" to now.toDateString(),
                "time" to now.toLocaleTimeString(),
            ),
        ) + ".json"
    }

    private fun loadScenarioFile(file: dynamic) {
        val state = GameHolder.instance?.state
        if (state == null || !ProfileBackup.canImport()) {
            setStatus(I18n.t("save_load.backup.busy"))
            return
        }
        state.restoreFromFile(
            file,
            onSuccess = {
                setStatus("")
                makeHidden("smState")
                makeHidden("smMain")
                gameRef()?.ui?.startMenuButton("continuegame")
            },
            onError = { setStatus(I18n.t("save_load.error.invalid")) },
        )
    }

    private fun setStatus(text: String) {
        byId("diskloaderror")?.textContent = text
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
        val canSave = scenarioSaveAvailable()
        byId("disksave")?.apply {
            classList.toggle("osada-sl-btn--disabled", !canSave)
            setAttribute("aria-disabled", (!canSave).toString())
            querySelector(".osada-sl-btn__sub")?.textContent =
                I18n.t(if (canSave) "save_load.save.subtitle" else "save_load.save.unavailable")
        }
        byId("diskload")?.apply {
            classList.toggle("osada-sl-btn--disabled", OsadaMultiplayer.active)
            setAttribute("aria-disabled", OsadaMultiplayer.active.toString())
        }
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
        val buttons =
            listOf(
                "disksave" to "save",
                "diskload" to "load",
                "profileBackupExport" to "profile_export",
                "profileBackupImport" to "profile_import",
            )
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

    /** "disksave"/"diskload": the current battle alone, as a file. */
    fun gameStateButton(id: String) {
        when (id) {
            "disksave" -> saveScenarioFile()
            "diskload" -> if (!OsadaMultiplayer.active) byId("scenarioLoadFile")?.asDynamic()?.click()
        }
    }
}
