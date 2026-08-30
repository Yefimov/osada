package org.osada.rules

import org.osada.GameHolder
import org.osada.model.acquireUnit
import org.osada.scenario.getSideUnitsAvgExp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * OG's two per-player purchase defaults, imported 2026-08-29 (`docs/og-fidelity-plan.md` §AF).
 *
 * `opt_default_xp` (224 of the 397 deployed scenarios whose source parses) and
 * `opt_allow_default_str` (149) were the two largest authored options that never reached the game.
 * The values sit at player record `+37` and `+39`, and `uspanwar1` states the second in prose --
 * *"New purchased units will have 5 as default strength"*, with its byte reading 5.
 *
 * **The half worth testing hardest is the unauthored one.** The value bytes carry leftover editor
 * state when their switch is off -- 264 player records still read `defaultstr = 10` with
 * `opt_allow_default_str` off -- so the importer writes 0 there, and 0 must mean "keep what OSADA
 * did before" rather than "arrive at zero strength with no experience".
 */
class PlayerPurchaseDefaultsTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() = installTestWorld()

    @AfterTest
    fun teardown() = clearTestWorld()

    @Test
    fun anAuthoredDefaultStrengthIsWhatANewFormationArrivesWith() {
        val map = world()
        GameHolder.instance = holderFor(map)
        friendly.defaultStrength = 5

        friendly.acquireUnit(infantryEqid, 0)

        assertEquals(5, friendly.getCoreUnitList().last().strength, "uspanwar1's own briefed number")
    }

    @Test
    fun anAuthoredDefaultExperienceIsWhatANewFormationArrivesWith() {
        val map = world()
        GameHolder.instance = holderFor(map)
        friendly.defaultExperience = 200

        friendly.acquireUnit(infantryEqid, 0)

        assertEquals(200, friendly.getCoreUnitList().last().experience)
    }

    @Test
    fun anUnauthoredScenarioKeepsExactlyWhatItHadBefore() {
        val map = world()
        GameHolder.instance = holderFor(map)
        // Both switches off in the source, so the importer wrote 0 for both -- which is the state
        // 183 of the 397 parseable scenarios are in.
        friendly.defaultExperience = 0
        friendly.defaultStrength = 0

        friendly.acquireUnit(infantryEqid, 0)
        val bought = friendly.getCoreUnitList().last()

        assertEquals(10, bought.strength, "full strength, not the unauthored zero")
        assertEquals(
            GameHolder.instance?.scenario?.getSideUnitsAvgExp(1 - friendly.side) ?: 0,
            bought.experience,
            "OSADA's own opponent-average rule survives where OG says nothing",
        )
    }

    @Test
    fun theTwoDefaultsAreIndependent() {
        val map = world()
        GameHolder.instance = holderFor(map)
        // A scenario may switch on one and not the other; 214 author either, far fewer author both.
        friendly.defaultStrength = 8
        friendly.defaultExperience = 0

        friendly.acquireUnit(infantryEqid, 0)
        val bought = friendly.getCoreUnitList().last()

        assertEquals(8, bought.strength)
        assertEquals(0, bought.experience, "no authored XP, and no units on the map to average")
    }

    @Test
    fun theDefaultsSurviveASave() {
        val source =
            org.osada.model.Player().apply {
                id = 0
                defaultExperience = 150
                defaultStrength = 6
            }

        val restored =
            org.osada.GameStateDeserializer.deserializePlayer(
                reparse(org.osada.GameStateSerializer.serializePlayer(source)),
            )

        assertEquals(150, restored.defaultExperience)
        assertEquals(6, restored.defaultStrength)
    }
}
