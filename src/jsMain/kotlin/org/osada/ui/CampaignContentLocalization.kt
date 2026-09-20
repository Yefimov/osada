package org.osada.ui

import org.osada.i18n.I18n

/** Localized player-facing campaign metadata; file identity remains the authored JSON filename. */
internal object CampaignContentLocalization {
    private val localizedCampaigns =
        setOf(
            "062d",
            "camp6bn4",
            "camp6bn5",
            "camp6bn9",
            "novemberrevolution",
            "rhu",
        )

    private fun stem(file: String?): String = file?.substringBeforeLast('.')?.lowercase().orEmpty()

    private fun domain(file: String?): String? = stem(file).takeIf { it in localizedCampaigns }?.let { "campaigns/$it" }

    fun ensure(
        file: String?,
        onReady: () -> Unit = {},
    ) {
        val contentDomain = domain(file)
        if (contentDomain == null) onReady() else I18n.ensureDomain(contentDomain, onReady)
    }

    fun intro(
        campaignFile: String?,
        scenarioFile: String?,
        authored: String?,
    ): String? {
        val contentDomain = domain(campaignFile) ?: return authored
        val scenario = scenarioFile?.substringAfterLast('/')?.substringAfterLast('\\')
        val key = scenario?.let { "campaign.${stem(campaignFile)}.scenario.$it.intro" }
        return authored?.let { value ->
            if (key == null) value else I18n.tOrNull(key, domain = contentDomain) ?: value
        }
    }

    fun outcome(
        campaignFile: String?,
        scenarioFile: String?,
        outcome: String,
        authored: String,
    ): String {
        val contentDomain = domain(campaignFile) ?: return authored
        val scenario = scenarioFile?.substringAfterLast('/')?.substringAfterLast('\\')
        val key = scenario?.let { "campaign.${stem(campaignFile)}.scenario.$it.outcome.$outcome" }
        return if (key == null) authored else I18n.tOrNull(key, domain = contentDomain) ?: authored
    }

    fun title(campaign: dynamic): String {
        val fallback = campaign?.title as? String ?: ""
        val key = "campaign.${stem(campaign?.file as? String)}.title"
        return I18n.tOrNull(key) ?: fallback
    }

    fun description(campaign: dynamic): String {
        val fallback = campaign?.desc as? String ?: ""
        val key = "campaign.${stem(campaign?.file as? String)}.description"
        return I18n.tOrNull(key) ?: fallback
    }
}
