package org.osada.ui

import org.osada.GameHolder
import org.osada.model.Cell
import org.osada.rules.HexGeometry
import org.osada.rules.OgRulesTestHarness
import org.osada.ui.keyboard.CommandCatalog
import org.osada.uiSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `docs/design/og-parity-followups.md` §2 (range of fire for the selected unit, [FireRangeOverlay])
 * and §3 (hide strength numbers and the combat estimate, [HiddenNumbers]).
 */
class FireRangeAndHiddenNumbersTest : OgRulesTestHarness() {
    private var fogWasOff = false
    private var hideWas = false

    @BeforeTest
    fun setup() {
        installTestWorld()
        fogWasOff = uiSettings.noFOW
        hideWas = uiSettings.hideNumbers
        uiSettings.noFOW = false
        FireRangeOverlay.inspectedEnemyId = null
    }

    @AfterTest
    fun teardown() {
        uiSettings.noFOW = fogWasOff
        uiSettings.hideNumbers = hideWas
        FireRangeOverlay.inspectedEnemyId = null
        clearTestWorld()
    }

    private fun keys(
        cells: List<Cell>,
        cols: Int,
    ): Set<Int> = cells.map { it.row * cols + it.col }.toSet()

    @Test
    fun aRangeOneUnitReachesExactlyItsSixNeighbours() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val unit = place(map, infantryEqid, 3, 3, 0)
        map.currentUnit = unit

        val envelope = FireRangeOverlay.compute(map, 0)

        assertEquals(keys(HexGeometry.getAdjacent(3, 3), map.cols), envelope)
        assertFalse(3 * map.cols + 3 in envelope, "the unit's own hex is not a target")
    }

    @Test
    fun aRangedUnitReachesFurtherThanItsNeighbours() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val gun = place(map, riflemanEqid, 3, 3, 0)
        map.currentUnit = gun

        val envelope = FireRangeOverlay.compute(map, 0)

        assertTrue(envelope.containsAll(keys(HexGeometry.getAdjacent(3, 3), map.cols)))
        assertTrue(3 * map.cols + 7 in envelope, "four columns away is inside a range-4 envelope")
        assertFalse(3 * map.cols + 3 in envelope)
    }

    @Test
    fun withNothingSelectedTheInspectedVisibleEnemyIsDrawn() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val enemy = place(map, riflemanEqid, 4, 4, 1)
        map.map!![4][4].setSpotted(0, true)
        FireRangeOverlay.inspectedEnemyId = enemy.id

        assertEquals(enemy.id, FireRangeOverlay.subject(map, 0)?.id)
        assertTrue(FireRangeOverlay.compute(map, 0).isNotEmpty())
    }

    @Test
    fun anEnemyBackInFogTakesItsEnvelopeWithIt() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val enemy = place(map, riflemanEqid, 4, 4, 1)
        val hex = map.map!![4][4]
        while (hex.isSpotted(0)) hex.setSpotted(0, false)
        FireRangeOverlay.inspectedEnemyId = enemy.id

        assertEquals(null, FireRangeOverlay.subject(map, 0))
        assertTrue(FireRangeOverlay.compute(map, 0).isEmpty())
    }

    @Test
    fun theOwnSelectionWinsOverAnInspectedEnemy() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val own = place(map, infantryEqid, 1, 1, 0)
        val enemy = place(map, riflemanEqid, 5, 5, 1)
        map.map!![5][5].setSpotted(0, true)
        FireRangeOverlay.inspectedEnemyId = enemy.id
        map.currentUnit = own

        assertEquals(own.id, FireRangeOverlay.subject(map, 0)?.id)
    }

    @Test
    fun hiddenNumbersMaskOnlyOtherSidesStrength() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val own = place(map, infantryEqid, 1, 1, 0).apply { strength = 7 }
        val enemy = place(map, infantryEqid, 5, 5, 1).apply { strength = 4 }

        uiSettings.hideNumbers = false
        assertEquals("4", HiddenNumbers.strengthFor(enemy, 0))
        assertTrue(HiddenNumbers.showCounterStrength && HiddenNumbers.showCombatEstimate)

        uiSettings.hideNumbers = true
        assertEquals("7", HiddenNumbers.strengthFor(own, 0), "the player's own strength stays readable")
        assertEquals(HiddenNumbers.PLACEHOLDER, HiddenNumbers.strengthFor(enemy, 0))
        assertFalse(HiddenNumbers.showCounterStrength)
        assertFalse(HiddenNumbers.showCombatEstimate)
    }

    @Test
    fun bothPreferencesPersistWithTheSettings() {
        val fireWas = uiSettings.fireRange
        try {
            uiSettings.fireRange = true
            uiSettings.setFlag("hideNumbers", true)
            assertTrue(uiSettings.getFlag("hideNumbers"))
            val json = uiSettings.toJSON()
            assertEquals(true, json.fireRange)
            assertEquals(true, json.hideNumbers)
        } finally {
            uiSettings.fireRange = fireWas
        }
    }

    @Test
    fun gTogglesTheRangeOfFireUnderAnyLayout() {
        assertEquals(
            CommandCatalog.FIRE_RANGE,
            CommandCatalog.match(code = "KeyG", key = "п", ctrl = false, alt = false)?.id,
        )
    }
}
