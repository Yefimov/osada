package org.osada.rules

import org.osada.UnitClass
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the split between the equipment window's TAB list (a UI concern) and the rule about
 * which equipment classes may be purchased.
 *
 * PM 3.2.14 used `UIBuilder.eqClassButtons` — an 8-entry tab map — as the purchase whitelist
 * (`openpanzer.js:6524`), keyed on the *currently selected unit's* class. Selecting a
 * fortification, transport or ship therefore disabled the Buy button for every item in the
 * catalogue, even though buying only ever reads the selected EQUIPMENT
 * (`EquipmentWindowButtons.onBuy`). These tests lock in that all 21 real classes are purchasable
 * and that the tab list can never again silently become a rules gate.
 */
class PurchasableClassTest {
    @Test
    fun everyRealClassIsPurchasable() {
        UnitClass.entries
            .filter { it != UnitClass.NONE }
            .forEach {
                assertTrue(
                    GameRules.isPurchasableClass(it.value),
                    "${it.name} is real equipment in OG efile data and must be purchasable",
                )
            }
    }

    @Test
    fun noClassIsNotPurchasable() {
        assertFalse(
            GameRules.isPurchasableClass(UnitClass.NONE.value),
            "NONE is the absent-equipment sentinel, not a buyable class",
        )
    }

    /**
     * The classes PM's tab-map gate excluded. Named explicitly so a future change that
     * reintroduces a tab-shaped whitelist fails here rather than silently in the UI.
     */
    @Test
    fun classesWithoutATabAreStillPurchasable() {
        listOf(
            UnitClass.FLAK,
            UnitClass.FORTIFICATION,
            UnitClass.GROUND_TRANSPORT,
            UnitClass.LEVEL_BOMBER,
            UnitClass.AIR_TRANSPORT,
            UnitClass.SUBMARINE,
            UnitClass.DESTROYER,
            UnitClass.BATTLESHIP,
            UnitClass.CARRIER,
            UnitClass.NAVAL_TRANSPORT,
            UnitClass.BATTLE_CRUISER,
            UnitClass.CRUISER,
            UnitClass.LIGHT_CRUISER,
        ).forEach {
            assertTrue(
                GameRules.isPurchasableClass(it.value),
                "${it.name} has no equipment tab of its own but must still be purchasable",
            )
        }
    }
}
