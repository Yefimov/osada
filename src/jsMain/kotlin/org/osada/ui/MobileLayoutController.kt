package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.GameHolder
import org.osada.uiSettings

/**
 * The single owner of "which layout is the game in right now" (spec §9).
 *
 * It watches the capability media queries, the Visual Viewport, orientation, fullscreen and the
 * HUD region sizes, coalesces every one of those into a single `requestAnimationFrame`, and
 * publishes the result as semantic classes on `<body>`. Every other builder styles itself from
 * those classes instead of repeating its own width threshold — the duplicated-`window.innerWidth`
 * problem the audit calls out (MOB-AUDIT-007) has exactly one fix, which is this object.
 */
internal object MobileLayoutController {
    private const val MEDIA_COARSE = "(pointer: coarse)"
    private const val MEDIA_NO_HOVER = "(hover: none)"
    private const val MEDIA_ANY_FINE = "(any-pointer: fine)"
    private const val MEDIA_ANY_HOVER = "(any-hover: hover)"

    private var installed = false
    private var frameHandle = 0
    private var lastWidth = -1.0
    private var lastHeight = -1.0
    private var lastDockHeight = -1.0

    var mode: LayoutMode = LayoutMode.DESKTOP
        private set

    /**
     * True while mobile CSS owns `#game`'s box. The renderer must then NOT write inline
     * width/height/left/top on it, or the inline style silently beats the stylesheet (the
     * "inline style precedence" risk in spec §65).
     */
    val cssOwnsMapViewport: Boolean get() = mode.isMobileShell

    /** Coarse primary pointer, i.e. finger-first interaction — drives target sizes and confirmations. */
    val isCoarsePointer: Boolean get() = matches(MEDIA_COARSE)

    fun install() {
        if (installed) return
        installed = true
        listOf(MEDIA_COARSE, MEDIA_NO_HOVER, MEDIA_ANY_FINE, MEDIA_ANY_HOVER).forEach(::listenMedia)
        window.addEventListener("resize", { schedule() })
        window.addEventListener("orientationchange", { schedule() })
        document.addEventListener("fullscreenchange", { schedule() })
        val vv = window.asDynamic().visualViewport
        if (vv != null) {
            vv.addEventListener("resize", { schedule() })
            vv.addEventListener("scroll", { schedule() })
        }
        observeHudRegions()
        applyNow()
    }

    /** Coalesces every trigger into one frame — resize storms are the norm on mobile, not the exception. */
    fun schedule() {
        if (frameHandle != 0) return
        frameHandle =
            window.requestAnimationFrame {
                frameHandle = 0
                applyNow()
            }
    }

    fun applyNow() {
        val metrics = ViewportMetricsService.refresh()
        val previous = mode
        mode = resolveLayoutMode(matches(MEDIA_COARSE), metrics.width, metrics.height, uiSettings.mobileUiMode)
        applyBodyClasses()
        OrientationGate.update(mode)
        if (previous != mode) MobileDrawer.onLayoutModeChanged(mode)
        // `visualViewport.scroll` fires continuously while a mobile address bar animates, and a
        // relayout is a canvas re-position plus a full minimap composite — far too expensive to do
        // per event. Only an actual change of usable area (or of shell) can move the map.
        // The dock counts too: it is subtracted from the map viewport, so a taller unit card with
        // more action chips shrinks the map exactly as a smaller window would.
        val changed =
            previous != mode ||
                metrics.width != lastWidth ||
                metrics.height != lastHeight ||
                metrics.bottomDockHeight != lastDockHeight
        lastWidth = metrics.width
        lastHeight = metrics.height
        lastDockHeight = metrics.bottomDockHeight
        if (changed) relayoutMap()
    }

    /** Called when the user changes the Mobile-interface preference; re-evaluates immediately. */
    fun setOverride(value: String) {
        uiSettings.mobileUiMode = value
        applyNow()
    }

    private fun applyBodyClasses() {
        val body = document.body ?: return
        val landscape = ViewportMetricsService.current.width >= ViewportMetricsService.current.height
        val classes = body.classList
        classes.toggle("osada-layout-desktop", mode == LayoutMode.DESKTOP)
        classes.toggle("osada-layout-tablet", mode == LayoutMode.TABLET)
        classes.toggle("osada-layout-phone", mode.isPhone)
        classes.toggle("osada-layout-compact", mode == LayoutMode.PHONE_COMPACT)
        classes.toggle("osada-orientation-landscape", landscape)
        classes.toggle("osada-orientation-portrait", !landscape)
        classes.toggle("osada-input-coarse", matches(MEDIA_COARSE))
        classes.toggle("osada-input-fine", matches(MEDIA_ANY_FINE))
        classes.toggle("osada-no-hover", matches(MEDIA_NO_HOVER))
        classes.toggle("osada-has-hover", matches(MEDIA_ANY_HOVER))
        val density = uiSettings.interfaceDensity
        classes.toggle("osada-density-compact", density == "compact")
        classes.toggle("osada-density-large", density == "large")
        classes.toggle("osada-reduced-effects", uiSettings.reducedEffects)
    }

    /**
     * Re-lays-out the map for the new usable area while keeping the player's place: capture the
     * native coordinate at the viewport centre, let the renderer resize, then put it back.
     */
    private fun relayoutMap() {
        val ui = GameHolder.instance?.ui ?: return
        val center = captureMapCenterNative()
        ui.render.positionLayers()
        restoreMapCenterNative(center)
        MinimapBuilder.refresh()
    }

    /** The top bar and bottom dock change height with content (long scenario names, action chips). */
    private fun observeHudRegions() {
        val ctor = window.asDynamic().ResizeObserver
        if (ctor == null || ctor == undefined) return
        // `js(...)` cannot capture Kotlin locals, so the constructor is invoked through a tiny
        // factory that takes both the class and the callback as ordinary arguments.
        val construct = js("(function(C, cb) { return new C(cb); })")
        val observer = construct(ctor, { schedule() })
        listOf("statusbar", "osada-bottomzone").forEach { id -> byId(id)?.let { observer.observe(it) } }
    }

    private fun listenMedia(query: String) {
        val mql = window.matchMedia(query).asDynamic()
        // addEventListener is the modern form; addListener is the fallback older Safari needs.
        if (mql.addEventListener != null && mql.addEventListener != undefined) {
            mql.addEventListener("change", { schedule() })
        } else if (mql.addListener != null && mql.addListener != undefined) {
            mql.addListener({ schedule() })
        }
    }

    private fun matches(query: String): Boolean = window.matchMedia(query).matches
}
