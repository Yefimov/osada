package org.osada.ui

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [UIBuilder.buildMainMenu] — OSADA Stage-3 top bar (MainMenuBuilder.kt). The former
 * floating icon rail (#menu/#slidemenu, smallButtonMenu buttons incl. #endturn/#inspectunit/
 * #hex/#air/#mainmenu) was dissolved; buildMainMenu() now builds the full-width top bar directly
 * (osada-tb-* / osada-et classes) and only reuses a handful of legacy ids (buy/zoom/options,
 * kept so MenuController.mainMenuButton's action router doesn't change; combatLogButton/
 * statusBarButton/unitsBarButton, repositioned by CSS not rebuilt).
 */
class UIBuilderMainMenuTest {
    @BeforeTest
    fun setup() {
        listOf(
            "statusbar",
            "statusmsg",
            "weathermsg",
            "locmsg",
            "combatLogButton",
            "statusBarButton",
            "unitsBarButton",
            "osadaSideToggle",
        ).forEach { id ->
            if (byId(id) == null) {
                val container = document.createElement("div") as HTMLElement
                container.id = id
                document.body?.appendChild(container)
            }
        }
    }

    @Test
    fun buildMainMenuCreatesTopBarButtons() {
        UIBuilder.buildMainMenu()

        val reserves = byId("buy")
        assertNotNull(reserves, "expected Reserves button with id buy")
        assertTrue(reserves.className.contains("osada-tb-btn"))

        listOf("zoom" to "recon", "options" to "settings").forEach { (id, icoMod) ->
            val button = byId(id)
            assertNotNull(button, "expected icon button with id $id")
            assertTrue(button.className.contains("osada-tb-icon"), "$id should have osada-tb-icon class")
            assertTrue(button.className.contains("osada-ico--$icoMod"), "$id should have osada-ico--$icoMod class")
        }
    }

    @Test
    fun endTurnButtonUsesOsadaEtClasses() {
        UIBuilder.buildMainMenu()
        val endTurn = byId("osadaEndTurn")
        assertNotNull(endTurn)
        assertTrue(endTurn.className.contains("osada-et"))
        assertEquals("End turn", byId("osadaEndTurn")?.firstElementChild?.textContent)
    }

    @Test
    fun readyUnitNavigatorIsCreated() {
        UIBuilder.buildMainMenu()
        assertNotNull(byId("osadaNav"))
        assertNotNull(byId("osadaNavPrev"))
        assertNotNull(byId("osadaNavNext"))
        assertEquals("0", byId("osadaNavCount")?.textContent)
    }

    @Test
    fun importantTopBarControlsExplainTheirGameplayEffect() {
        UIBuilder.buildMainMenu()
        listOf(
            "buy",
            "zoom",
            "options",
            "osadaHqBtn",
            "osadaNavCount",
            "osadaEndTurn",
            "statusBarButton",
            "unitsBarButton",
        ).forEach { id ->
            val title = byId(id)?.title.orEmpty()
            assertTrue(title.length > 25, "$id should have an explanatory tooltip, got '$title'")
        }
    }

    @Test
    fun buildMainMenuDissolvesFloatingRail() {
        val menu =
            byId("menu") ?: (document.createElement("div") as HTMLElement).apply {
                id = "menu"
                document.body?.appendChild(this)
            }
        UIBuilder.buildMainMenu()
        assertEquals("none", menu.style.display)
    }

    @Test
    fun combatLogButtonHasSelectedGlyph() {
        UIBuilder.buildMainMenu()
        val combatLogButton = byId("combatLogButton")
        assertNotNull(combatLogButton)
        assertTrue(combatLogButton.asDynamic().hasSelectedGlyph as? Boolean ?: false)
    }
}
