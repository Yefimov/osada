package org.osada.ui

import kotlinx.browser.document
import org.osada.TooltipColor
import org.osada.TooltipStyle
import org.w3c.dom.HTMLElement
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // OSADA: the tooltip is no longer placed at a fixed (x - 55, y + 55); it takes an ANCHOR
    // PROVIDER re-evaluated on every placement pass (see GameToolTipPlacement), so the position
    // depends on the measured panel and on #game's visible box. This test asserts the contract
    // that survives without a stylesheet — shown, filled, anchored, dismissible — not pixels.
    @Test
    fun gameToolTipShowsTooltipAndConsultsItsAnchor() {
        var anchorCalls = 0
        UIBuilder.gameToolTip("Hint") {
            anchorCalls++
            GameToolTipAnchor(x = 100.0, y = 200.0, halfWidth = 20.0)
        }
        assertTrue(isVisible("gameToolTip"))
        val tooltip = byId("gameToolTip")
        assertNotNull(tooltip)
        assertEquals("game", tooltip.getAttribute("type"))
        // flex, not makeVisible()'s "inline": the message/footer column layout depends on it.
        assertEquals("flex", tooltip.style.display)
        assertEquals("Hint", byId("gameToolTipMessage")?.innerHTML)
        assertTrue(byId("gameToolTipOk")?.title?.isNotEmpty() == true)
        assertTrue(anchorCalls > 0, "placement must read the anchor provider")
        assertTrue(tooltip.style.left.endsWith("px"))
        assertTrue(tooltip.style.top.endsWith("px"))
        assertNotNull(tooltip.getAttribute("orientation"))
    }

    @Test
    fun gameToolTipOkHidesTooltip() {
        UIBuilder.gameToolTip("Hint") { GameToolTipAnchor(x = 10.0, y = 10.0, halfWidth = 5.0) }
        assertTrue(isVisible("gameToolTip"))
        byId("gameToolTipOk")?.click()
        assertFalse(isVisible("gameToolTip"))
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
