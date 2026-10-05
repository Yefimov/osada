package org.osada.ui.music

import org.osada.ui.StartMenuBuilder
import org.osada.ui.addTag
import org.osada.ui.byId
import org.osada.ui.isVisible
import org.w3c.dom.HTMLElement

/** Static native controls; the list presenter updates content independently. */
internal object MusicLibraryBuilder {
    private const val DEFAULT_VOLUME = 0.2

    fun buildTransport(box: HTMLElement) {
        val now = addTag(box, "div").apply { className = "osada-music-current" }
        addTag(now, "strong").id = "osadaMusicNow"
        addTag(now, "div").id = "osadaMusicCredit"
        val progress = addTag(now, "div").apply { className = "osada-music-progress" }
        addTag(progress, "span").id = "osadaMusicElapsed"
        addTag(progress, "input").apply {
            id = "osadaMusicSeek"
            setAttribute("type", "range")
            setAttribute("min", "0")
            setAttribute("step", "0.25")
            setAttribute("aria-label", musicText("seek"))
            oninput = { MusicSession.seek(asDynamic().value.toString().toDoubleOrNull() ?: 0.0) }
        }
        addTag(progress, "span").id = "osadaMusicDuration"
        val row = addTag(now, "div").apply { className = "osada-music-transport" }
        musicButton(row, "previous", MusicSession::previous).apply {
            id = "osadaMusicPrevious"
            musicIcon(this, "previous")
        }
        musicButton(row, "play", MusicSession::toggle).apply {
            id = "osadaMusicToggle"
            musicIcon(this, "play")
        }
        musicButton(row, "next", MusicSession::next).apply {
            id = "osadaMusicNext"
            musicIcon(this, "next")
        }
        val panel = addTag(row, "div").apply { className = "osada-music-volume-inline" }
        musicButton(panel, "mute", MusicSession::mute).apply {
            id = "osadaMusicMute"
            setAttribute("aria-pressed", "false")
            musicIcon(this, "mute", "speaker")
        }
        addTag(panel, "input").apply {
            id = "osadaMusicVolume"
            setAttribute("type", "range")
            setAttribute("min", "0")
            setAttribute("max", "1")
            setAttribute("step", "0.01")
            setAttribute("aria-label", musicText("volume"))
            oninput = { MusicSession.setVolume(asDynamic().value.toString().toDoubleOrNull() ?: DEFAULT_VOLUME) }
        }
        addTag(panel, "span").id = "osadaMusicPercentage"
        musicButton(row, "retry", MusicSession::retry).id = "osadaMusicRetry"
    }

    fun buildBrowser(box: HTMLElement) {
        val browser =
            addTag(box, "div").apply {
                id = "osadaMusicBrowser"
                className = "osada-music-browser"
            }
        val left = addTag(browser, "nav").apply { className = "osada-music-collections" }
        musicLabel(left, "collections")
        musicButton(left, "match_campaign", ::matchSelectedMusicContext).id = "osadaMusicMatch"
        addTag(left, "div").id = "osadaMusicCollections"
        val right = addTag(browser, "div").apply { className = "osada-music-tracks" }
        val tabs = addTag(right, "div").apply { className = "osada-music-tabs" }
        musicButton(tabs, "back") {
            MusicLibraryPresenter.collectionsVisible = true
            MusicLibraryPresenter.refresh(force = true)
        }.id = "osadaMusicBack"
        val searchLabel = addTag(right, "label").apply { id = "osadaMusicSearchLabel" }
        addTag(searchLabel, "span").id = "osadaMusicSearchScope"
        addTag(searchLabel, "input").apply {
            id = "osadaMusicSearch"
            setAttribute("aria-label", musicText("search"))
            setAttribute("type", "search")
            setAttribute("placeholder", musicText("search"))
            oninput = {
                MusicLibraryPresenter.search = asDynamic().value.toString()
                MusicLibraryPresenter.refresh()
            }
        }
        addTag(right, "div").apply {
            id = "osadaMusicList"
            className = "osada-music-list"
        }
    }

    fun buildOptions(box: HTMLElement) {
        val footer = addTag(box, "footer")
        musicCheckbox(footer, "osadaMusicShuffle", "shuffle", MusicPreferences.shuffle) { checked ->
            MusicPreferences.shuffle = checked
            MusicPreferences.save("shuffle")
            MusicSession.reconcile()
        }
        musicCheckbox(footer, "osadaMusicInstrumental", "instrumental", MusicPreferences.instrumental) { checked ->
            MusicPreferences.instrumental = checked
            MusicPreferences.save("instrumental")
            MusicSession.reconcile()
        }
        addTag(footer, "span").id = "osadaMusicCount"
        val options = addTag(footer, "details").apply { className = "osada-music-options" }
        addTag(options, "summary").apply {
            textContent = musicText("options")
            setAttribute("data-music-label", "options")
        }
        val content = addTag(options, "div").apply { className = "osada-music-options-panel" }
        musicCheckbox(content, "osadaMusicResume", "auto_resume", MusicPreferences.autoResume) { checked ->
            MusicPreferences.autoResume = checked
            MusicPreferences.save("autoResume")
        }
        musicLabel(content, "auto_resume_help").className = "osada-music-help"
        musicButton(content, "reset_position", MusicMenuPlacement::reset)
        val summary = options.querySelector("summary") as? HTMLElement
        if (summary != null) {
            musicIcon(summary, "options")
            summary.setAttribute("aria-expanded", "false")
            options.addEventListener("toggle", {
                summary.setAttribute("aria-expanded", (options.asDynamic().open == true).toString())
            })
        }
        addTag(box, "div").apply {
            id = "osadaMusicUndo"
            className = "osada-music-undo"
        }
    }
}

internal fun musicOption(
    parent: HTMLElement,
    value: String,
    key: String,
): HTMLElement =
    addTag(parent, "option").apply {
        setAttribute("value", value)
        setAttribute("data-music-label", key)
        textContent = musicText(key)
    }

internal fun musicLabel(
    parent: HTMLElement,
    key: String,
): HTMLElement =
    addTag(parent, "span").apply {
        setAttribute("data-music-label", key)
        textContent = musicText(key)
    }

internal fun musicCheckbox(
    parent: HTMLElement,
    id: String,
    key: String?,
    checked: Boolean,
    changed: (Boolean) -> Unit,
): HTMLElement {
    val container = if (key != null) addTag(parent, "label") else parent
    val input = addTag(container, "input")
    input.id = id
    input.setAttribute("type", "checkbox")
    input.asDynamic().checked = checked
    input.onchange = { changed(input.asDynamic().checked == true) }
    if (key != null) musicLabel(container, key)
    return input
}

/** In a picker the highlighted entry is the requested context, even before a battle is loaded. */
private fun matchSelectedMusicContext() {
    val target = musicPickerContext()
    if (target == null) MusicSession.matchCampaign() else MusicSession.matchCampaign(target.first, target.second)
}

internal fun musicPickerContext(): Pair<String?, String?>? =
    when {
        isVisible("smCamp") && (byId("smCamp")?.getBoundingClientRect()?.width ?: 0.0) > 0 -> {
            val selected = byId("smCamp")?.asDynamic()?.selectedCampaign as? Int
            val campaign = selected?.let { StartMenuBuilder.campaignList().getOrNull(it)?.file as? String }
            campaign to null
        }
        isVisible("smScen") && (byId("smScen")?.getBoundingClientRect()?.width ?: 0.0) > 0 -> {
            val selected = byId("smScen")?.asDynamic()?.selectedScenario as? Int
            val row = selected?.let { StartMenuBuilder.scenarioList().getOrNull(it) }
            val scenario = if (row == null) null else row[0] as? String
            null to scenario
        }
        else -> null
    }

private const val MUSIC_TAP_SLOP = 8.0

/** A captured scrim drag cannot become a dismissal or a map order when it is released. */
internal fun bindMusicSurface(
    backdrop: HTMLElement,
    close: () -> Unit,
) {
    var tapped = false
    var startX = 0.0
    var startY = 0.0
    backdrop.addEventListener("pointerdown", { event ->
        val raw = event.asDynamic()
        tapped = event.target == backdrop && raw.isPrimary != false
        startX = (raw.clientX as? Number)?.toDouble() ?: 0.0
        startY = (raw.clientY as? Number)?.toDouble() ?: 0.0
        if (tapped) backdrop.asDynamic().setPointerCapture(raw.pointerId)
        event.stopPropagation()
    })
    backdrop.addEventListener("pointermove", { event ->
        val raw = event.asDynamic()
        val dx = kotlin.math.abs(((raw.clientX as? Number)?.toDouble() ?: startX) - startX)
        val dy = kotlin.math.abs(((raw.clientY as? Number)?.toDouble() ?: startY) - startY)
        if (dx > MUSIC_TAP_SLOP || dy > MUSIC_TAP_SLOP) tapped = false
        event.stopPropagation()
    })
    backdrop.addEventListener("pointerup", { event ->
        val raw = event.asDynamic()
        val point =
            kotlinx.browser.document
                .asDynamic()
                .elementFromPoint(raw.clientX, raw.clientY)
        if (event.target == backdrop && tapped && point == backdrop) close()
        tapped = false
        event.stopPropagation()
    })
    backdrop.addEventListener("pointercancel", {
        tapped = false
        it.stopPropagation()
    })
    listOf("click", "wheel", "contextmenu").forEach { type ->
        backdrop.addEventListener(type, { it.stopPropagation() })
    }
}
