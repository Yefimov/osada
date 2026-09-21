package org.osada

import org.osada.scenario.ScenarioEquipmentScan
import org.w3c.dom.parsing.DOMParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two collectors that tell the equipment loader which ids a battle will need.
 *
 * Both exist because a formation's nationality does not name the merged country file its equipment
 * record lives in ([org.osada.model.EquipmentCountryIndex]). What is asserted here is coverage: a
 * placement site missed by the scan is a unit that comes back as an empty record, which is exactly
 * the Tiganca failure, so every site a scenario or a save can hold one has a case below.
 */
class EquipmentScanTest {
    private fun parse(xml: String) = DOMParser().parseFromString(xml, "text/xml".unsafeCast<dynamic>())

    /** Falciu 2's own hex, verbatim, plus the other three places a scenario may place a formation. */
    private val scenarioXml =
        """
        <scenario>
          <player id="0" country="61" support="62,0,0,0"/>
          <player id="1" country="13" support="14,0,0,0"/>
          <hex row="9" col="8" terrain="1" name="Tiganca" owner="1" flag="13" victory="1">
            <unit id="16132" owner="1" flag="14" face="0" exp="192" str="7" bstr="5"/>
          </hex>
          <hex row="4" col="4" trigequip="31337"/>
          <hex row="5" col="5"><unit id="2001" owner="0" transport="2002" carrier="2003"/></hex>
          <reinforce turn="3">
            <at row="1" col="1"><unit id="4004" owner="0"/></at>
          </reinforce>
          <events>
            <event id="e1" row="2" col="2">
              <spawn row="2" col="2"><unit id="5005" owner="1"/></spawn>
            </event>
          </events>
        </scenario>
        """.trimIndent()

    @Test
    fun everyAuthoredPlacementSiteIsScanned() {
        val ids = ScenarioEquipmentScan.collect(parse(scenarioXml))

        assertTrue(16132 in ids, "a unit on a hex — the reported Tiganca garrison")
        assertTrue(2001 in ids, "a unit's own record")
        assertTrue(2002 in ids, "its organic transport")
        assertTrue(2003 in ids, "the container it deploys from")
        assertTrue(4004 in ids, "a reinforcement wave that has not arrived yet")
        assertTrue(5005 in ids, "a unit an authored event will spawn")
        assertTrue(31337 in ids, "the equipment a trigger hex hands the player")
    }

    @Test
    fun nonEquipmentAttributesAreNotMistakenForIds() {
        val ids = ScenarioEquipmentScan.collect(parse(scenarioXml))

        assertFalse(192 in ids, "`exp` is not an equipment id")
        assertFalse(7 in ids, "`str` is not an equipment id")
        assertFalse(0 in ids, "zero is never fetched")
    }

    @Test
    fun aScenarioWithNoUnitsAsksForNothing() {
        val ids = ScenarioEquipmentScan.collect(parse("<scenario><hex row=\"0\" col=\"0\"/></scenario>"))

        assertEquals(emptySet(), ids)
    }

    /**
     * A save has strictly more hiding places than a scenario file: undeployed core units on no hex,
     * unlanded reinforcement waves, unfired events and passengers inside a container.
     */
    @Test
    fun everySavedPlacementSiteIsScanned() {
        val save =
            JSON.parse<dynamic>(
                """
                {
                  "scenario": {
                    "map": {
                      "hexes": [[
                        {"unit": {"eqid": 16132, "carrier": 900, "transport": {"eqid": 901}},
                         "airunit": {"eqid": 902}, "triggerEquip": 903},
                        {"unit": {"eqid": 904, "hangar": [{"eqid": 905}]}}
                      ]]
                    },
                    "reinforcements": [{"turn": 4, "units": [{"row": 1, "col": 1, "unit": {"eqid": 906}}]}],
                    "events": [{"id": "e1", "spawns": [{"row": 2, "col": 2, "unit": {"eqid": 907}}]}]
                  },
                  "players": [{"id": 0, "coreUnits": [{"eqid": 908}]}],
                  "campaign": {"coreUnits": [{"eqid": 909, "transport": {"eqid": 910}}]}
                }
                """.trimIndent(),
            )

        val ids = SavedEquipmentScan.collect(save.scenario, save.players, save.campaign)

        assertEquals((900..910).toSet() + 16132, ids)
    }

    /** The legacy `{turn: [...]}` reinforcement block and the legacy `map.map` grid key. */
    @Test
    fun legacySaveShapesAreScannedToo() {
        val save =
            JSON.parse<dynamic>(
                """
                {
                  "scenario": {
                    "map": {"map": [[{"unit": {"eqid": 701}}]]},
                    "reinforcements": {"5": [{"row": 0, "col": 0, "unit": {"eqid": 702}}]}
                  },
                  "players": []
                }
                """.trimIndent(),
            )

        val ids = SavedEquipmentScan.collect(save.scenario, save.players, null)

        assertEquals(setOf(701, 702), ids)
    }

    /** A save missing any of these blocks is an older save, not a broken one. */
    @Test
    fun absentBlocksContributeNothingAndDoNotThrow() {
        val save = JSON.parse<dynamic>("""{"scenario": {}, "players": []}""")

        assertEquals(emptySet(), SavedEquipmentScan.collect(save.scenario, save.players, null))
        assertEquals(emptySet(), SavedEquipmentScan.collect(null, null, null))
    }
}
