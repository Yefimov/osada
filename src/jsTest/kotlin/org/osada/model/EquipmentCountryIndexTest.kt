package org.osada.model

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Equipment is fetched for the ids a battle REFERENCES, not for the nationalities it declares.
 *
 * The bug this locks down was reported against Falciu 2 (`rcampfa2.xml`): the Romanian objective at
 * Tiganca is garrisoned by eqid 16132, written `flag="14"` under a player with `country="13"` and
 * `support="14"`. Every one of those resolves to country file 14, and the record lives in file 13 —
 * so the file was never fetched, `getEquipment(16132)` answered an empty [EquipmentData], and the
 * garrison sat on the victory hex with no class, no icon and no combat stats, unattackable for the
 * whole battle. A repo-wide audit found 820 such references across 131 deployed scenarios.
 *
 * The numbers below are the real ones from that scenario and from the shipped index.
 */
class EquipmentCountryIndexTest {
    /** The three country files Falciu 2's own equipment is split across, as the sidecar states. */
    private val shippedShape =
        listOf(
            listOf(0, 1, 1602),
            listOf(13, 15815, 16264),
            listOf(14, 16265, 16700),
            listOf(62, 30000, 30500),
        )

    @BeforeTest
    fun setUp() {
        Equipment.asyncLoad = false
        EquipmentCountryIndex.resetForTest()
        Equipment.resetEquipment()
    }

    @AfterTest
    fun tearDown() {
        EquipmentCountryIndex.resetForTest()
        Equipment.resetEquipment()
    }

    @Test
    fun resolvesAnEqidToTheCountryFileThatOwnsIt() {
        EquipmentCountryIndex.installForTest(shippedShape)

        assertEquals(13, EquipmentCountryIndex.countryFileFor(16132), "Tiganca's garrison")
        assertEquals(13, EquipmentCountryIndex.countryFileFor(15815), "first id of the segment")
        assertEquals(13, EquipmentCountryIndex.countryFileFor(16264), "last id of the segment")
        assertEquals(14, EquipmentCountryIndex.countryFileFor(16265), "first id of the next one")
        assertEquals(0, EquipmentCountryIndex.countryFileFor(1), "the universal file")
    }

    @Test
    fun anIdInNoSegmentIsUnknownRatherThanGuessed() {
        EquipmentCountryIndex.installForTest(shippedShape)

        assertNull(EquipmentCountryIndex.countryFileFor(1603), "the gap between two segments")
        assertNull(EquipmentCountryIndex.countryFileFor(999_999), "past the end")
        assertNull(EquipmentCountryIndex.countryFileFor(0), "not an equipment id")
    }

    /** No sidecar (an old build, or Karma, where `resources/` is not served) must degrade, not fail. */
    @Test
    fun withoutAnIndexNothingIsResolvedAndNothingThrows() {
        assertFalse(EquipmentCountryIndex.isLoaded)
        assertNull(EquipmentCountryIndex.countryFileFor(16132))
        assertEquals(emptySet(), EquipmentCountryIndex.countryFilesFor(listOf(16132, 1)))
    }

    @Test
    fun theFalciuTwoGarrisonsCountryFileIsAddedToTheLoadSet() {
        EquipmentCountryIndex.installForTest(shippedShape)
        val romanian =
            Player().apply {
                country = 13
                supportCountries = mutableListOf(14)
            }
        val soviet =
            Player().apply {
                country = 61
                supportCountries = mutableListOf(62)
            }

        Equipment.addPlayersEquipment(listOf(soviet, romanian), setOf(16132)) { }

        // The load set holds 0-BASED country ids; file N is fetched as id N-1.
        assertTrue(12 in Equipment.equipmentToLoadSet, "country file 13 — where eqid 16132 lives")
        assertTrue(13 in Equipment.equipmentToLoadSet, "country file 14 — the player's own")
        assertTrue(-1 in Equipment.equipmentToLoadSet, "the universal file is always loaded")
        assertTrue(61 in Equipment.equipmentToLoadSet, "the Soviet player's own file")
    }

    /**
     * The whole point of resolving by id: the fetch widens, the player's shopping list does not.
     * [org.osada.ui.EquipmentCatalogStrip] builds the purchase/upgrade catalogue from
     * `supportCountries`, so repairing a fetch through that list would hand the player another
     * nation's equipment.
     */
    @Test
    fun loadingByIdDoesNotWidenPurchasePermissions() {
        EquipmentCountryIndex.installForTest(shippedShape)
        val romanian =
            Player().apply {
                country = 13
                supportCountries = mutableListOf(14)
            }

        Equipment.addPlayersEquipment(listOf(romanian), setOf(16132)) { }

        assertEquals(listOf(14), romanian.supportCountries, "support countries were not touched")
        assertEquals(13, romanian.country, "nationality was not touched")
    }

    /** Without required ids this is exactly the player-list loader it replaced. */
    @Test
    fun withNoRequiredIdsOnlyThePlayerListIsLoaded() {
        EquipmentCountryIndex.installForTest(shippedShape)
        val romanian =
            Player().apply {
                country = 13
                supportCountries = mutableListOf(14)
            }

        Equipment.addPlayersEquipment(listOf(romanian)) { }

        assertEquals(setOf(-1, 13), Equipment.equipmentToLoadSet)
    }

    @Test
    fun theCompletionCallbackStillRunsOnce() {
        EquipmentCountryIndex.installForTest(shippedShape)
        var completions = 0

        Equipment.addPlayersEquipment(listOf(Player().apply { country = 13 }), setOf(16132)) {
            completions++
        }

        assertEquals(1, completions, "onComplete fires exactly once, after every file is accounted for")
    }
}
