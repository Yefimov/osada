package org.osada.ui

import kotlinx.browser.document
import org.osada.UnitClass
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.resetEquipment
import org.w3c.dom.HTMLElement
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [UIBuilder.showEquipmentCosts] and [UIBuilder.showAttackInfo].
 */
class UIBuilderCombatInfoTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
        Equipment.resetEquipment()
        Equipment.putEquipment(
            1,
            EquipmentData().apply {
                name = "Test Infantry"
                uclass = UnitClass.INFANTRY.value
            },
        )
        Equipment.putEquipment(
            2,
            EquipmentData().apply {
                name = "Test Tank"
                uclass = UnitClass.TANK.value
            },
        )

        listOf(
            "statusmsg",
            "eqNewText",
            "eqNewCost",
            "eqNewBut",
            "eqUpgradeText",
            "eqUpgradeCost",
            "eqUpgradeBut",
            "eqSellText",
            "eqSellCost",
            "eqSellBut",
            "currentPrestige",
            "currentPrestigeAmount",
        ).forEach { id ->
            if (byId(id) == null) {
                val container = document.createElement("div") as HTMLElement
                container.id = id
                document.body?.appendChild(container)
            }
        }
    }

    @Test
    fun showEquipmentCostsDisplaysBuyButtonWhenAffordable() {
        UIBuilder.showEquipmentCosts(prestige = 100, buy = 50, upgrade = 0, sell = 0)
        assertEquals("Buy", byId("eqNewText")?.innerHTML)
        val costHtml = byId("eqNewCost")?.innerHTML ?: ""
        assertTrue(costHtml.contains("50"))
        assertTrue(costHtml.contains("currency.png"))
        assertEquals("inline-block", byId("eqNewBut")?.style?.display)
    }

    @Test
    fun showEquipmentCostsHidesBuyButtonAndShowsShortfallWhenTooExpensive() {
        UIBuilder.showEquipmentCosts(prestige = 30, buy = 50, upgrade = 0, sell = 0)
        assertTrue(byId("eqNewText")?.innerHTML?.contains("Need 20 more prestige to buy.") ?: false)
        assertEquals("none", byId("eqNewBut")?.style?.display)
        assertEquals("", byId("eqNewCost")?.textContent)
    }

    /**
     * A rule refusing the purchase outranks the wallet: saving up cannot lift the rule, so the
     * shortfall line would point the player at a unit they can never buy and hide the reason why.
     */
    @Test
    fun showEquipmentCostsPrefersTheBlockingRuleOverTheShortfall() {
        UIBuilder.showEquipmentCosts(
            prestige = 30,
            buy = 50,
            upgrade = 0,
            sell = 0,
            buyBlockedReason = "No supply hex or deployment zone in this scenario",
        )
        val html = byId("eqNewText")?.innerHTML ?: ""
        assertTrue(html.contains("No supply hex or deployment zone in this scenario"), html)
        assertTrue(!html.contains("Need 20 more prestige"), html)
        assertEquals("none", byId("eqNewBut")?.style?.display)
    }

    @Test
    fun showEquipmentCostsHidesBuyButtonWhenCostIsZero() {
        UIBuilder.showEquipmentCosts(prestige = 100, buy = 0, upgrade = 0, sell = 0)
        assertEquals("", byId("eqNewText")?.textContent)
        assertEquals("none", byId("eqNewBut")?.style?.display)
    }

    @Test
    fun showEquipmentCostsDisplaysUpgradeButtonWhenAffordable() {
        UIBuilder.showEquipmentCosts(prestige = 100, buy = 0, upgrade = 40, sell = 0)
        assertEquals("Upgrade", byId("eqUpgradeText")?.innerHTML)
        val costHtml = byId("eqUpgradeCost")?.innerHTML ?: ""
        assertTrue(costHtml.contains("40"))
        assertTrue(costHtml.contains("currency.png"))
        assertEquals("inline-block", byId("eqUpgradeBut")?.style?.display)
    }

    @Test
    fun showEquipmentCostsHidesUpgradeButtonAndShowsShortfallWhenTooExpensive() {
        UIBuilder.showEquipmentCosts(prestige = 10, buy = 0, upgrade = 25, sell = 0)
        assertTrue(byId("eqUpgradeText")?.innerHTML?.contains("Need 15 more prestige to upgrade.") ?: false)
        assertEquals("none", byId("eqUpgradeBut")?.style?.display)
        assertEquals("", byId("eqUpgradeCost")?.textContent)
    }

    @Test
    fun showEquipmentCostsDisplaysSellButtonWhenSellValuePositive() {
        UIBuilder.showEquipmentCosts(prestige = 100, buy = 0, upgrade = 0, sell = 15)
        assertEquals("Sell", byId("eqSellText")?.innerHTML)
        val costHtml = byId("eqSellCost")?.innerHTML ?: ""
        assertTrue(costHtml.contains("15"))
        assertTrue(costHtml.contains("currency.png"))
        assertEquals("inline-block", byId("eqSellBut")?.style?.display)
    }

    @Test
    fun showEquipmentCostsHidesSellButtonWhenSellValueZero() {
        UIBuilder.showEquipmentCosts(prestige = 100, buy = 0, upgrade = 0, sell = 0)
        assertEquals("none", byId("eqSellBut")?.style?.display)
        assertEquals("", byId("eqSellCost")?.textContent)
        assertEquals("", byId("eqSellText")?.textContent)
    }

    @Test
    fun showEquipmentCostsUpdatesCurrentPrestige() {
        UIBuilder.showEquipmentCosts(prestige = 123, buy = 0, upgrade = 0, sell = 0)
        val label = byId("currentPrestige")?.textContent ?: ""
        assertTrue(label == "Available Prestige: " || label == "Now: ")
        val amountHtml = byId("currentPrestigeAmount")?.innerHTML ?: ""
        assertTrue(amountHtml.contains("123"))
        assertTrue(amountHtml.contains("currency.png"))
    }

    @Test
    fun showAttackInfoRendersAttackerAndDefenderDetails() {
        val attacker = GameUnit(1).apply { flag = 1 }
        val defender = GameUnit(2).apply { flag = 2 }

        UIBuilder.showAttackInfo(attacker, defender)

        val statusMsg = byId("statusmsg")
        assertNotNull(statusMsg)
        val children = statusMsg.childNodes.length
        assertTrue(children >= 5)
        assertTrue(statusMsg.innerHTML.contains("Test Infantry"))
        assertTrue(statusMsg.innerHTML.contains("Infantry"))
        assertTrue(statusMsg.innerHTML.contains("Test Tank"))
        assertTrue(statusMsg.innerHTML.contains("Tank"))
    }

    @Test
    fun showAttackInfoClearsPreviousContent() {
        byId("statusmsg")?.innerHTML = "old content"
        val attacker = GameUnit(1).apply { flag = 1 }
        val defender = GameUnit(2).apply { flag = 2 }
        UIBuilder.showAttackInfo(attacker, defender)
        assertTrue((byId("statusmsg")?.innerHTML?.contains("old content") ?: true).not())
    }
}
