package org.osada.ui

import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * Story-campaign marker (brass open-book icon, on the row and in the dossier header) and the
 * "Story only" filter chip on the campaign register. Split out of [StartMenuCampaignScreen]
 * purely to keep that object within the project's function-count limits. A campaign counts as
 * "story" iff [StoryCampaignDetector] finds authored dialogue/briefing content for it -- there is
 * no manual flag in campaign data, so newly-authored content lights the marker automatically.
 */
internal object StartMenuCampaignStory {
    private const val STORY_TOOLTIP = "Story campaign: dialogue & briefings"
    private const val ICON_SVG =
        "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">" +
            "<path d=\"M12 5C10 3.4 7 3 3 3v15c4 0 7 .4 9 2 2-1.6 5-2 9-2V3c-4 0-7 .4-9 2z\" " +
            "fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linejoin=\"round\"/>" +
            "<path d=\"M12 5v15\" stroke=\"currentColor\" stroke-width=\"1.6\"/>" +
            "</svg>"

    /** Marks [row] with the story icon once (if ever) [StoryCampaignDetector] resolves it as
     *  such, then re-applies the current filter/sort so a "Story only" toggle already on picks
     *  up the newly-known row without the player having to touch the chip again. */
    fun applyRowBadge(
        row: HTMLElement,
        list: HTMLElement,
        campaignFile: String?,
    ) {
        if (campaignFile.isNullOrBlank()) return
        StoryCampaignDetector.isStory(campaignFile) { isStory ->
            if (isStory) {
                row.asDynamic().storyFlag = true
                row.appendChild(icon("osadaStoryBadge"))
                StartMenuListToolbar.applyListView(list)
            }
        }
    }

    /** Marks the dossier header with the same icon when the selected campaign is a story
     *  campaign. [headText] must expose `currentCampaignFile` (set by the caller before calling
     *  this) so a slow async result can't paint the badge onto a since-reselected campaign. */
    fun applyDossierBadge(
        headText: HTMLElement,
        campaignFile: String?,
    ) {
        headText.querySelector(".osadaStoryBadge")?.let { headText.removeChild(it) }
        if (campaignFile.isNullOrBlank()) return
        headText.asDynamic().currentCampaignFile = campaignFile
        StoryCampaignDetector.isStory(campaignFile) { isStory ->
            if (isStory && headText.asDynamic().currentCampaignFile == campaignFile) {
                headText.appendChild(icon("osadaStoryBadge osadaStoryBadge--dossier"))
            }
        }
    }

    private fun icon(className: String): HTMLElement {
        val span = addTag(null, "span")
        span.className = className
        span.title = STORY_TOOLTIP
        span.innerHTML = ICON_SVG
        return span
    }

    /** "Story only" toggle chip (default off), combining with the existing side filter/search/
     *  sort via the same [StartMenuListToolbar.applyListView] pass -- never reorders the list. */
    fun buildStoryOnlyChip(
        chipRow: HTMLElement,
        list: HTMLElement,
    ) {
        val chip = addTag(chipRow, "div")
        chip.className = "osada-seg"
        chip.textContent = "Story only"
        chip.title = "Show only campaigns with authored dialogue & briefings"
        list.asDynamic().storyOnly = false
        chip.onclick = { _: MouseEvent ->
            val next = !(list.asDynamic().storyOnly as? Boolean ?: false)
            list.asDynamic().storyOnly = next
            chip.classList.toggle("osada-seg--on", next)
            StartMenuListToolbar.applyListView(list)
        }
    }
}
