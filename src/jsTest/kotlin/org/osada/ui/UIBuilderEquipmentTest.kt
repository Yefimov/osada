package org.osada.ui

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [UIBuilder.buildEquipmentWindow] and [UIBuilder.setDefaultUserSelections].
 */
class UIBuilderEquipmentTest {
    @BeforeTest
    fun setup() {
        val ids =
            listOf(
                "eqSelCountry",
                "eqUserSel",
                "eqSortOrderBut",
                "eqSortOptionsBut",
                "eqSelClass",
                "eqNewBut",
                "eqUpgradeBut",
                "eqSellBut",
                "eqCloseBut",
                "equipment",
                "container-unitlist",
                "unitsBarButton",
            )
        ids.forEach { id ->
            if (byId(id) == null) {
                val container = document.createElement("div") as HTMLElement
                container.id = id
                document.body?.appendChild(container)
            }
        }
    }

    @Test
    fun setDefaultUserSelectionsResetsUserSel() {
        UIBuilder.setDefaultUserSelections()
        val userSel = byId("eqUserSel")?.asDynamic()
        assertNotNull(userSel)
        assertEquals(-1, userSel.deployunit)
        assertEquals(-1, userSel.userunit)
        assertEquals(-1, userSel.equnit)
        assertEquals(-1, userSel.eqtransport)
        assertEquals(0, userSel.sortorder)
        assertEquals("cost", userSel.sortproperty)
    }

    @Test
    fun buildEquipmentWindowCreatesClassButtons() {
        UIBuilder.buildEquipmentWindow()
        UIBuilder.eqClassButtons.keys.forEach { eqClass ->
            val button = byId("eqclass-$eqClass")
            assertNotNull(button, "expected class button for $eqClass")
            assertTrue(button.className.contains("smallButtonSubMenu"))
            assertEquals(eqClass, button.asDynamic().eqclass)
        }
    }

    @Test
    fun buildEquipmentWindowConfiguresSortButtons() {
        UIBuilder.buildEquipmentWindow()
        val sortOrderBut = byId("eqSortOrderBut")
        assertNotNull(sortOrderBut)
        assertTrue(sortOrderBut.asDynamic().hasSelectedGlyph as? Boolean ?: false)

        val sortOptionsBut = byId("eqSortOptionsBut")
        assertNotNull(sortOptionsBut)
        assertTrue(sortOptionsBut.asDynamic().hasSelectedGlyph as? Boolean ?: false)
    }

    @Test
    fun buildEquipmentWindowConfiguresActionButtons() {
        UIBuilder.buildEquipmentWindow()
        assertNotNull(byId("eqNewBut")?.title)
        assertNotNull(byId("eqUpgradeBut")?.title)
        assertNotNull(byId("eqSellBut")?.title)
        assertNotNull(byId("eqCloseBut")?.title)
        assertTrue(byId("eqNewBut")?.title?.contains("reserve tray") == true)
        assertTrue(byId("eqUpgradeBut")?.title?.contains("experience and hero") == true)
    }
}
