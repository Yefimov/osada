package org.osada.ui.briefing

internal enum class BriefingStage {
    DIALOGUE,
    ORDERS,
}

internal data class BriefingChoice(
    val id: String,
    val text: String,
    val next: String?,
)

internal data class BriefingParticipant(
    val speaker: String,
    val role: String,
    val portrait: String?,
    val side: String,
    val initials: String,
)

internal data class BriefingLine(
    val id: String,
    val speaker: String,
    val role: String,
    val text: String,
    val portrait: String?,
    val side: String,
    val initials: String,
    val next: String?,
    val choices: List<BriefingChoice>,
) {
    fun participant(): BriefingParticipant =
        BriefingParticipant(
            speaker = speaker,
            role = role,
            portrait = portrait,
            side = side,
            initials = initials,
        )
}

internal data class DialogueTurn(
    val participant: BriefingParticipant,
    val text: String,
    val isPlayerResponse: Boolean = false,
)

internal data class BriefingOrders(
    val situation: String = "",
    val mission: String = "",
    val primaryObjectives: List<String> = emptyList(),
    val secondaryObjectives: List<String> = emptyList(),
    val enemyIntelligence: String = "",
    val availableSupport: String = "",
    val notes: String = "",
) {
    fun isEmpty(): Boolean =
        situation.isBlank() &&
            mission.isBlank() &&
            primaryObjectives.isEmpty() &&
            secondaryObjectives.isEmpty() &&
            enemyIntelligence.isBlank() &&
            availableSupport.isBlank() &&
            notes.isBlank()
}

internal data class ScenarioBriefing(
    val title: String,
    val actLabel: String,
    val locationLabel: String,
    val background: String?,
    val dialogue: List<BriefingLine>,
    val player: BriefingParticipant,
    val orders: BriefingOrders,
) {
    fun hasContent(): Boolean = dialogue.isNotEmpty() || !orders.isEmpty()

    fun lineById(id: String?): BriefingLine? = id?.let { target -> dialogue.firstOrNull { it.id == target } }

    fun nextSequential(line: BriefingLine): BriefingLine? {
        val index = dialogue.indexOfFirst { it.id == line.id }
        return if (index >= 0) dialogue.getOrNull(index + 1) else null
    }
}
