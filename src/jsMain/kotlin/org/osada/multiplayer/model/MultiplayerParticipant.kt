@file:Suppress("UnusedParameter")

package org.osada.multiplayer.model

enum class ParticipantConnectionState {
    CONNECTED,
    UNSTABLE,
    RECONNECTING,
    DISCONNECTED,
}

data class MultiplayerParticipant(
    val participantId: String,
    val displayName: String,
    val seatId: String?,
    val isHost: Boolean,
    val isReady: Boolean,
    val connectionState: ParticipantConnectionState,
)

data class ControlScope(
    val playerIds: Set<Int>,
    val unitIds: Set<Int> = emptySet(),
    val sharedControlGroupIds: Set<String> = emptySet(),
)

enum class PlayerExecution {
    LOCAL_HUMAN,
    REMOTE_HUMAN,
    AUTHORITY_AI,
    REMOTE_AI,
}

data class PlayerControlPlan(
    val executionByPlayerId: Map<Int, PlayerExecution>,
    val participantScopes: Map<String, ControlScope>,
)

class MultiplayerSession(
    val participantId: String,
    val roomConfig: MultiplayerRoomConfig,
    val controlPlan: PlayerControlPlan,
    runtimeState: MultiplayerRuntimeState? = null,
) {
    var runtimeState: MultiplayerRuntimeState? = runtimeState
        private set

    fun mayExecuteAuthoritativeSimulation(): Boolean =
        runtimeState?.let {
            it.status == MatchStatus.RUNNING && it.authorityParticipantId == participantId
        } ?: false

    fun controlsPlayer(playerId: Int): Boolean =
        controlPlan
            .participantScopes[participantId]
            ?.playerIds
            ?.contains(playerId) == true

    @Suppress("ReturnCount")
    fun controlsUnit(
        playerId: Int,
        unitId: Int,
    ): Boolean {
        if (!controlsPlayer(playerId)) return false
        val scope = controlPlan.participantScopes[participantId] ?: return false
        if (scope.unitIds.isNotEmpty() && unitId !in scope.unitIds) return false
        val assignedParticipant = runtimeState?.unitAssignments?.get(unitId)
        return roomConfig.controlPolicy == ControlPolicy.FLEXIBLE ||
            assignedParticipant == null ||
            assignedParticipant == participantId
    }

    fun isPlayerLocallyActionable(playerId: Int): Boolean {
        val execution = controlPlan.executionByPlayerId[playerId]
        return execution == PlayerExecution.LOCAL_HUMAN &&
            controlsPlayer(playerId) &&
            runtimeState?.status == MatchStatus.RUNNING &&
            runtimeState?.pendingCommand == null
    }

    fun updateRuntimeState(state: MultiplayerRuntimeState) {
        require(state.revision >= (runtimeState?.revision ?: -1L)) {
            "Multiplayer revision cannot move backwards"
        }
        runtimeState = state
    }
}
