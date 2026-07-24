package org.osada.ui

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [UIBuilder.buildUnitInfoWindow].
 */
class UIBuilderUnitInfoTest {
    @BeforeTest
    fun setup() {
        listOf("statsRow", "statsRowTop").forEach { id ->
            if (byId(id) == null) {
                val container = document.createElement("div") as HTMLElement
                container.id = id
                document.body?.appendChild(container)
            }
        }
    }

    @Test
    fun buildUnitInfoWindowCreatesImageContainers() {
        UIBuilder.buildUnitInfoWindow()
        assertNotNull(byId("uImageBg"))
        assertNotNull(byId("uImage"))
    }

    @Test
    fun buildUnitInfoWindowCreatesStatEntries() {
        UIBuilder.buildUnitInfoWindow()
        UIBuilder.unitStats.forEach { stat ->
            if (stat.id == "uCost") return@forEach
            val element = byId(stat.id)
            assertNotNull(element, "expected stat element ${stat.id}")
            assertEquals("statsText", element.className)
        }
    }

    @Test
    fun statWithGlyphHasGlyphContainer() {
        UIBuilder.buildUnitInfoWindow()
        val stat = UIBuilder.unitStats.find { it.glyph != null }
        assertNotNull(stat)
        val element = byId(stat.id)
        assertNotNull(element)
        assertTrue(element.parentElement?.className?.contains("statsGlyph") ?: false)
        assertTrue(
            element.parentElement?.getAttribute("title")?.contains(":") == true,
            "stat tooltip should explain the rule",
        )
    }

    @Test
    fun topRowStatsAreCreatedInStatsRowTop() {
        UIBuilder.buildUnitInfoWindow()
        val topStat = UIBuilder.unitStats.find { it.isTopRow }
        assertNotNull(topStat)
        val element = byId(topStat.id)
        assertNotNull(element)
        assertEquals("statsRowTop", element.parentElement?.id)
    }
}
