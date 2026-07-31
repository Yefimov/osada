package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window

/**
 * One measured snapshot of the usable browser viewport (spec §11).
 *
 * Mobile browser chrome (address bar), the software keyboard and device cutouts all change the
 * usable area at runtime, and the project previously derived that area from four disagreeing
 * sources (`window.innerWidth/Height`, element `clientWidth/Height`, a hardcoded 30px top bar and
 * CSS custom properties). Everything that needs viewport geometry now reads this one snapshot.
 */
internal data class ViewportMetrics(
    val width: Double,
    val height: Double,
    val offsetLeft: Double,
    val offsetTop: Double,
    val topBarHeight: Double,
    val bottomDockHeight: Double,
    val safeTop: Double,
    val safeRight: Double,
    val safeBottom: Double,
    val safeLeft: Double,
) {
    /** Width left for the map once device cutouts are excluded. */
    val usableWidth: Double get() = (width - safeLeft - safeRight).coerceAtLeast(0.0)

    /** Height left for the map once cutouts, the top bar and the bottom dock are excluded. */
    val usableHeight: Double
        get() = (height - safeTop - safeBottom - topBarHeight - bottomDockHeight).coerceAtLeast(0.0)
}

/**
 * Measures [ViewportMetrics] and republishes the parts CSS cannot compute for itself.
 *
 * Measurement order follows the spec: Visual Viewport when available (it is the only API that
 * reports the area actually visible under a collapsing address bar or a raised keyboard), then
 * `documentElement.clientWidth/Height`, then the real top-bar/bottom-dock rectangles, then the
 * safe-area environment variables read back through a probe element.
 */
internal object ViewportMetricsService {
    private const val PROBE_ID = "osada-safe-area-probe"

    private var probe: dynamic = null
    private var cached: ViewportMetrics? = null

    /** Last published snapshot; measured on first use so nothing runs before the DOM exists. */
    val current: ViewportMetrics get() = cached ?: refresh()

    fun measure(): ViewportMetrics {
        val vv = window.asDynamic().visualViewport
        val docEl = document.documentElement?.asDynamic()
        val fallbackWidth = (docEl?.clientWidth as? Number)?.toDouble() ?: window.innerWidth.toDouble()
        val fallbackHeight = (docEl?.clientHeight as? Number)?.toDouble() ?: window.innerHeight.toDouble()
        val insets = safeAreaInsets()
        return ViewportMetrics(
            width = (vv?.width as? Number)?.toDouble() ?: fallbackWidth,
            height = (vv?.height as? Number)?.toDouble() ?: fallbackHeight,
            offsetLeft = (vv?.offsetLeft as? Number)?.toDouble() ?: 0.0,
            offsetTop = (vv?.offsetTop as? Number)?.toDouble() ?: 0.0,
            // The rendered HUD boxes include their safe-area padding. Store only the content
            // height because safeTop/safeBottom are separate terms in the viewport model and CSS
            // adds them separately when positioning the map.
            topBarHeight = heightExcludingSafeArea(rectHeight("statusbar"), insets[0]),
            bottomDockHeight = heightExcludingSafeArea(rectHeight("osada-bottomzone"), insets[2]),
            safeTop = insets[0],
            safeRight = insets[1],
            safeBottom = insets[2],
            safeLeft = insets[3],
        )
    }

    /** Re-measures and republishes; returns the fresh snapshot. Cheap enough to call per frame. */
    fun refresh(): ViewportMetrics {
        val metrics = measure()
        cached = metrics
        publish(metrics)
        return metrics
    }

    /**
     * Hands back the two numbers CSS cannot compute for itself:
     *
     * - visual-viewport size, because `100dvh` tracks the *layout* viewport and does not shrink
     *   for a software keyboard;
     * - the measured bottom-dock height, because the dock grows and shrinks with the selected
     *   unit's action chips, and the map's bottom edge has to follow it exactly — a static token
     *   either lets the dock cover the map or wastes rows of hexes.
     */
    private fun publish(metrics: ViewportMetrics) {
        val root = document.documentElement?.asDynamic() ?: return
        root.style.setProperty("--osada-vv-h", "${metrics.height}px")
        root.style.setProperty("--osada-vv-w", "${metrics.width}px")
        root.style.setProperty("--osada-dock-h", "${metrics.bottomDockHeight}px")
        root.style.setProperty("--osada-topbar-h", "${metrics.topBarHeight}px")
    }

    /** Rendered height of a HUD region, or 0 when it is absent/hidden — never a hardcoded guess. */
    private fun rectHeight(id: String): Double {
        val el = byId(id)?.asDynamic() ?: return 0.0
        val rect = el.getBoundingClientRect()
        return (rect?.height as? Number)?.toDouble() ?: 0.0
    }

    /**
     * `env(safe-area-inset-*)` is only readable from CSS, so a zero-size probe element carries the
     * four values as padding and getComputedStyle hands them back as pixels.
     */
    private fun safeAreaInsets(): DoubleArray {
        val el = ensureProbe() ?: return DoubleArray(SAFE_AREA_SIDES)
        val style = window.asDynamic().getComputedStyle(el)

        fun px(name: String): Double = ((style[name] as? String) ?: "").removeSuffix("px").toDoubleOrNull() ?: 0.0
        return doubleArrayOf(px("paddingTop"), px("paddingRight"), px("paddingBottom"), px("paddingLeft"))
    }

    private fun ensureProbe(): dynamic {
        if (probe != null) return probe
        val existing = document.getElementById(PROBE_ID)
        val el = existing?.asDynamic() ?: document.createElement("div").asDynamic()
        if (existing == null) {
            el.id = PROBE_ID
            el.style.position = "fixed"
            el.style.top = "0"
            el.style.left = "0"
            el.style.width = "0"
            el.style.height = "0"
            el.style.visibility = "hidden"
            el.style.pointerEvents = "none"
            el.style.paddingTop = "env(safe-area-inset-top, 0px)"
            el.style.paddingRight = "env(safe-area-inset-right, 0px)"
            el.style.paddingBottom = "env(safe-area-inset-bottom, 0px)"
            el.style.paddingLeft = "env(safe-area-inset-left, 0px)"
            document.body?.appendChild(el.unsafeCast<org.w3c.dom.Node>())
        }
        probe = el
        return el
    }

    private const val SAFE_AREA_SIDES = 4
}

internal fun heightExcludingSafeArea(
    renderedHeight: Double,
    safeInset: Double,
): Double = (renderedHeight - safeInset).coerceAtLeast(0.0)
