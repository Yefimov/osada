package org.osada

import org.osada.model.FrontFactionSlot
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.scenario.AuthoredOptionsBackfill
import org.osada.scenario.AuthoredScenarioOptions
import org.osada.scenario.PROTOTYPE_DEFAULT_MONTHS
import org.osada.scenario.Scenario
import org.w3c.dom.Document
import org.w3c.dom.parsing.DOMParser
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A save has to carry the whole authored scenario, not one field of it.**
 *
 * `GameStateRestore` rebuilds a battle from the save alone and never re-reads the scenario XML.
 * Until now the save wrote exactly one of OG's 27 authored options — and wrote it as `== true`,
 * which cannot say "the author said nothing" — so every reload silently re-authored the battle:
 * `canbuild`, `barrage`, `truerange0`, the Fronts/Factions masks and the shipped `.buy4` lists all
 * came back as unauthored. `docs/og-import-rules-backlog.md` recorded the gap; this is the test
 * that closes it.
 *
 * Three properties are load-bearing and each has its own test:
 *
 * 1. **Everything round-trips** — all 24 switches, the prototype time frame, the music track, the
 *    weather/ground link, and the per-player purchase family (`purchasecap`, `.buy4`, `ff`).
 * 2. **Absence stays absence.** An option the author never set comes back `null`, not `false`.
 *    That is the direction that keeps a battle playable: `null` means every reader falls back to
 *    its ruleset key, while `false` is the author forbidding a mechanic outright.
 * 3. **A save written before any of this is completed from the scenario XML**, which is still the
 *    author's own record, and the back-fill never overwrites what the save did carry.
 */
class AuthoredScenarioOptionsSaveTest {
    /** `Scenario`'s own init block reads the global `scenariolist`, which Karma does not serve. */
    @BeforeTest
    fun stubScenarioList() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
    }

    private fun reparse(payload: dynamic): dynamic = JSON.parse<dynamic>(JSON.stringify(payload))

    /** Serializes [source] and applies the result to a fresh scenario, exactly as a load does. */
    private fun roundTrip(source: Scenario): Scenario {
        val payload = reparse(GameStateSerializer.serializeScenario(source))
        return Scenario(source.file).also { restored ->
            restoreVictoryMetadata(restored, payload)
            AuthoredScenarioOptions.restore(restored, payload.options)
        }
    }

    private fun parseXml(xml: String): Document = DOMParser().parseFromString(xml, "application/xml")

    /**
     * The names are the save-file format AND the scenario-XML attribute names, deliberately one
     * vocabulary. Listing them here means adding a switch to [Scenario] without adding it to the
     * table cannot pass silently — which is precisely how the save came to know one of them.
     */
    @Test
    fun theTableCarriesEveryDeployedSwitchUnderItsOwnXmlName() {
        assertEquals(
            listOf(
                // The order is the one `add_scenario_options.py` deploys them in, and the blank
                // groupings this list used to carry (one per deployment batch) are the comments
                // below: ktlint wants one argument per line.
                "canbuild",
                "canblow",
                "canrepair",
                "extlos",
                "truedlof",
                "unitsblocklof",
                "barrage",
                "airzoc",
                "airmissions",
                "extnaval",
                // 2026-08-28
                "airintercept",
                "portsnosupply",
                "portsnonavaldeploy",
                // 2026-08-29, both stored inverted
                "prototypes",
                "subsneedlof",
                // 2026-08-30
                "truerange0",
                "truespotting0",
                "reinfwhenactive",
                "capitalflak",
                "basicstrength",
                "typedvh",
                // 2026-08-31, and `paradropocean` is the third inverted one
                "ehmsuonly",
                "paradropocean",
                // 2026-09-01
                "coresoffcap",
            ),
            AuthoredScenarioOptions.SWITCHES.map { it.attribute },
        )
    }

    /**
     * Every switch, in BOTH states, plus the three options that are not booleans.
     *
     * The alternating pattern matters: a writer that coerced with `== true` and a reader that
     * defaulted to `false` would both pass a test that only authored `true`.
     */
    @Test
    fun everyAuthoredOptionSurvivesASaveAndReload() {
        val source =
            Scenario("test.xml").apply {
                AuthoredScenarioOptions.SWITCHES.forEachIndexed { i, option ->
                    option.write(this, i % 2 == 0)
                }
                prototypeTimeFrameMonths = 7
                musicTrack = "africa2.mp3"
                weatherCanChangeGround = true
            }

        val restored = roundTrip(source)

        AuthoredScenarioOptions.SWITCHES.forEachIndexed { i, option ->
            assertEquals(i % 2 == 0, option.read(restored), option.attribute)
        }
        assertEquals(7, restored.prototypeTimeFrameMonths, "prototimeframe")
        assertEquals("africa2.mp3", restored.musicTrack, "music")
        assertTrue(restored.weatherCanChangeGround, "weatherchg")
    }

    /**
     * The four the backlog entry named by hand, asserted by name rather than through the table, so
     * the ones a reader actually looks for are legible in the test source.
     */
    @Test
    fun theNamedOptionsSurviveByName() {
        val source =
            Scenario("test.xml").apply {
                canBuild = false
                barrageAllowed = true
                trueRangeZero = true
                useBasicStrength = false
                typedVictoryHexes = true
                escapeHexesForMsuOnly = true
                coresExemptFromPurchaseCap = true
                prototypeTimeFrameMonths = PROTOTYPE_DEFAULT_MONTHS
            }

        val restored = roundTrip(source)

        assertEquals(false, restored.canBuild)
        assertEquals(true, restored.barrageAllowed)
        assertEquals(true, restored.trueRangeZero)
        assertEquals(false, restored.useBasicStrength)
        assertEquals(true, restored.typedVictoryHexes)
        assertEquals(true, restored.escapeHexesForMsuOnly)
        assertEquals(true, restored.coresExemptFromPurchaseCap)
        assertEquals(PROTOTYPE_DEFAULT_MONTHS, restored.prototypeTimeFrameMonths)
    }

    /**
     * **The property the whole feature rests on.** 105 of the 502 deployed scenarios author nothing
     * at all, and turning their silence into `false` on the first reload would switch mechanics off
     * for them alone.
     */
    @Test
    fun anUnauthoredOptionComesBackNullAndNotFalse() {
        val payload = reparse(GameStateSerializer.serializeScenario(Scenario("test.xml")))

        AuthoredScenarioOptions.SWITCHES.forEach { option ->
            assertTrue(payload.options[option.attribute] == undefined, "${option.attribute} wrote a key")
        }

        val restored = roundTrip(Scenario("test.xml"))

        AuthoredScenarioOptions.SWITCHES.forEach { option ->
            assertNull(option.read(restored), option.attribute)
        }
        assertNull(restored.prototypeTimeFrameMonths, "prototimeframe")
        assertNull(restored.musicTrack, "music")
        assertFalse(restored.weatherCanChangeGround, "weatherchg has always defaulted to off")
    }

    /**
     * The legacy top-level `typedVictoryHexes` key was written as `== true`, so it says `false` for
     * a scenario that authored nothing. A save that carries the options block must be read from the
     * block, or the one option that WAS saved would be the one option this fix broke.
     */
    @Test
    fun theLossyLegacyTypedVhKeyIsIgnoredWhenTheOptionsBlockIsPresent() {
        val payload = reparse(GameStateSerializer.serializeScenario(Scenario("test.xml")))
        assertEquals(false, payload.typedVictoryHexes as? Boolean, "the legacy key still writes false")

        val restored = roundTrip(Scenario("test.xml"))

        assertNull(restored.typedVictoryHexes, "and the options block is what the restore believes")
    }

    /**
     * A save written before this existed carries no `options` key, and the scenario XML is still the
     * author's own record — so the whole set is recovered from it, `<map>` switches and per-player
     * limits alike.
     */
    @Test
    fun aLegacySaveIsCompletedFromTheScenarioXml() {
        val scenario = Scenario("legacy.xml")
        scenario.map.addPlayer(Player().apply { id = 0 })
        scenario.map.addPlayer(Player().apply { id = 1 })

        AuthoredOptionsBackfill.applyDocument(
            scenario,
            parseXml(
                """
                <scenario>
                  <map canbuild="1" canblow="0" barrage="1" truerange0="1" basicstrength="1"
                       coresoffcap="1" prototimeframe="12" music="africa2.mp3" weatherchg="1">
                    <player id="0" purchasecap="4" buylist="11,22,33" ff="20:1:32768" airtrans="2"/>
                    <player id="1" ff="27:0:4"/>
                  </map>
                </scenario>
                """.trimIndent(),
            ),
        )

        assertEquals(true, scenario.canBuild)
        assertEquals(false, scenario.canBlow)
        assertEquals(true, scenario.barrageAllowed)
        assertEquals(true, scenario.trueRangeZero)
        assertEquals(true, scenario.useBasicStrength)
        assertEquals(true, scenario.coresExemptFromPurchaseCap)
        assertEquals(12, scenario.prototypeTimeFrameMonths)
        assertEquals("africa2.mp3", scenario.musicTrack)
        assertTrue(scenario.weatherCanChangeGround)
        // Unwritten attributes stay unauthored even in the back-fill: the XML is read exactly as a
        // fresh load reads it, so a scenario whose source could not be re-exported is not re-authored.
        assertNull(scenario.airZoc)
        assertNull(scenario.escapeHexesForMsuOnly)

        val first = scenario.map.players[0]
        assertEquals(4, first.purchaseCap)
        assertEquals(setOf(11, 22, 33), first.purchaseList, "the shipped .buy4 whitelist")
        assertEquals(listOf(FrontFactionSlot(20, 1, 32768)), first.frontFactionSlots)
        assertTrue(first.transportPoolsAuthored, "presence of a pool attribute, not its value")
        assertEquals(0, first.airTransports, "and the live pool COUNT is never re-read from the XML")

        val second = scenario.map.players[1]
        assertNull(second.purchaseCap)
        assertEquals(listOf(FrontFactionSlot(27, 0, 4)), second.frontFactionSlots)
        assertFalse(second.transportPoolsAuthored)
    }

    /**
     * OG's own "switch on, value never configured" state, which 48 of the 69 scenarios that author a
     * time frame are in. The back-fill substitutes the manual's default exactly as the loader does.
     */
    @Test
    fun aZeroTimeFrameBecomesTheManualsDefaultInTheBackFillToo() {
        val scenario = Scenario("legacy.xml")

        AuthoredOptionsBackfill.applyDocument(scenario, parseXml("""<map prototimeframe="0"/>"""))

        assertEquals(PROTOTYPE_DEFAULT_MONTHS, scenario.prototypeTimeFrameMonths)
    }

    /** The save is authoritative wherever it spoke; the XML only completes what it left out. */
    @Test
    fun theBackFillNeverOverwritesWhatTheSaveCarried() {
        val scenario = Scenario("legacy.xml")
        scenario.map.addPlayer(
            Player().apply {
                id = 0
                purchaseCap = 3
                purchaseList = setOf(99)
                frontFactionSlots = listOf(FrontFactionSlot(20, 2, 2))
            },
        )

        AuthoredOptionsBackfill.applyDocument(
            scenario,
            parseXml("""<map><player id="0" purchasecap="9" buylist="1,2" ff="27:0:4"/></map>"""),
        )

        val player = scenario.map.players[0]
        assertEquals(3, player.purchaseCap)
        assertEquals(setOf(99), player.purchaseList)
        assertEquals(listOf(FrontFactionSlot(20, 2, 2)), player.frontFactionSlots)
    }

    /**
     * A modern save must not pay for a network request, and must not have its options re-derived
     * from a scenario file that may since have been re-imported.
     */
    @Test
    fun aSaveCarryingTheOptionsBlockIsNeverBackFilled() {
        val scenario = Scenario("legacy.xml").apply { canBuild = false }
        var completed = false

        AuthoredOptionsBackfill.completeIfAbsent(
            scenario,
            reparse(GameStateSerializer.serializeScenario(scenario)),
        ) { completed = true }

        assertTrue(completed, "the callback runs on the same tick, with no fetch in between")
        assertEquals(false, scenario.canBuild)
    }

    /**
     * An EMPTY options block is a scenario that authored nothing, not a save that said nothing —
     * the same distinction the individual keys make, one level up.
     */
    @Test
    fun anEmptyOptionsBlockIsNotTreatedAsAMissingOne() {
        var completed = false

        AuthoredOptionsBackfill.completeIfAbsent(
            Scenario("legacy.xml"),
            reparse(GameStateSerializer.serializeScenario(Scenario("legacy.xml"))),
        ) { completed = true }

        assertTrue(completed)
    }

    /**
     * The per-player half of the same authored scenario: OG's purchase cap and its two counters, the
     * `.buy4` whitelist and the Fronts/Factions masks. These were already serialized when the cap
     * rule was built; asserted here so the whole authored scenario has one round-trip test.
     */
    @Test
    fun thePurchaseFamilyRoundTripsWithTheOptions() {
        val player =
            Player().apply {
                id = 1
                purchaseCap = 5
                purchaseList = setOf(101, 202, 303)
                frontFactionSlots =
                    listOf(FrontFactionSlot(20, 1, -2147483648), FrontFactionSlot(27, 0, 4))
                transportPoolsAuthored = true
                purchaseGrowthSpent = 2
                replacementCredits = 3
            }

        val restored =
            GameStateDeserializer.deserializePlayer(reparse(GameStateSerializer.serializePlayer(player)))

        assertEquals(5, restored.purchaseCap)
        assertEquals(setOf(101, 202, 303), restored.purchaseList)
        assertEquals(player.frontFactionSlots, restored.frontFactionSlots)
        assertTrue(restored.transportPoolsAuthored)
        assertEquals(2, restored.purchaseGrowthSpent, "spent cap slots are not refunded by a reload")
        assertEquals(3, restored.replacementCredits)
    }
}
