@file:Suppress("TooManyFunctions", "LargeClass")

package org.osada.multiplayer.client

import kotlinx.browser.window
import org.osada.Game
import org.osada.PlayerType
import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.model.getPlayer
import org.osada.model.getPlayers
import org.osada.model.getUnits
import org.osada.multiplayer.command.AttackUnit
import org.osada.multiplayer.command.ChooseCampaignDialogueOption
import org.osada.multiplayer.command.CommandValidation
import org.osada.multiplayer.command.EndPlayerTurn
import org.osada.multiplayer.command.EndTurnReady
import org.osada.multiplayer.command.ExcuseSeat
import org.osada.multiplayer.command.GameCommand
import org.osada.multiplayer.command.GameCommandJson
import org.osada.multiplayer.command.HexCoordinate
import org.osada.multiplayer.command.MoveUnit
import org.osada.multiplayer.command.OsadaGameCommandApplier
import org.osada.multiplayer.command.OsadaGameCommandValidator
import org.osada.multiplayer.command.ProposeTransfer
import org.osada.multiplayer.command.RespondTransfer
import org.osada.multiplayer.command.SetLoadout
import org.osada.multiplayer.command.SetUnitAssignment
import org.osada.multiplayer.command.toPayloadJson
import org.osada.multiplayer.coop.CoopAbilityCatalogue
import org.osada.multiplayer.coop.CoopCheckpoint
import org.osada.multiplayer.coop.CoopCommandContext
import org.osada.multiplayer.coop.CoopCommandPipeline
import org.osada.multiplayer.coop.CoopCommander
import org.osada.multiplayer.coop.CoopCommanderProfile
import org.osada.multiplayer.coop.CoopLoadout
import org.osada.multiplayer.coop.CoopPortrait
import org.osada.multiplayer.coop.CoopPortraitGeneration
import org.osada.multiplayer.coop.CoopRecoveryPlan
import org.osada.multiplayer.coop.CoopRuntime
import org.osada.multiplayer.coop.CoopState
import org.osada.multiplayer.coop.CoopStateJson
import org.osada.multiplayer.model.MatchStatus
import org.osada.multiplayer.model.MultiplayerEndpoint
import org.osada.multiplayer.model.MultiplayerRuntimeState
import org.osada.multiplayer.persistence.CoopCheckpointStore
import org.osada.multiplayer.protocol.MultiplayerMessageType
import org.osada.multiplayer.sync.CanonicalStateHasher
import org.osada.multiplayer.sync.GameStateNetworkAdapter
import org.osada.multiplayer.sync.MultiplayerSnapshot
import org.osada.multiplayer.sync.MultiplayerSnapshotFactory
import org.osada.multiplayer.sync.MultiplayerSnapshotJson
import org.osada.multiplayer.sync.MultiplayerSnapshotValidator
import org.osada.multiplayer.transport.OnlineRoomLink
import org.osada.multiplayer.transport.ROOM_MODE_COOP
import org.osada.multiplayer.transport.ROOM_MODE_SHARED
import org.osada.multiplayer.transport.RoomLink
import org.osada.multiplayer.transport.RoomLinkListener
import org.osada.multiplayer.transport.RoomLinkState
import org.osada.multiplayer.transport.RoomLobbyParticipant
import org.osada.multiplayer.transport.RoomWelcome
import org.osada.multiplayer.ui.CampaignChoice
import org.osada.multiplayer.ui.CoopCardModel
import org.osada.multiplayer.ui.CoopHud
import org.osada.multiplayer.ui.CoopLobbyModel
import org.osada.multiplayer.ui.CoopLobbyScreen
import org.osada.multiplayer.ui.CoopRecoveryScreen
import org.osada.multiplayer.ui.HubModel
import org.osada.multiplayer.ui.LobbyModel
import org.osada.multiplayer.ui.LobbyRow
import org.osada.multiplayer.ui.MultiplayerScenarioChoices
import org.osada.multiplayer.ui.MultiplayerScreen
import org.osada.multiplayer.ui.ScenarioChoice
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RulesetCompatibility
import org.osada.ui.MinimapPings
import org.osada.ui.ObserverModeLock
import org.osada.ui.StartMenuBuilder
import org.osada.ui.presentPendingHeroEvents
import org.osada.uiSettings
import kotlin.js.Date
import kotlin.js.json
import kotlin.random.Random

/**
 * The multiplayer session on the client.
 *
 * The room server owns the lobby — the roster, the room code, the chosen scenario and the ordering
 * of commands. The host client stays the *game* authority: it validates a proposed command against
 * the real rules, applies it once and publishes the resulting snapshot.
 */
object OsadaMultiplayer : RoomLinkListener {
    private data class Participant(
        val participantId: String,
        val displayName: String,
        var ready: Boolean,
        var connected: Boolean = true,
        var profile: CoopCommanderProfile? = null,
    )

    private var game: Game? = null
    private var link: RoomLink? = null
    private var isHost = false
    private var selfId: String = ""
    private var roomCode: String? = null
    private var hostParticipantId: String? = null
    private var displayName = ""
    private var scenarioFile: String? = null
    private var campaignFile: String? = null
    private var campaignDifficulty = 0
    private val participants = linkedMapOf<String, Participant>()
    private var revision = 0L
    private var authorityEpoch = 0L
    private var started = false
    private var paused = false
    private var waitingForScenario = false
    private var restoringSnapshot = false
    private var awaitingRejoinSnapshot = false
    private var adoptingCheckpointRules = false
    private var queuedSnapshotJson: String? = null
    private var profilePublishTimer: Int? = null
    private var lastSubmitted: GameCommand? = null
    private var statusHoldUntil = 0.0
    private var retryAttempts = 0
    private var pendingCommandId: String? = null
    private var lobbyMessage: String? = null
    private val processedCommandIds = mutableSetOf<String>()
    private var roomMode = ROOM_MODE_SHARED
    private var roomCapacity = MAX_PARTICIPANTS
    private var coopDraft: CoopCommanderProfile? = null
    private var recoveryCheckpoint: CoopCheckpoint? = null
    private var recoveryPlan: CoopRecoveryPlan? = null
    private var selectedHumanPlayerId = 0
    private val humanPlayerId: Int
        get() = CoopRuntime.state?.humanPlayerId ?: selectedHumanPlayerId
    private var validator: OsadaGameCommandValidator? = null
    private var applier: OsadaGameCommandApplier? = null

    val active: Boolean
        get() = started && link != null

    /**
     * Whether a local command may be issued at all right now. Outside a room this is always true;
     * inside one it mirrors exactly the gate [submitMove] applies, so the action strip disables the
     * same commands the transport would refuse instead of failing silently on click.
     */
    fun acceptsLocalCommands(): Boolean =
        !active || (!paused && !syncing && link?.state == RoomLinkState.CONNECTED && pendingCommandId == null)

    private val syncing: Boolean
        get() = waitingForScenario || restoringSnapshot || awaitingRejoinSnapshot

    /** Local UI calculations borrow only the selected unit's prospective commander. */
    fun <T> preview(
        unit: GameUnit,
        block: () -> T,
    ): T = CoopRuntime.preview(selfId, unit, block)

    fun openHub(game: Game) {
        this.game = game
        reset()
        MultiplayerScreen.showHub(
            HubModel(
                displayName = storedDisplayName(),
                onlineAvailable = MultiplayerEndpoint.isOnlineAvailable(),
                backendLabel = MultiplayerEndpoint.resolve().environment,
                onCreate = ::createRoom,
                onCreateCoop = ::createCoopRoom,
                onJoin = ::joinRoom,
                onBack = { MultiplayerScreen.closeToMainMenu() },
                onRestore = ::createRecoveryRoom,
            ),
        )
    }

    @Suppress("ReturnCount")
    fun submitMove(
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Boolean {
        if (!active || !acceptsLocalCommands()) return false
        val position = unit.getPos() ?: return false
        val command =
            MoveUnit(
                unitId = unit.id,
                from = HexCoordinate(position.col, position.row),
                to = HexCoordinate(col, row),
                path = listOf(HexCoordinate(position.col, position.row), HexCoordinate(col, row)),
                actorPlayerId = unit.owner,
            )
        return submit(command)
    }

    /**
     * An attack ORDER, sent to the host like a move (`docs/OSADA_MULTIPLAYER_TECH_SPEC.md` lists
     * `AttackUnit` among the commands). Until 2026-09-27 an attack in a room ran only on the clicking
     * client: a peer's attack was overwritten by the host's next snapshot, and the host's own attack
     * reached the peers only with whatever command came after it. The host resolves the complete
     * order (`model/executeAttack`) and publishes the result.
     */
    fun submitAttack(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean {
        if (!active || !acceptsLocalCommands()) return false
        return submit(AttackUnit(attacker.id, defender.id, attacker.owner))
    }

    /**
     * Any other player order in a room -- a unit action, a purchase, a deployment, the end of a
     * turn. The host validates and applies it and publishes the resulting state, which is the only
     * way a change reaches the other participant; a client that changed its own copy instead was
     * simply overwritten by the next snapshot.
     */
    fun submitOrder(command: GameCommand): Boolean {
        if (!active || !acceptsLocalCommands()) return false
        retryAttempts = 0
        return submit(coopRouted(command))
    }

    /**
     * In a cooperative room "End turn" is not one player's decision: the button, the hotkey and the
     * end-of-turn flow all land here, and the army's [EndPlayerTurn] becomes THIS commander's
     * done/not-done toggle. The host ends the turn itself once every required commander is done.
     * The AI players' own turn endings (another actor) pass through untouched.
     */
    private fun coopRouted(command: GameCommand): GameCommand {
        val coop = CoopRuntime.state
        val endsArmyTurn = coop != null && command is EndPlayerTurn && command.actorPlayerId == coop.humanPlayerId
        if (!endsArmyTurn) return command
        requireNotNull(coop)
        val self = coop.commanderOfParticipant(selfId)
        return EndTurnReady(self == null || self.id !in coop.done, coop.humanPlayerId)
    }

    /** Whether this client is the room's game authority. Only the host runs the AI players: a
     *  peer that ran them too sent every AI order a second time. */
    fun isAuthority(): Boolean = !active || (isHost && !paused && !syncing && link?.state == RoomLinkState.CONNECTED)

    private fun submit(command: GameCommand): Boolean {
        val commandId = newCommandId()
        pendingCommandId = commandId
        lastSubmitted = command
        MultiplayerScreen.setStatus(I18n.t("multiplayer.status.order_pending"))
        // The host's own human orders in a cooperative room take the SAME road as a guest's -- through
        // the room server's single ordering point -- so four players contend fairly; only the host's
        // bookkeeping for AI players (another actor) is applied directly, because the AI loop must
        // never wait on a seat's pending command.
        val viaServer = !isHost || command.actorPlayerId == CoopRuntime.state?.humanPlayerId
        if (!viaServer) {
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
        if (!started || restoringSnapshot || awaitingRejoinSnapshot) return
        if (!waitingForScenario) {
            // A campaign moved to its next mission on the host (a guest's loads are restores).
            if (isHost && roomMode == ROOM_MODE_COOP && CoopRuntime.active) onHostMissionLoaded()
            return
        }
        waitingForScenario = false
        prepareGameForSharedControl()
        if (isHost && roomMode == ROOM_MODE_COOP) installCoopState()
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

    private fun createRoom(rawName: String) {
        displayName = rememberDisplayName(rawName)
        openLink()
        link?.createRoom(displayName)
    }

    private fun createCoopRoom(rawName: String) {
        displayName = rememberDisplayName(rawName)
        openLink()
        roomMode = ROOM_MODE_COOP
        link?.createRoom(displayName, ROOM_MODE_COOP)
    }

    private fun createRecoveryRoom(
        rawName: String,
        checkpoint: CoopCheckpoint,
    ) {
        recoveryCheckpoint = checkpoint
        recoveryPlan = CoopRecoveryPlan.create(checkpoint, emptyMap())
        createCoopRoom(rawName)
    }

    private fun joinRoom(
        rawCode: String,
        rawName: String,
    ) {
        val code = rawCode.trim().uppercase()
        if (!ROOM_CODE.matches(code)) {
            MultiplayerScreen.showError(I18n.t("multiplayer.error.room_code"))
            return
        }
        displayName = rememberDisplayName(rawName)
        openLink()
        link?.joinRoom(code, displayName, reconnectTokenFor(code))
    }

    private fun openLink() {
        link?.close()
        participants.clear()
        link = OnlineRoomLink(MultiplayerEndpoint.resolve(), this)
    }

    // -- RoomLinkListener ---------------------------------------------------------------------

    override fun onLinkState(state: RoomLinkState) {
        refreshMapPings()
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
        welcome.scenarioFile?.let { scenarioFile = it }
        campaignFile = welcome.campaignFile
        campaignDifficulty = welcome.difficulty
        selectedHumanPlayerId = welcome.humanPlayerId
        roomMode = welcome.mode
        roomCapacity = welcome.capacity
        if (roomMode == ROOM_MODE_COOP && coopDraft == null) coopDraft = defaultDraft()
        if (welcome.isHost) hostParticipantId = welcome.participantId
        welcome.reconnectToken?.let { rememberReconnectToken(welcome.roomCode, it) }
        if (welcome.started) {
            // Rejoining a match in progress: the server pushes its stored snapshot next.
            // Restore it directly; loading the original content would restart a campaign and
            // let a returning host publish that fresh game over the room's progress.
            beginMatch()
            awaitingRejoinSnapshot = true
            adoptingCheckpointRules = welcome.restoredRoom
            waitingForScenario = false
            pendingCommandId = null
            MultiplayerScreen.hide()
        } else if (roomMode == ROOM_MODE_COOP) {
            publishProfile()
            recoveryPlan?.let { post(MultiplayerMessageType.LOBBY_PATCH_PROPOSE, json("recovery" to it.value)) }
        }
        renderLobby()
    }

    override fun onLobby(
        hostParticipantId: String?,
        participants: List<RoomLobbyParticipant>,
        scenarioFile: String?,
        mode: String?,
        campaignFile: String?,
        difficulty: Int,
        recovery: dynamic,
        humanPlayerId: Int,
    ) {
        mode?.let { roomMode = it }
        this.campaignFile = campaignFile
        this.campaignDifficulty = difficulty
        selectedHumanPlayerId = humanPlayerId
        recoveryPlan = if (recovery == null) null else CoopRecoveryPlan(recovery)
        if (isHost && recoveryCheckpoint == null) {
            recoveryCheckpoint =
                CoopCheckpointStore.latest()?.takeIf { it.snapshot.stateHash == recoveryPlan?.value?.snapshotHash }
        }
        this.hostParticipantId = hostParticipantId
        this.scenarioFile = scenarioFile ?: this.scenarioFile.takeIf { campaignFile == null }
        this.participants.clear()
        participants.forEach { participant ->
            this.participants[participant.participantId] =
                Participant(
                    participantId = participant.participantId,
                    displayName = participant.displayName,
                    ready = participant.ready,
                    connected = participant.connected,
                    profile = CoopStateJson.decodeProfile(participant.profile),
                )
        }
        isHost = hostParticipantId != null && hostParticipantId == selfId
        if (!started) renderLobby() else refreshCoopHud()
    }

    override fun onRoomMessage(
        type: String,
        senderParticipantId: String,
        payload: dynamic,
    ) {
        when (type) {
            MultiplayerMessageType.START_GAME.name -> handleStart(payload)
            MultiplayerMessageType.SNAPSHOT.name, MultiplayerMessageType.COMMAND_COMMIT.name -> applySnapshot(payload)
            MultiplayerMessageType.SNAPSHOT_ACK.name -> {
                runCatching { CoopCheckpointStore.acknowledge(payload.stateHash as? String ?: "") }
                refreshCoopHud()
            }
            MultiplayerMessageType.COMMAND_PROPOSE.name -> if (isHost) handleProposal(senderParticipantId, payload)
            MultiplayerMessageType.COMMAND_REJECT.name -> handleRejection(payload)
            MultiplayerMessageType.PAUSE_STATE.name -> handlePause(payload)
            MultiplayerMessageType.PRESENCE_MAP_PING.name -> {
                val participant = participants[senderParticipantId]
                if (active && participant != null) {
                    val name =
                        CoopRuntime.state?.commanderOfParticipant(senderParticipantId)?.name
                            ?: participant.displayName
                    MinimapPings.receive(senderParticipantId, name, payload)
                }
            }
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

    private fun setReady(ready: Boolean) {
        if (ready && roomMode == ROOM_MODE_COOP && coopDraft?.let(::draftIssues).orEmpty().isNotEmpty()) return
        participants[selfId]?.ready = ready
        post(MultiplayerMessageType.SET_READY, json("ready" to ready))
    }

    private fun chooseCampaign(
        file: String,
        difficulty: Int,
    ) {
        if (!isHost || file.isBlank()) return
        campaignFile = file
        campaignDifficulty = difficulty
        scenarioFile = null
        post(MultiplayerMessageType.LOBBY_PATCH_PROPOSE, json("campaignFile" to file, "difficulty" to difficulty))
    }

    private fun chooseScenario(file: String) {
        if (!isHost || file.isBlank()) return
        campaignFile = null
        scenarioFile = file
        post(MultiplayerMessageType.LOBBY_PATCH_PROPOSE, json("scenarioFile" to file))
    }

    private fun chooseSide(playerId: Int) {
        if (!isHost ||
            campaignFile != null ||
            MultiplayerScenarioChoices.sides(scenarioFile).none { it.playerId == playerId }
        ) {
            return
        }
        post(
            MultiplayerMessageType.LOBBY_PATCH_PROPOSE,
            json(
                "scenarioFile" to scenarioFile,
                "humanPlayerId" to playerId,
            ),
        )
    }

    @Suppress("ReturnCount")
    private fun startMatch() {
        if (recoveryPlan != null) {
            startRecovery()
            return
        }
        if (scenarioFile == null && campaignFile == null) return
        if (campaignFile == null &&
            MultiplayerScenarioChoices.sides(scenarioFile).none { it.playerId == selectedHumanPlayerId }
        ) {
            return
        }
        if (!isHost || !everyoneReady()) return
        if (roomMode == ROOM_MODE_COOP) {
            coopStartIssue()?.let {
                MultiplayerScreen.showError(it)
                return
            }
        }
        post(
            MultiplayerMessageType.START_GAME,
            json("scenarioFile" to scenarioFile, "hostParticipantId" to selfId),
        )
        // The server broadcasts START_GAME back to everyone, this client included.
    }

    private fun everyoneReady(): Boolean =
        if (roomMode == ROOM_MODE_COOP) {
            participants.size in COOP_MIN_PARTICIPANTS..roomCapacity &&
                participants.values.all { it.ready && it.connected && it.profile != null }
        } else {
            participants.size == MAX_PARTICIPANTS && participants.values.all { it.ready && it.connected }
        }

    private fun leaveRoom() {
        if (link != null) post(MultiplayerMessageType.LEAVE_ROOM, json())
        reset()
        MultiplayerScreen.closeToMainMenu()
    }

    @Suppress("ReturnCount")
    private fun renderLobby() {
        if (started) return
        recoveryPlan?.let {
            renderRecoveryLobby(it)
            return
        }
        if (roomMode == ROOM_MODE_COOP) {
            renderCoopLobby()
            return
        }
        val chosen = scenarioFile
        MultiplayerScreen.showLobby(
            LobbyModel(
                roomCode = roomCode,
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
                readyEnabled = participants[selfId] != null && chosen != null,
                isHost = isHost,
                scenarios = scenarioChoices(),
                selectedScenarioFile = chosen,
                selectedScenarioName = chosen?.let(::scenarioNameOf),
                startEnabled = everyoneReady() && chosen != null,
                onScenarioSelected = ::chooseScenario,
                sides = MultiplayerScenarioChoices.sides(chosen),
                selectedPlayerId = selectedHumanPlayerId,
                onSideSelected = ::chooseSide,
                onToggleReady = { setReady(participants[selfId]?.ready != true) },
                onStart = ::startMatch,
                onLeave = ::leaveRoom,
            ),
        )
    }

    // -- Cooperative lobby --------------------------------------------------------------------

    private fun renderRecoveryLobby(plan: CoopRecoveryPlan) {
        CoopRecoveryScreen.showLobby(
            CoopRecoveryScreen.Lobby(
                roomCode = roomCode,
                plan = plan,
                participants =
                    participants.values.map {
                        RoomLobbyParticipant(
                            it.participantId,
                            it.displayName,
                            it.participantId == hostParticipantId,
                            it.ready,
                            it.connected,
                        )
                    },
                selfId = selfId,
                isHost = isHost,
                canStart = recoveryReady(plan),
                message = lobbyMessage,
                onBind = { id, participant ->
                    val next =
                        (recoveryPlan?.bindings ?: plan.bindings)
                            .filterValues {
                                participants[it]?.connected == true
                            }.toMutableMap()
                    if (participant.isEmpty()) next.remove(id) else next[id] = participant
                    recoveryCheckpoint?.let { checkpoint ->
                        val updated = CoopRecoveryPlan.create(checkpoint, next)
                        recoveryPlan = updated
                        post(MultiplayerMessageType.LOBBY_PATCH_PROPOSE, json("recovery" to updated.value))
                    }
                },
                onReady = { setReady(participants[selfId]?.ready != true) },
                onStart = ::startMatch,
                onLeave = ::leaveRoom,
                onReplace = ::replaceRecoveryCommander,
            ),
        )
    }

    @Suppress("ReturnCount")
    private fun replaceRecoveryCommander(id: String) {
        if (!isHost) return
        val checkpoint = recoveryCheckpoint ?: return
        val plan = recoveryPlan ?: return
        val participantId = plan.bindings[id] ?: return
        val participant = participants[participantId] ?: return
        runCatching {
            val updated = checkpoint.replaceCommander(id, participant.displayName)
            val successor = updated.state.commanders.first { it.id !in plan.commanderIds }
            val bindings = plan.bindings.filterKeys { it != id } + (successor.id to participantId)
            val next = CoopRecoveryPlan.create(updated, bindings)
            recoveryCheckpoint = updated
            recoveryPlan = next
            post(MultiplayerMessageType.LOBBY_PATCH_PROPOSE, json("recovery" to next.value))
        }.onFailure { MultiplayerScreen.showError(I18n.t("multiplayer.coop.replacement.unavailable")) }
    }

    private fun recoveryReady(plan: CoopRecoveryPlan): Boolean =
        recoveryCheckpoint != null &&
            everyoneReady() &&
            plan.bindings.keys == plan.commanderIds.toSet() &&
            plan.bindings.size == participants.size &&
            plan.bindings.values.toSet() == participants.keys

    @Suppress("ReturnCount")
    private fun startRecovery() {
        val plan = recoveryPlan ?: return
        val checkpoint = recoveryCheckpoint ?: return
        if (!isHost || !recoveryReady(plan)) return
        runCatching {
            requireCompatibleRules(checkpoint.snapshot.gameStateJson, adopt = true)
            val snapshot = checkpoint.bind(requireNotNull(roomCode), selfId, plan.bindings, participants.keys)
            post(
                MultiplayerMessageType.START_GAME,
                json(
                    "recoverySnapshot" to JSON.parse<dynamic>(MultiplayerSnapshotJson.encode(snapshot)),
                ),
            )
        }.onFailure { MultiplayerScreen.showError(I18n.t("multiplayer.coop.recovery.invalid")) }
    }

    private fun defaultDraft(): CoopCommanderProfile =
        CoopCommanderProfile(
            name = CoopCommander.cleanName(displayName),
            portrait = CoopPortrait.generated(Random.nextInt()),
            abilityIds = emptyList(),
        )

    private fun renderCoopLobby() {
        val draft = coopDraft ?: defaultDraft().also { coopDraft = it }
        val chosen = scenarioFile ?: campaignFile
        val issues = draftIssues(draft)
        CoopLobbyScreen.show(
            CoopLobbyModel(
                roomCode = roomCode,
                message = lobbyMessage,
                cards =
                    participants.values.map {
                        CoopCardModel(
                            participantId = it.participantId,
                            displayName = it.displayName,
                            isHost = it.participantId == hostParticipantId,
                            isSelf = it.participantId == selfId,
                            ready = it.ready,
                            connected = it.connected,
                            profile = it.profile,
                        )
                    },
                capacity = roomCapacity,
                minimum = COOP_MIN_PARTICIPANTS,
                draft = draft,
                slotCap = CoopLoadout.slotCap(0),
                loadoutIssues = issues,
                selfReady = participants[selfId]?.ready == true,
                isHost = isHost,
                scenarios = scenarioChoices(),
                selectedScenarioFile = scenarioFile,
                selectedScenarioName = scenarioFile?.let(::scenarioNameOf),
                campaigns = campaignChoices(),
                selectedCampaignFile = campaignFile,
                campaignDifficulty = campaignDifficulty,
                contentChosen = chosen != null,
                startEnabled = everyoneReady() && chosen != null,
                onScenarioSelected = ::chooseScenario,
                sides = MultiplayerScenarioChoices.sides(chosen),
                selectedPlayerId = selectedHumanPlayerId,
                onSideSelected = ::chooseSide,
                onCampaignSelected = ::chooseCampaign,
                onRestore = { createRecoveryRoom(displayName, it) },
                onNameChanged = ::onDraftNameChanged,
                onPortraitChosen = { updateDraft(portrait = it) },
                onReroll = { era, female ->
                    updateDraft(portrait = CoopPortraitGeneration.generate(Random.nextInt(), era, female))
                },
                onAbilityToggled = ::toggleAbility,
                onToggleReady = { setReady(participants[selfId]?.ready != true) },
                onStart = ::startMatch,
                onLeave = ::leaveRoom,
            ),
        )
    }

    private fun draftIssues(draft: CoopCommanderProfile): List<String> =
        CoopLoadout.validate(draft.abilityIds, CoopLoadout.slotCap(0)).map {
            I18n.t("multiplayer.coop.loadout.${it.name.lowercase()}")
        }

    private fun updateDraft(
        name: String? = null,
        portrait: CoopPortrait? = null,
        abilityIds: List<String>? = null,
    ) {
        val current = coopDraft ?: defaultDraft()
        coopDraft =
            current.copy(
                name = name ?: current.name,
                portrait = portrait ?: current.portrait,
                abilityIds = abilityIds ?: current.abilityIds,
            )
        participants[selfId]?.ready = false
        publishProfile()
        renderLobby()
    }

    private fun toggleAbility(id: String) {
        val current = coopDraft?.abilityIds.orEmpty()
        val next =
            when {
                id in current -> current - id
                current.size >= CoopLoadout.slotCap(0) -> current
                CoopAbilityCatalogue.byId(id) == null -> current
                else -> current + id
            }
        updateDraft(abilityIds = next)
    }

    /** Typing must not re-render the lobby under the cursor: remember the name, publish when idle. */
    private fun onDraftNameChanged(value: String) {
        val current = coopDraft ?: defaultDraft()
        coopDraft = current.copy(name = CoopCommander.cleanName(value))
        profilePublishTimer?.let { window.clearTimeout(it) }
        profilePublishTimer =
            window.setTimeout({
                profilePublishTimer = null
                participants[selfId]?.ready = false
                publishProfile()
            }, PROFILE_DEBOUNCE_MILLIS)
    }

    /** The profile goes to the room as plain identifiers; the server relays it without reading it. */
    private fun publishProfile() {
        val draft = coopDraft ?: return
        if (link == null || started) return
        post(MultiplayerMessageType.LOBBY_PATCH_PROPOSE, json("profile" to CoopStateJson.encodeProfile(draft)))
    }

    /** Why the host may not start a cooperative match yet, or null. Re-validated here, not trusted. */
    private fun coopStartIssue(): String? =
        participants.values.firstNotNullOfOrNull { participant ->
            val profile = participant.profile
            when {
                profile == null -> I18n.t("multiplayer.coop.start.no_profile", mapOf("name" to participant.displayName))
                CoopLoadout.validate(profile.abilityIds, CoopLoadout.slotCap(0)).isNotEmpty() ->
                    I18n.t("multiplayer.coop.start.bad_loadout", mapOf("name" to participant.displayName))
                else -> null
            }
        }

    /**
     * The scenario register, flattened for a picker: `scenariolist.js` is a run of rows where a
     * one-element row opens a new group and every following row belongs to it.
     */
    private fun scenarioChoices(): List<ScenarioChoice> = MultiplayerScenarioChoices.scenarios()

    /** The campaign register, as the start menu's own campaign list reads it. */
    private fun campaignChoices(): List<CampaignChoice> =
        StartMenuBuilder.campaignList().mapNotNull { entry ->
            val file = entry.file as? String ?: return@mapNotNull null
            CampaignChoice(file, entry.title as? String ?: file, (entry.scenarios as? Number)?.toInt() ?: 0)
        }

    private fun scenarioNameOf(file: String): String = scenarioChoices().firstOrNull { it.file == file }?.name ?: file

    // -- Match --------------------------------------------------------------------------------

    /**
     * Marks this client as being IN a match — the single place `started` is raised.
     *
     * Observer Mode is switched off here rather than left to the settings screen: no fog of war and
     * shown hidden objectives give one seat information the other does not have, and Stalin Regime
     * multiplies that seat's combat/movement/prestige by ten. All three are single-player
     * conveniences and none of them may follow a player into a shared, host-authoritative match
     * (2026-08-16 user request). [ObserverModeLock.refresh] then keeps the
     * controls greyed out and unclickable for as long as [active] stays true.
     */
    private fun beginMatch() {
        started = true
        ObserverModeLock.clearAll()
    }

    private fun handleStart(payload: dynamic) {
        beginMatch()
        (payload.mode as? String)?.let { roomMode = it }
        hostParticipantId = payload.hostParticipantId as? String ?: hostParticipantId
        isHost = hostParticipantId == selfId
        revision = (payload.revision as? Number)?.toLong() ?: 0
        authorityEpoch = 0
        waitingForScenario = true
        MultiplayerScreen.hide()
        campaignFile = payload.campaignFile as? String
        campaignDifficulty = (payload.difficulty as? Number)?.toInt() ?: 0
        selectedHumanPlayerId = (payload.humanPlayerId as? Number)?.toInt() ?: 0
        val file = payload.scenarioFile as? String ?: scenarioFile
        if (campaignFile == null && file == null) return
        scenarioFile = file
        if (payload.recovery == true) {
            waitingForScenario = false
            awaitingRejoinSnapshot = true
            adoptingCheckpointRules = true
            return
        }
        loadContent(campaignFile, campaignDifficulty, file)
    }

    /** Starts whatever the room was set up to play: a whole campaign, or one battle. */
    private fun loadContent(
        campaign: String?,
        difficulty: Int,
        scenario: String?,
    ) {
        if (campaign != null) startCampaign(campaign, difficulty) else scenario?.let(::loadScenario)
    }

    /**
     * Every client starts the same campaign run; the host's snapshot then makes them one game. The
     * run begins exactly as the start menu begins one (`Game.newCampaign`), minus what is single-
     * player housekeeping: the hero archive is not touched, because a cooperative commander is a
     * session profile and never enters the Hall of Fame.
     */
    private fun startCampaign(
        file: String,
        difficulty: Int,
    ) {
        val currentGame = game ?: return
        val id = StartMenuBuilder.campaignList().indexOfFirst { (it.file as? String) == file }
        if (id < 0) {
            MultiplayerScreen.setStatus(I18n.t("multiplayer.coop.campaign.unknown", mapOf("file" to file)))
            return
        }
        selectedHumanPlayerId = 0
        uiSettings.isAI.indices.forEach { uiSettings.isAI[it] = if (it == humanPlayerId) 0 else 1 }
        currentGame.newCampaign(id, difficulty)
    }

    private fun loadScenario(file: String) {
        val currentGame = game ?: return
        currentGame.campaign = null
        uiSettings.isAI.indices.forEach { uiSettings.isAI[it] = if (it == humanPlayerId) 0 else 1 }
        currentGame.newScenario(file, null)
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

    @Suppress("ReturnCount")
    private fun processCommand(
        commandId: String,
        expectedRevision: Long,
        command: GameCommand,
        senderParticipantId: String,
    ) {
        if (syncing || paused) {
            rejectCommand(senderParticipantId, commandId, "COMMAND_PENDING")
            return
        }
        if (commandId in processedCommandIds) return
        if (expectedRevision != revision) {
            rejectCommand(senderParticipantId, commandId, "STALE_STATE")
            return
        }
        when (val result = evaluate(command, senderParticipantId)) {
            CommandValidation.Accepted -> {
                // The host resolves orders without their animations; an AI turn waiting on one
                // (`GameTurnFlow` raises the flag before each order) would otherwise never resume.
                game?.waitUIAnimation = false
                processedCommandIds += commandId
                revision++
                pendingCommandId = null
                post(
                    MultiplayerMessageType.COMMAND_COMMIT,
                    JSON.parse(MultiplayerSnapshotJson.encode(createSnapshot(revision))),
                )
                game?.ui?.render?.render()
                // The host resolves orders without their animations, so the hero events the model
                // queued (an emergence, a promotion choice, a casualty) are shown here.
                if (game?.ui != null) presentPendingHeroEvents()
                renderOnlineStatus()
            }

            is CommandValidation.Rejected ->
                rejectCommand(senderParticipantId, commandId, result.rejection.code.name, result.rejection.message)
        }
    }

    /**
     * Validates and APPLIES [command]. A cooperative room hands the whole thing to
     * [CoopCommandPipeline], which binds the authenticated sender to a commander first; every other
     * room keeps the original validate-then-apply.
     */
    private fun evaluate(
        command: GameCommand,
        senderParticipantId: String,
    ): CommandValidation {
        val gameValidator = requireNotNull(validator)
        val gameApplier = requireNotNull(applier)
        val coop = CoopRuntime.state
        val map = game?.scenario?.map
        if (coop == null || map == null) {
            return gameValidator.validate(command, null).also {
                if (it == CommandValidation.Accepted) gameApplier.apply(command)
            }
        }
        return CoopCommandPipeline.execute(
            CoopCommandContext(
                map = map,
                coop = coop,
                command = command,
                senderParticipantId = senderParticipantId,
                senderIsHost = senderParticipantId == hostParticipantId,
                committingRevision = revision + 1,
                validate = { gameValidator.validate(it, null) },
                apply = { gameApplier.apply(it) },
                quotePurchase = org.osada.ui.CoopPurchaseButton::quote,
            ),
        )
    }

    private fun rejectCommand(
        targetParticipantId: String,
        commandId: String,
        code: String,
        detail: String? = null,
    ) {
        post(
            MultiplayerMessageType.COMMAND_REJECT,
            json(
                "targetParticipantId" to targetParticipantId,
                "clientMessageId" to commandId,
                "code" to code,
                "detail" to detail,
            ),
        )
        if (targetParticipantId == selfId) {
            pendingCommandId = null
            if (!retryIfBusy(code)) showTransientStatus(rejectionText(code, detail))
        }
    }

    /**
     * Two commanders pressing Done within the same few milliseconds is the normal case in a four-seat
     * room, and the room serializes orders, so the second one is answered "busy" or "stale". A
     * readiness/vote choice states a DESIRED outcome rather than reacting to the position, so it is
     * safe to ask again a moment later; a move, an attack or a purchase is not (the position it was aimed
     * at is gone) and is never replayed behind the player's back.
     */
    private fun retryIfBusy(code: String): Boolean {
        val command = lastSubmitted
        val retryable = code == BUSY_CODE || code == STALE_CODE
        val metadata =
            command is EndTurnReady ||
                command is org.osada.multiplayer.command.CoopBriefingOrder ||
                command is ChooseCampaignDialogueOption
        if (!metadata || !retryable || retryAttempts >= MAX_RETRIES) return false
        val attemptedRoom = roomCode
        retryAttempts++
        window.setTimeout({
            val stillCurrent = roomCode == attemptedRoom && lastSubmitted === command
            val canRetry = active && !paused && pendingCommandId == null
            if (canRetry && stillCurrent) {
                if (syncing) retryIfBusy(code) else submit(command)
            }
        }, RETRY_DELAY_MILLIS * retryAttempts)
        return true
    }

    /**
     * What the player reads when an order is refused. A cooperative refusal carries a coded reason
     * (`coop.unit_commanded_by:Name`) that is localized here, so the message can name the teammate
     * who now owns the unit instead of an opaque code.
     */
    private fun rejectionText(
        code: String,
        detail: String?,
    ): String {
        if (detail != null && detail.startsWith(CoopCommandPipeline.REASON_PREFIX)) {
            val body = detail.removePrefix(CoopCommandPipeline.REASON_PREFIX)
            val key = body.substringBefore(':')
            val arg = body.substringAfter(':', "")
            return I18n.tOrNull("multiplayer.coop.reject.$key", mapOf("name" to arg))
                ?: I18n.t("multiplayer.status.order_rejected", mapOf("code" to code))
        }
        return when (code) {
            BUSY_CODE -> I18n.t("multiplayer.status.order_busy")
            STALE_CODE -> I18n.t("multiplayer.status.order_stale")
            else -> I18n.t("multiplayer.status.order_rejected", mapOf("code" to code))
        }
    }

    private fun handleRejection(payload: dynamic) {
        if ((payload.targetParticipantId as? String) != selfId) return
        if ((payload.clientMessageId as? String) == pendingCommandId) pendingCommandId = null
        val code = payload.code as? String ?: I18n.t("common.unknown")
        if (retryIfBusy(code)) return
        showTransientStatus(rejectionText(code, payload.detail as? String))
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
                coopJson = CoopRuntime.state?.let(CoopStateJson::encode),
            )
        return MultiplayerSnapshotFactory(
            adapter = GameStateNetworkAdapter { game },
            hasher = CanonicalStateHasher(),
            gameVersion = org.osada.VERSION,
            contentManifestHash = "scenario:${scenarioFile.orEmpty()}",
            roomConfigHash = "room:${roomCode ?: ""}",
        ).create(runtime)
    }

    private fun applySnapshot(payload: dynamic) {
        if (waitingForScenario || restoringSnapshot) {
            queuedSnapshotJson = JSON.stringify(payload)
            return
        }
        val snapshot = MultiplayerSnapshotJson.decode(JSON.stringify(payload))
        if (snapshot.revision < revision) return
        runCatching {
            val validation = MultiplayerSnapshotValidator().validate(snapshot)
            require(validation.valid) { validation.errors.joinToString() }
            requireCompatibleRules(snapshot.gameStateJson, adopt = adoptingCheckpointRules)
            // Decoded BEFORE the game is touched: a snapshot from an incompatible cooperative build is
            // refused whole rather than restored with every commander's bonus silently gone.
            val coopState = snapshot.multiplayerState.coopJson?.let(CoopStateJson::decode)
            val state = requireNotNull(game?.state) { "No active game state" }
            restoringSnapshot = true
            // Install before setupGameState draws ranges or runs lifecycle hooks.
            if (coopState != null) CoopRuntime.install(coopState) else CoopRuntime.clear()
            org.osada.ui.briefing.CoopBriefingBridge
                .renderVotes()
            uiSettings.isAI.indices.forEach { uiSettings.isAI[it] = if (it == humanPlayerId) 0 else 1 }
            require(
                state.restoreFromString(snapshot.gameStateJson) {
                    prepareGameForSharedControl()
                    revision = snapshot.revision
                    authorityEpoch = snapshot.authorityEpoch
                    pendingCommandId = null
                    restoringSnapshot = false
                    awaitingRejoinSnapshot = false
                    adoptingCheckpointRules = false
                    saveCheckpoint(snapshot)
                    renderOnlineStatus()
                    queuedSnapshotJson?.let { encoded ->
                        queuedSnapshotJson = null
                        applySnapshot(JSON.parse(encoded))
                    }
                },
            ) { "Network game state could not be restored" }
        }.onFailure {
            restoringSnapshot = false
            console.error("[multiplayer] snapshot apply failed: ${it.message ?: it.toString()}")
            MultiplayerScreen.setStatus(I18n.t("multiplayer.status.sync_failed"))
        }
    }

    private fun saveCheckpoint(snapshot: MultiplayerSnapshot) {
        if (roomMode != ROOM_MODE_COOP) return
        runCatching { CoopCheckpointStore.save(snapshot) }.onFailure {
            console.warn("[coop] checkpoint refused", it.message)
        }
    }

    /**
     * The host's effective rules travel inside the shared game state
     * (`docs/design/ruleset-profiles.md` §8). A client with no ruleset of its own adopts them; a
     * client that deliberately resolved a DIFFERENT one, or a room declaring a schema or a rule this
     * build cannot execute, is refused here with both hashes rather than silently switched into
     * somebody else's game.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun requireCompatibleRules(
        gameStateJson: String,
        adopt: Boolean = false,
    ) {
        val remoteBlock =
            try {
                JSON.parse<dynamic>(gameStateJson).ruleset
            } catch (e: Throwable) {
                console.warn("[multiplayer] host ruleset unreadable; adopting the shared state as-is", e)
                null
            } ?: return
        val verdict = RulesetCompatibility.verdict(if (adopt) null else ActiveRuleset.currentOrNull(), remoteBlock)
        require(verdict.allowed) { rulesRefusalMessage(verdict) }
    }

    private fun rulesRefusalMessage(verdict: RulesetCompatibility.Verdict): String {
        val hashes =
            mapOf(
                "localHash" to shortHash(verdict.localHash),
                "remoteHash" to shortHash(verdict.remoteHash),
            )
        return when (verdict.refusal) {
            RulesetCompatibility.Refusal.UNSUPPORTED_SCHEMA ->
                I18n.t("multiplayer.rules.unsupported_schema", hashes)

            RulesetCompatibility.Refusal.UNKNOWN_RULES ->
                I18n.t(
                    "multiplayer.rules.unknown",
                    hashes + ("rules" to verdict.unknownKeys.sorted().joinToString(", ")),
                )

            else ->
                I18n.t(
                    "multiplayer.rules.mismatch",
                    hashes +
                        (
                            "rules" to
                                verdict.differingRules.joinToString(", ") { rule ->
                                    I18n.t("rules.${rule.key}.label")
                                }
                        ),
                )
        }
    }

    /** Enough of the fingerprint to compare by eye in a bug report, without a wall of hex. */
    private fun shortHash(hash: String): String = if (hash.length <= HASH_PREVIEW) hash else hash.take(HASH_PREVIEW)

    /**
     * Builds the cooperative state from the lobby the match started from: one commander per seated
     * participant, in the server's join order (the same on every client), each bound to its
     * authenticated participant id. Standalone battles have no campaign unlocks, so the loadouts were
     * validated at two slots.
     */
    private fun installCoopState() {
        val map = game?.scenario?.map ?: return
        val commanders =
            participants.values
                .filter { it.profile != null }
                .mapIndexed { seat, participant ->
                    val profile = requireNotNull(participant.profile)
                    CoopCommander(
                        id = CoopCommander.idForSeat(seat),
                        seat = seat,
                        participantId = participant.participantId,
                        name = profile.name.ifBlank { participant.displayName },
                        portrait = profile.portrait,
                        abilityIds = profile.abilityIds,
                    )
                }
        val state = CoopState(commanders, humanPlayerId)
        state.campaignTotal = campaignFile?.let(::campaignScenarioCount) ?: 0
        state.beginNextMission(game?.scenario?.file.orEmpty())
        state.onTurnKey(CoopCommandPipeline.turnKeyOf(map), friendlyTurn = false)
        CoopRuntime.install(state)
        org.osada.ui.briefing.CoopBriefingBridge
            .capture()
    }

    private fun campaignScenarioCount(file: String): Int {
        val entry = StartMenuBuilder.campaignList().firstOrNull { (it.file as? String) == file }
        return (entry?.scenarios as? Number)?.toInt() ?: 0
    }

    /**
     * The campaign has loaded its next mission on the host. The shared state moves on -- the
     * finished mission is archived, a common ability slot is granted at its milestone, tactical
     * assignments reset and last mission's allocation is offered back by formation -- and the new
     * state is published to everyone as the next revision. Commanders and their portraits carry on.
     */
    @Suppress("ReturnCount")
    private fun onHostMissionLoaded() {
        val coop = CoopRuntime.state ?: return
        val currentGame = game ?: return
        val map = currentGame.scenario?.map ?: return
        prepareGameForSharedControl()
        coop.beginNextMission(currentGame.scenario?.file.orEmpty())
        val mine = map.getPlayer(humanPlayerId)
        val units =
            (map.getUnits().toList() + mine.getCoreUnitList())
                .filter {
                    it.owner == humanPlayerId
                }.distinctBy { it.id }
        coop.applySuggestions(units.map { it.id }) { id -> units.firstOrNull { it.id == id }?.formationId }
        coop.onTurnKey(CoopCommandPipeline.turnKeyOf(map), friendlyTurn = true)
        org.osada.ui.briefing.CoopBriefingBridge
            .capture()
        revision++
        pendingCommandId = null
        post(MultiplayerMessageType.SNAPSHOT, JSON.parse(MultiplayerSnapshotJson.encode(createSnapshot(revision))))
        renderOnlineStatus()
    }

    private fun prepareGameForSharedControl() {
        val currentGame = game ?: return
        val map = currentGame.scenario?.map ?: return
        map.getPlayers().forEach { player ->
            player.type = if (player.id == humanPlayerId) PlayerType.HUMAN_LOCAL else PlayerType.AI_LOCAL
        }
        validator = OsadaGameCommandValidator { game }
        applier =
            OsadaGameCommandApplier(
                gameProvider = { game },
                dialogueChoiceHandler = org.osada.ui.briefing.CoopBriefingBridge::commit,
            )
        // Restoring a snapshot re-runs this; it must not replace a refusal that is still being read.
        val held = Date.now() < statusHoldUntil
        MultiplayerScreen.installStatusBadge(
            if (held) {
                MultiplayerScreen.statusText()
            } else {
                I18n.t(
                    "multiplayer.status.online",
                    mapOf(
                        "revision" to revision,
                    ),
                )
            },
        )
    }

    private fun renderOnlineStatus() {
        org.osada.ui.briefing.CoopBriefingBridge
            .synchronize()
        refreshMapPings()
        // A refusal must stay readable: the winner's commit arrives a moment later and used to wipe
        // the very sentence that explained why the order did not happen.
        if (Date.now() >= statusHoldUntil) {
            MultiplayerScreen.setStatus(I18n.t("multiplayer.status.online", mapOf("revision" to revision)))
        }
        refreshCoopHud()
    }

    internal fun localParticipantId(): String? = selfId

    internal fun onCoopOpeningFinished() {
        if (!started || !isHost || CoopRuntime.state?.briefing?.open != false) return
        revision++
        post(MultiplayerMessageType.SNAPSHOT, JSON.parse(MultiplayerSnapshotJson.encode(createSnapshot(revision))))
    }

    private fun showTransientStatus(text: String) {
        MultiplayerScreen.setStatus(text)
        statusHoldUntil = Date.now() + STATUS_HOLD_MILLIS
        window.setTimeout({
            if (started &&
                Date.now() >= statusHoldUntil
            ) {
                renderOnlineStatus()
            }
        }, STATUS_HOLD_MILLIS + STATUS_REFRESH_SLACK_MILLIS)
    }

    private fun refreshCoopHud() {
        val coop = CoopRuntime.state
        if (coop == null || !started) {
            CoopHud.remove()
            return
        }
        CoopHud.render(
            CoopHud.Model(
                state = coop,
                selfCommanderId = coop.commanderOfParticipant(selfId)?.id,
                isHost = isHost,
                connected = participants.mapValues { it.value.connected },
                gameEnded = game?.gameEnded == true,
                checkpointDurable = CoopCheckpointStore.durable,
                onExportCheckpoint = CoopRecoveryScreen::exportLatest,
                onToggleDone = { submitOrder(EndPlayerTurn(coop.humanPlayerId)) },
                onTransferProposal = { unitId, to -> submitOrder(ProposeTransfer(unitId, to, coop.humanPlayerId)) },
                onTransferAnswer = {
                    unitId,
                    accept,
                    ->
                    submitOrder(RespondTransfer(unitId, accept, coop.humanPlayerId))
                },
                onSetLoadout = { abilityIds -> submitOrder(SetLoadout(abilityIds, coop.humanPlayerId)) },
                onReserveUnit = { unitId, reserve ->
                    submitOrder(SetUnitAssignment(unitId, if (reserve) selfId else null, coop.humanPlayerId))
                },
                onExcuse = { commanderId, excused, delegate ->
                    submitOrder(ExcuseSeat(commanderId, excused, delegate, coop.humanPlayerId))
                },
            ),
        )
    }

    // -- Plumbing -----------------------------------------------------------------------------

    private fun refreshMapPings() {
        val file = game?.scenario?.file
        val mission =
            if (active && !syncing && !file.isNullOrBlank()) {
                "${CoopRuntime.state?.missionIndex ?: 0}:$file"
            } else {
                null
            }
        val sender: ((Int, Int, String) -> Unit)? =
            if (mission != null && link?.state == RoomLinkState.CONNECTED) {
                { x, y, key ->
                    post(MultiplayerMessageType.PRESENCE_MAP_PING, json("x" to x, "y" to y, "mission" to key))
                }
            } else {
                null
            }
        MinimapPings.configure(mission, sender)
    }

    private fun post(
        type: MultiplayerMessageType,
        payload: dynamic,
    ) {
        if (roomMode == ROOM_MODE_COOP &&
            (type == MultiplayerMessageType.SNAPSHOT || type == MultiplayerMessageType.COMMAND_COMMIT)
        ) {
            CoopCheckpointStore.stage(MultiplayerSnapshotJson.decode(JSON.stringify(payload)))
        }
        link?.post(type.name, payload)
    }

    private fun reset() {
        selectedHumanPlayerId = 0
        org.osada.ui.briefing.CoopBriefingBridge
            .reset()
        link?.close()
        link = null
        isHost = false
        selfId = ""
        roomCode = null
        hostParticipantId = null
        scenarioFile = null
        campaignFile = null
        recoveryCheckpoint = null
        recoveryPlan = null
        CoopCheckpointStore.clearPending()
        participants.clear()
        revision = 0
        authorityEpoch = 0
        started = false
        paused = false
        waitingForScenario = false
        restoringSnapshot = false
        awaitingRejoinSnapshot = false
        adoptingCheckpointRules = false
        queuedSnapshotJson = null
        pendingCommandId = null
        lobbyMessage = null
        processedCommandIds.clear()
        validator = null
        applier = null
        roomMode = ROOM_MODE_SHARED
        roomCapacity = MAX_PARTICIPANTS
        coopDraft = null
        profilePublishTimer?.let { window.clearTimeout(it) }
        profilePublishTimer = null
        CoopRuntime.clear()
        CoopHud.remove()
        MinimapPings.configure(null, null)
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

    private fun newCommandId(): String =
        "${selfId.ifEmpty { "local" }}-${Date.now().toLong().toString(ID_RADIX)}" +
            "-${Random.nextInt().toUInt().toString(ID_RADIX)}"

    private const val DISPLAY_NAME_KEY = "osada-mp-display-name-v1"
    private const val SESSION_KEY = "osada-mp-session-v1"
    private const val MAX_PARTICIPANTS = 2
    private const val COOP_MIN_PARTICIPANTS = 2
    private const val PROFILE_DEBOUNCE_MILLIS = 450
    private const val STATUS_HOLD_MILLIS = 6_000
    private const val STATUS_REFRESH_SLACK_MILLIS = 50
    private const val BUSY_CODE = "COMMAND_PENDING"
    private const val STALE_CODE = "STALE_STATE"
    private const val MAX_RETRIES = 4
    private const val RETRY_DELAY_MILLIS = 180
    private const val MAX_NAME_LENGTH = 40
    private const val ROOM_CODE_LENGTH = 6
    private const val ID_RADIX = 36

    /** Enough hash to compare by eye in a bug report. */
    private const val HASH_PREVIEW = 12
    private val ROOM_CODE = Regex("[A-Z2-9]{$ROOM_CODE_LENGTH}")
}
