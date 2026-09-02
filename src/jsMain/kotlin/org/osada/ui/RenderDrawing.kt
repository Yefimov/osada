package org.osada.ui

import org.osada.model.Cell

/** Drawing/animation forwarders for [Render], split out to keep its function count in bounds. */
fun Render.drawHexByCell(
    cell: Cell,
    style: dynamic,
) = mapRenderer.drawHexByCell(cell, style)

fun Render.drawCursor(cell: Cell) = cursorRenderer.drawCursor(cell)

fun Render.runAnimation(callback: dynamic) = animator.runAnimation(callback)

/** [unit] is whose animation this is, when the caller knows -- it lets OG's per-equipment attack
 *  and destruction sound replace the animation's class sound (`ui/OgSoundLibrary`). */
fun Render.addAnimation(
    row: Int,
    col: Int,
    type: String,
    direction: Int,
    unit: org.osada.model.GameUnit? = null,
): Boolean = animator.addAnimation(row, col, type, direction, unit)

fun Render.moveAnimation(params: dynamic) = animator.moveAnimation(params)
