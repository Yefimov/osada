@file:Suppress("UnusedParameter")

package org.osada.multiplayer.ui

import kotlinx.browser.document
import org.osada.multiplayer.model.MultiplayerRuntimeState
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

class MultiplayerPanelController {
    private var root: HTMLElement? = null

    fun open(root: HTMLElement) {
        this.root = root
        root.style.display = "block"
        if (root.childElementCount == 0) {
            root.appendChild((document.createElement("h2") as HTMLElement).apply { textContent = "Multiplayer" })
            root.appendChild((document.createElement("div") as HTMLElement).apply { className = "mp-panel-state" })
        }
    }

    fun close() {
        root?.style?.display = "none"
    }

    fun render(state: MultiplayerRuntimeState) {
        val summary = root?.querySelector(".mp-panel-state") as? HTMLElement ?: return
        summary.textContent =
            "${state.status.name} · revision ${state.revision} · authority ${state.authorityParticipantId}"
        root?.setAttribute("data-status", state.status.name.lowercase())
    }

    fun requestResync() = dispatch("osada-mp-resync")

    fun leaveMatch() = dispatch("osada-mp-leave")

    fun pauseMatch() = dispatch("osada-mp-pause")

    fun transferHost(participantId: String) {
        root?.setAttribute("data-transfer-participant-id", participantId)
        dispatch("osada-mp-transfer-host")
    }

    fun saveRecoverySnapshot() = dispatch("osada-mp-save-recovery")

    private fun dispatch(name: String) {
        root?.dispatchEvent(Event(name))
    }
}
