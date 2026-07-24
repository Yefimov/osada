package org.osada.ui

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the cross-campaign Hall of Fame store (design brief §14.6): notable commanders persist
 * across campaigns in localStorage and de-duplicate on name + campaign.
 */
class HallOfFameTest {
    private fun entry(
        name: String,
        campaign: String,
    ) = HallOfFame.Entry(name, "Major", "Hero", "Legendary", "Killed in Action", campaign)

    @BeforeTest
    fun setup() = HallOfFame.clear()

    @AfterTest
    fun teardown() = HallOfFame.clear()

    @Test
    fun emptyByDefault() {
        assertTrue(HallOfFame.all().isEmpty())
        assertFalse(HallOfFame.isNotEmpty())
    }

    @Test
    fun harvestStoresAndPersistsEntries() {
        HallOfFame.harvest(listOf(entry("Voroshin", "Uranus"), entry("Belov", "Uranus")))
        assertTrue(HallOfFame.isNotEmpty())
        val names = HallOfFame.all().map { it.name }.toSet()
        assertEquals(setOf("Voroshin", "Belov"), names)
    }

    @Test
    fun deduplicatesByNameAndCampaign() {
        HallOfFame.harvest(listOf(entry("Voroshin", "Uranus")))
        HallOfFame.harvest(listOf(entry("Voroshin", "Uranus")))
        assertEquals(1, HallOfFame.all().count { it.name == "Voroshin" })
        // The same officer in a different campaign is a distinct legend.
        HallOfFame.harvest(listOf(entry("Voroshin", "Bagration")))
        assertEquals(2, HallOfFame.all().count { it.name == "Voroshin" })
    }
}
