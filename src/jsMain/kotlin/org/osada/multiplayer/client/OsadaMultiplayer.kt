@file:Suppress("TooManyFunctions", "LargeClass")

package org.osada.multiplayer.client

import kotlinx.browser.sessionStorage
import kotlinx.browser.window
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
import org.osada.multiplayer.model.MultiplayerEndpoint
import org.osada.multiplayer.model.MultiplayerRuntimeState
import org.osada.multiplayer.protocol.MultiplayerMessageType
import org.osada.multiplayer.sync.CanonicalStateHasher
import org.osada.multiplayer.sync.GameStateNetworkAdapter
import org.osada.multiplayer.sync.MultiplayerSnapshot
import org.osada.multiplayer.sync.MultiplayerSnapshotFactory
import org.osada.multiplayer.sync.MultiplayerSnapshotJson
import org.osada.multiplayer.sync.MultiplayerSnapshotValidator
import org.osada.multiplayer.transport.LocalRoomLink
import org.osada.multiplayer.transport.OnlineRoomLink
import org.osada.multiplayer.transport.RoomLink
import org.osada.multiplayer.transport.RoomLinkListener
import org.osada.multiplayer.transport.RoomLinkState
import org.osada.multiplayer.transport.RoomLobbyParticipant
import org.osada.multiplayer.transport.RoomWelcome
import org.osada.multiplayer.ui.HubModel
import org.osada.multiplayer.ui.LobbyModel
import org.osada.multiplayer.ui.LobbyRow
import org.osada.multiplayer.ui.MultiplayerScreen
import org.osada.uiSettings
import kotlin.js.Date
import kotlin.js.json
import kotlin.random.Random

/**
 * The multiplayer session on the client.
 *
 * Transport-agnostic: it drives either [LocalRoomLink] (two tabs, no server) or [OnlineRoomLink]
 * (the self-hosted room server). Either way the host client stays the game authority — it validates
 * a proposed command against the real rules, applies it once and publishes the resulting snapshot.
 */
object OsadaMultiplayer : RoomLinkListener {
    private data class Participant(
        val participantId: String,
        val displayName: String,
        var ready: Boolean,
        var connected: Boolean = true,
    )

    private var game: Game? = null
    private var link: RoomLink? = null
    private var isHost = false
    private var selfId: String = ""
    private var roomCode: String? = null
    private var hostParticipantId: String? = null
    private var displayName = ""
    private val participants = linkedMapOf<String, Participant>()
    private var revision = 0L
    private var authorityEpoch = 0L
    private var started = false
    private var paused = false
    private var waitingForScenario = false
    private var queuedSnapshotJson: String? = null
    private var pendingCommandId: String? = null
    private var lobbyMessage: String? = null
    private val processedCommandIds = mutableSetOf<String>()
    private var validator: OsadaGameCommandValidator? = null
    private var applier: OsadaGameCommandApplier? = null

    val active: Boolean
        get() = started && link != null

    private val tabId: String by lazy {
        sessionStorage.getItem(TAB_ID_KEY)
            ?: "tab-${Date.now().toLong().toString(ID_RADIX)}-${Random.nextInt().toUInt().toString(ID_RADIX)}"
                .also { sessionStorage.setItem(TAB_ID_KEY, it) }
    }

    fun openHub(game: Game) {
        this.game = game
        reset()
        MultiplayerScreen.showHub(
            HubModel(
                displayName = storedDisplayName(),
                onlineAvailable = MultiplayerEndpoint.isOnlineAvailable(),
                onlinePreferred = MultiplayerEndpoint.isOnlineAvailable(),
                backendLabel = MultiplayerEndpoint.resolve().environment,
                onCreate = ::createRoom,
                onJoin = ::joinRoom,
                onBack = { MultiplayerScreen.closeToMainMenu() },
            ),
        )
    }

    @Suppress("ReturnCount")
    fun submitMove(
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Boolean {
        if (!active || paused || pendingCommandId != null) return false
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
        MultiplayerScreen.setStatus(I18n.t("multiplayer.status.order_pending"))
        if (isHost) {
            processCommand(commandId, revision, command, selfId)
        } else {
            post(
                MultiplayerMessageType.COMMAND_PROPOSE,
                json(
                    "clientMessageId" to commandId,
                    "expectedRevision" to revision.toDouble(),
                    "authorityEpoch" to authorityEpoch.toDouble(),
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
        if (isHost) {
            post(MultiplayerMessageType.SNAPSHOT, JSON.parse(MultiplayerSnapshotJson.encode(createSnapshot(revision))))
            renderOnlineStatus()
        } else {
            queuedSnapshotJson?.let { encoded ->
                queuedSnapshotJson = null
                applySnapshot(JSON.parse(encoded))
            }
        }
    }

    private fun createRoom(
        rawName: String,
        online: Boolean,
    ) {
        displayName = rememberDisplayName(rawName)
        openLink(online)
        link?.createRoom(displayName)
    }

    private fun joinRoom(
        rawCode: String,
        rawName: String,
        online: Boolean,
    ) {
        val code = rawCode.trim().uppercase()
        if (!ROOM_CODE.matches(code)) {
            MultiplayerScreen.showError(I18n.t("multiplayer.error.room_code"))
            return
        }
        displayName = rememberDisplayName(rawName)
        openLink(online)
        link?.joinRoom(code, displayName, reconnectTokenFor(code))
    }

    private fun openLink(online: Boolean) {
        link?.close()
        participants.clear()
        link =
            if (online) {
                OnlineRoomLink(MultiplayerEndpoint.resolve(), this)
            } else {
                LocalRoomLink(tabId, this, ::generateRoomCode)
            }
    }

    // -- RoomLinkListener ---------------------------------------------------------------------

    override fun onLinkState(state: RoomLinkState) {
        lobbyMessage =
            when (state) {
                RoomLinkState.CONNECTING -> I18n.t("multiplayer.status.connecting")
                RoomLinkState.RECONNECTING -> I18n.t("multiplayer.connection.reconnecting")
                RoomLinkState.CLOSED -> if (started) I18n.t("multiplayer.connection.disconnected") else null
                else -> null
            }
        if (started) {
            lobbyMessage?.let(MultiplayerScreen::setStatus)
        } else if (roomCode != null) {
            renderLobby()
        }
    }

    override fun onWelcome(welcome: RoomWelcome) {
        selfId = welcome.participantId
        roomCode = welcome.roomCode
        isHost = welcome.isHost
        revision = welcome.revision
        paused = welcome.paused
        if (welcome.isHost) hostParticipantId = welcome.participantId
        welcome.reconnectToken?.let { rememberReconnectToken(welcome.roomCode, it) }
        if (link?.serverManaged != true) {
            participants[selfId] = Participant(selfId, displayName, ready = false)
        }
        if (welcome.started) {
            // Rejoining a match in progress: the server pushes its stored snapshot next.
            started = true
            waitingForScenario = true
            welcome.scenarioFile?.let(::loadScenario)
        }
        renderLobby()
    }

    override fun onLobby(
        hostParticipantId: String?,
        participants: List<RoomLobbyParticipant>,
    ) {
        this.hostParticipantId = hostParticipantId
        this.participants.clear()
        participants.forEach { participant ->
            this.participants[participant.participantId] =
                Participant(
                    participantId = participant.participantId,
                    displayName = participant.displayName,
                    ready = participant.ready,
                    connected = participant.connected,
                )
        }
        isHost = hostParticipantId != null && hostParticipantId == selfId
        if (!started) renderLobby()
    }

    @Suppress("CyclomaticComplexMethod")
    override fun onRoomMessage(
        type: String,
        senderParticipantId: String,
        payload: dynamic,
    ) {
        val peerManaged = link?.serverManaged != true
        when (type) {
            MultiplayerMessageType.JOIN_ROOM.name -> if (peerManaged && isHost) peerJoined(senderParticipantId, payload)
            MultiplayerMessageType.LOBBY_STATE.name -> if (peerManaged && !isHost) applyPeerLobby(payload)
            MultiplayerMessageType.SET_READY.name -> if (peerManaged && isHost) peerReady(senderParticipantId, payload)
            MultiplayerMessageType.LEAVE_ROOM.name -> if (peerManaged && isHost) peerLeft(senderParticipantId)
            MultiplayerMessageType.START_GAME.name -> handleStart(payload)
            MultiplayerMessageType.SNAPSHOT.name, MultiplayerMessageType.COMMAND_COMMIT.name -> applySnapshot(payload)
            MultiplayerMessageType.COMMAND_PROPOSE.name -> if (isHost) handleProposal(senderParticipantId, payload)
            MultiplayerMessageType.COMMAND_REJECT.name -> handleRejection(payload)
            MultiplayerMessageType.PAUSE_STATE.name -> handlePause(payload)
        }
    }

    override fun onRoomError(
        code: String,
        message: String,
    ) {
        val text = message.ifBlank { I18n.t("multiplayer.status.order_rejected", mapOf("code" to code)) }
        if (started) MultiplayerScreen.setStatus(text) else MultiplayerScreen.showError(text)
    }

    // -- Lobby --------------------------------------------------------------------------------

    private fun peerJoined(
        participantId: String,
        payload: dynamic,
    ) {
        if (started || participants.size >= MAX_PARTICIPANTS) return
        participants[participantId] =
            Participant(participantId, normalizedName(payload?.displayName as? String), ready = false)
        broadcastPeerLobby()
        renderLobby()
    }

    private fun peerReady(
        participantId: String,
        payload: dynamic,
    ) {
        participants[participantId]?.ready = payload?.ready as? Boolean ?: false
        broadcastPeerLobby()
        renderLobby()
    }

    private fun peerLeft(participantId: String) {
        participants.remove(participantId)
        broadcastPeerLobby()
        renderLobby()
    }

    private fun applyPeerLobby(payload: dynamic) {
        hostParticipantId = payload.hostParticipantId as? String
        participants.clear()
        val values = payload.participants
        if (js("Array.isArray(values)") as Boolean) {
            for (index in 0 until (values.length as Number).toInt()) {
                val value = values[index]
                val id = value.participantId as? String ?: continue
                participants[id] =
                    Participant(
                        participantId = id,
                        displayName = value.displayName as? String ?: I18n.t("multiplayer.default_name"),
                        ready = value.ready as? Boolean ?: false,
                    )
            }
        }
        renderLobby()
    }

    private fun broadcastPeerLobby() {
        val values =
            participants.values
                .map {
                    json("participantId" to it.participantId, "displayName" to it.displayName, "ready" to it.ready)
                }.toTypedArray()
        post(
            MultiplayerMessageType.LOBBY_STATE,
            json("hostParticipantId" to selfId, "participants" to values),
        )
    }

    private fun setReady(ready: Boolean) {
        participants[selfId]?.ready = ready
        if (link?.serverManaged == true) {
            post(MultiplayerMessageType.SET_READY, json("ready" to ready))
        } else if (isHost) {
            broadcastPeerLobby()
            renderLobby()
        } else {
            post(MultiplayerMessageType.SET_READY, json("ready" to ready))
            renderLobby()
        }
    }

    private fun startMatch() {
        if (!isHost || !everyoneReady()) return
        val payload = json("scenarioFile" to SMOKE_SCENARIO, "hostParticipantId" to selfId)
        post(MultiplayerMessageType.START_GAME, payload)
        // Over a server the START_GAME broadcast comes back to everyone, including this client.
        if (link?.serverManaged != true) handleStart(payload)
    }

    private fun everyoneReady(): Boolean =
        participants.size == MAX_PARTICIPANTS && participants.values.all { it.ready && it.connected }

    private fun leaveRoom() {
        if (link != null) post(MultiplayerMessageType.LEAVE_ROOM, json())
        reset()
        MultiplayerScreen.closeToMainMenu()
    }

    private fun renderLobby() {
        if (started) return
        MultiplayerScreen.showLobby(
            LobbyModel(
                roomCode = roomCode,
                online = link?.serverManaged == true,
                message = lobbyMessage,
                rows =
                    participants.values.map {
                        LobbyRow(
                            displayName = it.displayName,
                            isHost = it.participantId == hostParticipantId,
                            ready = it.ready,
                            connected = it.connected,
                        )
                    },
                maxParticipants = MAX_PARTICIPANTS,
                selfReady = participants[selfId]?.ready == true,
                readyEnabled = participants[selfId] != null,
                isHost = isHost,
                startEnabled = everyoneReady(),
                onToggleReady = { setReady(participants[selfId]?.ready != true) },
                onStart = ::startMatch,
                onLeave = ::leaveRoom,
            ),
        )
    }

    // -- Match --------------------------------------------------------------------------------

    private fun handleStart(payload: dynamic) {
        started = true
        hostParticipantId = payload.hostParticipantId as? String ?: hostParticipantId
        isHost = hostParticipantId == selfId
        revision = 0
        authorityEpoch = 0
        waitingForScenario = true
        MultiplayerScreen.hide()
        loadScenario(payload.scenarioFile as? String ?: SMOKE_SCENARIO)
    }

    private fun loadScenario(scenarioFile: String) {
        val currentGame = game ?: return
        currentGame.campaign = null
        uiSettings.isAI.indices.forEach { uiSettings.isAI[it] = if (it == 0) 0 else 1 }
        currentGame.newScenario(scenarioFile, null)
    }

    private fun handlePause(payload: dynamic) {
        paused = payload.paused as? Boolean ?: false
        MultiplayerScreen.setStatus(
            if (paused) {
                I18n.t("multiplayer.status.paused")
            } else {
                I18n.t("multiplayer.status.online", mapOf("revision" to revision))
            },
        )
    }

    @Suppress("ReturnCount")
    private fun handleProposal(
        senderParticipantId: String,
        payload: dynamic,
    ) {
        val commandId = payload.clientMessageId as? String ?: return
        val expectedRevision = (payload.expectedRevision as? Number)?.toLong() ?: return
        val command = GameCommandJson.decode(JSON.stringify(payload.command))
        processCommand(commandId, expectedRevision, command, senderParticipantId)
    }

    private fun processCommand(
        commandId: String,
        expectedRevision: Long,
        command: GameCommand,
        senderParticipantId: String,
    ) {
        if (commandId in processedCommandIds) return
        if (expectedRevision != revision) {
            rejectCommand(senderParticipantId, commandId, "STALE_STATE")
            return
        }
        when (val result = requireNotNull(validator).validate(command, null)) {
            CommandValidation.Accepted -> {
                requireNotNull(applier).apply(command)
                processedCommandIds += commandId
                revision++
                pendingCommandId = null
                post(
                    MultiplayerMessageType.COMMAND_COMMIT,
                    JSON.parse(MultiplayerSnapshotJson.encode(createSnapshot(revision))),
                )
                game?.ui?.render?.render()
                renderOnlineStatus()
            }

            is CommandValidation.Rejected ->
                rejectCommand(senderParticipantId, commandId, result.rejection.code.name)
        }
    }

    private fun rejectCommand(
        targetParticipantId: String,
        commandId: String,
        code: String,
    ) {
        post(
            MultiplayerMessageType.COMMAND_REJECT,
            json(
                "targetParticipantId" to targetParticipantId,
                "clientMessageId" to commandId,
                "code" to code,
            ),
        )
        if (targetParticipantId == selfId) {
            pendingCommandId = null
            MultiplayerScreen.setStatus(I18n.t("multiplayer.status.order_rejected", mapOf("code" to code)))
        }
    }

    private fun handleRejection(payload: dynamic) {
        if ((payload.targetParticipantId as? String) != selfId) return
        if ((payload.clientMessageId as? String) == pendingCommandId) pendingCommandId = null
        MultiplayerScreen.setStatus(
            I18n.t(
                "multiplayer.status.order_rejected",
                mapOf("code" to (payload.code as? String ?: I18n.t("common.unknown"))),
            ),
        )
    }

    private fun createSnapshot(targetRevision: Long): MultiplayerSnapshot {
        val runtime =
            MultiplayerRuntimeState(
                status = if (paused) MatchStatus.PAUSED else MatchStatus.RUNNING,
                revision = targetRevision,
                authorityParticipantId = hostParticipantId ?: selfId,
                authorityEpoch = authorityEpoch,
                readyParticipantIds =
                    participants.values
                        .filter { it.ready }
                        .map { it.participantId }
                        .toSet(),
                sharedPrestigeAccounts = emptyMap(),
                unitAssignments = emptyMap(),
                pendingCommand = null,
            )
        return MultiplayerSnapshotFactory(
            adapter = GameStateNetworkAdapter { game },
            hasher = CanonicalStateHasher(),
            gameVersion = org.osada.VERSION,
            contentManifestHash = "scenario:$SMOKE_SCENARIO",
            roomConfigHash = "room:${roomCode ?: ""}",
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
            MultiplayerScreen.setStatus(I18n.t("multiplayer.status.sync_failed"))
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
        MultiplayerScreen.installStatusBadge(I18n.t("multiplayer.status.online", mapOf("revision" to revision)))
    }

    private fun renderOnlineStatus() {
        MultiplayerScreen.setStatus(I18n.t("multiplayer.status.online", mapOf("revision" to revision)))
    }

    // -- Plumbing -----------------------------------------------------------------------------

    private fun post(
        type: MultiplayerMessageType,
        payload: dynamic,
    ) {
        link?.post(type.name, payload)
    }

    private fun reset() {
        link?.close()
        link = null
        isHost = false
        selfId = ""
        roomCode = null
        hostParticipantId = null
        participants.clear()
        revision = 0
        authorityEpoch = 0
        started = false
        paused = false
        waitingForScenario = false
        queuedSnapshotJson = null
        pendingCommandId = null
        lobbyMessage = null
        processedCommandIds.clear()
        validator = null
        applier = null
    }

    private fun storedDisplayName(): String =
        window.localStorage.getItem(DISPLAY_NAME_KEY)
            ?: displayName.takeIf { it.isNotBlank() }
            ?: I18n.t("multiplayer.default_name")

    private fun rememberDisplayName(rawName: String): String {
        val name = normalizedName(rawName)
        window.localStorage.setItem(DISPLAY_NAME_KEY, name)
        return name
    }

    private fun rememberReconnectToken(
        code: String,
        token: String,
    ) {
        runCatching {
            window.localStorage.setItem(
                SESSION_KEY,
                JSON.stringify(json("roomCode" to code, "reconnectToken" to token)),
            )
        }
    }

    private fun reconnectTokenFor(code: String): String? =
        runCatching {
            val stored = window.localStorage.getItem(SESSION_KEY) ?: return null
            val value = JSON.parse<dynamic>(stored)
            if (value.roomCode as? String != code) null else value.reconnectToken as? String
        }.getOrNull()

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
        "${selfId.ifEmpty { tabId }}-${Date.now().toLong().toString(ID_RADIX)}" +
            "-${Random.nextInt().toUInt().toString(ID_RADIX)}"

    private const val TAB_ID_KEY = "osada-mp-tab-id-v1"
    private const val DISPLAY_NAME_KEY = "osada-mp-display-name-v1"
    private const val SESSION_KEY = "osada-mp-session-v1"
    private const val SMOKE_SCENARIO = "bn9s00.xml"
    private const val MAX_PARTICIPANTS = 2
    private const val MAX_NAME_LENGTH = 40
    private const val ROOM_CODE_LENGTH = 6
    private const val ID_RADIX = 36
    private val ROOM_CODE = Regex("[A-Z2-9]{$ROOM_CODE_LENGTH}")
    private const val ROOM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
}
