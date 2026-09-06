package org.osada

import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.allocMap
import org.osada.model.delAttackSel
import org.osada.model.delMoveSel
import org.osada.ui.RenderContext
import org.osada.ui.uniteRepaintRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stale move/attack overlay, reported 2026-09-03: *"when I select a unit the available move
 * hexes are drawn, but when I move it a square is cut out around it and the rest of the hexes
 * outside that square are still there."*
 *
 * `MapRenderer.render(centerRow, centerCol, radius)` repaints a SQUARE (`RenderContext.getBounds`)
 * and every caller sizes it with `getUnitRenderRadius`, which is the unit's own reach in movement
 * POINTS. A movement point is not a hex — on a road or on rails it buys several — so the
 * highlighted range routinely extends past that square, and the hexes outside it were cleared in
 * the MODEL but never repainted on the CANVAS.
 *
 * The fix is a dirty region ([GameMap.pendingRepaint]) that the model fills as it switches overlay
 * flags on and off, and that the renderer unions into whatever square it was asked for. Both
 * halves are covered here: the model records the right rectangle, and the union actually widens
 * the region a too-small radius would have produced.
 */
class MapRepaintRegionTest {
    private fun map(
        rows: Int = 40,
        cols: Int = 44,
    ): GameMap =
        GameMap().apply {
            this.rows = rows
            this.cols = cols
            allocMap()
        }

    @Test
    fun clearingAMoveRangeRecordsEveryHexItHadLit() {
        val map = map()
        // A range shaped like a road: one hex up, and eight along the row — the case a radius in
        // movement points under-measures.
        val range = listOf(Cell(10, 10), Cell(9, 10), Cell(10, 18), Cell(12, 4))
        range.forEach { cell ->
            map.currentMoveRange.add(cell)
            map.map!![cell.row][cell.col].isMoveSel = true
        }

        map.delMoveSel()

        val box = assertNotNull(map.pendingRepaint, "clearing an overlay must record what it cleared")
        assertEquals(9, box.srow)
        assertEquals(4, box.scol)
        assertEquals(12, box.erow)
        assertEquals(18, box.ecol)
        assertTrue(range.none { map.map!![it.row][it.col].isMoveSel }, "flags must be off")
        assertEquals(0, map.currentMoveRange.size)
    }

    @Test
    fun attackAndMoveRangesShareOneRectangle() {
        val map = map()
        map.currentMoveRange.add(Cell(10, 10))
        map.currentAttackRange.add(Cell(30, 40))

        map.delMoveSel()
        map.delAttackSel()

        val box = assertNotNull(map.pendingRepaint)
        assertEquals(10, box.srow)
        assertEquals(10, box.scol)
        assertEquals(30, box.erow)
        assertEquals(40, box.ecol)
    }

    @Test
    fun nothingClearedRecordsNothing() {
        val map = map()
        map.delMoveSel()
        map.delAttackSel()
        assertNull(map.pendingRepaint, "an empty clear must not force a repaint")
    }

    /** The defect itself: a radius of 4 around (10,10) cannot contain a range that reached col 25. */
    @Test
    fun theUnionWidensASquareTooSmallForTheOverlay() {
        val tooSmall = RenderContext.Bounds(5, 5, 15, 15)
        val stale = GameMap.RepaintBox(9, 3, 11, 25)

        val united = uniteRepaintRegion(tooSmall, stale, 1, 4, 40, 44)

        assertEquals(5, united.srow, "the square already reached row 5")
        assertEquals(2, united.scol, "col 3 was lit, so the region starts a margin before it")
        assertEquals(15, united.erow)
        assertEquals(26, united.ecol, "col 25 was lit and must now be repainted")
    }

    @Test
    fun theUnionNeverLeavesTheMap() {
        val united =
            uniteRepaintRegion(
                RenderContext.Bounds(5, 5, 15, 15),
                GameMap.RepaintBox(0, 0, 39, 43),
                2,
                4,
                40,
                44,
            )
        assertEquals(0, united.srow)
        assertEquals(0, united.scol)
        assertEquals(40, united.erow)
        assertEquals(44, united.ecol)
    }

    @Test
    fun aFullRedrawIgnoresTheRegionItAlreadyCovers() {
        val whole = RenderContext.Bounds(0, 0, 40, 44)
        val united = uniteRepaintRegion(whole, GameMap.RepaintBox(9, 3, 11, 25), 1, -3, 40, 44)
        assertEquals(whole, united)
    }

    @Test
    fun noRegionLeavesTheRequestedSquareAlone() {
        val square = RenderContext.Bounds(5, 5, 15, 15)
        assertEquals(square, uniteRepaintRegion(square, null, 1, 4, 40, 44))
    }
}
