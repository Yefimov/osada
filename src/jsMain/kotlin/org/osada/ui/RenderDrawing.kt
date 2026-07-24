package org.osada.ui

import org.osada.model.Cell

/** Drawing/animation forwarders for [Render], split out to keep its function count in bounds. */
fun Render.drawHexByCell(
    cell: Cell,
    style: dynamic,
) = mapRenderer.drawHexByCell(cell, style)

fun Render.drawCursor(cell: Cell) = cursorRenderer.drawCursor(cell)

fun Render.runAnimation(callback: dynamic) = animator.runAnimation(callback)

fun Render.addAnimation(
    row: Int,
    col: Int,
    type: String,
    direction: Int,
): Boolean = animator.addAnimation(row, col, type, direction)

fun Render.moveAnimation(params: dynamic) = animator.moveAnimation(params)
