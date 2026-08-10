@file:Suppress("TooManyFunctions")

package org.osada.multiplayer.ui

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.osada.ui.makeHidden
import org.osada.ui.makeVisible
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement

/** A seat as the lobby renders it — no transport or game types leak into the DOM layer. */
data class LobbyRow(
    val displayName: String,
    val isHost: Boolean,
    val ready: Boolean,
    val connected: Boolean,
)

class HubModel(
    val displayName: String,
    val onlineAvailable: Boolean,
    val backendLabel: String,
    val onCreate: (displayName: String) -> Unit,
    val onJoin: (roomCode: String, displayName: String) -> Unit,
    val onBack: () -> Unit,
)

/** One row of the scenario picker; [group] is the campaign the scenario belongs to. */
data class ScenarioChoice(
    val file: String,
    val name: String,
    val group: String,
)

class LobbyModel(
    val roomCode: String?,
    val message: String?,
    val rows: List<LobbyRow>,
    val maxParticipants: Int,
    val selfReady: Boolean,
    val readyEnabled: Boolean,
    val isHost: Boolean,
    val scenarios: List<ScenarioChoice>,
    val selectedScenarioFile: String?,
    val selectedScenarioName: String?,
    val startEnabled: Boolean,
    val onScenarioSelected: (String) -> Unit,
    val onToggleReady: () -> Unit,
    val onStart: () -> Unit,
    val onLeave: () -> Unit,
)

/**
 * All DOM for the multiplayer screens.
 *
 * The root keeps the id `smMultiplayerLocal` because the whole `.osada-mp-*` block in
 * `osada-theme.css` is anchored on it; the screen itself is no longer local-only.
 */
object MultiplayerScreen {
    private const val ROOT_ID = "smMultiplayerLocal"
    private const val STATUS_ID = "multiplayerNetworkStatus"
    private const val ROOM_CODE_LENGTH = 6

    fun showHub(model: HubModel) {
        makeHidden("smMain")
        makeVisible("startmenu")
        val root = ensureRoot()
        root.innerHTML = ""
        appendHeader(
            root,
            I18n.t("multiplayer.online.eyebrow"),
            I18n.t("multiplayer.title"),
            I18n.t("multiplayer.online.description"),
        )

        val body = panel("osada-mp-body")
        root.appendChild(body)

        val nameInput = input(I18n.t("multiplayer.display_name.label"), model.displayName)
        nameInput.id = "mpLocalName"
        val identity = panel("osada-mp-card osada-mp-identity")
        identity.appendChild(cardHeading(I18n.t("multiplayer.identity.title"), I18n.t("multiplayer.identity.help")))
        identity.appendChild(field(I18n.t("multiplayer.display_name.label"), nameInput))
        body.appendChild(identity)

        if (!model.onlineAvailable) body.appendChild(offlineNotice())

        val choices = panel("osada-mp-choices")
        body.appendChild(choices)
        choices.appendChild(createCard(model, nameInput))
        choices.appendChild(joinCard(model, nameInput))

        val footer = panel("osada-mp-footer")
        footer.appendChild(
            text(I18n.t("multiplayer.online.same_build.help")).apply { className = "osada-mp-footnote" },
        )
        val back = button(I18n.t("multiplayer.back.label"), quiet = true)
        back.onclick = { model.onBack() }
        footer.appendChild(back)
        root.appendChild(footer)
    }

    fun showLobby(model: LobbyModel) {
        val root = ensureRoot()
        root.innerHTML = ""
        appendHeader(
            root,
            I18n.t("multiplayer.online.eyebrow"),
            I18n.t("multiplayer.lobby.title"),
            I18n.t("multiplayer.lobby.description"),
        )

        val body = panel("osada-mp-body osada-mp-body--lobby")
        root.appendChild(body)
        body.appendChild(roomStrip(model))
        model.message?.let {
            body.appendChild(text(it).apply { className = "osada-mp-alert osada-mp-alert--info" })
        }
        body.appendChild(scenarioCard(model))
        body.appendChild(rosterCard(model))
        root.appendChild(lobbyFooter(model))
    }

    fun showError(value: String) {
        val root = ensureRoot()
        root.querySelector(".osada-mp-alert--error")?.remove()
        root.querySelector(".osada-mp-body")?.appendChild(
            text(value).apply { className = "osada-mp-alert osada-mp-alert--error" },
        )
    }

    fun hide() {
        ensureRoot().style.display = "none"
        makeHidden("startmenu")
    }

    fun closeToMainMenu() {
        ensureRoot().style.display = "none"
        makeVisible("smMain")
        makeVisible("startmenu")
    }

    fun installStatusBadge(text: String) {
        val existing = document.getElementById(STATUS_ID) as? HTMLDivElement
        val status =
            existing ?: (document.createElement("div") as HTMLDivElement).also {
                it.id = STATUS_ID
                it.style.position = "fixed"
                it.style.top = "6px"
                it.style.right = "12px"
                it.style.zIndex = "200"
                it.style.padding = "5px 10px"
                it.style.background = "rgba(0,0,0,.78)"
                it.style.color = "#9fe3a9"
                document.body?.appendChild(it)
            }
        status.textContent = text
    }

    fun setStatus(value: String) {
        (document.getElementById(STATUS_ID) as? HTMLDivElement)?.textContent = value
    }

    /** Shown when the page was not served over HTTP, where no socket can be opened at all. */
    private fun offlineNotice(): HTMLDivElement =
        panel("osada-mp-alert osada-mp-alert--error").apply {
            textContent = I18n.t("multiplayer.online.unavailable")
        }

    private fun createCard(
        model: HubModel,
        nameInput: HTMLInputElement,
    ): HTMLDivElement {
        val card = panel("osada-mp-card osada-mp-action-card osada-mp-action-card--host")
        card.appendChild(stepBadge("01", I18n.t("multiplayer.host.label")))
        card.appendChild(cardHeading(I18n.t("multiplayer.create.title"), I18n.t("multiplayer.create.help")))
        val create = button(I18n.t("multiplayer.create.label"), primary = true)
        create.disabled = !model.onlineAvailable
        create.onclick = { model.onCreate(nameInput.value) }
        card.appendChild(create)
        return card
    }

    private fun joinCard(
        model: HubModel,
        nameInput: HTMLInputElement,
    ): HTMLDivElement {
        val codeInput = input(I18n.t("multiplayer.room_code.label"), "")
        codeInput.id = "mpLocalCode"
        codeInput.maxLength = ROOM_CODE_LENGTH
        codeInput.classList.add("osada-mp-input--code")
        val card = panel("osada-mp-card osada-mp-action-card osada-mp-action-card--join")
        card.appendChild(stepBadge("02", I18n.t("multiplayer.join_step.label")))
        card.appendChild(cardHeading(I18n.t("multiplayer.join.title"), I18n.t("multiplayer.join.help")))
        card.appendChild(field(I18n.t("multiplayer.room_code.label"), codeInput))
        val join = button(I18n.t("multiplayer.join.label"))
        join.disabled = !model.onlineAvailable
        join.onclick = { model.onJoin(codeInput.value, nameInput.value) }
        card.appendChild(join)
        return card
    }

    private fun roomStrip(model: LobbyModel): HTMLDivElement {
        val room = panel("osada-mp-room-strip")
        room.appendChild(
            text(I18n.t("multiplayer.room_code.label").uppercase()).apply { className = "osada-mp-room-label" },
        )
        room.appendChild(text(model.roomCode ?: "—").apply { className = "osada-mp-room-code" })
        room.appendChild(
            text(I18n.t("multiplayer.online.room_code.help")).apply { className = "osada-mp-room-help" },
        )
        return room
    }

    /**
     * Which battle the room will play. Only the host can change it; the guest sees the same line
     * read-only, because the server clears everyone's readiness whenever the choice changes.
     */
    private fun scenarioCard(model: LobbyModel): HTMLDivElement {
        val card = panel("osada-mp-card osada-mp-scenario-card")
        card.appendChild(
            cardHeading(
                I18n.t("multiplayer.scenario.title"),
                if (model.isHost) {
                    I18n.t("multiplayer.scenario.help.host")
                } else {
                    I18n.t("multiplayer.scenario.help.guest")
                },
            ),
        )
        if (model.isHost) {
            card.appendChild(scenarioSelect(model))
        } else {
            card.appendChild(
                text(model.selectedScenarioName ?: I18n.t("multiplayer.scenario.waiting"))
                    .apply { className = "osada-mp-scenario-name" },
            )
        }
        return card
    }

    private fun scenarioSelect(model: LobbyModel): HTMLElement {
        val select = document.createElement("select") as HTMLSelectElement
        select.className = "osada-mp-input osada-mp-scenario-select"
        if (model.selectedScenarioFile == null) {
            val placeholder = document.createElement("option") as HTMLOptionElement
            placeholder.value = ""
            placeholder.textContent = I18n.t("multiplayer.scenario.choose")
            select.appendChild(placeholder)
        }
        // Campaigns become <optgroup>s, which is exactly the folded-by-campaign reading the
        // scenario register gives — for free, and with the browser's own keyboard search.
        var group: HTMLElement? = null
        var groupName = ""
        model.scenarios.forEach { choice ->
            if (choice.group != groupName || group == null) {
                groupName = choice.group
                group =
                    (document.createElement("optgroup") as HTMLElement).also {
                        it.setAttribute("label", groupName)
                        select.appendChild(it)
                    }
            }
            val option = document.createElement("option") as HTMLOptionElement
            option.value = choice.file
            option.textContent = choice.name
            option.selected = choice.file == model.selectedScenarioFile
            group?.appendChild(option)
        }
        select.onchange = {
            val value = select.value
            if (value.isNotEmpty()) model.onScenarioSelected(value)
        }
        return select
    }

    private fun rosterCard(model: LobbyModel): HTMLDivElement {
        val card = panel("osada-mp-card osada-mp-roster-card")
        card.appendChild(
            cardHeading(
                I18n.t("multiplayer.roster.title"),
                I18n.t(
                    "multiplayer.roster.count",
                    mapOf("count" to model.rows.size, "max" to model.maxParticipants),
                ),
            ),
        )
        val roster = panel("osada-mp-roster")
        model.rows.forEachIndexed { index, row -> roster.appendChild(rosterRow(index, row)) }
        repeat((model.maxParticipants - model.rows.size).coerceAtLeast(0)) { offset ->
            val empty = panel("osada-mp-player osada-mp-player--empty")
            empty.appendChild(
                text((model.rows.size + offset + 1).toString()).apply { className = "osada-mp-player-index" },
            )
            empty.appendChild(
                text(I18n.t("multiplayer.participant.waiting")).apply { className = "osada-mp-player-name" },
            )
            roster.appendChild(empty)
        }
        card.appendChild(roster)
        return card
    }

    private fun rosterRow(
        index: Int,
        row: LobbyRow,
    ): HTMLDivElement {
        val player = panel("osada-mp-player")
        player.appendChild(text((index + 1).toString()).apply { className = "osada-mp-player-index" })
        val identity = panel("osada-mp-player-identity")
        identity.appendChild(text(row.displayName).apply { className = "osada-mp-player-name" })
        identity.appendChild(
            text(
                I18n.t(
                    if (row.isHost) "multiplayer.participant.host" else "multiplayer.participant.guest",
                ),
            ).apply { className = "osada-mp-player-role" },
        )
        player.appendChild(identity)
        val stateKey =
            when {
                !row.connected -> "multiplayer.connection.reconnecting"
                row.ready -> "multiplayer.participant.ready"
                else -> "multiplayer.participant.not_ready"
            }
        player.appendChild(
            text(I18n.t(stateKey).uppercase()).apply {
                className =
                    if (row.ready && row.connected) {
                        "osada-mp-ready-state osada-mp-ready-state--ready"
                    } else {
                        "osada-mp-ready-state"
                    }
            },
        )
        return player
    }

    private fun lobbyFooter(model: LobbyModel): HTMLDivElement {
        val footer = panel("osada-mp-footer")
        val ready =
            button(
                I18n.t(if (model.selfReady) "multiplayer.ready.cancel.label" else "multiplayer.ready.label"),
            )
        ready.disabled = !model.readyEnabled
        ready.onclick = { model.onToggleReady() }
        footer.appendChild(ready)
        if (model.isHost) {
            val start = button(I18n.t("multiplayer.start.label"), primary = true)
            start.disabled = !model.startEnabled
            start.onclick = { model.onStart() }
            footer.appendChild(start)
        }
        val leave = button(I18n.t("multiplayer.leave.label"), quiet = true)
        leave.onclick = { model.onLeave() }
        footer.appendChild(leave)
        return footer
    }

    private fun ensureRoot(): HTMLDivElement {
        val existing = document.getElementById(ROOT_ID) as? HTMLDivElement
        if (existing != null) {
            existing.className = "mainPanel osada-mp-screen"
            existing.removeAttribute("style")
            existing.style.display = "flex"
            return existing
        }
        val root = document.createElement("div") as HTMLDivElement
        root.id = ROOT_ID
        root.className = "mainPanel osada-mp-screen"
        document.getElementById("startmenu")?.appendChild(root)
        return root
    }

    private fun appendHeader(
        root: HTMLDivElement,
        eyebrow: String,
        heading: String,
        description: String,
    ) {
        val header = panel("osada-mp-header")
        val copy = panel("osada-mp-header-copy")
        copy.appendChild(text(eyebrow).apply { className = "osada-mp-eyebrow" })
        copy.appendChild(
            document.createElement("h2").apply {
                className = "osada-mp-title"
                textContent = heading
            },
        )
        copy.appendChild(text(description).apply { className = "osada-mp-subtitle" })
        header.appendChild(copy)
        header.appendChild(
            panel("osada-mp-signal").apply {
                appendChild(panel("osada-mp-signal-ring"))
                appendChild(
                    text(I18n.t("multiplayer.link.label")).apply { className = "osada-mp-signal-label" },
                )
            },
        )
        root.appendChild(header)
    }

    private fun text(value: String) = (document.createElement("div") as HTMLDivElement).apply { textContent = value }

    private fun panel(className: String) =
        (document.createElement("div") as HTMLDivElement).apply { this.className = className }

    private fun cardHeading(
        heading: String,
        description: String,
    ): HTMLDivElement =
        panel("osada-mp-card-heading").apply {
            appendChild(text(heading).apply { className = "osada-mp-card-title" })
            appendChild(text(description).apply { className = "osada-mp-card-copy" })
        }

    private fun stepBadge(
        number: String,
        label: String,
    ): HTMLDivElement =
        panel("osada-mp-step").apply {
            appendChild(text(number).apply { className = "osada-mp-step-number" })
            appendChild(text(label).apply { className = "osada-mp-step-label" })
        }

    private fun field(
        label: String,
        control: HTMLInputElement,
    ): HTMLDivElement =
        panel("osada-mp-field").apply {
            appendChild(text(label).apply { className = "osada-mp-field-label" })
            appendChild(control)
        }

    private fun button(
        value: String,
        primary: Boolean = false,
        quiet: Boolean = false,
    ) = (document.createElement("button") as HTMLButtonElement).apply {
        type = "button"
        textContent = value
        className =
            when {
                primary -> "osada-mp-button osada-mp-button--primary"
                quiet -> "osada-mp-button osada-mp-button--quiet"
                else -> "osada-mp-button"
            }
    }

    private fun input(
        placeholder: String,
        value: String,
    ) = (document.createElement("input") as HTMLInputElement).apply {
        this.placeholder = placeholder
        this.value = value
        type = "text"
        className = "osada-mp-input"
    }
}
