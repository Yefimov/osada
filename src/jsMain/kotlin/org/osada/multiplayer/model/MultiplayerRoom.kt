package org.osada.multiplayer.model

import org.osada.multiplayer.command.GameCommand

enum class MultiplayerMode {
    COOP_CAMPAIGN_SHARED,
    COOP_CAMPAIGN_SPLIT,
    COOP_SCENARIO_SHARED,
    COOP_SCENARIO_SPLIT,
    VERSUS_SCENARIO,
}

enum class MultiplayerContentKind {
    CAMPAIGN,
    SCENARIO,
}

data class MultiplayerContentRef(
    val kind: MultiplayerContentKind,
    val contentId: String,
)

enum class EconomyPolicy {
    SHARED_PLAYER,
    SHARED_SIDE,
    PER_PLAYER,
    PER_CAMPAIGN_SLOT,
}

enum class ControlPolicy {
    FLEXIBLE,
    STRICT,
}

enum class SeatRole {
    HUMAN,
    AI,
    SPECTATOR,
}

enum class MatchStatus {
    LOBBY,
    STARTING,
    RUNNING,
    PAUSED,
    SUSPENDED,
    ENDED,
}

data class SeatConfig(
    val seatId: String,
    val participantId: String?,
    val sideId: Int,
    val controlledPlayerIds: Set<Int>,
    val sharedControlGroupId: String?,
    val campaignSlotIds: Set<String>,
    val role: SeatRole,
)

data class MultiplayerRoomConfig(
    val roomId: String,
    val mode: MultiplayerMode,
    val contentRef: MultiplayerContentRef,
    val economyPolicy: EconomyPolicy,
    val controlPolicy: ControlPolicy,
    val seats: List<SeatConfig>,
    val createdByParticipantId: String,
    val protocolVersion: Int,
    val gameBuild: String,
    val contentManifestHash: String,
)

data class PendingCommand(
    val clientMessageId: String,
    val participantId: String,
    val serverSequence: Long,
    val expectedRevision: Long,
    val authorityEpoch: Long,
    val command: GameCommand,
)

data class MultiplayerRuntimeState(
    val status: MatchStatus,
    val revision: Long,
    val authorityParticipantId: String,
    val authorityEpoch: Long,
    val readyParticipantIds: Set<String>,
    val sharedPrestigeAccounts: Map<String, Int>,
    val unitAssignments: Map<Int, String>,
    val pendingCommand: PendingCommand?,
)
