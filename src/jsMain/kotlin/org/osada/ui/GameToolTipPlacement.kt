package org.osada.ui

import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.math.max
import kotlin.math.min

/**
 * The map-anchored point a `#gameToolTip` points at, in `#game`'s scroll-content space (the
 * space [RenderContext.cellToScreen] returns with `absolute = true`). [halfWidth] is the
 * distance from the hex centre to its side points at the current map zoom.
 */
data class GameToolTipAnchor(
    val x: Double,
    val y: Double,
    val halfWidth: Double,
)

/**
 * Measures the rendered `#gameToolTip` and places it beside its anchor hex: right side first,
 * left side (arrow reversed) when that does not fit, and clamped inside the VISIBLE part of
 * `#game` either way — `#game` already excludes the top HUD and the bottom unit dock.
 *
 * Everything is measured with getBoundingClientRect(), because the tooltip carries the UI-scale
 * `zoom` (or `transform: scale`) from [UILayout.scaleUI]: its style px are not screen px. The
 * style-px → screen-px mapping is calibrated from two probe positions rather than derived from
 * the scale setting, so it holds for both scaling paths.
 */
internal object GameToolTipPlacement {
    private const val VIEWPORT_MARGIN = 8.0

    // Gap between the hex's side point and the panel edge; the arrow (20px) pokes back into it.
    private const val HEX_GAP = 10.0
    private const val ARROW_LENGTH = 20.0

    // Keep the arrow's centre this far from the panel's rounded top/bottom corners (local px).
    private const val ARROW_EDGE_INSET = 22.0
    private const val PROBE_OFFSET = 100.0
    private const val ARROW_Y_PROPERTY = "--osada-gtt-arrow-y"
    private val SCALE_PATTERN = Regex("""scale\(\s*([\d.]+)""")

    private var anchor: (() -> GameToolTipAnchor?)? = null
    private var listenersInstalled = false
    private var frameHandle = 0

    fun show(anchorProvider: () -> GameToolTipAnchor?) {
        anchor = anchorProvider
        installListeners()
        place()
    }

    fun hide() {
        anchor = null
    }

    fun place() {
        val tooltip = byId("gameToolTip")?.takeIf { it.style.display.let { d -> d.isNotEmpty() && d != "none" } }
        val game = byId("game")
        val target = anchor?.invoke()
        if (tooltip != null && game != null && target != null) layout(tooltip, game, target)
    }

    private fun layout(
        tooltip: HTMLElement,
        game: HTMLElement,
        target: GameToolTipAnchor,
    ) {
        val scale = uiScaleOf(tooltip)
        val area = visibleGameArea(game)
        // Cap the panel to the usable area first so its measured height is the final one.
        tooltip.style.maxWidth = "${max(0.0, area.width) / scale}px"
        tooltip.style.maxHeight = "${max(0.0, area.height) / scale}px"

        val probe = calibrate(tooltip)
        val gameRect = game.getBoundingClientRect()
        val ax = gameRect.left + game.clientLeft - game.scrollLeft + target.x
        val ay = gameRect.top + game.clientTop - game.scrollTop + target.y

        val (rawLeft, orientation) = chooseSide(area, ax, target.halfWidth + HEX_GAP, probe.width, ARROW_LENGTH * scale)
        val left = clamp(rawLeft, area.left, area.right - probe.width)
        val top = clamp(ay - probe.height / 2, area.top, area.bottom - probe.height)

        tooltip.style.left = "${(left - probe.x0) / probe.kx}px"
        tooltip.style.top = "${(top - probe.y0) / probe.ky}px"

        // Arrow height follows the anchor, not the panel's middle, once the panel is clamped.
        val localHeight = probe.height / scale
        val arrowY = (ay - top) / scale
        val arrowOnPanel = arrowY >= ARROW_EDGE_INSET && arrowY <= localHeight - ARROW_EDGE_INSET
        tooltip.setAttribute("orientation", if (arrowOnPanel) orientation else "none")
        tooltip.style.setProperty(ARROW_Y_PROPERTY, "${arrowY}px")
    }

    /** Screen-px left edge and arrow orientation: right of the hex, else left, else overlapping. */
    private fun chooseSide(
        area: Area,
        anchorX: Double,
        gap: Double,
        width: Double,
        arrow: Double,
    ): Pair<Double, String> {
        val rightLeft = anchorX + gap
        val leftLeft = anchorX - gap - width
        val roomRight = area.right - rightLeft
        val roomLeft = anchorX - gap - area.left
        return when {
            width <= roomRight && rightLeft - arrow >= area.left -> rightLeft to "left"
            width <= roomLeft && anchorX - gap + arrow <= area.right -> leftLeft to "right"
            // Neither side has room (narrow phone): overlap the hex, drop the arrow.
            roomRight >= roomLeft -> rightLeft to "none"
            else -> leftLeft to "none"
        }
    }

    private data class Area(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) {
        val width: Double get() = right - left
        val height: Double get() = bottom - top
    }

    /** Linear map from style left/top to screen px, plus the rendered size. */
    private data class Probe(
        val x0: Double,
        val y0: Double,
        val kx: Double,
        val ky: Double,
        val width: Double,
        val height: Double,
    )

    private fun calibrate(tooltip: HTMLElement): Probe {
        tooltip.style.left = "0px"
        tooltip.style.top = "0px"
        val r0 = tooltip.getBoundingClientRect()
        tooltip.style.left = "${PROBE_OFFSET}px"
        tooltip.style.top = "${PROBE_OFFSET}px"
        val r1 = tooltip.getBoundingClientRect()
        val kx = ((r1.left - r0.left) / PROBE_OFFSET).takeIf { it > 0.0 } ?: 1.0
        val ky = ((r1.top - r0.top) / PROBE_OFFSET).takeIf { it > 0.0 } ?: 1.0
        return Probe(r0.left, r0.top, kx, ky, r0.width, r0.height)
    }

    /** The on-screen part of #game's client box (no scrollbars), inset by the margin. */
    private fun visibleGameArea(game: HTMLElement): Area {
        val r = game.getBoundingClientRect()
        val left = max(r.left + game.clientLeft, 0.0)
        val top = max(r.top + game.clientTop, 0.0)
        val right = min(r.left + game.clientLeft + game.clientWidth, window.innerWidth.toDouble())
        val bottom = min(r.top + game.clientTop + game.clientHeight, window.innerHeight.toDouble())
        return Area(left + VIEWPORT_MARGIN, top + VIEWPORT_MARGIN, right - VIEWPORT_MARGIN, bottom - VIEWPORT_MARGIN)
    }

    /** The UI scale [UILayout.scaleUI] wrote on the element, via `zoom` or `transform: scale()`. */
    private fun uiScaleOf(element: HTMLElement): Double {
        val zoom = (element.style.asDynamic().zoom as? String)?.toDoubleOrNull()
        if (zoom != null && zoom > 0.0) return zoom
        val transform = element.style.transform
        val match = SCALE_PATTERN.find(transform)
        val scale = match?.groupValues?.get(1)?.toDoubleOrNull()
        return scale?.takeIf { it > 0.0 } ?: 1.0
    }

    // Unlike coerceIn, tolerates an inverted range (panel larger than the area): pins to the start.
    private fun clamp(
        value: Double,
        low: Double,
        high: Double,
    ): Double = max(low, min(value, high))

    private fun installListeners() {
        if (listenersInstalled) return
        listenersInstalled = true
        window.addEventListener("resize", { schedule() })
        window.addEventListener("orientationchange", { schedule() })
        window.asDynamic().visualViewport?.addEventListener("resize", { schedule() })
        // #game's box changes when the HUD/dock re-lays-out without any window resize.
        val ctor = window.asDynamic().ResizeObserver
        if (ctor != null && ctor != undefined) {
            val construct = js("(function(C, cb) { return new C(cb); })")
            val observer = construct(ctor, { schedule() })
            byId("game")?.let { observer.observe(it) }
        }
    }

    private fun schedule() {
        if (anchor == null || frameHandle != 0) return
        frameHandle =
            window.requestAnimationFrame {
                frameHandle = 0
                place()
            }
    }
}
