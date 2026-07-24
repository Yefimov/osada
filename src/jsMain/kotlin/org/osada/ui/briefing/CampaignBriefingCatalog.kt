package org.osada.ui.briefing

/**
 * Optional catalogue for campaign-only conversations and operational summaries.
 *
 * Currently supported story campaigns keep their authored briefings embedded directly in their campaign JSON files.
 * No campaign is currently enabled through this catalogue.
 */
internal object CampaignBriefingCatalog {
    private val entries: Map<String, () -> dynamic> = emptyMap()

    fun forScenario(file: String): dynamic = entries[file.lowercase()]?.invoke()

    internal val storyScenarioFiles: Set<String>
        get() = entries.keys
}
