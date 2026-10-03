package org.osada.ui

import org.osada.rules.UnitActionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnitActionPagesTest {
    @Test
    fun constructionIsGroupedWithoutHidingRepairOrDemolition() {
        assertEquals(
            listOf("mount", UnitActionPages.BUILD, "repair", "demolish", "sleep"),
            UnitActionPages.main(listOf("mount", "build_bridge", "build_port", "repair", "demolish", "sleep")),
        )
        assertEquals(listOf("repair", "sleep"), UnitActionPages.main(listOf("repair", "sleep")))
    }

    @Test
    fun everyCommandRemainsReachableWithinSixSlotsIncludingNavigation() {
        val all = UnitActionId.entries.map { it.id }
        for (count in 0..all.size) {
            val actions = UnitActionPages.main(all.take(count))
            val pages = UnitActionPages.pages(actions)
            assertTrue(pages.all { it.size <= 6 })
            assertEquals(actions, pages.flatten().filter { it != UnitActionPages.BACK && it != UnitActionPages.MORE })
            pages.forEachIndexed { index, page ->
                assertEquals(index > 0, UnitActionPages.BACK in page)
                assertEquals(index < pages.lastIndex, UnitActionPages.MORE in page)
            }
        }
        assertEquals(6, UnitActionPages.construction.size + 1)
    }
}
