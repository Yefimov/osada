package org.osada.ui

import org.osada.ui.briefing.BriefingParsingUtils
import org.osada.ui.briefing.CampaignBriefingCatalog
import org.w3c.xhr.XMLHttpRequest

/**
 * Detects whether a campaign counts as "story" -- has authored dialogue/briefing content for at
 * least one of its scenarios -- purely by scanning its own campaign data against
 * [CampaignBriefingCatalog]. No manual flag lives in campaign data: a scenario gaining a catalog
 * entry (or a campaign JSON gaining an embedded `briefing`/`dialogue`/`dialogues` key) makes its
 * campaign light up here automatically. Each campaign file's data is fetched at most once per
 * session and the result cached; concurrent callers for the same file share one request.
 */
internal object StoryCampaignDetector {
    private val resultCache = mutableMapOf<String, Boolean>()
    private val pendingCallbacks = mutableMapOf<String, MutableList<(Boolean) -> Unit>>()

    fun isStory(
        campaignFile: String,
        onResult: (Boolean) -> Unit,
    ) {
        val cached = resultCache[campaignFile]
        val waiting = pendingCallbacks[campaignFile]
        when {
            cached != null -> onResult(cached)
            waiting != null -> waiting.add(onResult)
            else -> {
                pendingCallbacks[campaignFile] = mutableListOf(onResult)
                fetchAndCompute(campaignFile)
            }
        }
    }

    private fun fetchAndCompute(campaignFile: String) {
        val request = XMLHttpRequest()
        request.onload = { resolve(campaignFile, isStorySuccessResponse(request)) }
        request.onerror = { resolve(campaignFile, false) }
        request.open("GET", "resources/campaigns/data/$campaignFile", true)
        request.send(null)
    }

    private fun isStorySuccessResponse(request: XMLHttpRequest): Boolean {
        val ok = request.status == 200.toShort() || request.status == 0.toShort()
        return ok && computeStory(request.responseText)
    }

    private fun resolve(
        campaignFile: String,
        story: Boolean,
    ) {
        resultCache[campaignFile] = story
        pendingCallbacks.remove(campaignFile).orEmpty().forEach { it(story) }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun computeStory(responseText: String): Boolean =
        try {
            val scenarios: Array<dynamic> = JSON.parse(responseText)
            scenarios.any { entry -> hasStoryContent(entry) }
        } catch (e: Throwable) {
            false
        }

    private fun hasStoryContent(entry: dynamic): Boolean {
        val scenarioFile = (entry.scenario as? String)?.lowercase()
        val catalogHit = scenarioFile != null && scenarioFile in CampaignBriefingCatalog.storyScenarioFiles
        val embedded =
            BriefingParsingUtils.isPresent(entry.briefing) ||
                BriefingParsingUtils.isPresent(entry.dialogue) ||
                BriefingParsingUtils.isPresent(entry.dialogues)
        return catalogHit || embedded
    }
}
