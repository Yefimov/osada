package org.osada.ui.music

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.osada.ui.addTag
import org.osada.ui.byId
import org.w3c.dom.HTMLElement

internal fun musicText(name: String): String = I18n.t("music.$name")

internal fun musicButton(
    parent: HTMLElement,
    name: String,
    action: () -> Unit,
): HTMLElement =
    addTag(parent, "button").apply {
        setAttribute("type", "button")
        setAttribute("data-music-label", name)
        textContent = musicText(name)
        onclick = { action() }
    }

private const val SECONDS_PER_MINUTE = 60

internal fun musicTime(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite()) return "—"
    val whole = seconds.toInt().coerceAtLeast(0)
    return "${whole / SECONDS_PER_MINUTE}:${(whole % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

/** Only the menu surface moves. Picker movement is confined to its reserved header row. */
internal object MusicMenuPlacement {
    private const val DRAG_THRESHOLD = 8.0
    private const val DESKTOP_WIDTH = 640
    private const val FOOTER_GAP = 48.0
    private const val LIMIT_BOTTOM = 3
    private const val EDGE_GAP = 8.0
    private var installed = false
    private var surface = "menu"
    private var dragging = false

    fun bind(
        root: HTMLElement,
        handle: HTMLElement,
    ) {
        var startX = 0.0
        var startY = 0.0
        var left = 0.0
        var top = 0.0
        var pressed = false
        var suppressClick = false
        handle.addEventListener("pointerdown", { event ->
            val raw = event.asDynamic()
            if (raw.pointerType == "mouse" && raw.button == 0 && movable()) {
                pressed = true
                startX = (raw.clientX as Number).toDouble()
                startY = (raw.clientY as Number).toDouble()
                left = root.offsetLeft.toDouble()
                top = root.offsetTop.toDouble()
                handle.asDynamic().setPointerCapture(raw.pointerId)
                event.stopPropagation()
            }
        })
        handle.addEventListener("pointermove", { event ->
            if (pressed) {
                val raw = event.asDynamic()
                val dx = (raw.clientX as Number).toDouble() - startX
                val dy = (raw.clientY as Number).toDouble() - startY
                if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > DRAG_THRESHOLD) dragging = true
                if (dragging) {
                    move(root, left + dx, top + dy)
                    event.preventDefault()
                    event.stopPropagation()
                }
            }
        })
        handle.addEventListener("pointerup", { event ->
            if (pressed && dragging) {
                save(root)
                suppressClick = true
                event.preventDefault()
                event.stopPropagation()
            }
            pressed = false
            dragging = false
        })
        handle.addEventListener("pointercancel", {
            pressed = false
            dragging = false
            place()
        })
        handle.addEventListener("click", { event ->
            if (suppressClick) {
                event.preventDefault()
                event.stopPropagation()
                suppressClick = false
            }
        }, true)
        installResize()
    }

    private fun installResize() {
        if (installed) return
        installed = true
        kotlinx.browser.window.addEventListener("resize", { place() })
        kotlinx.browser.window
            .asDynamic()
            .visualViewport
            ?.addEventListener("resize", { _: dynamic -> place() })
    }

    private fun movable(): Boolean =
        !org.osada.ui.MobileLayoutController.mode.isPhone &&
            kotlinx.browser.window
                .matchMedia("(pointer:fine)")
                .matches &&
            kotlinx.browser.window.innerWidth >= DESKTOP_WIDTH

    fun place() {
        if (dragging) return
        val root = byId("osadaMusicMenu") ?: return
        val picker =
            listOf("smCamp", "smScen")
                .mapNotNull(::byId)
                .firstOrNull { org.osada.ui.isVisible(it.id) }
        surface = if (picker == null) "menu" else "picker"
        val parent = placementParent(picker)
        if (parent != null && root.parentElement != parent) parent.appendChild(root)
        root.setAttribute("data-music-surface", surface)
        root
            .querySelector("[data-music-drag]")
            ?.asDynamic()
            ?.style
            ?.display = if (movable()) "" else "none"
        root.style.left = ""
        root.style.top = ""
        root.style.right = ""
        root.style.bottom = ""
        if (movable()) restorePosition(root)
    }

    private fun placementParent(picker: HTMLElement?): HTMLElement? =
        if (picker != null) {
            byId("${picker.id}MusicSlot") ?: addTag(picker, "div").apply {
                id = "${picker.id}MusicSlot"
                className = "osada-music-picker-slot"
                picker.classList.add("osada-has-music-slot")
            }
        } else if (org.osada.ui.MobileLayoutController.mode.isPhone) {
            byId("smButtons")
        } else {
            byId("startmenu")
        }

    private fun restorePosition(root: HTMLElement) {
        val saved = MusicPreferences.positions[surface]
        val x = MusicPreferences.level(saved?.x)
        val y = MusicPreferences.level(saved?.y)
        val limits = bounds(root)
        val defaultY =
            if (surface ==
                "menu"
            ) {
                (limits[LIMIT_BOTTOM] - FOOTER_GAP).coerceAtLeast(limits[1])
            } else {
                limits[1]
            }
        move(
            root,
            if (x == null || y == null) limits[2] else limits[0] + x * (limits[2] - limits[0]),
            if (x == null || y == null) defaultY else limits[1] + y * (limits[LIMIT_BOTTOM] - limits[1]),
        )
    }

    private fun bounds(root: HTMLElement): DoubleArray {
        val parent = root.parentElement?.getBoundingClientRect() ?: root.getBoundingClientRect()
        val metrics =
            org.osada.ui.ViewportMetricsService
                .measure()
        val left = (metrics.offsetLeft + metrics.safeLeft - parent.left).coerceAtLeast(EDGE_GAP)
        val top = (metrics.offsetTop + metrics.safeTop - parent.top).coerceAtLeast(0.0)
        val right =
            kotlin.math.min(
                parent.width - EDGE_GAP,
                metrics.offsetLeft + metrics.width - metrics.safeRight - parent.left,
            ) -
                root.offsetWidth
        val bottom =
            kotlin.math.min(parent.height, metrics.offsetTop + metrics.height - metrics.safeBottom - parent.top) -
                root.offsetHeight
        return doubleArrayOf(left, top, right.coerceAtLeast(left), bottom.coerceAtLeast(top))
    }

    private fun move(
        root: HTMLElement,
        x: Double,
        y: Double,
    ) {
        val limits = bounds(root)
        root.style.right = "auto"
        root.style.bottom = "auto"
        root.style.left = "${x.coerceIn(limits[0], limits[2])}px"
        val clampedY = y.coerceIn(limits[1], limits[LIMIT_BOTTOM])
        root.style.top = "${clampedY}px"
    }

    private fun save(root: HTMLElement) {
        val limits = bounds(root)
        val saved = js("({})")
        saved.x = (root.offsetLeft - limits[0]) / (limits[2] - limits[0]).coerceAtLeast(1.0)
        saved.y = (root.offsetTop - limits[1]) / (limits[LIMIT_BOTTOM] - limits[1]).coerceAtLeast(1.0)
        MusicPreferences.positions[surface] = saved
        MusicPreferences.save("positions")
    }

    fun reset() {
        MusicPreferences.positions = js("({})")
        MusicPreferences.save("positions")
        place()
    }
}

internal fun musicStatus(): String =
    when {
        !MusicSession.loaded -> musicText("loading_catalog")
        MusicSession.catalogFailed -> musicText("catalog_error")
        MusicSession.status in
            setOf("loading", "buffering", "blocked", "error", "stalled", "interrupted", "transferred") ->
            musicText("status.${MusicSession.status}")
        MusicSession.status == "empty" || (MusicSession.current == null && MusicSession.eligible().isEmpty()) ->
            musicText(
                "empty_queue",
            )
        MusicSession.current == null -> musicText("enable")
        MusicSession.status == "ended" -> musicText("status.ended")
        MusicSession.playingOnce -> musicText("once")
        MusicSession.upcoming().isEmpty() && MusicSession.status != "ended" ->
            if (MusicSession.eligible().size == 1) {
                musicText("single")
            } else {
                musicText("empty_queue")
            }
        else -> musicText("status.${MusicSession.status}")
    }

internal fun musicCanPlay(): Boolean =
    MusicSession.loaded &&
        !MusicSession.catalogFailed &&
        (MusicSession.current in MusicSession.validIds() || MusicSession.eligible().isNotEmpty())

internal fun musicCanNext(): Boolean =
    MusicSession.loaded &&
        !MusicSession.catalogFailed &&
        (MusicSession.current != null || MusicSession.eligible().isNotEmpty())

/** Shared 24px glyph family, decorative SVGs; the controls own accessible names. */
internal object MusicIcons {
    private const val MEDIUM_VOLUME = .33
    private const val HIGH_VOLUME = .66

    fun svg(name: String): String {
        val shape =
            when (name) {
                "play" -> "<path d='M7 4v16l13-8z'/>"
                "pause" -> "<path d='M6 4h4v16H6zM14 4h4v16h-4z'/>"
                "previous" -> "<path d='M4 4h3v16H4zM20 4v16L8 12z'/>"
                "next" -> "<path d='M17 4h3v16h-3zM4 4v16l12-8z'/>"
                "close" -> "<path d='m6 6 12 12M6 18 18 6' fill='none' stroke='currentColor' stroke-width='2'/>"
                "options" ->
                    "<path d='m10 2 4 0 1 3 3 1 3-1 2 4-2 2v3l2 2-2 4-3-1-3 1-1 3h-4l-1-3-3-1-3 1-2-4 2-2" +
                        "v-3L1 9l2-4 3 1 3-1z' fill='none' stroke='currentColor'/>" +
                        "<circle cx='12' cy='12' r='3' fill='none' stroke='currentColor'/>"
                "drag" -> "<path d='M9 5h2v2H9zM14 5h2v2h-2zM9 11h2v2H9zM14 11h2v2h-2zM9 17h2v2H9zM14 17h2v2h-2z'/>"
                "open" -> "<path d='M9 3v13a3 3 0 1 0 2 3V7l8-2v9a3 3 0 1 0 2 3V1z'/>"
                else -> speaker()
            }
        return "<svg viewBox='0 0 24 24' aria-hidden='true' focusable='false' fill='currentColor'>$shape</svg>"
    }

    private fun speaker(): String {
        val level = if (MusicPreferences.muted) 0.0 else MusicPreferences.volume
        val base = "<path d='M3 9h4l5-4v14l-5-4H3z'/>"
        val waves =
            if (level == 0.0) {
                "M16 9l6 6M16 15l6-6"
            } else {
                "M15 9q3 3 0 6" + (if (level > MEDIUM_VOLUME) "M18 6q6 6 0 12" else "") +
                    (if (level > HIGH_VOLUME) "M21 3q9 9 0 18" else "")
            }
        return base + "<path d='$waves' fill='none' stroke='currentColor' stroke-width='1.5' stroke-linecap='round'/>"
    }
}

internal fun musicIcon(
    control: HTMLElement,
    name: String,
    icon: String = name,
) {
    control.setAttribute("data-music-label", name)
    control.setAttribute("data-music-icon", icon)
    control.setAttribute("aria-label", musicText(name))
    control.title = musicText(name)
    control.innerHTML = MusicIcons.svg(icon)
}

/** Rebinds by DOM identity, rather than retaining detached menu trees in subscriptions. */
internal object MusicMiniPlayer {
    fun install() {
        MusicSession.subscribe(::refresh)
        I18n.onLanguageChanged {
            refresh()
            MusicLibraryPresenter.refreshLocalization()
        }
    }

    fun mount(
        id: String,
        menu: Boolean = false,
    ) {
        val root = byId(id) ?: return
        if (root.childElementCount == 0) {
            root.classList.add("osada-music-mini")
            root.setAttribute("role", "group")
            if (menu) {
                musicButton(root, "drag") {}.apply {
                    setAttribute("data-music-drag", "true")
                    musicIcon(this, "drag")
                    MusicMenuPlacement.bind(root, this)
                }
            }
            musicButton(
                root,
                "open",
            ) { MusicLibraryPresenter.open(root.querySelector("[data-music-open]") as? HTMLElement) }
                .apply {
                    setAttribute("data-music-open", "true")
                    bindMusicTitleTooltip(this)
                }
            musicButton(root, "play", MusicSession::toggle).setAttribute("data-music-toggle", "true")
            musicButton(root, "next", MusicSession::next).setAttribute("data-music-next", "true")
        }
        reconcilePlacement()
        refresh()
    }

    fun reconcilePlacement() {
        val menu = byId("osadaMusicMenu") ?: return
        val parent = if (org.osada.ui.MobileLayoutController.mode.isPhone) byId("smButtons") else byId("startmenu")
        if (parent != null && menu.parentElement != parent) parent.appendChild(menu)
        MusicMenuPlacement.place()
    }

    fun refresh() {
        val roots = document.querySelectorAll(".osada-music-mini")
        for (index in 0 until roots.length) {
            val root = roots.item(index) as? HTMLElement ?: continue
            refreshRoot(root)
        }
        byId("musicvolume")?.asDynamic()?.value = MusicPreferences.volume.toString()
    }

    private fun refreshRoot(root: HTMLElement) {
        root.setAttribute("aria-label", musicText("title"))
        val status = musicStatus()
        val track = MusicSession.recording
        root.title = listOfNotNull(track?.displayTitle(), status).joinToString(" · ")
        (root.querySelector("[data-music-open]") as? HTMLElement)?.apply {
            val title = track?.displayTitle() ?: musicText("title")
            if (root.id == "osadaMusicRail") {
                musicIcon(this, "open")
            } else {
                val previous = getAttribute("data-current-title")
                if (previous != title) {
                    classList.remove("osada-music-title-overflow")
                    textContent = ""
                    addTag(this, "span").apply {
                        className = "osada-music-mini-title"
                        textContent = title
                    }
                    setAttribute("data-current-title", title)
                }
                classList.add("osada-music-open")
                onmouseenter = {
                    val span = firstElementChild as? HTMLElement
                    val travel = if (span == null) 0 else span.scrollWidth - span.clientWidth
                    if (travel > 0 &&
                        kotlinx.browser.window
                            .matchMedia(
                                "(hover:hover) and (pointer:fine) and (prefers-reduced-motion:no-preference)",
                            ).matches
                    ) {
                        style.setProperty("--music-title-travel", "${-travel}px")
                        classList.add("osada-music-title-overflow")
                    }
                }
                onmouseleave = { classList.remove("osada-music-title-overflow") }
            }
            setAttribute("aria-label", musicText("open") + ": " + title)
            this.title =
                listOfNotNull(
                    track?.displayTitle(),
                    track?.performer,
                    musicText("open"),
                ).filter(String::isNotBlank).joinToString(" · ")
        }
        (root.querySelector("[data-music-toggle]") as? HTMLElement)?.apply {
            musicIcon(this, if (MusicSession.desiredPlaying) "pause" else "play")
            title += " · $status"
            asDynamic().disabled = !musicCanPlay()
        }
        (root.querySelector("[data-music-next]") as? HTMLElement)?.apply {
            musicIcon(this, "next")
            asDynamic().disabled = !musicCanNext()
        }
    }
}

/** Keyboard access to the complete title/credit without motion or clipped sidebar content. */
private const val MUSIC_TOOLTIP_GAP = 4

private fun bindMusicTitleTooltip(button: HTMLElement) {
    button.onfocus = focus@{
        byId("osadaMusicTitleTooltip")?.remove()
        // Mouse users already get the native `title` on hover, and a clicked button keeps focus, so
        // an unconditional panel doubled the same text. Keyboard focus is what has no hover.
        if (!button.matches(":focus-visible")) return@focus null
        val tooltip = addTag(document.body, "div")
        tooltip.id = "osadaMusicTitleTooltip"
        tooltip.className = "osada-music-title-tooltip"
        tooltip.setAttribute("role", "tooltip")
        tooltip.textContent = button.title
        button.setAttribute("aria-describedby", tooltip.id)
        val rect = button.getBoundingClientRect()
        val metrics =
            org.osada.ui.ViewportMetricsService
                .measure()
        tooltip.style.left =
            "${rect.left.coerceIn(
                metrics.offsetLeft + metrics.safeLeft,
                (metrics.offsetLeft + metrics.width - tooltip.offsetWidth - metrics.safeRight).coerceAtLeast(0.0),
            )}px"
        tooltip.style.top =
            "${(rect.top - tooltip.offsetHeight - MUSIC_TOOLTIP_GAP).coerceAtLeast(
                metrics.offsetTop + metrics.safeTop,
            )}px"
    }
    button.onblur = {
        byId("osadaMusicTitleTooltip")?.remove()
        button.removeAttribute("aria-describedby")
    }
}
