@file:Suppress("UnusedParameter")

package org.osada.multiplayer.ui

import kotlinx.browser.document
import org.osada.multiplayer.model.MultiplayerParticipant
import org.osada.multiplayer.model.MultiplayerRoomConfig
import org.osada.multiplayer.room.LobbyValidationResult
import org.w3c.dom.HTMLElement

class MultiplayerLobbyBuilder {
    private var root: HTMLElement? = null

    fun build(
        root: HTMLElement,
        config: MultiplayerRoomConfig,
    ) {
        this.root = root
        root.clearLobbyChildren()
        root.className = "osada-multiplayer-lobby"
        root.appendChild(element("h2", "Multiplayer Lobby"))
        root.appendChild(element("div", config.contentRef.contentId, "mp-content"))
        root.appendChild(element("div", config.mode.name.replace('_', ' '), "mp-mode"))
        root.appendChild(element("div", "", "mp-room-code"))
        root.appendChild(element("div", "", "mp-participants"))
        root.appendChild(element("div", "", "mp-validation"))
    }

    fun renderParticipants(participants: List<MultiplayerParticipant>) {
        val container = child("mp-participants") ?: return
        container.clearLobbyChildren()
        participants.forEach { participant ->
            val status = if (participant.isReady) "Ready" else participant.connectionState.name.lowercase()
            val host = if (participant.isHost) " · Host" else ""
            container.appendChild(element("article", "${participant.displayName}$host · $status", "mp-participant"))
        }
    }

    fun renderValidation(result: LobbyValidationResult) {
        child("mp-validation")?.textContent =
            if (result.valid) "Ready to start" else result.errors.joinToString { it.name.replace('_', ' ') }
    }

    fun renderRoomCode(roomCode: String) {
        child("mp-room-code")?.textContent = "Room $roomCode"
    }

    fun setReadyState(
        participantId: String,
        ready: Boolean,
    ) {
        val participant =
            child("mp-participants")
                ?.querySelector("[data-participant-id=${JSON.stringify(participantId)}]") as? HTMLElement
        participant?.setAttribute("data-ready", ready.toString())
    }

    private fun child(className: String): HTMLElement? = root?.querySelector(".$className") as? HTMLElement
}

class MultiplayerAssignmentBoard {
    private var root: HTMLElement? = null

    fun build(
        root: HTMLElement,
        config: MultiplayerRoomConfig,
    ) {
        this.root = root
        root.clearLobbyChildren()
        root.className = "osada-multiplayer-assignment-board"
        config.seats.groupBy { it.sideId }.entries.sortedBy { it.key }.forEach { (side, seats) ->
            root.appendChild(element("h3", "Side $side"))
            seats.forEach { seat ->
                val controllers = seat.controlledPlayerIds.sorted().joinToString()
                val owner = seat.participantId ?: seat.role.name
                val label = "Players $controllers → $owner"
                root.appendChild(
                    element("div", label, "mp-assignment").apply {
                        setAttribute("data-seat-id", seat.seatId)
                    },
                )
            }
        }
    }

    fun assignParticipant(
        seatId: String,
        participantId: String?,
    ) {
        seat(seatId)?.setAttribute("data-participant-id", participantId ?: "")
    }

    fun assignAi(seatId: String) {
        seat(seatId)?.setAttribute("data-participant-id", "AI")
    }

    fun assignSharedControl(
        seatId: String,
        sharedControlGroupId: String,
    ) {
        seat(seatId)?.setAttribute("data-shared-control-group-id", sharedControlGroupId)
    }

    private fun seat(seatId: String): HTMLElement? =
        root?.querySelector("[data-seat-id=${JSON.stringify(seatId)}]") as? HTMLElement
}

private fun element(
    tag: String,
    text: String,
    className: String? = null,
): HTMLElement =
    (document.createElement(tag) as HTMLElement).apply {
        textContent = text
        if (className != null) this.className = className
    }

private fun HTMLElement.clearLobbyChildren() {
    while (firstChild != null) removeChild(firstChild!!)
}
