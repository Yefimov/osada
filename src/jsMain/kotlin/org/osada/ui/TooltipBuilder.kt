package org.osada.ui

import org.osada.TooltipColor
import org.osada.TooltipStyle
import org.w3c.dom.Element

/**
 * Builds the various tooltips: the in-game info tooltip, the small map pins/labels (tracked
 * in [UIBuilder.smallToolTipList]) and the UI tooltip (optionally anchored to an element).
 * Extracted from the former `UIBuilder` god-object.
 */
internal object TooltipBuilder {
    private const val GAME_TOOLTIP_OFFSET = 55
    private const val TOOLTIP_Y_OFFSET = 15
    private const val PIN_TOOLTIP_X_OFFSET = 8
    private const val TEXT_TOOLTIP_X_OFFSET = 38
    private const val TOOLTIP_ELEMENT_GAP = 5

    fun gameToolTip(
        text: String,
        x: Int,
        y: Int,
    ) {
        val tooltip = byId("gameToolTip") ?: return
        tooltip.setAttribute("type", "game")
        tooltip.style.top = "${y - GAME_TOOLTIP_OFFSET}px"
        tooltip.style.left = "${x + GAME_TOOLTIP_OFFSET}px"
        byId("gameToolTipMessage")?.innerHTML = text
        tooltip.setAttribute("orientation", "left")
        makeVisible("gameToolTip")
        byId("gameToolTipOk")?.title = "Dismiss this battlefield notice."
        byId("gameToolTipOk")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            makeHidden("gameToolTip")
            js("if (typeof game !== 'undefined') game.waitUIAnimation = false")
        }
    }

    fun gameSmallToolTip(
        text: String,
        x: Int,
        y: Int,
        color: Int,
        id: String?,
        style: Int,
    ) {
        val game = byId("game") ?: return
        val tooltipId = id ?: ("gstt" + UIBuilder.smallToolTipList.size)
        // Idempotent on id: an existing element with the same id must be removed first, not just
        // left behind as an orphaned duplicate. This was silently latent for ammo/fuel tooltips
        // (removeAllSmallToolTips()'s default clearUnitTooltips=false skips "gsttu*" ids — see
        // MapZoom.set's own comment — so a zoom change called addSmallToolTips() again WITHOUT
        // clearing them first) but only became visibly obvious once a tooltip had a position that
        // actually needed to move (a unit tooltip built at one hex, still present with its old
        // fixed position, plus a second copy at the new correct position — both real DOM nodes, so
        // both rendered, reading as "the tooltip appears twice / jumps between hexes on zoom").
        byId(tooltipId)?.let { stale ->
            val idx = UIBuilder.smallToolTipList.indexOf(tooltipId)
            if (idx >= 0) UIBuilder.smallToolTipList.removeAt(idx)
            delTag(stale)
        }
        val div = addTag(game, "div")
        div.id = tooltipId
        div.className = "smallToolTip"
        div.setAttribute("orientation", "bottom")
        if (color == TooltipColor.ENEMY) div.style.color = "#F8F864"
        if (style == TooltipStyle.PIN) {
            div.setAttribute("shape", "pin")
            div.style.top = "${y - TOOLTIP_Y_OFFSET}px"
            div.style.left = "${x - PIN_TOOLTIP_X_OFFSET}px"
        } else {
            div.style.top = "${y - TOOLTIP_Y_OFFSET}px"
            div.style.left = "${x - TEXT_TOOLTIP_X_OFFSET}px"
        }
        div.style.display = "inline"
        div.innerHTML = text
        UIBuilder.smallToolTipList.add(tooltipId)
        div.onclick = { _: org.w3c.dom.events.MouseEvent ->
            val index = UIBuilder.smallToolTipList.indexOf(tooltipId)
            if (index >= 0) UIBuilder.smallToolTipList.removeAt(index)
            delTag(div)
        }
    }

    fun uiToolTip(
        text: String,
        x: Int,
        y: Int,
        right: Boolean,
    ) {
        val tooltip = byId("uiToolTip") ?: return
        tooltip.setAttribute("type", "ui")
        tooltip.style.top = "${y}px"
        tooltip.style.left = "${x}px"
        // Grow the box to fit the text (the fixed 200x20 CSS box clipped longer messages, e.g. the
        // "N units haven't moved: ..." end-turn warning). Auto width up to a cap, wrapping height.
        tooltip.style.width = "auto"
        tooltip.asDynamic().style.maxWidth = "320px"
        tooltip.style.height = "auto"
        tooltip.asDynamic().style.whiteSpace = "normal"
        byId("uiToolTipMessage")?.innerHTML = text
        tooltip.setAttribute("orientation", if (right) "right" else "left")
        makeVisible("uiToolTip")
        tooltip.onclick = { _: org.w3c.dom.events.MouseEvent -> makeHidden("uiToolTip") }
    }

    fun uiToolTipAtElement(
        element: dynamic,
        text: String,
        right: Boolean,
    ) {
        val tooltip = byId("uiToolTip") ?: return
        val htmlElement = element as? Element ?: return
        uiToolTip(text, 0, 0, right)
        val coords = getCoordinates(htmlElement)
        uiToolTip(text, coords.x - tooltip.clientWidth - TOOLTIP_ELEMENT_GAP, coords.y, right)
    }
}
