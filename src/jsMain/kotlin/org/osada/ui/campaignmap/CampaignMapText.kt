package org.osada.ui.campaignmap

import org.osada.i18n.CalendarText
import org.osada.i18n.I18n

/** Every player-facing string of the campaign route views, so their wording lives in the bundles only. */
internal object CampaignMapText {
    fun t(
        key: String,
        args: Map<String, Any?> = emptyMap(),
    ): String = I18n.t("campaign.map.$key", args)

    /** "Brilliant Victory" etc. -- the same names the end-of-battle dialog uses. */
    fun gradeName(grade: OutcomeGrade): String =
        I18n.t(
            when (grade) {
                OutcomeGrade.BRILLIANT -> "game.outcome.brilliant"
                OutcomeGrade.VICTORY -> "game.outcome.victory"
                OutcomeGrade.TACTICAL -> "game.outcome.tactical"
                OutcomeGrade.LOSE -> "game.outcome.lose"
            },
        )

    /** The short form an edge chip carries. */
    fun gradeShort(grade: OutcomeGrade): String = t("grade.${grade.name.lowercase()}")

    /** An edge's chip text, or null for a main-line edge, which needs none. */
    fun edgeLabel(edge: CampaignMapEdge): String? =
        when {
            edge.choice != null -> edge.choice.label
            edge.style == EdgeStyle.MAIN -> null
            else -> OutcomeGrade.entries.filter { it in edge.grades }.joinToString(" · ") { gradeShort(it) }
        }

    fun year(year: Int?): String = year?.let(CalendarText::year).orEmpty()

    /** "Your current operation" / "Played: Victory", or null for an operation the run has not touched. */
    fun status(
        trace: CampaignRunTrace,
        id: Int,
    ): String? {
        val played = trace.played[id]
        return when {
            trace.current == id -> t("legend.current")
            played != null -> t("detail.played", mapOf("outcome" to gradeName(played)))
            else -> null
        }
    }

    /** "43 operations · 9–25 per playthrough · 4 branch points · 5 endings". */
    fun statsParts(stats: CampaignGraphStats): List<String> {
        val parts = mutableListOf(I18n.plural("campaign.row.operations", stats.operations))
        val shortest = stats.shortest
        val longest = stats.longest
        if (shortest != null && longest != null) {
            val low = I18n.formatNumber(shortest)
            val range = if (shortest == longest) low else "$low–${I18n.formatNumber(longest)}"
            parts += I18n.plural("campaign.map.stats.length", longest, mapOf("range" to range))
        }
        parts +=
            if (stats.forks.isEmpty()) t("stats.linear") else I18n.plural("campaign.map.stats.forks", stats.forks.size)
        if (stats.finales > 1) parts += I18n.plural("campaign.map.stats.finales", stats.finales)
        return parts
    }

    fun prestige(amount: Int?): String? =
        amount?.takeIf { it > 0 }?.let { t("detail.prestige", mapOf("amount" to I18n.formatNumber(it))) }
}
