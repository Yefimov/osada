package org.osada.ui

import org.osada.ui.briefing.BriefingLine
import org.osada.ui.briefing.BriefingParser
import org.osada.ui.briefing.CampaignBriefingCatalog
import org.w3c.xhr.XMLHttpRequest

/**
 * Detects whether a campaign counts as "story" -- has authored dialogue content for at least one
 * of its scenarios -- purely by scanning its own campaign data against
 * [CampaignBriefingCatalog]. No manual flag lives in campaign data: a scenario gaining a catalog
 * entry (or a campaign JSON gaining parseable dialogue) makes its campaign light up here
 * automatically. Each campaign file's data is fetched at most once per session and the result
 * cached; concurrent callers for the same file share one request.
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
    internal fun computeStory(responseText: String): Boolean =
        try {
            val scenarios: Array<dynamic> = JSON.parse(responseText)
            scenarios.any { entry -> hasStoryContent(entry) }
        } catch (e: Throwable) {
            false
        }

    private fun hasStoryContent(entry: dynamic): Boolean {
        val scenarioFile = (entry.scenario as? String)?.lowercase()
        val catalogHit = scenarioFile != null && scenarioFile in CampaignBriefingCatalog.storyScenarioFiles
        val authoredDialogue =
            BriefingParser
                .parse(scenarioTitle = "", rawData = entry)
                .dialogue
                .any { !isPathSelectionPrompt(it) }
        return catalogHit || authoredDialogue
    }

    /**
     * A branch node's own prompt is NOT story content.
     *
     * `tools/og-import/deploy_campaigns.py` emits one dialogue line per OG choice node, so that the
     * player can pick a path, and marks it `speaker = "General Staff"` / `role = "Path selection"`
     * precisely because no character is speaking — it is a menu OG renders as a system screen. Every
     * imported branching campaign therefore has "dialogue" whether or not anyone wrote a word of it,
     * which is what put the story badge on "Forward, Comrade!", "Greece: Resistance and Civil War"
     * and "Sim Pobedishi!" — three campaigns with no authored narrative at all.
     *
     * Matched on the generator's own two marker fields rather than on the presence of `choices`: an
     * authored conversation may legitimately branch too, and must keep counting as story.
     */
    private fun isPathSelectionPrompt(line: BriefingLine): Boolean =
        line.role.equals(PATH_SELECTION_ROLE, ignoreCase = true) &&
            line.speaker.equals(PATH_SELECTION_SPEAKER, ignoreCase = true)

    private const val PATH_SELECTION_ROLE = "Path selection"
    private const val PATH_SELECTION_SPEAKER = "General Staff"
}
