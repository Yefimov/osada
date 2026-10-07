package org.osada.ui.music

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.ui.ConfirmCard
import org.osada.ui.HeroPromotionPresenter
import org.osada.ui.MessageDialogs
import org.osada.ui.UIBuilder
import org.osada.ui.ViewportMetrics
import org.osada.ui.ViewportMetricsService
import org.osada.ui.addTag
import org.osada.ui.byId
import org.osada.ui.isScenarioBriefingVisible
import org.osada.ui.isVisible
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/** Presentation survives menu rebuilds; audio survives presentation. Keyboard belongs to CommandRouter. */
internal object MusicLibraryPresenter {
    private const val PROGRESS_UPDATE_MS = 250
    private const val SINGLE_PANE_WIDTH = 640
    private const val SHORT_VIEWPORT_HEIGHT = 480
    private var root: HTMLElement? = null
    private var dialog: HTMLElement? = null
    private var opener: HTMLElement? = null
    private var interval = 0
    private val inertElements = mutableListOf<HTMLElement>()
    private var viewSignature = ""
    var search = ""
    var collectionsVisible = true

    fun install() {
        MusicSession.subscribe { refresh() }
    }

    fun isOpen(): Boolean = root?.style?.display == "flex"

    fun open(button: HTMLElement?) {
        if (isOpen() || musicDecisionOpen()) return
        MusicSession.beginEditing(musicPickerContext())
        opener = button ?: document.activeElement as? HTMLElement
        if (root == null) build()
        MessageDialogs.suspendDynamicMessages("music")
        val children = document.body?.children
        if (children != null) {
            for (index in 0 until children.length) {
                val child = children.item(index) as? HTMLElement ?: continue
                if (child != root && child.asDynamic().inert != true) {
                    child.asDynamic().inert = true
                    inertElements.add(child)
                }
            }
        }
        root?.style?.display = "flex"
        resize(ViewportMetricsService.current)
        refresh(force = true)
        byId("osadaMusicClose")?.focus()
        interval =
            window.setInterval({
                if (document.asDynamic().visibilityState == "visible") MusicLibraryState.updateProgress()
            }, PROGRESS_UPDATE_MS)
    }

    fun close(restoreFocus: Boolean = true) {
        if (!isOpen()) return
        MusicSession.endEditing()
        root?.style?.display = "none"
        window.clearInterval(interval)
        inertElements.forEach { it.asDynamic().inert = false }
        inertElements.clear()
        MessageDialogs.resumeDynamicMessages("music")
        if (restoreFocus) {
            val actual =
                opener?.takeIf { it.isConnected && it.getBoundingClientRect().width > 0 }
                    ?: visibleMusicOpener()
            actual?.focus()
        }
        opener = null
    }

    fun yieldToDecision() {
        close(restoreFocus = true)
    }

    /** Called by the single document router even for Escape in a search field/native range. */
    fun handleKey(event: Event): Boolean {
        if (!isOpen()) return false
        val raw = event.asDynamic()
        when (raw.key as? String) {
            "Escape" -> {
                event.preventDefault()
                val details = dialog?.querySelector("details[open]") as? HTMLElement
                if (details != null) details.removeAttribute("open") else close()
            }
            "Tab" -> trapTab(event)
        }
        return true
    }

    private fun trapTab(event: Event) {
        val nodes =
            dialog?.querySelectorAll("button:not(:disabled),input:not(:disabled),select:not(:disabled),summary")
                ?: return
        val visible =
            (0 until nodes.length)
                .mapNotNull { nodes.item(it) as? HTMLElement }
                .filter { it.getBoundingClientRect().width > 0 }
        val first = visible.firstOrNull() ?: return
        val last = visible.last()
        val backwards = event.asDynamic().shiftKey == true
        if (backwards && document.activeElement == first) {
            event.preventDefault()
            last.focus()
        } else if (!backwards && document.activeElement == last) {
            event.preventDefault()
            first.focus()
        }
    }

    fun resize(metrics: ViewportMetrics) {
        val box = root ?: return
        box.style.left = "${metrics.offsetLeft + metrics.safeLeft}px"
        box.style.top = "${metrics.offsetTop + metrics.safeTop}px"
        box.style.width = "${(metrics.width - metrics.safeLeft - metrics.safeRight).coerceAtLeast(0.0)}px"
        box.style.height = "${(metrics.height - metrics.safeTop - metrics.safeBottom).coerceAtLeast(0.0)}px"
        box.classList.toggle(
            "osada-music-narrow",
            metrics.width - metrics.safeLeft - metrics.safeRight < SINGLE_PANE_WIDTH,
        )
        box.classList.toggle("osada-music-short", metrics.height < SHORT_VIEWPORT_HEIGHT)
    }

    private fun build() {
        val body = document.body ?: return
        val backdrop = addTag(body, "div")
        backdrop.id = "osadaMusicLibrary"
        backdrop.className = "osada-music-backdrop"
        backdrop.style.display = "none"
        root = backdrop
        val box = addTag(backdrop, "section")
        dialog = box
        box.className = "osada-music-library"
        box.setAttribute("role", "dialog")
        box.setAttribute("aria-modal", "true")
        box.setAttribute("aria-labelledby", "osadaMusicTitle")
        val header = addTag(box, "header")
        addTag(header, "h2").apply {
            id = "osadaMusicTitle"
            textContent = musicText("title")
        }
        musicButton(header, "close") { close() }.apply {
            id = "osadaMusicClose"
            musicIcon(this, "close")
        }
        MusicLibraryBuilder.buildTransport(box)
        MusicLibraryBuilder.buildBrowser(box)
        MusicLibraryBuilder.buildOptions(box)
        val live = addTag(box, "div")
        live.id = "osadaMusicStatus"
        live.className = "osada-music-status"
        live.setAttribute("aria-live", "polite")
        bindMusicSurface(backdrop) { close() }
    }

    fun refreshLocalization() {
        val box = dialog ?: return
        val labels = box.querySelectorAll("[data-music-label]")
        for (index in 0 until labels.length) {
            val node = labels.item(index) as? HTMLElement ?: continue
            val name = node.getAttribute("data-music-label") ?: "title"
            val icon = node.getAttribute("data-music-icon")
            if (icon != null) musicIcon(node, name, icon) else node.textContent = musicText(name)
        }
        byId("osadaMusicTitle")?.textContent = musicText("title")
        byId("osadaMusicSearch")?.setAttribute("placeholder", musicText("search"))
        byId("osadaMusicSearch")?.setAttribute("aria-label", musicText("search"))
        byId("osadaMusicVolume")?.setAttribute("aria-label", musicText("volume"))
        byId("osadaMusicSeek")?.setAttribute("aria-label", musicText("seek"))
        refresh(force = true)
    }

    fun refresh(force: Boolean = false) {
        if (!isOpen()) return
        MusicLibraryState.refresh()
        val signature =
            listOf(
                search,
                MusicPreferences.inspected,
                MusicPreferences.selected,
                MusicPreferences.instrumental,
                MusicSession.current,
                MusicSession.upcoming(),
                MusicSession.status,
                MusicSession.loaded,
                MusicSession.undoSelection,
            ).toString()
        if (force || signature != viewSignature) {
            viewSignature = signature
            MusicLibraryViews.renderLists()
        }
    }
}

private fun musicDecisionOpen(): Boolean {
    val gameDecision =
        UIBuilder.isScenarioBriefingVisible() ||
            HeroPromotionPresenter.isOpen() ||
            ConfirmCard.isOpen() ||
            org.osada.ui.campaignmap.CampaignMapPresenter
                .isOpen()
    val mobileDecision = isVisible("osadaOrientationGate") || isVisible("osadaGestureTutorial")
    return gameDecision || mobileDecision || isVisible("ui-message")
}

private fun visibleMusicOpener(): HTMLElement? {
    val nodes = document.querySelectorAll(".osada-music-mini [data-music-open]")
    return (0 until nodes.length)
        .mapNotNull { nodes.item(it) as? HTMLElement }
        .firstOrNull { it.getBoundingClientRect().width > 0 }
}
