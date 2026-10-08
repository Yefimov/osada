package org.osada.rules

import org.osada.GameHolder
import org.osada.model.setMoveRange
import org.osada.multiplayer.command.GameCommandJson
import org.osada.multiplayer.command.HexCoordinate
import org.osada.multiplayer.command.MoveUnit
import org.osada.multiplayer.command.toPayloadJson
import org.osada.uiSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OG's cautious move (`rules/CautiousMove`): move only by spotted hexes, per selection. */
class CautiousMoveTest : OgRulesTestHarness() {
    private var fogWasOff = false

    @BeforeTest
    fun setup() {
        installTestWorld()
        // Restored saves set this global; with the fog off every hex counts as spotted.
        fogWasOff = uiSettings.noFOW
        uiSettings.noFOW = false
    }

    @AfterTest
    fun teardown() {
        uiSettings.noFOW = fogWasOff
        clearTestWorld()
    }

    private fun reachable(
        map: org.osada.model.GameMap,
        unit: org.osada.model.GameUnit,
    ): Set<Pair<Int, Int>> =
        MoveRangeCalculation
            .getMoveRange(map, unit)
            .filter { it.canMove }
            .map { it.row to it.col }
            .toSet()

    @Test
    fun theRangeKeepsToSpottedHexes() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val unit = place(map, infantryEqid, 3, 3, 0)
        // Placing the unit spots its surroundings; start from a blind map and a known corridor.
        map.map!!.forEach { row -> row.forEach { hex -> while (hex.isSpotted(0)) hex.setSpotted(0, false) } }
        listOf(3 to 4, 3 to 5).forEach { (r, c) -> map.map!![r][c].setSpotted(0, true) }

        val normal = reachable(map, unit)
        assertTrue((2 to 3) in normal, "an unspotted neighbour is normally reachable")

        CautiousMove.toggle(map, unit)
        val cautious = reachable(map, unit)
        assertTrue((3 to 4) in cautious && (3 to 5) in cautious, "spotted hexes stay reachable")
        assertFalse((2 to 3) in cautious, "unspotted ground is closed")
        assertEquals(setOf(3 to 4, 3 to 5), cautious)
    }

    @Test
    fun itBelongsToOneSelectionAndTogglesOff() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val first = place(map, infantryEqid, 3, 3, 0)
        val second = place(map, infantryEqid, 5, 5, 0)

        CautiousMove.toggle(map, first)
        assertTrue(CautiousMove.isOn(map, first))
        assertFalse(CautiousMove.isOn(map, second), "another unit is not cautious")

        CautiousMove.toggle(map, first)
        assertFalse(CautiousMove.isOn(map, first))
        map.setMoveRange(first)
    }

    @Test
    fun theChipDisappearsOnceTheUnitHasMoved() {
        val map = world()
        val unit = place(map, infantryEqid, 3, 3, 0)
        val context = UnitActionContext(map = map, unit = unit, currentPlayer = friendly)

        assertTrue(CautiousMove.availability(context).applicable)
        unit.hasMoved = true
        assertFalse(CautiousMove.availability(context).applicable)
    }

    @Test
    fun theOrderCarriesTheFlagAndOldOrdersMeanNormal() {
        val order =
            MoveUnit(7, HexCoordinate(1, 2), HexCoordinate(3, 4), emptyList(), 0, cautious = true)
        assertTrue((GameCommandJson.decode(order.toPayloadJson()) as MoveUnit).cautious)

        val plain = order.copy(cautious = false)
        assertFalse(plain.toPayloadJson().contains("cautious"), "absent unless true")
        assertFalse((GameCommandJson.decode(plain.toPayloadJson()) as MoveUnit).cautious)
    }
}
