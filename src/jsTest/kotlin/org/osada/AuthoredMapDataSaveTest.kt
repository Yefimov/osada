package org.osada

import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.allocMap
import org.osada.scenario.Scenario
import org.osada.scenario.ScenarioHexParser
import org.osada.scenario.addReinforcement
import org.w3c.dom.Document
import org.w3c.dom.parsing.DOMParser
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Authored map data that only the scenario XML knows has to travel in the save.**
 *
 * `GameStateRestore` rebuilds the battle from the save alone and never re-reads the scenario XML —
 * the reason `serializeHexEngineering` already carries `dirt` and `station`, both of which no rule
 * can create and both of which would otherwise vanish on load. Two more fields were on exactly that
 * footing and were missed:
 *
 * 1. **The escape hexes.** `Hex.escapeGround` / `Hex.escapeAir` are read only by
 *    [org.osada.rules.ExtendedVictory.canWithdrawThrough], and 50 of the 502 deployed scenarios
 *    author one. A save that dropped them reloaded with every exit gone, which makes the scenario's
 *    own "retreat N units" objective permanently unwinnable — the quota stays, and nothing can
 *    satisfy it.
 * 2. **The reinforcement announcements.** `<reinforce message="...">` pops a box when the wave
 *    lands; the messages lived only in `Scenario.reinforcementMessages`, so a reload silenced every
 *    remaining wave.
 *
 * The third test here is the `<hex>` parser rather than the save: `deploy` and `supply` are two
 * spellings of one field and the second used to overwrite the first unconditionally.
 */
class AuthoredMapDataSaveTest {
    /** `Scenario`'s own init block reads the global `scenariolist`, which Karma does not serve. */
    @BeforeTest
    fun stubScenarioList() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
    }

    private fun reparse(payload: dynamic): dynamic = JSON.parse<dynamic>(JSON.stringify(payload))

    private fun parseXml(xml: String): Document = DOMParser().parseFromString(xml, "application/xml")

    @Test
    fun escapeHexesSurviveASave() {
        val source = Hex(3, 4)
        source.escapeGround = true
        source.escapeAir = true

        val restored = Hex(3, 4)
        restoreEngineering(restored, reparse(GameStateSerializer.serializeHex(source)))

        assertTrue(restored.escapeGround, "a ground exit is authored map data and must travel")
        assertTrue(restored.escapeAir, "so is an air exit, and OG keeps the two separate")
    }

    /** OG splits the two, so a ground-only exit must not come back accepting aircraft. */
    @Test
    fun theTwoExitKindsTravelIndependently() {
        val source = Hex(0, 0)
        source.escapeGround = true

        val restored = Hex(0, 0)
        restoreEngineering(restored, reparse(GameStateSerializer.serializeHex(source)))

        assertTrue(restored.escapeGround)
        assertFalse(restored.escapeAir, "an aircraft cannot walk out through a ground exit")
    }

    /**
     * The optional-key rule this file's serializer works to: a hex with no exit writes no key, so a
     * save of a scenario that authors none keeps exactly the shape it had, and a save written
     * before the keys existed restores to `false` — which is the state it loaded in with anyway.
     */
    @Test
    fun aHexWithNoExitWritesNoKeyAndALegacySaveRestoresToFalse() {
        val plain = reparse(GameStateSerializer.serializeHex(Hex(1, 1)))
        assertEquals(
            undefined,
            plain.escapeGround,
            "a hex with no exit must not grow a key",
        )
        assertEquals(undefined, plain.escapeAir)

        val restored = Hex(1, 1)
        restoreEngineering(restored, plain)
        assertFalse(restored.escapeGround)
        assertFalse(restored.escapeAir)
    }

    @Test
    fun aReinforcementAnnouncementSurvivesASave() {
        val source = Scenario(null)
        source.addReinforcement(3, 7, 41, GameUnit(0))
        source.addReinforcement(5, 1, 24, GameUnit(0))
        source.reinforcementMessages[3] = "The workers of Kiel stand with your uprising!"

        val payload =
            reparse(
                GameStateSerializer.serializeReinforcements(
                    source.reinforcements,
                    source.reinforcementMessages,
                ),
            )

        val restored = Scenario(null)
        restoreReinforcementsFromArray(restored, payload)

        assertEquals(
            "The workers of Kiel stand with your uprising!",
            restored.reinforcementMessages[3],
            "the box the author wrote has to keep appearing after a reload",
        )
        assertEquals(null, restored.reinforcementMessages[5], "a silent wave stays silent")
        assertEquals(2, restored.reinforcements.size, "and the waves themselves still arrive")
    }

    /** A wave with no announcement writes no key, so those saves are unchanged byte for byte. */
    @Test
    fun aWaveWithNoAnnouncementWritesNoKey() {
        val source = Scenario(null)
        source.addReinforcement(2, 0, 0, GameUnit(0))

        val payload = reparse(GameStateSerializer.serializeReinforcements(source.reinforcements))
        assertEquals(undefined, payload[0].message)

        val restored = Scenario(null)
        restoreReinforcementsFromArray(restored, payload)
        assertTrue(restored.reinforcementMessages.isEmpty())
    }

    /**
     * `deploy` is the OG importer's name for the deployment owner and `supply` is Panzer Marshal's,
     * kept alive by the 64 PM-authored scenarios. They used to be read in that order with no guard,
     * so `supply` won whenever a file carried both. 15 deployed hexes carry both and agree on 0, so
     * nothing shipped depended on the old precedence — but the trap is worth closing, and `deploy`
     * is the spelling the other 10,500 hexes use.
     */
    @Test
    fun deployWinsOverTheLegacySupplySpelling() {
        val hexes =
            """
            <hex row="0" col="0" deploy="1" supply="0"/>
            <hex row="0" col="1" supply="1"/>
            <hex row="0" col="2" deploy="1"/>
            <hex row="0" col="3"/>
            """.trimIndent()
        val scenario = scenarioWithHexes(hexes)

        assertEquals(1, scenario.map.map!![0][0].isDeployment, "deploy is authoritative")
        assertEquals(1, scenario.map.map!![0][1].isDeployment, "supply still works on its own")
        assertEquals(1, scenario.map.map!![0][2].isDeployment)
        assertEquals(-1, scenario.map.map!![0][3].isDeployment, "and neither leaves it alone")
    }

    private fun scenarioWithHexes(hexes: String): Scenario {
        val scenario = Scenario(null)
        scenario.map.rows = 1
        scenario.map.cols = 4
        scenario.map.allocMap()
        ScenarioHexParser.parse(scenario, parseXml("<map>$hexes</map>"))
        return scenario
    }
}
