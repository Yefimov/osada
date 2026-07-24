package org.osada.ui

import kotlinx.browser.document
import org.osada.TooltipColor
import org.osada.TooltipStyle
import org.w3c.dom.HTMLElement
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for tooltip helpers and deploy/combat-log state helpers in [UIBuilder].
 */
class UIBuilderMiscTest {
    @BeforeTest
    fun setup() {
        listOf(
            "game",
            "gameToolTip",
            "gameToolTipMessage",
            "gameToolTipOk",
            "uiToolTip",
            "uiToolTipMessage",
            "combatLogButton",
            "statusBarButton",
            "unitsBarButton",
            "statusbar",
            "weathermsg",
        ).forEach { id ->
            if (byId(id) == null) {
                val container = document.createElement("div") as HTMLElement
                container.id = id
                document.body?.appendChild(container)
            }
        }
    }

    @Test
    fun gameToolTipShowsTooltip() {
        UIBuilder.gameToolTip("Hint", 100, 200)
        assertTrue(isVisible("gameToolTip"))
        assertEquals("Hint", byId("gameToolTipMessage")?.innerHTML)
        assertTrue(byId("gameToolTipOk")?.title?.contains("Dismiss") == true)
        val tooltip = byId("gameToolTip")
        assertNotNull(tooltip)
        assertEquals("${200 - 55}px", tooltip.style.top)
        assertEquals("${100 + 55}px", tooltip.style.left)
    }

    @Test
    fun gameSmallToolTipCreatesElement() {
        UIBuilder.smallToolTipList.clear()
        UIBuilder.gameSmallToolTip("Small", 10, 20, TooltipColor.PLAYER, "mytip", TooltipStyle.TEXT)
        val tip = byId("mytip")
        assertNotNull(tip)
        assertEquals("smallToolTip", tip.className)
        assertEquals("inline", tip.style.display)
        assertTrue(UIBuilder.smallToolTipList.contains("mytip"))
    }

    @Test
    fun gameSmallToolTipAutoGeneratesId() {
        UIBuilder.smallToolTipList.clear()
        UIBuilder.gameSmallToolTip("A", 0, 0, TooltipColor.ENEMY, null, TooltipStyle.PIN)
        assertTrue(UIBuilder.smallToolTipList.isNotEmpty())
        assertNotNull(byId(UIBuilder.smallToolTipList[0]))
    }

    @Test
    fun uiToolTipShowsTooltip() {
        UIBuilder.uiToolTip("UI Hint", 30, 40, true)
        assertTrue(isVisible("uiToolTip"))
        assertEquals("UI Hint", byId("uiToolTipMessage")?.innerHTML)
        val tooltip = byId("uiToolTip") ?: return
        assertEquals("right", tooltip.getAttribute("orientation"))
        assertEquals("40px", tooltip.style.top)
        assertEquals("30px", tooltip.style.left)
    }

    @Test
    fun uiToolTipAtElementShowsTooltip() {
        val target = document.createElement("div") as HTMLElement
        target.id = "tooltipTarget"
        document.body?.appendChild(target)
        UIBuilder.uiToolTipAtElement(target, "Element hint", false)
        assertTrue(isVisible("uiToolTip"))
        assertEquals("Element hint", byId("uiToolTipMessage")?.innerHTML)
    }

    // OSADA: the deploy-strip buttons this once swapped in (#statusBarButton/#unitsBarButton)
    // are gone — the reserve list lives inside the equipment window now, and both ids stay
    // CSS-hidden regardless of what this function sets (see UIBuilder.setDeployOrCombatLogState
    // doc). It's now a one-line wrapper that unconditionally shows the always-on combat log
    // button, ignoring its `deploy` parameter — this test asserts exactly that reduced behavior.
    @Test
    fun setDeployOrCombatLogStateAlwaysShowsCombatLogButton() {
        UIBuilder.setDeployOrCombatLogState(true)
        assertTrue(isVisible("combatLogButton"))
        UIBuilder.setDeployOrCombatLogState(false)
        assertTrue(isVisible("combatLogButton"))
    }
}
