package org.osada.ui

import org.osada.model.GameUnit
import org.osada.model.ScreenPos

private const val DESKTOP_MARGIN = 0.15
private const val PHONE_MARGIN_X = 0.12
private const val PHONE_MARGIN_Y = 0.18

/** Viewport scrolling helpers for [UI], split out to keep its function count in bounds. */
fun UI.uiSetUnitOnViewPort(unit: GameUnit): Boolean {
    val pos = unit.getPos() ?: return false
    return uiSetCellOnViewPort(pos)
}

/** Scrolls [unit] into view ONLY if it isn't already comfortably visible — unlike
 *  [uiSetUnitOnViewPort] (an unconditional re-center, correct for "jump to" navigation:
 *  clicking an objective, the ready-unit nav), forcibly re-centering after every LOCAL move
 *  shifts everything on screen right as the player is about to click their next target — e.g.
 *  drive a tank up next to an enemy, and by the time the move animation ends the enemy has
 *  slid to a different screen position than where the player was about to click, so the click
 *  lands on the wrong hex instead of the attack. */
fun UI.uiScrollUnitIntoView(unit: GameUnit): Boolean {
    val pos = unit.getPos()
    val gameDiv = byId("game")?.asDynamic()
    if (pos == null || gameDiv == null) return false
    val clientWidth = (gameDiv.clientWidth as? Number)?.toDouble()
    val clientHeight = (gameDiv.clientHeight as? Number)?.toDouble()
    val screenPos = render.cellToScreen(pos.row, pos.col, true)
    val inView =
        clientWidth != null &&
            clientHeight != null &&
            isUnitScrolledIntoView(gameDiv, screenPos, clientWidth, clientHeight)
    return if (clientWidth == null || clientHeight == null || !inView) {
        uiSetUnitOnViewPort(unit)
    } else {
        true
    }
}

/** Whether [screenPos] sits comfortably inside #game's current scroll viewport (with margin),
 *  used by [uiScrollUnitIntoView] to decide whether a re-center is actually necessary. */
private fun isUnitScrolledIntoView(
    gameDiv: dynamic,
    screenPos: ScreenPos,
    clientWidth: Double,
    clientHeight: Double,
): Boolean {
    val scrollLeft = (gameDiv.scrollLeft as? Number)?.toDouble() ?: 0.0
    val scrollTop = (gameDiv.scrollTop as? Number)?.toDouble() ?: 0.0
    // Margin so the unit isn't left flush against the very edge either — still comfortably
    // clickable/visible, just not dead-center. A phone gets a taller vertical margin: the map
    // viewport is short, a fingertip is wide, and the bottom dock sits right under the edge.
    val phone = MobileLayoutController.mode.isPhone
    val marginX = clientWidth * (if (phone) PHONE_MARGIN_X else DESKTOP_MARGIN)
    val marginY = clientHeight * (if (phone) PHONE_MARGIN_Y else DESKTOP_MARGIN)
    return screenPos.x >= scrollLeft + marginX &&
        screenPos.x <= scrollLeft + clientWidth - marginX &&
        screenPos.y >= scrollTop + marginY &&
        screenPos.y <= scrollTop + clientHeight - marginY
}
