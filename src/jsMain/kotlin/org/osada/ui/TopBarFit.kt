package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * Fits the desktop top bar (`#statusbar`) to its width by measurement rather than by media query.
 *
 * Two decisions, made in this order on every layout change:
 *
 * 1. **Where the Turn Report toggle sits.** `#combatLogButton` is absolutely centred so it shares a
 *    centreline with the Turn Report window (pinned to the viewport centre). Being absolute it takes
 *    no room in the row, and the free gap it is meant to sit in -- the two `.osada-tb-spacer`s -- is
 *    NOT centred, because the right cluster is far wider than the left. Measured on `bn9s00` in a
 *    Russian UI, the arrow already clipped the prestige figure at 1900px and was hidden under the
 *    Reserves button at 1440px. So it stays centred only while it fits inside the spacers' span with
 *    nothing hidden; otherwise it gets [INLINE_CLASS] and joins the row, keeping its arrow (the
 *    owner's choice, 2026-09-28 -- only the phone bar wears the map sprite).
 * 2. **What gives way when the row is still too long.** Levels are applied one at a time, least
 *    valuable first, and only as many as the bar needs: the logo art, the hover line (`#locmsg`),
 *    the weather words (the icons and their hover panel stay), the scenario title, the date. The
 *    turn counter is never hidden (owner, 2026-09-28). Each level is a cumulative class on the bar,
 *    `osada-tb-fit-N`; the CSS for them is in `osada-theme.css`.
 *
 * Why measurement: where the row runs out depends on the scenario title, the language, the prestige
 * digits and the turn count. Fixed breakpoints tuned on one scenario overflowed on the next
 * (`bn9s19`'s "31604+270" prestige and 16-turn clock), measured with
 * `scripts/verify/topbar-collision-probe.mjs`.
 *
 * The whole decision is recomputed from level 0 inside one animation frame, with synchronous layout
 * reads between steps, so it depends only on the current widths and cannot oscillate; the final
 * state is the only one the ResizeObserver ever sees. The phone layout is left alone: `mobile.css`
 * owns that bar.
 */
object TopBarFit {
    const val INLINE_CLASS = "osada-tb-combatlog--inline"

    /** Levels of `osada-tb-fit-N`, see the class KDoc for what each hides. */
    private const val MAX_LEVEL = 5

    /** Breathing room each side of the centred arrow, so it never touches a neighbour. */
    private const val CLEARANCE = 6.0

    /** Sub-pixel tolerance for "past the bar's edge". */
    private const val EDGE_TOLERANCE = 0.5

    private var observer: dynamic = null
    private var scheduled = false

    /** Starts watching the bar. Call once the bar is fully built; safe to call again after a rebuild. */
    fun install() {
        val ctor = window.asDynamic().ResizeObserver
        observer?.disconnect()
        val bar = document.getElementById("statusbar")
        if (ctor != null && ctor != undefined && bar != null) {
            // `js(...)` cannot capture Kotlin locals; see MobileLayoutController.observeHudRegions.
            val construct = js("(function(C, cb) { return new C(cb); })")
            observer = construct(ctor, { schedule() })
            // The bar resizes with the window; each item resizes when its own text changes (prestige
            // digits, turn count, save status), which can overflow a bar whose width did not move.
            observer.observe(bar)
            for (i in 0 until bar.children.length) bar.children.item(i)?.let { observer.observe(it) }
        }
        schedule()
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        window.requestAnimationFrame {
            scheduled = false
            update()
        }
    }

    private fun update() {
        val bar = document.getElementById("statusbar") as? HTMLElement
        val button = document.getElementById("combatLogButton")
        if (bar == null || button == null) return
        setLevel(bar, 0)
        button.classList.remove(INLINE_CLASS)
        val phone = document.body?.classList?.contains("osada-layout-phone") == true
        if (!phone && !centredArrowFits(bar, button)) {
            button.classList.add(INLINE_CLASS)
            var level = 0
            while (overflows(bar) && level < MAX_LEVEL) {
                level++
                setLevel(bar, level)
            }
        }
    }

    private fun setLevel(
        bar: HTMLElement,
        level: Int,
    ) {
        for (n in 1..MAX_LEVEL) bar.classList.toggle("osada-tb-fit-$n", n <= level)
    }

    private fun spacers(): List<Element> {
        val nodes = document.querySelectorAll("#statusbar > .osada-tb-spacer")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    /**
     * Whether a button of [button]'s width, centred on [bar], lies inside the span from the first
     * spacer's left edge to the second's right edge with [CLEARANCE] to spare -- and the row itself
     * fits. The span is measured edge-to-edge across both spacers, which is the same width whether
     * the button is in the row or not, since the in-row button takes its room from the spacers.
     */
    private fun centredArrowFits(
        bar: HTMLElement,
        button: Element,
    ): Boolean {
        val gap = spacers()
        if (gap.size != 2 || overflows(bar)) return false
        val barBox = bar.getBoundingClientRect()
        val centre = barBox.left + barBox.width / 2
        val half = button.getBoundingClientRect().width / 2
        val spanStart = gap.first().getBoundingClientRect().left
        val spanEnd = gap.last().getBoundingClientRect().right
        return centre - half - CLEARANCE >= spanStart && centre + half + CLEARANCE <= spanEnd
    }

    /** Whether any visible in-row item reaches past the bar's content edge. */
    private fun overflows(bar: HTMLElement): Boolean {
        val barBox = bar.getBoundingClientRect()
        val padRight =
            window
                .getComputedStyle(bar)
                .paddingRight
                .removeSuffix("px")
                .toDoubleOrNull() ?: 0.0
        val limit = barBox.right - padRight + EDGE_TOLERANCE
        for (i in 0 until bar.children.length) {
            val child = bar.children.item(i) ?: continue
            val box = child.getBoundingClientRect()
            val inRow = window.getComputedStyle(child).position != "absolute"
            if (inRow && box.width > 0 && box.right > limit) return true
        }
        return false
    }
}
