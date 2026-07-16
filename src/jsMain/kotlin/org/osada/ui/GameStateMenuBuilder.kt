package org.osada.ui

import org.osada.*
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
        header.textContent = "Save / Load"

        val body = addTag(root, "div")
        body.className = "osada-sl-body"

        val saveBut = addTag(body, "div")
        saveBut.id = "disksave"
        saveBut.className = "osada-sl-btn"
        saveBut.innerHTML =
            "<span class='osada-sl-btn__label'>Save to Disk</span>" +
            "<span class='osada-sl-btn__sub'>Download the current battle as a file</span>" +
            "<a id='savedata' hidden download='none'></a>"
        saveBut.onclick = { _: org.w3c.dom.events.MouseEvent ->
            if (!saveBut.classList.contains("osada-sl-btn--disabled")) gameStateButton("disksave")
        }

        // The invisible file input stretches over the whole row (CSS) — clicking anywhere on
        // the button opens the file picker, and the label text is no longer interrupted by it.
        val loadBut = addTag(body, "div")
        loadBut.id = "diskload"
        loadBut.className = "osada-sl-btn"
        loadBut.innerHTML =
            "<span class='osada-sl-btn__label'>Load from Disk</span>" +
            "<span class='osada-sl-btn__sub'>Restore a battle from a save file</span>" +
            OSGlue.diskloadInputHTML
        OSGlue.diskloadEvent(loadBut) { gameStateButton("diskload") }

        val info = addTag(body, "div")
        info.className = "osada-sl-info"
        info.innerHTML = "Last saved to disk: <span id='disksaveupdate'>none</span>"

        val error = addTag(body, "div")
        error.id = "diskloaderror"
        error.className = "osada-sl-error"

        val footer = addTag(root, "div")
        footer.className = "osada-sl-footer"
        byId("smStOkBut")?.let { footer.appendChild(it) }

        byId("smStOkBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            makeHidden("smState")
            makeVisible("smMain")
            byId("diskloaderror")?.textContent = ""
        }
    }

    /** Called every time the screen is opened (MenuController "saveload"): pre-game there is
     *  nothing to save, so the window presents itself as "Load Game" with the Save row muted;
     *  mid-game (pause menu) it is the full "Save / Load". */
    fun applySaveLoadContext() {
        val inGame = GameHolder.instance?.gameStarted == true
        byId("smStateHeader")?.textContent = if (inGame) "Save / Load" else "Load Game"
        val saveBut = byId("disksave") ?: return
        saveBut.classList.toggle("osada-sl-btn--disabled", !inGame)
        (saveBut.query(".osada-sl-btn__sub") as? HTMLElement)?.textContent =
            if (inGame) "Download the current battle as a file"
            else "Available during a battle — start or load a game first"
    }

    private fun onGameLoadSuccess() {
        makeHidden("smState")
        makeHidden("smMain")
        gameRef()?.ui?.startMenuButton("continuegame")
    }

    private fun onGameLoadError() {
        byId("diskloaderror")?.textContent = "Error loading game. Cannot parse save game data."
    }

    fun gameStateButton(id: String) {
        val game = gameRef()
        when (id) {
            "disksave" -> {
                // Guard: pre-game there is no scenario to serialize (the button is disabled in
                // that context, but keep the invariant here too).
                if (GameHolder.instance?.gameStarted != true) return
                val now = Date()
                val prefix = if (game?.campaign != null) "(Campaign) " else "(Scenario) "
                val fileName = "$prefix${game?.scenario?.name} Turn ${game?.scenario?.map?.turn} ${now.toDateString()} ${now.toLocaleTimeString()}.json"
                OSGlue.disksave(fileName)
            }
            "diskload" -> OSGlue.diskload(::onGameLoadSuccess, ::onGameLoadError)
        }
    }
}
