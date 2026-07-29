@file:Suppress("TooManyFunctions", "LargeClass")

package org.osada.multiplayer.local

import kotlinx.browser.document
import kotlinx.browser.sessionStorage
import org.osada.Game
import org.osada.PlayerType
import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.model.getPlayers
import org.osada.multiplayer.command.CommandValidation
import org.osada.multiplayer.command.GameCommand
import org.osada.multiplayer.command.GameCommandJson
import org.osada.multiplayer.command.HexCoordinate
import org.osada.multiplayer.command.MoveUnit
import org.osada.multiplayer.command.OsadaGameCommandApplier
import org.osada.multiplayer.command.OsadaGameCommandValidator
import org.osada.multiplayer.command.toPayloadJson
import org.osada.multiplayer.model.MatchStatus
import org.osada.multiplayer.model.MultiplayerRuntimeState
import org.osada.multiplayer.sync.CanonicalStateHasher
import org.osada.multiplayer.sync.GameStateNetworkAdapter
import org.osada.multiplayer.sync.MultiplayerSnapshot
import org.osada.multiplayer.sync.MultiplayerSnapshotFactory
import org.osada.multiplayer.sync.MultiplayerSnapshotJson
import org.osada.multiplayer.sync.MultiplayerSnapshotValidator
import org.osada.ui.makeHidden
import org.osada.ui.makeVisible
import org.osada.uiSettings
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Date
import kotlin.js.json
import kotlin.random.Random

object LocalTwoTabMultiplayer {
    private enum class Role {
        HOST,
        GUEST,
    }

    private data class Participant(
        val tabId: String,
        val displayName: String,
        var ready: Boolean,
    )

    private var game: Game? = null
    private var role: Role? = null
    private var channel: dynamic = null
    private var roomCode: String? = null
    private var hostTabId: String? = null
    private var displayName = "Commander"
    private val participants = linkedMapOf<String, Participant>()
    private var revision = 0L
    private var authorityEpoch = 0L
    private var started = false
    private var waitingForScenario = false
    private var queuedSnapshotJson: String? = null
    private var pendingCommandId: String? = null
    private val processedCommandIds = mutableSetOf<String>()
    private var validator: OsadaGameCommandValidator? = null
    private var applier: OsadaGameCommandApplier? = null

    val active: Boolean
        get() = started && role != null

    private val tabId: String by lazy {
        sessionStorage.getItem(TAB_ID_KEY)
            ?: "tab-${Date.now().toLong().toString(ID_RADIX)}-${Random.nextInt().toUInt().toString(ID_RADIX)}"
                .also { sessionStorage.setItem(TAB_ID_KEY, it) }
    }

    @Suppress("LongMethod")
    fun openHub(game: Game) {
        this.game = game
        makeHidden("smMain")
        makeVisible("startmenu")
        val root = ensureRoot()
        root.innerHTML = ""
        appendScreenHeader(
            root = root,
            eyebrow = I18n.t("multiplayer.local.eyebrow"),
            heading = I18n.t("multiplayer.local.title"),
            description = I18n.t("multiplayer.local.description"),
        )

        val body = panel("osada-mp-body")
        root.appendChild(body)

        val nameInput =
            input(
                I18n.t("multiplayer.display_name.label"),
                sessionStorage.getItem(DISPLAY_NAME_KEY)
                    ?: displayName.takeUnless { it == "Commander" }
                    ?: I18n.t("multiplayer.default_name"),
            )
        nameInput.id = "mpLocalName"
        val identity = panel("osada-mp-card osada-mp-identity")
        identity.appendChild(
            cardHeading(
                I18n.t("multiplayer.identity.title"),
                I18n.t("multiplayer.identity.help"),
            ),
        )
        identity.appendChild(field(I18n.t("multiplayer.display_name.label"), nameInput))
        body.appendChild(identity)

        val choices = panel("osada-mp-choices")
        body.appendChild(choices)

        val createCard = panel("osada-mp-card osada-mp-action-card osada-mp-action-card--host")
        createCard.appendChild(stepBadge("01", I18n.t("multiplayer.host.label")))
        createCard.appendChild(
            cardHeading(
                I18n.t("multiplayer.create.title"),
                I18n.t("multiplayer.create.help"),
            ),
        )
        val create = button(I18n.t("multiplayer.create.label"), primary = true)
        create.onclick = {
            displayName = normalizedName(nameInput.value)
            sessionStorage.setItem(DISPLAY_NAME_KEY, displayName)
            createRoom()
        }
        createCard.appendChild(create)
        choices.appendChild(createCard)

        val codeInput = input(I18n.t("multiplayer.room_code.label"), "")
        codeInput.id = "mpLocalCode"
        codeInput.maxLength = ROOM_CODE_LENGTH
        codeInput.classList.add("osada-mp-input--code")
        val joinCard = panel("osada-mp-card osada-mp-action-card osada-mp-action-card--join")
        joinCard.appendChild(stepBadge("02", I18n.t("multiplayer.join_step.label")))
        joinCard.appendChild(
            cardHeading(
                I18n.t("multiplayer.join.title"),
                I18n.t("multiplayer.join.help"),
            ),
        )
        joinCard.appendChild(field(I18n.t("multiplayer.room_code.label"), codeInput))
        val join = button(I18n.t("multiplayer.join.label"))
        join.onclick = {
            displayName = normalizedName(nameInput.value)
            sessionStorage.setItem(DISPLAY_NAME_KEY, displayName)
            joinRoom(codeInput.value)
        }
        joinCard.appendChild(join)
        choices.appendChild(joinCard)

        val footer = panel("osada-mp-footer")
        footer.appendChild(
            text(I18n.t("multiplayer.local.same_profile.help")).apply {
                className = "osada-mp-footnote"
            },
        )
        val back = button(I18n.t("multiplayer.back.label"), quiet = true)
        back.onclick = { closeToMainMenu() }
        footer.appendChild(back)
        root.appendChild(footer)
    }

    @Suppress("ReturnCount")
    fun submitMove(
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Boolean {
        if (!active || pendingCommandId != null) return false
        val position = unit.getPos() ?: return false
        val command =
            MoveUnit(
                unitId = unit.id,
                from = HexCoordinate(position.col, position.row),
                to = HexCoordinate(col, row),
                path = listOf(HexCoordinate(position.col, position.row), HexCoordinate(col, row)),
                actorPlayerId = unit.owner,
            )
        val commandId = newCommandId()
        pendingCommandId = commandId
        renderNetworkStatus(I18n.t("multiplayer.status.order_pending"))
        if (role == Role.HOST) {
            processCommand(commandId, revision, command, tabId)
        } else {
            post(
                COMMAND_PROPOSE,
                json(
                    "clientMessageId" to commandId,
                    "expectedRevision" to revision.toDouble(),
                    "command" to JSON.parse<dynamic>(command.toPayloadJson()),
                ),
            )
        }
        return true
    }

    fun onScenarioLoaded() {
        if (!waitingForScenario || !started) return
        waitingForScenario = false
        prepareGameForSharedControl()
        if (role == Role.HOST) {
            val snapshot = createSnapshot(revision)
            post(SNAPSHOT, JSON.parse<dynamic>(MultiplayerSnapshotJson.encode(snapshot)))
            renderOnlineStatus()
        } else {
            queuedSnapshotJson?.let { encoded ->
                queuedSnapshotJson = null
                applySnapshot(JSON.parse<dynamic>(encoded))
            }
        }
    }

    private fun createRoom() {
        role = Role.HOST
        hostTabId = tabId
        roomCode = generateRoomCode()
        participants.clear()
        participants[tabId] = Participant(tabId, displayName, false)
        openChannel(requireNotNull(roomCode))
        renderLobby()
    }

    private fun joinRoom(rawCode: String) {
        val code = rawCode.trim().uppercase()
        if (!ROOM_CODE.matches(code)) {
            showHubError(I18n.t("multiplayer.error.room_code"))
            return
        }
        role = Role.GUEST
        roomCode = code
        participants.clear()
        openChannel(code)
        post(JOIN, json("displayName" to displayName))
        renderLobby(I18n.t("multiplayer.status.connecting"))
    }

    @Suppress("UnusedParameter")
    private fun openChannel(code: String) {
        channel?.close()
        channel = js("new BroadcastChannel('osada-mp-' + code)")
        channel.onmessage = { event: dynamic ->
            val message = event.data
            if (message != null) handleMessage(message)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun handleMessage(message: dynamic) {
        if ((message.roomCode as? String) != roomCode || (message.senderTabId as? String) == tabId) return
        when (message.type as? String) {
            JOIN -> if (role == Role.HOST) handleJoin(message)
            LOBBY -> if (role == Role.GUEST) handleLobby(message.payload)
            READY -> if (role == Role.HOST) handleReady(message)
            START -> handleStart(message.payload)
            SNAPSHOT, COMMAND_COMMIT -> applySnapshot(message.payload)
            COMMAND_PROPOSE -> if (role == Role.HOST) handleCommandProposal(message)
            COMMAND_REJECT -> handleCommandReject(message.payload)
            LEAVE -> if (role == Role.HOST) handleLeave(message.senderTabId as? String)
        }
    }

    private fun handleJoin(message: dynamic) {
        if (started || participants.size >= MAX_PARTICIPANTS) return
        val guestId = message.senderTabId as? String ?: return
        val name = normalizedName(message.payload?.displayName as? String)
        participants[guestId] = Participant(guestId, name, false)
        broadcastLobby()
        renderLobby()
    }

    private fun handleLobby(payload: dynamic) {
        hostTabId = payload.hostTabId as? String
        participants.clear()
        val values = payload.participants
        if (js("Array.isArray(values)") as Boolean) {
            for (index in 0 until (values.length as Number).toInt()) {
                val value = values[index]
                val id = value.tabId as? String ?: continue
                participants[id] =
                    Participant(
                        tabId = id,
                        displayName = value.displayName as? String ?: I18n.t("multiplayer.default_name"),
                        ready = value.ready as? Boolean ?: false,
                    )
            }
        }
        renderLobby()
    }

    private fun handleReady(message: dynamic) {
        val participant = participants[message.senderTabId as? String] ?: return
        participant.ready = message.payload?.ready as? Boolean ?: false
        broadcastLobby()
        renderLobby()
    }

    private fun setReady(ready: Boolean) {
        participants[tabId]?.ready = ready
        if (role == Role.HOST) {
            broadcastLobby()
            renderLobby()
        } else {
            post(READY, json("ready" to ready))
        }
    }

    private fun startMatch() {
        if (role != Role.HOST || participants.size != MAX_PARTICIPANTS || participants.values.any { !it.ready }) return
        started = true
        revision = 0
        authorityEpoch = 0
        val payload = json("scenarioFile" to SMOKE_SCENARIO, "hostTabId" to tabId)
        post(START, payload)
        handleStart(payload)
    }

    private fun handleStart(payload: dynamic) {
        started = true
        hostTabId = payload.hostTabId as? String ?: hostTabId
        revision = 0
        authorityEpoch = 0
        waitingForScenario = true
        hideMultiplayerScreen()
        val currentGame = game ?: return
        currentGame.campaign = null
        uiSettings.isAI.indices.forEach { uiSettings.isAI[it] = if (it == 0) 0 else 1 }
        currentGame.newScenario(payload.scenarioFile as? String ?: SMOKE_SCENARIO, null)
    }

    @Suppress("ReturnCount")
    private fun handleCommandProposal(message: dynamic) {
        val payload = message.payload ?: return
        val commandId = payload.clientMessageId as? String ?: return
        val expectedRevision = (payload.expectedRevision as? Number)?.toLong() ?: return
        val command = GameCommandJson.decode(JSON.stringify(payload.command))
        processCommand(commandId, expectedRevision, command, message.senderTabId as? String ?: "")
    }

    private fun processCommand(
        commandId: String,
        expectedRevision: Long,
        command: GameCommand,
        senderTabId: String,
    ) {
        if (commandId in processedCommandIds) return
        if (expectedRevision != revision) {
            rejectCommand(senderTabId, commandId, "STALE_STATE")
            return
        }
        when (val result = requireNotNull(validator).validate(command, null)) {
            CommandValidation.Accepted -> {
                requireNotNull(applier).apply(command)
                processedCommandIds += commandId
                revision++
                pendingCommandId = null
                val snapshot = createSnapshot(revision)
                post(COMMAND_COMMIT, JSON.parse<dynamic>(MultiplayerSnapshotJson.encode(snapshot)))
                game?.ui?.render?.render()
                renderOnlineStatus()
            }
            is CommandValidation.Rejected -> rejectCommand(senderTabId, commandId, result.rejection.code.name)
        }
    }

    private fun rejectCommand(
        targetTabId: String,
        commandId: String,
        code: String,
    ) {
        post(
            COMMAND_REJECT,
            json("targetTabId" to targetTabId, "clientMessageId" to commandId, "code" to code),
        )
        if (targetTabId == tabId) {
            pendingCommandId = null
            renderNetworkStatus(I18n.t("multiplayer.status.order_rejected", mapOf("code" to code)))
        }
    }

    private fun handleCommandReject(payload: dynamic) {
        if ((payload.targetTabId as? String) != tabId) return
        if ((payload.clientMessageId as? String) == pendingCommandId) pendingCommandId = null
        renderNetworkStatus(
            I18n.t(
                "multiplayer.status.order_rejected",
                mapOf("code" to (payload.code as? String ?: I18n.t("common.unknown"))),
            ),
        )
    }

    private fun createSnapshot(targetRevision: Long): MultiplayerSnapshot {
        val runtime =
            MultiplayerRuntimeState(
                status = MatchStatus.RUNNING,
                revision = targetRevision,
                authorityParticipantId = hostTabId ?: tabId,
                authorityEpoch = authorityEpoch,
                readyParticipantIds =
                    participants.values
                        .filter { it.ready }
                        .map { it.tabId }
                        .toSet(),
                sharedPrestigeAccounts = emptyMap(),
                unitAssignments = emptyMap(),
                pendingCommand = null,
            )
        val adapter = GameStateNetworkAdapter { game }
        return MultiplayerSnapshotFactory(
            adapter = adapter,
            hasher = CanonicalStateHasher(),
            gameVersion = org.osada.VERSION,
            contentManifestHash = "local:$SMOKE_SCENARIO",
            roomConfigHash = "local:${roomCode ?: ""}",
        ).create(runtime)
    }

    private fun applySnapshot(payload: dynamic) {
        if (waitingForScenario) {
            queuedSnapshotJson = JSON.stringify(payload)
            return
        }
        val snapshot = MultiplayerSnapshotJson.decode(JSON.stringify(payload))
        if (snapshot.revision < revision) return
        runCatching {
            val validation = MultiplayerSnapshotValidator().validate(snapshot)
            require(validation.valid) { validation.errors.joinToString() }
            val state = requireNotNull(game?.state) { "No active game state" }
            require(
                state.restoreFromString(snapshot.gameStateJson) {
                    prepareGameForSharedControl()
                    revision = snapshot.revision
                    pendingCommandId = null
                    renderOnlineStatus()
                },
            ) { "Network game state could not be restored" }
        }.onFailure {
            console.error("[multiplayer] snapshot apply failed: ${it.message ?: it.toString()}")
            renderNetworkStatus(I18n.t("multiplayer.status.sync_failed"))
        }
    }

    private fun prepareGameForSharedControl() {
        val currentGame = game ?: return
        val map = currentGame.scenario?.map ?: return
        map.getPlayers().forEach { player ->
            player.type = if (player.id == 0) PlayerType.HUMAN_LOCAL else PlayerType.AI_LOCAL
        }
        validator = OsadaGameCommandValidator { game }
        applier = OsadaGameCommandApplier(gameProvider = { game })
        installNetworkStatus()
    }

    private fun broadcastLobby() {
        val values =
            participants.values
                .map {
                    json("tabId" to it.tabId, "displayName" to it.displayName, "ready" to it.ready)
                }.toTypedArray()
        post(LOBBY, json("hostTabId" to tabId, "participants" to values))
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun renderLobby(message: String? = null) {
        val root = ensureRoot()
        root.innerHTML = ""
        appendScreenHeader(
            root = root,
            eyebrow = I18n.t("multiplayer.local.eyebrow"),
            heading = I18n.t("multiplayer.lobby.title"),
            description = I18n.t("multiplayer.lobby.description"),
        )

        val body = panel("osada-mp-body osada-mp-body--lobby")
        root.appendChild(body)

        val room = panel("osada-mp-room-strip")
        room.appendChild(
            text(I18n.t("multiplayer.room_code.label").uppercase()).apply {
                className = "osada-mp-room-label"
            },
        )
        room.appendChild(text(roomCode ?: "—").apply { className = "osada-mp-room-code" })
        room.appendChild(
            text(I18n.t("multiplayer.room_code.help")).apply {
                className = "osada-mp-room-help"
            },
        )
        body.appendChild(room)

        if (message != null) {
            body.appendChild(text(message).apply { className = "osada-mp-alert osada-mp-alert--info" })
        }

        val rosterCard = panel("osada-mp-card osada-mp-roster-card")
        rosterCard.appendChild(
            cardHeading(
                I18n.t("multiplayer.roster.title"),
                I18n.t(
                    "multiplayer.roster.count",
                    mapOf("count" to participants.size, "max" to MAX_PARTICIPANTS),
                ),
            ),
        )
        val roster = panel("osada-mp-roster")
        participants.values.forEachIndexed { index, participant ->
            val player = panel("osada-mp-player")
            player.appendChild(text((index + 1).toString()).apply { className = "osada-mp-player-index" })
            val identity = panel("osada-mp-player-identity")
            identity.appendChild(text(participant.displayName).apply { className = "osada-mp-player-name" })
            identity.appendChild(
                text(
                    I18n.t(
                        if (participant.tabId == hostTabId) {
                            "multiplayer.participant.host"
                        } else {
                            "multiplayer.participant.guest"
                        },
                    ),
                ).apply {
                    className = "osada-mp-player-role"
                },
            )
            player.appendChild(identity)
            val readyKey =
                if (participant.ready) {
                    "multiplayer.participant.ready"
                } else {
                    "multiplayer.participant.not_ready"
                }
            val readyLabel = I18n.t(readyKey).uppercase()
            player.appendChild(
                text(readyLabel).apply {
                    className =
                        if (participant.ready) {
                            "osada-mp-ready-state osada-mp-ready-state--ready"
                        } else {
                            "osada-mp-ready-state"
                        }
                },
            )
            roster.appendChild(player)
        }
        repeat((MAX_PARTICIPANTS - participants.size).coerceAtLeast(0)) { emptyIndex ->
            val empty = panel("osada-mp-player osada-mp-player--empty")
            empty.appendChild(
                text((participants.size + emptyIndex + 1).toString()).apply {
                    className = "osada-mp-player-index"
                },
            )
            empty.appendChild(
                text(I18n.t("multiplayer.participant.waiting")).apply {
                    className = "osada-mp-player-name"
                },
            )
            roster.appendChild(empty)
        }
        rosterCard.appendChild(roster)
        body.appendChild(rosterCard)

        val footer = panel("osada-mp-footer")
        val ownReady = participants[tabId]?.ready == true
        val ready =
            button(
                I18n.t(
                    if (ownReady) "multiplayer.ready.cancel.label" else "multiplayer.ready.label",
                ),
            )
        ready.disabled = participants[tabId] == null
        ready.onclick = { setReady(!ownReady) }
        footer.appendChild(ready)
        if (role == Role.HOST) {
            val start = button(I18n.t("multiplayer.start.label"), primary = true)
            start.disabled =
                participants.size != MAX_PARTICIPANTS ||
                participants.values.any { !it.ready }
            start.onclick = { startMatch() }
            footer.appendChild(start)
        }
        val leave = button(I18n.t("multiplayer.leave.label"), quiet = true)
        leave.onclick = { leaveRoom() }
        footer.appendChild(leave)
        root.appendChild(footer)
    }

    private fun post(
        type: String,
        payload: dynamic,
    ) {
        channel?.postMessage(
            json(
                "type" to type,
                "roomCode" to roomCode,
                "senderTabId" to tabId,
                "payload" to payload,
            ),
        )
    }

    private fun leaveRoom() {
        if (role != null) post(LEAVE, json())
        reset()
        closeToMainMenu()
    }

    private fun handleLeave(leavingTabId: String?) {
        if (leavingTabId != null) participants.remove(leavingTabId)
        broadcastLobby()
        renderLobby()
    }

    private fun reset() {
        channel?.close()
        channel = null
        role = null
        roomCode = null
        hostTabId = null
        participants.clear()
        revision = 0
        started = false
        waitingForScenario = false
        queuedSnapshotJson = null
        pendingCommandId = null
        processedCommandIds.clear()
        validator = null
        applier = null
    }

    private fun closeToMainMenu() {
        ensureRoot().style.display = "none"
        makeVisible("smMain")
        makeVisible("startmenu")
    }

    private fun hideMultiplayerScreen() {
        ensureRoot().style.display = "none"
        makeHidden("startmenu")
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

    private fun installNetworkStatus() {
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
        status.textContent = I18n.t("multiplayer.status.online", mapOf("revision" to revision))
    }

    private fun renderNetworkStatus(value: String) {
        (document.getElementById(STATUS_ID) as? HTMLDivElement)?.textContent = value
    }

    private fun renderOnlineStatus() {
        renderNetworkStatus(I18n.t("multiplayer.status.online", mapOf("revision" to revision)))
    }

    private fun showHubError(value: String) {
        val root = ensureRoot()
        root.querySelector(".osada-mp-alert--error")?.remove()
        root.querySelector(".osada-mp-body")?.appendChild(
            text(value).apply { className = "osada-mp-alert osada-mp-alert--error" },
        )
    }

    private fun text(value: String) = (document.createElement("div") as HTMLDivElement).apply { textContent = value }

    private fun panel(className: String) =
        (document.createElement("div") as HTMLDivElement).apply { this.className = className }

    private fun appendScreenHeader(
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
                    text(I18n.t("multiplayer.link.label")).apply {
                        className = "osada-mp-signal-label"
                    },
                )
            },
        )
        root.appendChild(header)
    }

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

    private fun normalizedName(value: String?): String =
        value
            ?.trim()
            ?.take(MAX_NAME_LENGTH)
            ?.takeIf { it.isNotBlank() }
            ?: I18n.t("multiplayer.default_name")

    private fun generateRoomCode(): String =
        buildString {
            repeat(ROOM_CODE_LENGTH) { append(ROOM_ALPHABET.random()) }
        }

    private fun newCommandId(): String =
        "$tabId-${Date.now().toLong().toString(ID_RADIX)}-${Random.nextInt().toUInt().toString(ID_RADIX)}"

    private const val ROOT_ID = "smMultiplayerLocal"
    private const val STATUS_ID = "multiplayerNetworkStatus"
    private const val TAB_ID_KEY = "osada-mp-tab-id-v1"
    private const val DISPLAY_NAME_KEY = "osada-mp-tab-display-name-v1"
    private const val SMOKE_SCENARIO = "bn9s00.xml"
    private const val MAX_PARTICIPANTS = 2
    private const val MAX_NAME_LENGTH = 40
    private const val ROOM_CODE_LENGTH = 6
    private const val ID_RADIX = 36
    private const val JOIN = "JOIN"
    private const val LOBBY = "LOBBY"
    private const val READY = "READY"
    private const val START = "START"
    private const val SNAPSHOT = "SNAPSHOT"
    private const val COMMAND_PROPOSE = "COMMAND_PROPOSE"
    private const val COMMAND_COMMIT = "COMMAND_COMMIT"
    private const val COMMAND_REJECT = "COMMAND_REJECT"
    private const val LEAVE = "LEAVE"
    private val ROOM_CODE = Regex("[A-Z2-9]{$ROOM_CODE_LENGTH}")
    private const val ROOM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
}
