package org.osada.rules

import org.osada.GameHolder
import org.osada.TerrainType
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Open General's per-scenario **Game settings**, imported 2026-08-26 from the `.xscn` bitfield
 * (`docs/og-fidelity-plan.md` §O).
 *
 * OG opens §9.3 with *"All this options must be enabled by the scenario designer to work"*, and
 * §9.5 with *"When this scenario option is activated"*. Until these attributes existed, the Open
 * General Fidelity profile applied one set of engineering and sight rules to 502 scenarios that
 * authored their own — the `authored_options` gap.
 *
 * The third case is the one worth having a test for: a scenario whose source could not be read
 * carries **no** attribute, and must behave exactly as it did before the import rather than losing
 * a mechanic to silence.
 */
class AuthoredScenarioOptionsTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() = installTestWorld()

    @AfterTest
    fun teardown() = clearTestWorld()

    @Test
    fun aScenarioThatForbidsBuildingOffersNoConstructionEvenWithTheKeyOn() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        val holder = holderFor(map)
        holder.scenario?.canBuild = false
        GameHolder.instance = holder
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertFalse(EngineeringWork.AIRFIELD in Engineering.availableWork(sapper))
        assertFalse(Engineering.authorisedByScenario(EngineeringWork.AIRFIELD))
    }

    @Test
    fun forbiddingOneSwitchLeavesTheOtherTwoAlone() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        val holder = holderFor(map)
        holder.scenario?.canBuild = false
        holder.scenario?.canBlow = true
        holder.scenario?.canRepair = true
        GameHolder.instance = holder
        map.map!![2][2].terrain = TerrainType.CITY.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        val offered = Engineering.availableWork(sapper)
        assertTrue(EngineeringWork.RAZE in offered, "Can Blow is authorised, so the demolition stands")
        assertFalse(EngineeringWork.FORTIFICATION in offered, "Can Build is not")
    }

    @Test
    fun aScenarioThatForbidsBlowingKeepsItsBridges() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        val holder = holderFor(map)
        holder.scenario?.canBlow = false
        GameHolder.instance = holder
        map.map!![2][2].terrain = TerrainType.RIVER.value
        map.map!![2][2].road = PARTIAL_ROAD_MASK
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertFalse(EngineeringWork.BLOW_BRIDGE in Engineering.availableWork(sapper))
    }

    @Test
    fun anUnimportedScenarioKeepsEveryJobItHadBefore() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        // Every switch left null: the 105 deployed scenarios whose `.xscn` cannot be read or found.
        GameHolder.instance = holderFor(map)
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertTrue(EngineeringWork.AIRFIELD in Engineering.availableWork(sapper))
        assertTrue(Engineering.authorisedByScenario(EngineeringWork.REPAIR))
    }

    @Test
    fun extendedLosNeedsBothTheKeyAndTheScenario() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        val holder = holderFor(map)
        GameHolder.instance = holder

        holder.scenario?.extendedLos = null
        assertTrue(ExtendedLos.enabled(), "an unimported scenario follows the key alone")

        holder.scenario?.extendedLos = true
        assertTrue(ExtendedLos.enabled())

        holder.scenario?.extendedLos = false
        assertFalse(ExtendedLos.enabled(), "OG: 'when this scenario option is activated'")
    }

    @Test
    fun theScenarioCannotTurnARuleOnThatTheRulesetHasOff() {
        ruleset(RuleKey.EXTENDED_LOS to 0, RuleKey.BUILD_AND_REPAIR to 0)
        val map = world(prestige = 100)
        val holder = holderFor(map)
        holder.scenario?.extendedLos = true
        holder.scenario?.canBuild = true
        GameHolder.instance = holder
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertFalse(ExtendedLos.enabled())
        assertEquals(emptyList(), Engineering.availableWork(sapper))
    }
}
