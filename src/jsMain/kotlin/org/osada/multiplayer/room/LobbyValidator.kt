@file:Suppress("UnusedParameter")

package org.osada.multiplayer.room

import org.osada.multiplayer.model.MultiplayerParticipant
import org.osada.multiplayer.model.MultiplayerRoomConfig

enum class LobbyValidationError {
    PARTICIPANT_WITHOUT_CONTROL_SCOPE,
    INVALID_SIDE_ASSIGNMENT,
    UNCONTROLLED_REQUIRED_PLAYER,
    INVALID_CAMPAIGN_SLOT_MAPPING,
    UNSUPPORTED_ECONOMY_POLICY,
    PARTICIPANT_NOT_READY,
    CONTENT_MISMATCH,
}

data class LobbyValidationResult(
    val valid: Boolean,
    val errors: List<LobbyValidationError>,
)

class LobbyValidator {
    fun validate(
        config: MultiplayerRoomConfig,
        participants: List<MultiplayerParticipant>,
    ): LobbyValidationResult {
        val errors = mutableListOf<LobbyValidationError>()
        val humanSeats = config.seats.filter { it.role == org.osada.multiplayer.model.SeatRole.HUMAN }
        if (humanSeats.any { it.controlledPlayerIds.isEmpty() }) {
            errors += LobbyValidationError.PARTICIPANT_WITHOUT_CONTROL_SCOPE
        }
        val participantIds = participants.map { it.participantId }.toSet()
        val hasUnassignedSeat =
            humanSeats.any { seat ->
                val participantId = seat.participantId
                participantId == null || participantId !in participantIds
            }
        if (hasUnassignedSeat) {
            errors += LobbyValidationError.PARTICIPANT_WITHOUT_CONTROL_SCOPE
        }
        val sides = humanSeats.map { it.sideId }.distinct()
        val versus = config.mode == org.osada.multiplayer.model.MultiplayerMode.VERSUS_SCENARIO
        val invalidVersusSides = versus && sides.size < 2
        val invalidCooperativeSides = !versus && sides.size > 1
        if (invalidVersusSides || invalidCooperativeSides) {
            errors += LobbyValidationError.INVALID_SIDE_ASSIGNMENT
        }
        val campaignMode = config.mode.name.contains("CAMPAIGN")
        val hasMissingCampaignSlots = humanSeats.any { it.campaignSlotIds.isEmpty() }
        if (campaignMode && hasMissingCampaignSlots) {
            errors += LobbyValidationError.INVALID_CAMPAIGN_SLOT_MAPPING
        }
        if (participants.any { !it.isReady }) errors += LobbyValidationError.PARTICIPANT_NOT_READY
        return LobbyValidationResult(errors.isEmpty(), errors.distinct())
    }
}

class ReadinessCoordinator(
    private val requiredParticipantIds: () -> Set<String>,
    private val mayForceEnd: (String) -> Boolean = { false },
) {
    private val readyParticipantIds = mutableSetOf<String>()

    fun setReady(
        participantId: String,
        ready: Boolean,
    ) {
        require(participantId in requiredParticipantIds())
        if (ready) readyParticipantIds += participantId else readyParticipantIds -= participantId
    }

    fun clearAfterGameCommand() {
        readyParticipantIds.clear()
    }

    fun mayEndTurn(): Boolean {
        val required = requiredParticipantIds()
        return required.isNotEmpty() && readyParticipantIds.containsAll(required)
    }

    fun forceEndTurn(participantId: String) {
        require(mayForceEnd(participantId)) { "Participant may not force the end of turn" }
        readyParticipantIds += requiredParticipantIds()
    }
}
