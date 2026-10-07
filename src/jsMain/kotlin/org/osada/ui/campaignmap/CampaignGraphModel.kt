package org.osada.ui.campaignmap

/** The four campaign outcome grades, in the campaign JSON's own key spelling (`briliant` sic). */
internal enum class OutcomeGrade(
    val key: String,
) {
    BRILLIANT("briliant"),
    VICTORY("victory"),
    TACTICAL("tactical"),
    LOSE("lose"),
    ;

    companion object {
        val WINS = listOf(BRILLIANT, VICTORY, TACTICAL)

        fun byKey(key: String?): OutcomeGrade? = entries.firstOrNull { it.key == key }
    }
}

/** One briefing-dialogue choice that carries a `route` effect: the player picks the next operation. */
internal data class ChoiceRoute(
    val choiceId: String,
    val label: String,
    val hint: String,
    val target: Int,
    /** The dialogue line offering it has authored conditions, so it may not be offered at all. */
    val conditional: Boolean,
)

/**
 * One campaign scenario record, flattened out of campaign JSON so the graph, its layout and its
 * statistics are all testable without a browser or a loaded campaign.
 */
internal data class CampaignScenarioRecord(
    val index: Int,
    val scenarioFile: String,
    val title: String,
    val year: Int?,
    /** Plain-text opening of the (localized) briefing intro; may be empty. */
    val summary: String,
    val gotos: Map<OutcomeGrade, Int>,
    val prestige: Map<OutcomeGrade, Int> = emptyMap(),
    val choices: List<ChoiceRoute> = emptyList(),
)

/**
 * How an edge is drawn. [MAIN] carries every victory the scenario has; the grade styles carry only
 * some of them, which is exactly when the result matters and the edge is labelled.
 */
internal enum class EdgeStyle {
    MAIN,
    BRILLIANT,
    VICTORY,
    TACTICAL,
    LOSE,
    CHOICE,
}

internal data class CampaignMapEdge(
    val from: Int,
    val to: Int,
    /** Grades routed along this edge; empty for a [choice] edge, which every victory follows. */
    val grades: Set<OutcomeGrade>,
    val choice: ChoiceRoute?,
    val style: EdgeStyle,
    /** Points to an operation already on the way here (a cycle): shown as a marker, not a line. */
    val back: Boolean,
)

internal data class CampaignMapNode(
    val id: Int,
    /** The scenario this node is, or null for a campaign ending. */
    val record: CampaignScenarioRecord?,
    /** For an ending: the scenario whose victory ends the campaign here. */
    val finaleOf: Int? = null,
    /** Grades that end the campaign in defeat straight after this scenario. */
    val defeatGrades: Set<OutcomeGrade> = emptySet(),
    /** Grades that send the player into this same scenario again. */
    val retryGrades: Set<OutcomeGrade> = emptySet(),
) {
    val isFinale: Boolean get() = record == null
}

internal data class CampaignGraph(
    val nodes: List<CampaignMapNode>,
    val edges: List<CampaignMapEdge>,
    /** Scenario indexes no transition can reach: left out of [nodes] entirely. */
    val unreachable: List<Int>,
) {
    private val byId = nodes.associateBy { it.id }

    fun node(id: Int): CampaignMapNode? = byId[id]

    fun outgoing(id: Int): List<CampaignMapEdge> = edges.filter { it.from == id }

    companion object {
        const val START = 0

        /** Ending node ids sit above any scenario index a campaign can have. */
        private const val FINALE_ID_BASE = 100_000

        fun finaleId(source: Int): Int = FINALE_ID_BASE + source
    }
}
