package org.osada.ui.campaignmap

/** One finished battle of a run, as the save's `campaign.narrative.outcomes` records it. */
internal data class PlayedOutcome(
    val scenarioFile: String,
    val grade: OutcomeGrade,
    /** The scenario the campaign went on to, or null where it ended. */
    val nextFile: String?,
)

/** The player's own way through the map: what they played, which edges they took, where they stand. */
internal data class CampaignRunTrace(
    val played: Map<Int, OutcomeGrade>,
    val travelled: Set<Int>,
    /** The operation the run stands at; null when there is no run or it has finished. */
    val current: Int?,
    /** The scenario whose loss ended the run, if it ended that way. */
    val defeatedAt: Int?,
) {
    val isEmpty: Boolean get() = played.isEmpty() && current == null

    companion object {
        val NONE = CampaignRunTrace(emptyMap(), emptySet(), null, null)

        fun of(
            graph: CampaignGraph,
            outcomes: List<PlayedOutcome>,
            current: Int?,
        ): CampaignRunTrace {
            val indexOf =
                graph.nodes
                    .mapNotNull { node -> node.record?.let { it.scenarioFile.lowercase() to it.index } }
                    .toMap()
            val played = mutableMapOf<Int, OutcomeGrade>()
            val travelled = mutableSetOf<Int>()
            var defeatedAt: Int? = null
            outcomes.forEach { outcome ->
                val from = indexOf[outcome.scenarioFile.lowercase()] ?: return@forEach
                played[from] = outcome.grade
                val to = outcome.nextFile?.let { indexOf[it.lowercase()] }
                when {
                    to == from -> Unit
                    to != null -> edgeTaken(graph, from, to, outcome.grade)?.let { travelled += it }
                    outcome.grade == OutcomeGrade.LOSE -> defeatedAt = from
                    else -> edgeTaken(graph, from, CampaignGraph.finaleId(from), outcome.grade)?.let { travelled += it }
                }
            }
            return CampaignRunTrace(played, travelled, current?.takeIf { graph.node(it) != null }, defeatedAt)
        }

        /**
         * The battle that ended a finished run. `Game.continueCampaign` writes no snapshot once a
         * campaign is over -- it only marks the index row completed with the final [outcomeKey] -- so
         * the stored narrative stops one battle short. [scenarioIndex] is the row's
         * `campaignScenario`, still the last battle's since its autosaves were the last written.
         */
        fun finalOutcome(
            graph: CampaignGraph,
            scenarioIndex: Int,
            outcomeKey: String,
        ): PlayedOutcome? {
            val grade = OutcomeGrade.byKey(outcomeKey)
            val file = graph.node(scenarioIndex)?.record?.scenarioFile
            return if (grade == null || file == null) null else PlayedOutcome(file, grade, nextFile = null)
        }

        /** The edge a [grade] result took from [from] to [to]: its own grade edge first, else the
         *  choice that routed it. */
        private fun edgeTaken(
            graph: CampaignGraph,
            from: Int,
            to: Int,
            grade: OutcomeGrade,
        ): Int? {
            val candidates = graph.edges.withIndex().filter { it.value.from == from && it.value.to == to }
            return (candidates.firstOrNull { grade in it.value.grades } ?: candidates.firstOrNull())?.index
        }
    }
}
