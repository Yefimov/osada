package org.osada.rules

import org.osada.GameHolder
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.delCurrentUnit
import org.osada.model.setCurrentUnit
import org.osada.model.setMoveRange
import org.osada.ui.RoutePreview
import org.osada.ui.ShiftCautiousHold
import org.osada.uiSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OG's Shift route marking (`ui/RoutePreview`): the drawn route is the walked route, and holding
 *  Shift is a cautious move that ends with the key. */
class RoutePreviewTest : OgRulesTestHarness() {
    private var fogWasOff = false

    @BeforeTest
    fun setup() {
        installTestWorld()
        fogWasOff = uiSettings.noFOW
        uiSettings.noFOW = false
        ShiftCautiousHold.reset()
    }

    @AfterTest
    fun teardown() {
        ShiftCautiousHold.reset()
        uiSettings.noFOW = fogWasOff
        clearTestWorld()
    }

    private fun blindExcept(
        map: GameMap,
        spotted: List<Pair<Int, Int>>,
    ) {
        map.map!!.forEach { row -> row.forEach { hex -> while (hex.isSpotted(0)) hex.setSpotted(0, false) } }
        spotted.forEach { (r, c) -> map.map!![r][c].setSpotted(0, true) }
    }

    @Test
    fun theRouteRunsFromTheUnitToTheHoveredHexOverTheDrawnRange() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val unit = place(map, infantryEqid, 3, 3, 0)
        map.setCurrentUnit(unit)
        map.setMoveRange(unit)

        val route = RoutePreview.route(map, Cell(3, 5))
        assertEquals(3 to 3, route.first().let { it.row to it.col })
        assertEquals(3 to 5, route.last().let { it.row to it.col })
        assertEquals(
            GameRules.getShortestPath(Cell(3, 3), Cell(3, 5), map.currentMoveRange).map { it.row to it.col },
            route.map { it.row to it.col },
            "the preview is the very path MoveExecutor walks",
        )
        assertTrue(RoutePreview.route(map, Cell(3, 3)).isEmpty(), "no route to the unit's own hex")
    }

    @Test
    fun noRouteOutsideTheRangeOrWithoutASelection() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val unit = place(map, infantryEqid, 3, 3, 0)
        blindExcept(map, listOf(3 to 4, 3 to 5))
        map.setCurrentUnit(unit)
        map.cautiousMoveUnitId = unit.id
        map.setMoveRange(unit)

        assertTrue(RoutePreview.route(map, Cell(2, 3)).isEmpty(), "unspotted ground is not offered cautiously")
        assertTrue(RoutePreview.route(map, Cell(3, 5)).isNotEmpty())
        unit.hasMoved = true
        assertTrue(RoutePreview.route(map, Cell(3, 5)).isEmpty(), "a spent formation has no route")
        unit.hasMoved = false
        map.delCurrentUnit()
        assertTrue(RoutePreview.route(map, Cell(3, 5)).isEmpty())
    }

    @Test
    fun holdingShiftIsACautiousMoveThatEndsWithTheKey() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val unit = place(map, infantryEqid, 3, 3, 0)
        blindExcept(map, listOf(3 to 4, 3 to 5))
        map.setCurrentUnit(unit)
        map.setMoveRange(unit)
        assertTrue(map.map!![2][3].isMoveSel, "normally the unspotted neighbour is in range")

        assertTrue(ShiftCautiousHold.engage(map))
        assertTrue(CautiousMove.isOn(map, unit))
        assertFalse(map.map!![2][3].isMoveSel, "the drawn range shrinks while Shift is down")

        assertEquals(unit, ShiftCautiousHold.release(map))
        assertFalse(CautiousMove.isOn(map, unit))
        assertTrue(map.map!![2][3].isMoveSel, "releasing Shift restores the normal range")
    }

    @Test
    fun theHoldNeverUndoesACautiousModeThePlayerChose() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val unit = place(map, infantryEqid, 3, 3, 0)
        map.setCurrentUnit(unit)
        CautiousMove.toggle(map, unit)

        assertFalse(ShiftCautiousHold.engage(map), "already cautious: the key changes nothing")
        assertNull(ShiftCautiousHold.release(map))
        assertTrue(CautiousMove.isOn(map, unit), "the chip's choice survives the key")
    }

    @Test
    fun deselectingWithShiftDownDoesNotLeaveTheModeBehind() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val unit = place(map, infantryEqid, 3, 3, 0)
        map.setCurrentUnit(unit)
        assertTrue(ShiftCautiousHold.engage(map))
        map.delCurrentUnit()

        assertNull(ShiftCautiousHold.release(map))
        assertFalse(CautiousMove.isOn(map, unit), "selecting it again must find the normal mode")
    }

    @Test
    fun aShiftClickOnAnotherUnitKeepsThatUnitsChosenMode() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val first = place(map, infantryEqid, 3, 3, 0)
        val second = place(map, infantryEqid, 5, 5, 0)
        map.setCurrentUnit(first)
        assertTrue(ShiftCautiousHold.engage(map))
        // Shift+click on the second formation selects it straight into cautious move.
        map.cautiousMoveUnitId = second.id
        map.setCurrentUnit(second)

        assertNull(ShiftCautiousHold.release(map))
        assertTrue(CautiousMove.isOn(map, second))
    }
}
