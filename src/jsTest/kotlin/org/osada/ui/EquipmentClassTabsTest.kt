package org.osada.ui

import org.osada.UnitClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The class-tab row's coverage contract. PM shipped 8 tabs for 21 classes and no mapping, so 13
 * classes had no tab at all; "All", Naval and Fortification became real tabs on 2026-07-26 once
 * `#eqSelClass` scrolled and row width stopped being the constraint (`DEFERRED.md` §5.7).
 */
class EquipmentClassTabsTest {
    @Test
    fun everyRealUnitClassIsReachableFromSomeVisibleTab() {
        val covered =
            UIBuilder.eqClassButtons.keys
                .mapNotNull { it.toIntOrNull() }
                .filter { it != UnitClass.NONE.value }
                .flatMap { UIBuilder.classesForTab(it) }
                .map { it.value }
                .toSet()

        // The two carrier classes are deliberately reachable ONLY from "All" (2026-07-26): they
        // used to ride along in the Tank and Air Fighter tabs, which put Horse/Mules/Wagon in the
        // middle of the tank list. A transport is chosen in the transport strip under the unit
        // being bought, not browsed as a combat class.
        val allOnly = setOf(UnitClass.GROUND_TRANSPORT, UnitClass.AIR_TRANSPORT)
        val missing = UnitClass.entries.filter { it != UnitClass.NONE && it !in allOnly && it.value !in covered }
        assertTrue(missing.isEmpty(), "no tab lists: $missing")
        assertTrue(allOnly.none { it.value in covered }, "carrier classes must not reappear in a combat-class tab")
    }

    @Test
    fun allIsTheLeftmostTabAndSelectsEveryClass() {
        assertEquals(UnitClass.NONE.value.toString(), UIBuilder.eqClassButtons.keys.first())
    }

    @Test
    fun theNavalTabGathersAllEightNavalClasses() {
        val naval = UIBuilder.classesForTab(UIBuilder.navalTabClass).toSet()

        assertEquals(
            setOf(
                UnitClass.SUBMARINE,
                UnitClass.DESTROYER,
                UnitClass.BATTLESHIP,
                UnitClass.CARRIER,
                UnitClass.NAVAL_TRANSPORT,
                UnitClass.BATTLE_CRUISER,
                UnitClass.CRUISER,
                UnitClass.LIGHT_CRUISER,
            ),
            naval,
        )
    }

    /** Fortification used to be merged into Infantry purely for want of room. */
    @Test
    fun fortificationHasItsOwnTabAndIsNoLongerMergedIntoInfantry() {
        assertTrue(UIBuilder.eqClassButtons.containsKey(UnitClass.FORTIFICATION.value.toString()))
        assertEquals(
            listOf(UnitClass.INFANTRY),
            UIBuilder.classesForTab(UnitClass.INFANTRY.value),
        )
    }

    /** A merged class must normalise to the tab that lists it, or the strip filter and the tab
     *  highlight disagree about which tab the player is on. */
    @Test
    fun mergedClassesNormaliseToTheTabThatListsThem() {
        assertEquals(UnitClass.AIR_DEFENCE.value, EquipmentWindowState.normalizeUnitClass(UnitClass.FLAK.value))
        assertEquals(UIBuilder.navalTabClass, EquipmentWindowState.normalizeUnitClass(UnitClass.CARRIER.value))
        // Ground Transport no longer normalises onto Tank — it belongs to no combat-class tab.
        assertEquals(
            UnitClass.GROUND_TRANSPORT.value,
            EquipmentWindowState.normalizeUnitClass(UnitClass.GROUND_TRANSPORT.value),
        )
        // Own tab now, so it must normalise to itself rather than to Infantry.
        assertEquals(
            UnitClass.FORTIFICATION.value,
            EquipmentWindowState.normalizeUnitClass(UnitClass.FORTIFICATION.value),
        )
    }

    /** Every tab needs a localization key, or it silently keeps its English fallback label. */
    @Test
    fun everyTabHasALocalizationKey() {
        val keyed = setOf("0", "1", "2", "3", "4", "6", "8", "9", "10", "11", "15")

        assertEquals(keyed, UIBuilder.eqClassButtons.keys.toSet())
    }
}
