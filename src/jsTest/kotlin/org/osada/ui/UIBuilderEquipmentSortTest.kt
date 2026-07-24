package org.osada.ui

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for [UIBuilder.buildEquipmentSortOptions].
 */
class UIBuilderEquipmentSortTest {
    @BeforeTest
    fun setup() {
        listOf(
            "eqSortInfo",
            "eqSortOptionsContainer",
            "eqSortCloseBut",
            "eqSortOptions",
            "eqButtonsContainer",
            "eqUserSel",
        ).forEach { id ->
            if (byId(id) == null) {
                val container = document.createElement("div") as HTMLElement
                container.id = id
                document.body?.appendChild(container)
            }
        }
    }

    @Test
    fun buildEquipmentSortOptionsCreatesSortButtons() {
        UIBuilder.buildEquipmentSortOptions()
        val sortable = UIBuilder.unitStats.filter { it.isSortable && it.property != null && it.glyph != null }
        sortable.forEach { stat ->
            val button = byId("eqsort-${stat.property}")
            assertNotNull(button, "expected sort button for ${stat.property}")
            assertEquals("smallButtonSubMenu", button.className)
            assertEquals(stat.property, button.asDynamic().sortproperty)
        }
    }

    @Test
    fun buildEquipmentSortOptionsSetsInitialInfoText() {
        UIBuilder.buildEquipmentSortOptions()
        assertEquals("Sort equipment by: ", byId("eqSortInfo")?.innerHTML)
    }

    @Test
    fun buildEquipmentSortOptionsConfiguresCloseButton() {
        UIBuilder.buildEquipmentSortOptions()
        assertNotNull(byId("eqSortCloseBut")?.title)
    }
}
