@file:Suppress("UnusedParameter")

package org.osada.multiplayer.ui

import kotlinx.browser.document
import org.osada.multiplayer.model.MultiplayerEndpointConfig
import org.w3c.dom.HTMLElement

class MultiplayerHubBuilder {
    private var root: HTMLElement? = null

    fun build(
        root: HTMLElement,
        endpoint: MultiplayerEndpointConfig,
    ) {
        this.root = root
        root.clearHubChildren()
        root.className = "osada-multiplayer-hub"
        root.appendChild(heading("Multiplayer"))
        root.appendChild(paragraph("Real-time multiplayer. Both players must use the same game version."))
        root.appendChild(card("Create Room", "Host a new match.", "create"))
        root.appendChild(card("Join Room", "Enter a room code or use an invite link.", "join"))
        root.appendChild(card("Reconnect", "Resume the latest online match.", "reconnect"))
        root.appendChild(paragraph("Backend: ${endpoint.environment}"))
        val diagnostics = button("Connection diagnostics")
        diagnostics.onclick = { showDiagnostics() }
        root.appendChild(diagnostics)
        showCreateRoom()
    }

    fun showCreateRoom() {
        selectCard("create")
    }

    fun showJoinRoom() {
        selectCard("join")
    }

    fun showReconnect() {
        selectCard("reconnect")
    }

    fun showDiagnostics() {
        root?.setAttribute("data-view", "diagnostics")
    }

    private fun selectCard(role: String) {
        root?.setAttribute("data-view", role)
    }

    private fun card(
        title: String,
        description: String,
        role: String,
    ): HTMLElement {
        val card = document.createElement("button") as HTMLElement
        card.className = "osada-multiplayer-card"
        card.setAttribute("data-role", role)
        card.appendChild(heading(title))
        card.appendChild(paragraph(description))
        card.onclick = { selectCard(role) }
        return card
    }

    private fun heading(text: String): HTMLElement =
        (document.createElement("h2") as HTMLElement).apply { textContent = text }

    private fun paragraph(text: String): HTMLElement =
        (document.createElement("p") as HTMLElement).apply { textContent = text }

    private fun button(text: String): HTMLElement =
        (document.createElement("button") as HTMLElement).apply {
            textContent = text
            setAttribute("type", "button")
        }
}

private fun HTMLElement.clearHubChildren() {
    while (firstChild != null) removeChild(firstChild!!)
}
