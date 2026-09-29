package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.getUnits
import org.osada.scenario.Scenario
import org.osada.scenario.ScenarioReserveParser
import org.w3c.dom.parsing.DOMParser
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OG's undeployed pool, as `add_undeployed_reserve.py` writes it: parsed for the tray, never placed. */
class ScenarioReserveParserTest {
    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        Equipment.putEquipment(INFANTRY, EquipmentData())
    }

    private fun parse(xml: String): Scenario =
        Scenario("t.xml").also { scenario ->
            listOf(0, 1).forEach { playerId ->
                val player = Player()
                player.id = playerId
                player.side = playerId
                scenario.map.addPlayer(player)
            }
            ScenarioReserveParser.parse(scenario, DOMParser().parseFromString(xml, "application/xml"))
        }

    @Test
    fun readsEveryPoolUnitWithItsOwnerAndAttributes() {
        val scenario =
            parse(
                """
                <map>
                  <reserve>
                    <unit id="$INFANTRY" owner="0" flag="62" face="0" str="7"/>
                    <unit id="$INFANTRY" owner="1" flag="14" face="0" exp="120"/>
                  </reserve>
                </map>
                """.trimIndent(),
            )
        val pool = scenario.authoredReserve
        assertEquals(listOf(0, 1), pool.map { it.owner })
        assertEquals(7, pool[0].strength)
        assertEquals(120, pool[1].experience)
        assertTrue(scenario.map.getUnits().isEmpty(), "a pool unit is never put on the map")
    }

    /** Units in hexes and reinforcement waves are not the pool. */
    @Test
    fun ignoresUnitsOutsideTheReserveBlock() {
        val scenario =
            parse(
                """
                <map>
                  <reinforce turn="2"><at row="1" col="1"><unit id="$INFANTRY" owner="0"/></at></reinforce>
                  <hex row="0" col="0"><unit id="$INFANTRY" owner="1"/></hex>
                </map>
                """.trimIndent(),
            )
        assertTrue(scenario.authoredReserve.isEmpty())
    }

    private companion object {
        const val INFANTRY = 990_001
    }
}
