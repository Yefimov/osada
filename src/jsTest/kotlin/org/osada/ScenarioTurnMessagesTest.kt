package org.osada

import org.osada.scenario.Scenario
import org.osada.scenario.ScenarioTurnMessages
import org.w3c.dom.parsing.DOMParser
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OG's `.tmsg` turn messages, as `add_turn_messages.py` writes them into the scenario XML. */
class ScenarioTurnMessagesTest {
    @BeforeTest
    fun stubScenarioList() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
    }

    private fun parse(xml: String): Scenario =
        Scenario("t.xml").also {
            ScenarioTurnMessages.parse(it, DOMParser().parseFromString(xml, "application/xml"))
        }

    @Test
    fun readsEachTurnAndDecodesCharacterReferences() {
        val scenario =
            parse(
                """
                <map>
                  <turnmessages>
                    <msg turn="1">Hecker: "Put down your arms!"</msg>
                    <msg turn="2">Farmers from Schl&#228;chtenhaus join our fight!</msg>
                  </turnmessages>
                </map>
                """.trimIndent(),
            )
        assertEquals(
            mapOf(1 to "Hecker: \"Put down your arms!\"", 2 to "Farmers from Schlächtenhaus join our fight!"),
            scenario.turnMessages,
        )
    }

    /** A scenario without the block has been READ and has nothing -- not "unknown", which would
     *  make a restore fetch the XML again. */
    @Test
    fun absentBlockIsAnEmptyMapAndStrayMsgElementsAreIgnored() {
        val scenario = parse("""<map><events><msg turn="3">not a turn message</msg></events></map>""")
        assertTrue(scenario.turnMessages!!.isEmpty())
    }
}
