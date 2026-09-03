package org.osada.ui

import kotlinx.browser.document
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.scenario.ExtendedObjectiveKind
import org.osada.scenario.ExtendedObjectiveProgress
import org.osada.scenario.ObjectiveReport
import org.w3c.dom.HTMLElement
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtendedObjectivesRailTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
    }

    @Test
    fun rendersEveryAuthoredConditionWithItsLiveState() {
        val container = document.createElement("div") as HTMLElement
        val report =
            ObjectiveReport(
                rows = emptyList(),
                turn = 1,
                maxTurns = 10,
                deadlines = emptyList(),
                holdThresholds = emptyList(),
                extended =
                    listOf(
                        ExtendedObjectiveProgress(ExtendedObjectiveKind.RETREAT, 2, 3),
                        ExtendedObjectiveProgress(ExtendedObjectiveKind.KILL, 4, 4),
                        ExtendedObjectiveProgress(ExtendedObjectiveKind.MUST_SURVIVE, 1, 2),
                    ),
            )

        ExtendedObjectivesRail.render(container, report)

        val text = container.textContent ?: ""
        assertTrue(text.contains("Evacuate formations through exits"), text)
        assertTrue(text.contains("2/3"), text)
        assertTrue(text.contains("Destroy enemy formations"), text)
        assertTrue(text.contains("Complete: 4/4"), text)
        assertTrue(text.contains("Keep marked formations alive"), text)
        assertTrue(text.contains("Failed: 1/2"), text)
        assertEquals(1, container.querySelectorAll(".osada-obj-end--ok").length)
        assertEquals(1, container.querySelectorAll(".osada-obj-end--missed").length)
    }

    @Test
    fun standaloneOpeningListsConditionsBeforeTheFirstAction() {
        val html =
            extendedObjectiveOpeningHtml(
                "Advance at dawn.",
                listOf(ExtendedObjectiveProgress(ExtendedObjectiveKind.RETREAT, 0, 3)),
            )

        assertTrue(html.startsWith("Advance at dawn.<br><br>"), html)
        assertTrue(html.contains("SCENARIO CONDITIONS"), html)
        assertTrue(html.contains("Evacuate 3 formations"), html)
    }
}
