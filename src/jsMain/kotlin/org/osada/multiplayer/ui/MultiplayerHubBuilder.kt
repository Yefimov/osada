@file:Suppress("UnusedParameter")

package org.osada.multiplayer.ui

import kotlinx.browser.document
import org.osada.i18n.I18n
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
        root.appendChild(heading(I18n.t("multiplayer.title")))
        root.appendChild(paragraph(I18n.t("multiplayer.online.description")))
        root.appendChild(
            card(I18n.t("multiplayer.create.label"), I18n.t("multiplayer.online.create.help"), "create"),
        )
        root.appendChild(
            card(I18n.t("multiplayer.join.label"), I18n.t("multiplayer.online.join.help"), "join"),
        )
        root.appendChild(
            card(
                I18n.t("multiplayer.reconnect.label"),
                I18n.t("multiplayer.reconnect.help"),
                "reconnect",
            ),
        )
        root.appendChild(
            paragraph(I18n.t("multiplayer.backend", mapOf("environment" to endpoint.environment))),
        )
        val diagnostics = button(I18n.t("multiplayer.diagnostics.label"))
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
