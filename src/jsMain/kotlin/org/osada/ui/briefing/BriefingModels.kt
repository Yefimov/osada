package org.osada.ui.briefing

import org.osada.campaign.CampaignCondition
import org.osada.campaign.CampaignEffect

internal enum class BriefingStage {
    DIALOGUE,
    ORDERS,
}

/**
 * Scenario-derived header/orders facts passed alongside the authored briefing data. The
 * [ordersText] is read from the scenario's own description — the SAME field the standalone
 * scenario-start message shows — so the concise objective text stays single-sourced in
 * scenario data and is never copied into campaign/briefing content.
 */
internal data class ScenarioFacts(
    val title: String,
    val dateLabel: String,
    val sidesLabel: String,
    val ordersText: String,
)

internal data class BriefingChoice(
    val id: String,
    val text: String,
    val next: String?,
    /** Consequences committed once, when the player actually selects this branch. */
    val effects: List<CampaignEffect> = emptyList(),
    /**
     * Optional authored one-line preview of what taking this branch means, e.g.
     * "Command will note your caution". Qualitative on purpose: it may gesture at long-term
     * standing without naming the flag or route it sets.
     *
     * When blank, `BriefingChoicePreview` falls back to a generated summary of the IMMEDIATE
     * game-mechanic effects only. Narrative consequences (`setFlag`/`clearFlag`/`route`) are
     * never disclosed either way — that is the whole point of authoring a hint instead.
     */
    val hint: String = "",
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
    /**
     * Facts this line requires. [CampaignCondition.EMPTY] — what every condition-free line parses
     * to — always matches, which is why pre-existing dialogue keeps displaying unchanged.
     */
    val condition: CampaignCondition = CampaignCondition.EMPTY,
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
