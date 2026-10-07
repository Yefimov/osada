package org.osada.ui.campaignmap

import org.osada.campaign.BriefingDynamic
import org.osada.campaign.CampaignEffect
import org.osada.scenario.Campaign
import org.osada.scenario.ScenarioLoader
import org.osada.scenario.ScenarioTextLocalization
import org.osada.ui.CampaignContentLocalization
import org.osada.ui.StartMenuCampaignData
import org.osada.ui.briefing.BriefingLocalization
import org.osada.ui.briefing.BriefingSource
import org.osada.ui.briefing.DialogueParser

/** Everything the campaign screen and the operations map show about one campaign's route. */
internal class CampaignMapData(
    val campaignIndex: Int,
    val campaignFile: String,
    val records: List<CampaignScenarioRecord>,
) {
    val graph: CampaignGraph = CampaignGraphBuilder.build(records)
    val stats: CampaignGraphStats = CampaignGraphStats.of(graph)
    val layout: CampaignMapLayout by lazy { CampaignMapLayout.of(graph) }

    fun record(index: Int): CampaignScenarioRecord? = records.getOrNull(index)
}

/**
 * Reads a campaign's JSON (through [Campaign], which also loads its localized content domain) and
 * the briefing domains of the scenarios that offer a route choice, then flattens it all into
 * [CampaignScenarioRecord]s in the CURRENT language. Re-run it after a language change.
 */
internal object CampaignGraphSource {
    fun load(
        campaignIndex: Int,
        onReady: (CampaignMapData?) -> Unit,
    ) {
        var campaign: Campaign? = null
        campaign =
            Campaign(campaignIndex, StartMenuCampaignData.DIFFICULTY_HISTORICAL) {
                val loaded = campaign?.takeIf { it.isLoaded }
                if (loaded == null) {
                    onReady(null)
                } else {
                    ensureChoiceBriefings(loaded) { onReady(collect(campaignIndex, loaded)) }
                }
            }
    }

    /** Loads, one after another, the briefing translation domains of every scenario that offers a route. */
    private fun ensureChoiceBriefings(
        campaign: Campaign,
        onReady: () -> Unit,
    ) {
        val pending =
            campaign
                .getCampaignData()
                .filter { hasRouteChoice(it) }
                .map { briefingSource(campaign.file, it) }

        fun next(index: Int) {
            if (index >= pending.size) onReady() else BriefingLocalization.ensure(pending[index]) { next(index + 1) }
        }
        next(0)
    }

    private fun hasRouteChoice(raw: dynamic): Boolean = JSON.stringify(raw?.briefing ?: "").contains("\"route\"")

    private fun briefingSource(
        campaignFile: String,
        raw: dynamic,
    ): BriefingSource = BriefingSource(campaignFile, raw.scenario as? String ?: "", "", raw.briefing)

    private fun collect(
        campaignIndex: Int,
        campaign: Campaign,
    ): CampaignMapData {
        val records =
            campaign.getCampaignData().mapIndexed { index, raw -> recordOf(campaign.file, index, raw) }
        return CampaignMapData(campaignIndex, campaign.file, records)
    }

    private fun recordOf(
        campaignFile: String,
        index: Int,
        raw: dynamic,
    ): CampaignScenarioRecord {
        val file = raw.scenario as? String ?: ""
        val listed = ScenarioLoader.getScenarioDataByFileName(file)
        val listedName = listed?.getOrNull(1) as? String
        val authoredIntro = raw.intro as? String
        val intro = CampaignContentLocalization.intro(campaignFile, file, authoredIntro)
        val title = titleOf(file, listedName, authoredIntro, intro)
        return CampaignScenarioRecord(
            index = index,
            scenarioFile = file,
            title = title,
            year = CampaignIntroText.year(authoredIntro) ?: CampaignIntroText.year(listed?.getOrNull(2) as? String),
            summary = CampaignIntroText.summary(intro, title),
            gotos = gradeMap(raw) { it.goto },
            prestige = gradeMap(raw) { it.prestige },
            choices = choicesOf(campaignFile, raw),
        )
    }

    /**
     * The register's (possibly translated) scenario name. A localized campaign intro whose English
     * heading IS that name carries the translated name in its own heading, so it is used instead;
     * a heading that is not the bare name ("BATTLE OF PINDUS - 3 NOV 1940") is never promoted.
     */
    private fun titleOf(
        file: String,
        listedName: String?,
        authoredIntro: String?,
        intro: String?,
    ): String {
        val base = ScenarioTextLocalization.listTitle(file, listedName ?: file.substringBeforeLast('.'))
        val translatedHeading =
            CampaignIntroText.heading(intro).takeIf {
                intro != authoredIntro && listedName != null && CampaignIntroText.heading(authoredIntro) == listedName
            }
        return translatedHeading ?: base
    }

    private fun gradeMap(
        raw: dynamic,
        read: (dynamic) -> dynamic,
    ): Map<OutcomeGrade, Int> =
        OutcomeGrade.entries
            .mapNotNull { grade ->
                // Bracket access on the plain JS object: a Kotlin `.get(...)` call would throw.
                val outcomes: dynamic = raw.outcome
                val branch: dynamic = if (outcomes == null) null else outcomes[grade.key]
                val value = if (branch == null) null else BriefingDynamic.int(read(branch))
                value?.let { grade to it }
            }.toMap()

    private fun choicesOf(
        campaignFile: String,
        raw: dynamic,
    ): List<ChoiceRoute> {
        if (!hasRouteChoice(raw)) return emptyList()
        val source = briefingSource(campaignFile, raw)
        val lines = DialogueParser.parseDialogue(raw.briefing?.dialogue, BriefingLocalization.textResolver(source))
        return lines.flatMap { line ->
            line.choices.flatMap { choice ->
                choice.effects.filterIsInstance<CampaignEffect.Route>().map { route ->
                    ChoiceRoute(choice.id, choice.text, choice.hint, route.scenarioIndex, !line.condition.isEmpty())
                }
            }
        }
    }
}
