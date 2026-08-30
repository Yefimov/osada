package org.osada.ui

import org.osada.i18n.I18n

/** Localized player-facing campaign metadata; file identity remains the authored JSON filename. */
internal object CampaignContentLocalization {
    private fun stem(file: String?): String = file?.substringBeforeLast('.')?.lowercase().orEmpty()

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
