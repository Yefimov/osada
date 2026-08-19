package org.osada.ui

import org.osada.GameHolder
import org.osada.OSGlue
import org.osada.i18n.I18n
import org.osada.ui.GameStateMenuBuilder.applySaveLoadContext
import org.w3c.dom.HTMLElement
import kotlin.js.Date

/**
 * Builds the Save / Load sub-screen of the start menu and handles disk save & load actions.
 * Extracted from the former `UIBuilder` god-object; the actual storage I/O is delegated to
 * [OSGlue] and `game.state`.
 *
 * OSADA: rebuilt as a standard panel (header strip / body / footer, like the campaign browser
 * and the message box) — the legacy build was two bare `.smMainButton`s with a file input
 * embedded mid-label, which fragmented the "Load from Disk" text. Saving is only possible
 * while a game is running (there is nothing to serialize before that), so
 * [applySaveLoadContext] disables the Save row and retitles the window when opened pre-game.
 *
 * Cloud save/load (GitHub gist-backed, using the original Panzer Marshal author's personal
 * access token) was removed — see GameStatePersistence.kt's class doc for why. Disk-only now.
 */
internal object GameStateMenuBuilder {
    fun buildGameStateMenu() {
        val root = byId("smState") ?: return

        val header = addTag(root, "div")
        header.id = "smStateHeader"
        header.className = "osadaScreenHeader osada-sl-header"
        header.textContent = I18n.t("save_load.title")

        val body = addTag(root, "div")
        body.className = "osada-sl-body"

        val saveBut = addTag(body, "div")
        saveBut.id = "disksave"
        saveBut.className = "osada-sl-btn"
        saveBut.title = I18n.t("save_load.save.help")
        saveBut.innerHTML =
            "<span class='osada-sl-btn__label'>${I18n.t("save_load.save.label")}</span>" +
            "<span class='osada-sl-btn__sub'>${I18n.t("save_load.save.subtitle")}</span>" +
            "<a id='savedata' hidden download='none'></a>"
        saveBut.onclick = { _: org.w3c.dom.events.MouseEvent ->
            if (!saveBut.classList.contains("osada-sl-btn--disabled")) gameStateButton("disksave")
        }

        // The invisible file input stretches over the whole row (CSS) — clicking anywhere on
        // the button opens the file picker, and the label text is no longer interrupted by it.
        val loadBut = addTag(body, "div")
        loadBut.id = "diskload"
        loadBut.className = "osada-sl-btn"
        loadBut.title = I18n.t("save_load.load.help")
        loadBut.innerHTML =
            "<span class='osada-sl-btn__label'>${I18n.t("save_load.load.label")}</span>" +
            "<span class='osada-sl-btn__sub'>${I18n.t("save_load.load.subtitle")}</span>" +
            OSGlue.diskloadInputHTML
        OSGlue.diskloadEvent(loadBut) { gameStateButton("diskload") }

        val info = addTag(body, "div")
        info.className = "osada-sl-info"
        info.innerHTML =
            "${I18n.t("save_load.last_saved.label")} <span id='disksaveupdate'>${I18n.t("common.none")}</span>"

        buildProfileBackupRow(body)

        val error = addTag(body, "div")
        error.id = "diskloaderror"
        error.className = "osada-sl-error"

        val footer = addTag(root, "div")
        footer.className = "osada-sl-footer"
        byId("smStOkBut")?.let { footer.appendChild(it) }

        byId("smStOkBut")?.apply {
            title = I18n.t("save_load.close.help")
            setAttribute("data-label", I18n.t("common.close.label"))
        }
        byId("smStOkBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            makeHidden("smState")
            makeVisible("smMain")
            byId("diskloaderror")?.textContent = ""
        }
    }

    /** Export/import EVERY campaign run in the browser repository as one file (design doc sec 6's
     *  "combined profile backup"), distinct from the single-campaign disk save/load above it. */
    private fun buildProfileBackupRow(body: HTMLElement) {
        val exportBut = addTag(body, "div")
        exportBut.id = "profileBackupExport"
        exportBut.className = "osada-sl-btn"
        exportBut.title = I18n.t("save_load.profile_export.help")
        exportBut.innerHTML =
            "<span class='osada-sl-btn__label'>${I18n.t("save_load.profile_export.label")}</span>" +
            "<span class='osada-sl-btn__sub'>${I18n.t("save_load.profile_export.subtitle")}</span>"
        exportBut.onclick = { _: org.w3c.dom.events.MouseEvent -> ProfileBackup.exportToFile() }

        val importBut = addTag(body, "div")
        importBut.id = "profileBackupImport"
        importBut.className = "osada-sl-btn"
        importBut.title = I18n.t("save_load.profile_import.help")
        importBut.innerHTML =
            "<span class='osada-sl-btn__label'>${I18n.t("save_load.profile_import.label")}</span>" +
            "<span class='osada-sl-btn__sub'>${I18n.t("save_load.profile_import.subtitle")}</span>" +
            "<input id='profileBackupImportFile' type='file' accept='.json'/>"
        byId("profileBackupImportFile")?.addEventListener(
            "change",
            { _ ->
                val files = js("document.getElementById('profileBackupImportFile').files")
                if (files.length > 0) {
                    ProfileBackup.importFromFile(
                        files[0],
                        onSuccess = { count ->
                            byId("diskloaderror")?.textContent =
                                I18n.t("save_load.profile_import.success", mapOf("count" to count))
                        },
                        onError = { byId("diskloaderror")?.textContent = I18n.t("save_load.error.invalid") },
                    )
                    js("document.getElementById('profileBackupImportFile').value = ''")
                }
            },
        )
    }

    /** Called every time the screen is opened (StartMenuButtonHandler "saveload"): pre-game there is
     *  nothing to save, so the window presents itself as "Load Game" with the Save row muted;
     *  mid-game (pause menu) it is the full "Save / Load". */
    fun applySaveLoadContext() {
        val inGame = GameHolder.instance?.gameStarted == true
        byId("smStateHeader")?.textContent =
            I18n.t(if (inGame) "save_load.title" else "save_load.load_game.title")
        val saveBut = byId("disksave") ?: return
        saveBut.classList.toggle("osada-sl-btn--disabled", !inGame)
        (saveBut.query(".osada-sl-btn__sub") as? HTMLElement)?.textContent =
            I18n.t(if (inGame) "save_load.save.subtitle" else "save_load.save.unavailable")
    }

    /** Refreshes the already-built panel after an in-session language switch. */
    fun refreshLocalization() {
        byId("disksave")?.apply {
            title = I18n.t("save_load.save.help")
            querySelector(".osada-sl-btn__label")?.textContent = I18n.t("save_load.save.label")
        }
        byId("diskload")?.apply {
            title = I18n.t("save_load.load.help")
            querySelector(".osada-sl-btn__label")?.textContent = I18n.t("save_load.load.label")
            querySelector(".osada-sl-btn__sub")?.textContent = I18n.t("save_load.load.subtitle")
        }
        byId("smStOkBut")?.apply {
            title = I18n.t("save_load.close.help")
            setAttribute("data-label", I18n.t("common.close.label"))
        }
        byId("disksaveupdate")?.takeIf { it.textContent == "none" || it.textContent == "нет" }?.textContent =
            I18n.t("common.none")
        // Built once (buildProfileBackupRow) with their labels baked into innerHTML, so a later
        // language switch left them stuck in whatever language the panel first opened in -- the
        // whole-profile Export/Import row was the one pair on this screen refreshLocalization never
        // touched (2026-08-19 user report: "Export/Import" stayed Russian under English).
        byId("profileBackupExport")?.apply {
            title = I18n.t("save_load.profile_export.help")
            querySelector(".osada-sl-btn__label")?.textContent = I18n.t("save_load.profile_export.label")
            querySelector(".osada-sl-btn__sub")?.textContent = I18n.t("save_load.profile_export.subtitle")
        }
        byId("profileBackupImport")?.apply {
            title = I18n.t("save_load.profile_import.help")
            querySelector(".osada-sl-btn__label")?.textContent = I18n.t("save_load.profile_import.label")
            querySelector(".osada-sl-btn__sub")?.textContent = I18n.t("save_load.profile_import.subtitle")
        }
        applySaveLoadContext()
    }

    private fun onGameLoadSuccess() {
        makeHidden("smState")
        makeHidden("smMain")
        gameRef()?.ui?.startMenuButton("continuegame")
    }

    private fun onGameLoadError() {
        byId("diskloaderror")?.textContent = I18n.t("save_load.error.invalid")
    }

    fun gameStateButton(id: String) {
        val game = gameRef()
        when (id) {
            "disksave" -> {
                // Guard: pre-game there is no scenario to serialize (the button is disabled in
                // that context, but keep the invariant here too).
                if (GameHolder.instance?.gameStarted != true) return
                val now = Date()
                val fileName =
                    I18n.t(
                        "save_load.filename",
                        mapOf(
                            "mode" to
                                I18n.t(
                                    if (game?.campaign != null) {
                                        "save_load.filename.campaign"
                                    } else {
                                        "save_load.filename.scenario"
                                    },
                                ),
                            "name" to (game?.scenario?.name ?: ""),
                            "turn" to (game?.scenario?.map?.turn ?: 0),
                            "date" to now.toDateString(),
                            "time" to now.toLocaleTimeString(),
                        ),
                    ) + ".json"
                OSGlue.disksave(fileName)
            }

            "diskload" -> OSGlue.diskload(::onGameLoadSuccess, ::onGameLoadError)
        }
    }
}
