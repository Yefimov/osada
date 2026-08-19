package org.osada.ui

import kotlinx.browser.window
import org.osada.i18n.I18n
import org.osada.model.Cell
import org.osada.model.InterceptionEvent
import org.w3c.dom.events.MouseEvent

/**
 * The non-modal AA-interception event banner (`docs/player-comfort-roadmap.md` P1,
 * `docs/design/aa-interception.md`).
 *
 * Interception is the one combat the player does not initiate and does not watch happen: an
 * aircraft simply arrives weaker. Until now the only record was a combat-log row nobody had a
 * reason to open. This raises an obvious banner naming the gun, the aircraft and the losses, and
 * leaves the combat log's detail exactly as it was.
 *
 * Non-modal on purpose: it never takes focus, never blocks input, dismisses itself, and can be
 * clicked away or clicked to centre the map on where it happened. It also never fires for an
 * interception neither side of which the observer can see, and it publishes nothing at all before a
 * gun has actually fired — a banner that warned about hidden AA would delete the mechanic.
 */
internal object InterceptionBanner {
    const val BANNER_ID = "osadaInterceptBanner"

    private const val AUTO_DISMISS_MS = 7000
    private var dismissTimer: Int = 0

    /**
     * Raises the banner for [events]. [observerSide] is the side whose HUD this is; an interception
     * involving neither the observer's aircraft nor the observer's guns is not their event.
     */
    fun show(
        ui: UI,
        events: List<InterceptionEvent>,
        observerSide: Int,
    ): Boolean {
        val relevant =
            events.filter { event ->
                event.plane.player?.side == observerSide || event.interceptor.player?.side == observerSide
            }
        val first = relevant.firstOrNull()
        val host = byId("mainbody")
        if (first == null || host == null) return false
        hide()

        val banner = addTag(host, "div")
        banner.id = BANNER_ID
        banner.className = "osada-intercept-banner"
        // A live region, not a dialog: announced to assistive technology without stealing focus.
        banner.setAttribute("role", "status")
        banner.setAttribute("aria-live", "polite")

        val title = addTag(banner, "div")
        title.className = "osada-intercept-banner__title"
        // One banner, two reactions: AA interception and `Overwatch` opportunity fire. The title
        // follows the FIRST relevant event -- a move that draws both is vanishingly rare, and a
        // title that named neither would be worse than one that names the first.
        title.textContent = I18n.t(MoveReactionText.titleKey(first.kind))

        relevant.forEach { event -> addLine(banner, event, observerSide) }

        banner.onclick = { _: MouseEvent ->
            first.plane.getPos()?.let { pos -> ui.uiSetCellOnViewPort(Cell(pos.row, pos.col)) }
            hide()
        }
        scheduleDismiss()
        return true
    }

    fun hide() {
        if (dismissTimer != 0) {
            window.clearTimeout(dismissTimer)
            dismissTimer = 0
        }
        delTag(byId(BANNER_ID))
    }

    private fun addLine(
        banner: org.w3c.dom.HTMLElement,
        event: InterceptionEvent,
        observerSide: Int,
    ) {
        val line = addTag(banner, "div")
        val ownLoss = event.plane.player?.side == observerSide
        line.className = "osada-intercept-banner__line" + if (ownLoss) " osada-intercept-banner__line--own" else ""
        val key = MoveReactionText.lineKey(event.kind, event.planeDestroyed)
        line.textContent =
            I18n.t(
                key,
                mapOf(
                    "gun" to event.interceptor.unitData(true).name,
                    "plane" to event.plane.unitData(true).name,
                    "losses" to event.losses,
                ),
            )
    }

    private fun scheduleDismiss() {
        dismissTimer = window.setTimeout({ hide() }, AUTO_DISMISS_MS)
    }
}
