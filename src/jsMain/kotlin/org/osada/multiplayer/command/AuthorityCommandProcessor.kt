@file:Suppress("UnusedParameter")

package org.osada.multiplayer.command

import org.osada.multiplayer.model.PendingCommand
import org.osada.multiplayer.sync.MultiplayerSnapshot

sealed interface AuthorityDecision {
    data class Commit(
        val snapshot: MultiplayerSnapshot,
    ) : AuthorityDecision

    data class Reject(
        val rejection: CommandRejection,
    ) : AuthorityDecision
}

class AuthorityCommandProcessor(
    private val validator: GameCommandValidator,
    private val applier: GameCommandApplier,
    private val snapshotFactory: (PendingCommand) -> MultiplayerSnapshot,
) {
    private var restoredRevision = -1L
    private val appliedMessageIds = mutableSetOf<String>()

    fun process(command: PendingCommand): AuthorityDecision {
        if (command.clientMessageId in appliedMessageIds || command.expectedRevision != restoredRevision) {
            return AuthorityDecision.Reject(
                CommandRejection(
                    code = org.osada.multiplayer.protocol.MultiplayerErrorCode.STALE_STATE,
                    message = "Expected revision ${command.expectedRevision}, current revision is $restoredRevision",
                ),
            )
        }
        return when (val validation = validator.validate(command.command, null)) {
            CommandValidation.Accepted -> {
                applier.apply(command.command)
                val snapshot = snapshotFactory(command)
                require(snapshot.revision == restoredRevision + 1)
                appliedMessageIds += command.clientMessageId
                restoredRevision = snapshot.revision
                AuthorityDecision.Commit(snapshot)
            }
            is CommandValidation.Rejected -> AuthorityDecision.Reject(validation.rejection)
        }
    }

    fun restoreRevision(snapshot: MultiplayerSnapshot) {
        restoredRevision = snapshot.revision
        appliedMessageIds.clear()
    }
}
