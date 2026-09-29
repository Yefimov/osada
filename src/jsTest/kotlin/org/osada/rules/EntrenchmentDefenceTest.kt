package org.osada.rules

import org.osada.TerrainType
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.model.GameMap
import org.osada.model.TerrainEx
import org.osada.ui.EntrenchmentTooltip
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The defence entrenchment adds, as combat applies it and as the unit cards explain it
 * (`EntrenchmentDefence`, `ui/EntrenchmentTooltip`).
 */
class EntrenchmentDefenceTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() {
        installTestWorld()
        installEnglishUiBundleForTests()
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun cityWorld(): GameMap = world().also { it.map!![2][2].terrain = TerrainType.CITY.value }

    @Test
    fun aVehicleAttackingInfantryInACityMeetsItsEntrenchmentTwice() {
        val map = cityWorld()
        val infantry = place(map, infantryEqid, 2, 2, side = 1).apply { entrenchment = 4 }
        val tank = place(map, riflemanEqid, 2, 3, side = 0)

        val against = EntrenchmentDefence.forecast(tank, infantry)!!

        assertEquals(8, against.bonus)
        assertTrue(against.doubled)
        assertFalse(against.bypassed)
    }

    @Test
    fun infantryAttackingInfantryInACityIsCloseCombatAndNotDoubled() {
        val map = cityWorld()
        val infantry = place(map, infantryEqid, 2, 2, side = 1).apply { entrenchment = 4 }
        val attacker = place(map, infantryEqid, 2, 3, side = 0)

        val against = EntrenchmentDefence.forecast(attacker, infantry)!!

        assertEquals(4, against.bonus)
        assertFalse(against.doubled)
    }

    @Test
    fun openGroundCountsItOnce() {
        val map = world()
        val infantry = place(map, infantryEqid, 2, 2, side = 1).apply { entrenchment = 3 }
        val tank = place(map, riflemanEqid, 2, 3, side = 0)

        assertEquals(3, EntrenchmentDefence.forecast(tank, infantry)!!.bonus)
    }

    @Test
    fun theBaselineIsTheTerrainsOwnValue() {
        val map = cityWorld()
        val infantry = place(map, infantryEqid, 2, 2, side = 1)

        assertEquals(
            TerrainEx.baseEntrenchment(TerrainType.CITY.value),
            EntrenchmentDefence.terrainBaseline(infantry, map.map!![2][2]),
        )
    }

    @Test
    fun theEnemyCardNamesTheSelectedAttackersFigure() {
        val map = cityWorld()
        val infantry = place(map, infantryEqid, 2, 2, side = 1).apply { entrenchment = 4 }
        val tank = place(map, riflemanEqid, 2, 3, side = 0)

        val text = EntrenchmentTooltip.text(infantry, tank)

        assertTrue(text.startsWith("Entrenchment: 4"), text)
        assertTrue("Against Assault Gun Battalion: +8 to defence (counted twice)." in text, text)
        assertFalse("Against Assault" in EntrenchmentTooltip.text(infantry), "no attacker, no exchange line")
    }

    // ---- rugged defence: the odds combat rolls against, never a bare promise ----------------------

    @Test
    fun ruggedOddsAreTheCombatRulesOwnForTheSelectedPair() {
        val map = world()
        val defender =
            place(map, infantryEqid, 2, 2, side = 1).apply {
                entrenchment = 5
                ammo = 8
            }
        val infantry = place(map, infantryEqid, 2, 3, side = 0)
        val tank = place(map, riflemanEqid, 1, 2, side = 0)

        assertEquals(25.0, EntrenchmentDefence.ruggedOdds(infantry, defender), "5% a level")
        assertEquals(null, EntrenchmentDefence.ruggedOdds(tank, defender), "only an infantry attack raises one")

        defender.ammo = 0
        assertEquals(null, EntrenchmentDefence.ruggedOdds(infantry, defender), "no ammunition, no rugged defence")
    }

    @Test
    fun withNoAttackerTheCardStatesOnlyABaseChanceAndItsConditions() {
        val map = world()
        val defender =
            place(map, infantryEqid, 2, 2, side = 1).apply {
                entrenchment = 5
                ammo = 0
            }

        val text = EntrenchmentTooltip.text(defender)

        assertTrue("Base chance of a rugged defence: 25%" in text, text)
        assertTrue("No ammunition now" in text, text)
    }

    @Test
    fun aSelectedAttackerGetsThePairsOddsOrAPlainNone() {
        val map = world()
        val defender =
            place(map, infantryEqid, 2, 2, side = 1).apply {
                entrenchment = 5
                ammo = 8
            }
        val infantry = place(map, infantryEqid, 2, 3, side = 0)
        val tank = place(map, riflemanEqid, 1, 2, side = 0)

        val versusInfantry = EntrenchmentTooltip.text(defender, infantry)
        assertTrue("Against Rifle Division: 25% chance of a rugged defence." in versusInfantry, versusInfantry)
        val versusTank = EntrenchmentTooltip.text(defender, tank)
        assertTrue("Against Assault Gun Battalion: no rugged defence." in versusTank, versusTank)
        assertFalse("Base chance" in versusTank, "a pair is never shown the unconditional figure")
    }
}
