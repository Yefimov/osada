package org.osada.ui

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Short-tap explanation panels for touch screens.
 *
 * The desktop HUD explains itself on hover: `onmouseenter` panels for the weather readout and the
 * turn clock, a native `title=` on everything else. Neither survives a phone. There is no hover,
 * and mobile browsers never render `title` at all, so the same elements are simply mute there —
 * and on a phone they are often the ONLY place a fact appears, because the top bar hides its
 * desktop originals (`mobile.css`: `body.osada-layout-phone #weathermsg, #statusmsg`) in favour of
 * [MobileContextDock]'s copies.
 *
 * [UnitActionTooltip] already solved this for the action strip with a long press, and a long press
 * is right THERE because those chips have their own click to protect: a short tap has to stay the
 * action. The elements this helper serves — the context dock's readouts, the stat chips, the unit
 * card's labels — have no click at all, so nothing needs protecting, and a long press on them
 * would be a gesture nothing advertises and nobody would go looking for. A short tap is the
 * discoverable one, so that is the gesture these use.
 *
 * Tapping the same element again closes the panel, and a tap anywhere else closes it too, through
 * one document-level listener shared by every panel opened here.
 */
internal object TapTip {
    /** Every plain title+prose panel reuses one node: only one can be open at a time anyway, and a
     *  shared id keeps the dozens of call sites from each leaking a singleton div into `mainbody`. */
    const val HELP_TIP_ID = "osadaHelpTip"

    private var openTipId: String? = null
    private var openAnchor: HTMLElement? = null
    private var dismissInstalled = false

    /**
     * Opens [show] on a short tap of [anchor], and closes it on the next tap.
     *
     * Bound to `pointerup` and filtered to non-mouse pointers rather than to `onclick`, because on
     * a desktop these same panels are already driven by hover: if a click opened one too, the
     * panel would stay open after the pointer had left the element and only `mouseleave` — which
     * already fired — could have closed it.
     */
    fun attach(
        anchor: HTMLElement,
        tipId: String,
        show: (HTMLElement) -> Unit,
    ) {
        installDismissal()
        anchor.asDynamic().onpointerup = { event: dynamic ->
            if (event.pointerType != "mouse") {
                val alreadyOpen = openTipId == tipId && openAnchor === anchor
                close()
                if (!alreadyOpen) {
                    show(anchor)
                    openTipId = tipId
                    openAnchor = anchor
                }
            }
            Unit
        }
    }

    /**
     * Turns an element's native `title=` into a tap panel, with [heading] above it when the element
     * has a name worth repeating.
     *
     * The attribute stays where it is: it is still what a desktop pointer and a screen reader use.
     * Both it and [heading] are read at tap time, not at attach time, so a readout whose text
     * changes every turn explains its CURRENT state rather than the one it was born with.
     */
    fun fromTitle(
        anchor: HTMLElement,
        heading: () -> String = { "" },
    ) {
        attach(anchor, HELP_TIP_ID) { element ->
            val body = element.title
            if (body.isNotBlank()) {
                AnchoredTip.show(HELP_TIP_ID, element, AnchoredTip.helpHtml(heading(), body))
            }
        }
    }

    /**
     * Like [fromTitle], but with the explanation supplied instead of read from `title=`.
     *
     * Needed wherever the attribute is already spoken for: the dock's scenario readout overwrites
     * its own `title` every refresh with the date and operation name, so that the truncated line
     * can be read in full, which would leave [fromTitle] echoing the very text the reader just
     * tapped instead of explaining it.
     */
    fun attachHelp(
        anchor: HTMLElement,
        heading: () -> String = { "" },
        body: () -> String,
    ) {
        attach(anchor, HELP_TIP_ID) { element ->
            AnchoredTip.show(HELP_TIP_ID, element, AnchoredTip.helpHtml(heading(), body()))
        }
    }

    /** Closes whatever panel is open. Also called when a screen that owns tap tips is torn down. */
    fun close() {
        openTipId?.let { AnchoredTip.hide(it) }
        openTipId = null
        openAnchor = null
    }

    /**
     * One listener for every tap tip. It runs on `pointerdown`, i.e. BEFORE the `pointerup` that
     * toggles a panel, and deliberately keeps the panel open when the press landed on the open
     * anchor — otherwise closing here and re-opening there would make a second tap on the same
     * element look like it did nothing.
     */
    private fun installDismissal() {
        if (dismissInstalled) return
        dismissInstalled = true
        document.addEventListener("pointerdown", { event ->
            if (openTipId != null) {
                val target = event.target
                if (!contains(openTipId?.let { byId(it) }, target) && !contains(openAnchor, target)) {
                    close()
                }
            }
        })
    }

    private fun contains(
        element: HTMLElement?,
        target: dynamic,
    ): Boolean = element != null && target != null && (element.asDynamic().contains(target) as? Boolean ?: false)
}
