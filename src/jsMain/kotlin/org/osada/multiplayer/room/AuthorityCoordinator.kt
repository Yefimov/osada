@file:Suppress("UnusedParameter")

package org.osada.multiplayer.room

import org.osada.multiplayer.model.MatchStatus
import org.osada.multiplayer.model.MultiplayerRuntimeState
import org.osada.multiplayer.model.PendingCommand
import org.osada.multiplayer.sync.MultiplayerSnapshot

class AuthorityCoordinator(
    initialState: MultiplayerRuntimeState,
) {
    private var state = initialState
    private val rejectedMessageIds = mutableSetOf<String>()

    fun assignAuthority(participantId: String) {
        require(participantId.isNotBlank())
        state =
            state.copy(
                authorityParticipantId = participantId,
                authorityEpoch = state.authorityEpoch + 1,
                status = MatchStatus.PAUSED,
                pendingCommand = null,
            )
    }

    fun queue(command: PendingCommand) {
        check(state.status == MatchStatus.RUNNING)
        check(state.pendingCommand == null) { "A command is already awaiting authority" }
        require(command.expectedRevision == state.revision)
        require(command.authorityEpoch == state.authorityEpoch)
        require(command.clientMessageId !in rejectedMessageIds)
        state = state.copy(pendingCommand = command)
    }

    fun commit(snapshot: MultiplayerSnapshot) {
        val pending = requireNotNull(state.pendingCommand)
        require(snapshot.revision == state.revision + 1)
        require(snapshot.authorityEpoch == state.authorityEpoch)
        require(snapshot.multiplayerState.pendingCommand == null)
        state =
            snapshot.multiplayerState.copy(
                status = MatchStatus.RUNNING,
                pendingCommand = null,
            )
        rejectedMessageIds -= pending.clientMessageId
    }

    fun reject(
        clientMessageId: String,
        reasonCode: String,
    ) {
        val pending = state.pendingCommand
        require(pending == null || pending.clientMessageId == clientMessageId)
        rejectedMessageIds += clientMessageId
        state = state.copy(pendingCommand = null)
    }

    fun beginMigration() {
        state =
            state.copy(
                status = MatchStatus.PAUSED,
                authorityEpoch = state.authorityEpoch + 1,
                pendingCommand = null,
            )
    }

    fun confirmAuthorityReady(
        participantId: String,
        revision: Long,
        stateHash: String,
    ) {
        require(participantId == state.authorityParticipantId)
        require(revision == state.revision)
        require(stateHash.isNotBlank())
        state = state.copy(status = MatchStatus.RUNNING)
    }

    fun runtimeState(): MultiplayerRuntimeState = state
}
