package org.osada.ui

import kotlinx.browser.document
import org.osada.uiSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Folding the unit card (`#ucCollapse`) folds the desktop forecast and enemy card with it, because
 * the cursor already carries the same forecast -- but never where the forecast is the attack's own
 * confirmation ([TargetPreviewController]).
 */
class BottomZoneFoldTest {
    private var savedVisibility = true
    private var savedConfirm = ConfirmAttacks.AUTO

    @BeforeTest
    fun setUp() {
        savedVisibility = uiSettings.unitInfoVisibility
        savedConfirm = uiSettings.confirmAttacks
        val bz = document.createElement("div")
        bz.id = "osada-bottomzone"
        document.body!!.appendChild(bz)
    }

    @AfterTest
    fun tearDown() {
        uiSettings.unitInfoVisibility = savedVisibility
        uiSettings.confirmAttacks = savedConfirm
        document.getElementById("osada-bottomzone")?.remove()
    }

    private fun zoneClass() = document.getElementById("osada-bottomzone")!!.className

    @Test
    fun foldedCardHidesForecastAndEnemyCard() {
        uiSettings.unitInfoVisibility = false
        uiSettings.confirmAttacks = ConfirmAttacks.OFF
        BottomZoneBuilder.setState("hover")
        assertEquals("", zoneClass())
        BottomZoneBuilder.setState("enemyAlone")
        assertEquals("", zoneClass())
    }

    @Test
    fun openCardKeepsForecast() {
        uiSettings.unitInfoVisibility = true
        uiSettings.confirmAttacks = ConfirmAttacks.OFF
        BottomZoneBuilder.setState("hover")
        assertTrue(zoneClass().contains("bz--hover"))
    }

    @Test
    fun foldedCardKeepsForecastWhenItCarriesTheAttackConfirmation() {
        uiSettings.unitInfoVisibility = false
        uiSettings.confirmAttacks = ConfirmAttacks.ON
        BottomZoneBuilder.setState("hover")
        assertTrue(zoneClass().contains("bz--hover"))
    }

    @Test
    fun foldingWhileHoveringClearsTheZone() {
        uiSettings.unitInfoVisibility = true
        uiSettings.confirmAttacks = ConfirmAttacks.OFF
        BottomZoneBuilder.setState("hover")
        uiSettings.unitInfoVisibility = false
        BottomZoneBuilder.onPlayerCardHidden()
        assertEquals("", zoneClass())
    }
}
