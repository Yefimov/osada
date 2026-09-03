package org.osada.ui.briefing

import kotlinx.browser.document
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.scenario.ExtendedObjectiveKind
import org.osada.scenario.ExtendedObjectiveProgress
import org.w3c.dom.HTMLElement
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BriefingExtendedObjectivesTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
    }

    @Test
    fun engineAuthoredConditionsAreShownOnTheOrdersSheet() {
        val container = document.createElement("div") as HTMLElement

        addExtendedObjectiveSection(
            container,
            listOf(
                ExtendedObjectiveProgress(ExtendedObjectiveKind.RETREAT, 0, 3),
                ExtendedObjectiveProgress(ExtendedObjectiveKind.KILL, 0, 4),
                ExtendedObjectiveProgress(ExtendedObjectiveKind.MUST_SURVIVE, 2, 2),
            ),
        )

        val text = container.textContent ?: ""
        assertTrue(text.contains("SCENARIO CONDITIONS"), text)
        assertTrue(text.contains("Evacuate 3 formations"), text)
        assertTrue(text.contains("Destroy 4 enemy formations"), text)
        assertTrue(text.contains("Keep at least 2 marked formations alive"), text)
        assertTrue(text.contains("immediate defeat"), text)
        assertEquals(3, container.querySelectorAll(".osada-briefing__objective").length)
    }
}
