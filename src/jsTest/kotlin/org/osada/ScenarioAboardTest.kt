package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.allocMap
import org.osada.scenario.Scenario
import org.osada.scenario.ScenarioHexParser
import org.w3c.dom.parsing.DOMParser
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `aboard="1"`: an authored passenger starts inside the surface unit before it on its hex
 * (`ScenarioHexParser.stowAboard`, docs/README.md C1). Until 2026-10-10 the hex's single surface
 * slot kept only the last unit, so `bn5s04`'s British commando, authored on the same ocean hex as
 * the submarine carrying it, silently replaced the submarine.
 */
class ScenarioAboardTest {
    private val subEqid = 990_101
    private val scoutEqid = 990_102
    private val boatEqid = 990_103

    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        Equipment.putEquipment(
            subEqid,
            EquipmentData().apply {
                eqid = subEqid
                name = "Submarine"
                uclass = UnitClass.SUBMARINE.value
                movmethod = MovMethod.NAVAL.value
                hangarCap = 1
            },
        )
        Equipment.putEquipment(
            scoutEqid,
            EquipmentData().apply {
                eqid = scoutEqid
                name = "Scout"
                uclass = UnitClass.RECON.value
                movmethod = MovMethod.LEG.value
                movpoints = 5
                cost = 10
            },
        )
        Equipment.putEquipment(
            boatEqid,
            EquipmentData().apply {
                eqid = boatEqid
                name = "Patrol Boat"
                uclass = UnitClass.DESTROYER.value
                movmethod = MovMethod.NAVAL.value
            },
        )
    }

    private fun scenarioWithHex(units: String): Scenario {
        val scenario = Scenario(null)
        scenario.map.rows = 1
        scenario.map.cols = 1
        scenario.map.allocMap()
        scenario.map.addPlayer(
            Player().apply {
                id = 0
                side = 0
            },
        )
        scenario.map.addPlayer(
            Player().apply {
                id = 1
                side = 1
            },
        )
        val xml = """<map><hex row="0" col="0" terrain="9">$units</hex></map>"""
        ScenarioHexParser.parse(scenario, DOMParser().parseFromString(xml, "application/xml"))
        return scenario
    }

    @Test
    fun aPassengerStartsInsideItsContainer() {
        val scenario =
            scenarioWithHex(
                """<unit id="$subEqid" owner="0"/><unit id="$scoutEqid" owner="0" exp="200" aboard="1"/>""",
            )
        val sub = scenario.map.map!![0][0].unit!!
        assertEquals(subEqid, sub.eqid, "the container keeps the hex instead of being overwritten")
        val passenger = sub.hangar.single()
        assertEquals(scoutEqid, passenger.eqid)
        assertSame(sub, passenger.containedIn)
        assertNull(passenger.getHex(), "a contained formation is off the map")
        assertTrue(passenger.isDeployed)
        assertEquals(-1, passenger.landedTurn, "it never landed, so it may leave on the first turn")
        assertTrue(scenario.map.units.none { it.eqid == scoutEqid }, "and it is not a map unit")
    }

    @Test
    fun withoutHangarRoomTheOldSlotPlacementStands() {
        val scenario =
            scenarioWithHex("""<unit id="$boatEqid" owner="0"/><unit id="$scoutEqid" owner="0" aboard="1"/>""")
        assertEquals(
            scoutEqid,
            scenario.map.map!![0][0]
                .unit!!
                .eqid,
        )
    }

    @Test
    fun anotherSidesContainerTakesNoPassenger() {
        val scenario =
            scenarioWithHex("""<unit id="$subEqid" owner="1"/><unit id="$scoutEqid" owner="0" aboard="1"/>""")
        assertEquals(
            scoutEqid,
            scenario.map.map!![0][0]
                .unit!!
                .eqid,
        )
    }
}
