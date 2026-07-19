package org.osada.ui

import org.osada.model.Cell
import org.osada.model.ScreenPos

/** Canvas/geometry accessors for [Render], split out to keep its function count in bounds. */
fun Render.cellToScreen(
    row: Int,
    col: Int,
    absolute: Boolean,
): ScreenPos = ctx.cellToScreen(row, col, absolute)

fun Render.screenToCell(
    x: Int,
    y: Int,
): Cell = ctx.screenToCell(x, y)

fun Render.getHexesCanvas(): dynamic = ctx.hexesCanvas

fun Render.getMapCanvas(): dynamic = ctx.mapCanvas

fun Render.getCursorCanvas(): dynamic = ctx.cursorCanvas

/** The loaded terrain artwork (HTMLImageElement) — the minimap composites it as its base layer. */
fun Render.getTerrainImage(): dynamic = ctx.terrainImage
