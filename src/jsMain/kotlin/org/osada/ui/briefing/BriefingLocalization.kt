package org.osada.ui.briefing

import org.osada.i18n.I18n

/** Stable identity and untouched authored data needed to rebuild a briefing in another locale. */
internal data class BriefingSource(
    val campaignFile: String,
    val scenarioFile: String,
    val scenarioTitle: String,
    val rawData: dynamic,
) {
    val domain: String = BriefingLocalization.domain(campaignFile, scenarioFile)
}

/** Resolves one display string while leaving dialogue ids, conditions and effects untouched. */
internal fun interface BriefingTextResolver {
    fun resolve(
        key: String,
        fallback: String,
    ): String
}

internal object BriefingLocalization {
    private val localizedCampaigns = setOf("novemberrevolution", "rhu", "camp6bn4", "camp6bn9")
    private val sourceText = BriefingTextResolver { _, fallback -> fallback }

    fun sourceTextResolver(): BriefingTextResolver = sourceText

    fun domain(
        campaignFile: String,
        scenarioFile: String,
    ): String = "briefings/${fileStem(campaignFile)}/${fileStem(scenarioFile)}"

    fun ensure(
        source: BriefingSource,
        onReady: () -> Unit,
    ) {
        if (supports(source)) I18n.ensureDomain(source.domain, onReady) else onReady()
    }

    fun parse(source: BriefingSource): ScenarioBriefing {
        val resolver =
            if (supports(source)) {
                BriefingTextResolver { key, fallback ->
                    I18n.tOrNull(key, domain = source.domain) ?: fallback
                }
            } else {
                sourceText
            }
        return CampaignDialogueFilter.apply(
            BriefingParser.parse(resolver.resolve("scenario.title", source.scenarioTitle), source.rawData, resolver),
        )
    }

    fun localizeFacts(
        source: BriefingSource,
        facts: ScenarioFacts,
    ): ScenarioFacts =
        if (supports(source)) {
            facts.copy(
                title = resolve(source.domain, "scenario.title", facts.title),
                ordersText = resolve(source.domain, "intro.text", facts.ordersText),
            )
        } else {
            facts
        }

    fun resolve(
        domain: String,
        key: String,
        fallback: String,
    ): String = I18n.tOrNull(key, domain = domain) ?: fallback

    private fun fileStem(file: String): String =
        file
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringBeforeLast('.')
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "-")

    private fun supports(source: BriefingSource): Boolean = fileStem(source.campaignFile) in localizedCampaigns
}
