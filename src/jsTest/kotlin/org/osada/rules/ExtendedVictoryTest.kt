package org.osada.rules

import org.osada.GameHolder
import org.osada.GameStateSerializer
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.restoreCoreUnitList
import org.osada.restoreVictoryMetadata
import org.osada.scenario.AuthoredScenarioOptions
import org.osada.scenario.Scenario
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OG manual §3.7's extended victory conditions — the routes to winning that are not "take all the
 * victory hexes", decoded 2026-08-30 from the owner's controlled OpenSuite diffs on `BN9S01`.
 *
 * > *"Some of these conditions can be asked for simultaneously, but you must only meet ONE of them
 * > to win the scenario."* — §3.7
 *
 * That is the property this suite exists to hold: every assertion below is about an ALTERNATIVE
 * route to victory, and a scenario authoring none must behave exactly as it did before.
 */
class ExtendedVictoryTest : OgRulesTestHarness() {
    private val planeEqid = 980

    @BeforeTest
    fun setup() {
        installTestWorld()
        ruleset()
        Equipment.putEquipment(
            planeEqid,
            EquipmentData().apply {
                name = "Transport Wing"
                uclass = UnitClass.LEVEL_BOMBER.value
                // `UnitPredicates.isAir` reads the MOVEMENT METHOD, not the class -- a bomber
                // record without this is a ground unit as far as every rule is concerned.
                movmethod = MovMethod.AIR.value
            },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun scenarioWith(
        map: GameMap,
        configure: Scenario.() -> Unit,
    ): Scenario {
        val scenario = Scenario(null).apply { this.map = map }.apply(configure)
        GameHolder.instance = GameHolder.instance ?: org.osada.Game()
        GameHolder.instance?.scenario = scenario
        return scenario
    }

    // ---- Escape hexes, manual §3.7.4 ------------------------------------------------------------

    /**
     * *"Retreat N units to an Escape Hex"* — the formation leaves the map and its side's counter
     * ticks up. Withdrawal is NOT destruction: nothing is credited to the enemy.
     */
    @Test
    fun aGroundFormationLeavesTheMapThroughAGroundExit() {
        val map = world()
        val scenario = scenarioWith(map) { retreatUnitsPerSide = listOf(0, 2) }
        val hex = map.map!![4][4].apply { escapeGround = true }
        val unit = place(map, infantryEqid, 4, 4, 1)

        assertTrue(ExtendedVictory.withdraw(scenario, unit, hex))
        assertEquals(1, scenario.unitsWithdrawn[1])
        assertNull(hex.unit, "the formation is off the map")
        assertFalse(unit.destroyed, "and it is NOT a casualty -- it left under orders")
        assertEquals(0, scenario.unitsKilled[0], "so the other side is credited with nothing")
    }

    /** OG splits ground and air exits, so neither kind can use the other's. */
    @Test
    fun theAirAndGroundExitsAreNotInterchangeable() {
        val map = world()
        scenarioWith(map) { retreatUnitsPerSide = listOf(2, 2) }
        val groundExit = map.map!![4][4].apply { escapeGround = true }
        val airExit = map.map!![5][5].apply { escapeAir = true }
        val division = place(map, infantryEqid, 4, 4, 0)
        val wing = place(map, planeEqid, 5, 5, 0)

        assertTrue(ExtendedVictory.canWithdrawThrough(division, groundExit))
        assertFalse(ExtendedVictory.canWithdrawThrough(division, airExit), "a division cannot fly out")
        assertTrue(ExtendedVictory.canWithdrawThrough(wing, airExit))
        assertFalse(ExtendedVictory.canWithdrawThrough(wing, groundExit), "a wing cannot walk out")
    }

    /**
     * OG's *"EH for MSU only"* (`opt_eh_for_msu_only`, 15 deployed scenarios): with it on the exit
     * accepts only a formation the author designated Must-Survive.
     */
    @Test
    fun withEhForMsuOnlyTheExitTakesOnlyMustSurviveUnits() {
        val map = world()
        val scenario =
            scenarioWith(map) {
                retreatUnitsPerSide = listOf(0, 2)
                escapeHexesForMsuOnly = true
            }
        val hex = map.map!![4][4].apply { escapeGround = true }
        val ordinary = place(map, infantryEqid, 4, 4, 1)
        val designated = place(map, infantryEqid, 4, 5, 1).apply { mustSurvive = true }

        assertFalse(ExtendedVictory.canWithdrawThrough(ordinary, hex, scenario), "a spare truck may not")
        assertTrue(ExtendedVictory.canWithdrawThrough(designated, hex, scenario))
        assertFalse(ExtendedVictory.withdraw(scenario, ordinary, hex), "and the mutation refuses it too")
        assertEquals(0, scenario.unitsWithdrawn[1])
    }

    /** Absent means unrestricted -- the 105 scenarios with unreadable sources depend on it. */
    @Test
    fun withoutTheSwitchAnyFormationStillUsesTheExit() {
        val map = world()
        val scenario = scenarioWith(map) { retreatUnitsPerSide = listOf(0, 2) }
        val hex = map.map!![4][4].apply { escapeGround = true }
        val ordinary = place(map, infantryEqid, 4, 4, 1)

        assertTrue(ExtendedVictory.canWithdrawThrough(ordinary, hex, scenario))
        assertTrue(ExtendedVictory.withdraw(scenario, ordinary, hex))
    }

    /** A side with no authored quota cannot win this way, and its units do not leave. */
    @Test
    fun aSideWithNoQuotaCannotWithdrawAtAll() {
        val map = world()
        val scenario = scenarioWith(map) { retreatUnitsPerSide = listOf(0, 3) }
        val hex = map.map!![4][4].apply { escapeGround = true }
        val unit = place(map, infantryEqid, 4, 4, 0)

        assertFalse(ExtendedVictory.withdraw(scenario, unit, hex), "side 0 was given no quota")
        assertEquals(unit, hex.unit, "so the formation stays where it is")
    }

    /** The objective is met on the LAST required formation, not before. */
    @Test
    fun theRetreatObjectiveCompletesOnTheFinalUnit() {
        val map = world()
        val scenario = scenarioWith(map) { retreatUnitsPerSide = listOf(0, 2) }
        val hex = map.map!![4][4].apply { escapeGround = true }

        ExtendedVictory.withdraw(scenario, place(map, infantryEqid, 4, 4, 1), hex)
        assertFalse(ExtendedVictory.retreatObjectiveMet(scenario, 1), "one of two is not enough")

        ExtendedVictory.withdraw(scenario, place(map, infantryEqid, 4, 4, 1), hex)
        assertTrue(ExtendedVictory.retreatObjectiveMet(scenario, 1))
        assertEquals(1, ExtendedVictory.satisfiedSide(scenario, map))
    }

    // ---- Kill N enemy units ---------------------------------------------------------------------

    /** The quota is per side, and reaching it wins. */
    @Test
    fun theKillObjectiveCompletesOnTheFinalCasualty() {
        val map = world()
        val scenario = scenarioWith(map) { killUnitsPerSide = listOf(2, 0) }
        assertFalse(ExtendedVictory.killObjectiveMet(scenario, 0))
        scenario.unitsKilled[0] = 1
        assertFalse(ExtendedVictory.killObjectiveMet(scenario, 0), "one of two is not enough")
        scenario.unitsKilled[0] = 2
        assertTrue(ExtendedVictory.killObjectiveMet(scenario, 0))
        assertEquals(0, ExtendedVictory.satisfiedSide(scenario, map))
    }

    // ---- Must-Survive Units, manual 3.7.1 -------------------------------------------------------

    /**
     * **The only extended condition that is a DEFEAT rather than a victory** — *"the number of the
     * MSU that need to survive **not to lose** the scenario"*. It therefore lives outside
     * [ExtendedVictory.satisfiedSide] and has its own [ExtendedVictory.defeatedSide].
     */
    @Test
    fun losingTooManyMustSurviveUnitsLosesTheScenario() {
        val map = world()
        val scenario = scenarioWith(map) { mustSurvivePerSide = listOf(2, 0) }
        val first = place(map, infantryEqid, 2, 2, 0).apply { mustSurvive = true }
        place(map, infantryEqid, 2, 3, 0).apply { mustSurvive = true }

        assertFalse(ExtendedVictory.mustSurviveObjectiveFailed(scenario, map, 0), "both are alive")
        assertNull(ExtendedVictory.defeatedSide(scenario, map))

        first.destroyed = true
        assertTrue(ExtendedVictory.mustSurviveObjectiveFailed(scenario, map, 0), "one of two is not two")
        assertEquals(0, ExtendedVictory.defeatedSide(scenario, map))
    }

    /**
     * **A quota of 0 means "no requirement", never "all of them"** — `zero_msu` in
     * `EFILE_NOKORP/equip.cfg` exists to allow exactly that, so reading 0 as "every MSU must live"
     * would invent a defeat the author switched off.
     */
    @Test
    fun aZeroQuotaIsNoRequirementRatherThanAllOfThem() {
        val map = world()
        val scenario = scenarioWith(map) { mustSurvivePerSide = listOf(0, 0) }
        place(map, infantryEqid, 2, 2, 0).apply {
            mustSurvive = true
            destroyed = true
        }
        assertFalse(
            ExtendedVictory.mustSurviveObjectiveFailed(scenario, map, 0),
            "zero required means the side cannot lose this way",
        )
    }

    /** A designated unit on one side says nothing about the other side's quota. */
    @Test
    fun theMustSurviveQuotaIsPerSide() {
        val map = world()
        val scenario = scenarioWith(map) { mustSurvivePerSide = listOf(0, 1) }
        place(map, infantryEqid, 2, 2, 0).apply {
            mustSurvive = true
            destroyed = true
        }
        assertFalse(ExtendedVictory.mustSurviveObjectiveFailed(scenario, map, 0), "side 0 has no quota")
        assertTrue(ExtendedVictory.mustSurviveObjectiveFailed(scenario, map, 1), "side 1 has none alive")
    }

    /**
     * Campaign MSUs live in the deployment tray between scenarios. They still count as alive, and
     * their `msu` bit must survive the roster's separate save/restore path.
     */
    @Test
    fun anUndeployedRestoredCoreMsuCountsAsAlive() {
        val map = world()
        val original =
            place(map, infantryEqid, 2, 2, 0).apply {
                mustSurvive = true
                // The campaign transition saves tray formations as undeployed; the restore path
                // deliberately ignores records still marked as deployed because those are rebuilt
                // from the scenario map instead.
                isDeployed = false
            }
        val saved = reparse(GameStateSerializer.serializeCoreUnit(original))
        map.map!![2][2].delUnit(original)
        map.units.remove(original)

        map.restoreCoreUnitList(friendly, listOf(saved))
        val restored = friendly.getCoreUnitList().single()
        val scenario = scenarioWith(map) { mustSurvivePerSide = listOf(1, 0) }

        assertTrue(restored.mustSurvive, "the campaign-core restore must retain the authored flag")
        assertFalse(restored.isDeployed, "the formation is waiting in the deployment tray")
        assertEquals(1, ExtendedVictory.mustSurviveUnitsAlive(map, 0))
        assertFalse(ExtendedVictory.mustSurviveObjectiveFailed(scenario, map, 0))
    }

    /** A destroyed reserve formation is not made alive merely because it remains in the roster. */
    @Test
    fun aDestroyedCoreMsuDoesNotSatisfyTheQuota() {
        val map = world()
        val core =
            place(map, infantryEqid, 2, 2, 0).apply {
                mustSurvive = true
                destroyed = true
            }
        friendly.addCoreUnit(core)
        val scenario = scenarioWith(map) { mustSurvivePerSide = listOf(1, 0) }

        assertEquals(0, ExtendedVictory.mustSurviveUnitsAlive(map, 0))
        assertTrue(ExtendedVictory.mustSurviveObjectiveFailed(scenario, map, 0))
    }

    /** Authored conditions and their live counters must not reset when a battle save is loaded. */
    @Test
    fun extendedConditionsRoundTripThroughScenarioSaveData() {
        val source =
            scenarioWith(world()) {
                retreatUnitsPerSide = listOf(2, 0)
                killUnitsPerSide = listOf(0, 3)
                mustSurvivePerSide = listOf(1, 0)
                typedVictoryHexes = true
                unitsWithdrawn = mutableListOf(1, 0)
                unitsKilled = mutableListOf(0, 2)
            }
        val payload = reparse(GameStateSerializer.serializeScenario(source))
        val restored = Scenario(null)

        restoreVictoryMetadata(restored, payload)
        // `typedvh` is an AUTHORED option and travels in the save's `options` block since the
        // whole family started being serialized; the lossy top-level key it used to arrive under
        // cannot say "the author said nothing" (`AuthoredScenarioOptionsSaveTest`). A restore
        // applies both, so this test does too.
        AuthoredScenarioOptions.restore(restored, payload.options)

        assertEquals(listOf(2, 0), restored.retreatUnitsPerSide)
        assertEquals(listOf(0, 3), restored.killUnitsPerSide)
        assertEquals(listOf(1, 0), restored.mustSurvivePerSide)
        assertEquals(true, restored.typedVictoryHexes)
        assertEquals(listOf(1, 0), restored.unitsWithdrawn)
        assertEquals(listOf(0, 2), restored.unitsKilled)
    }

    // ---- The property that matters most ---------------------------------------------------------

    /**
     * **A scenario authoring no extended condition is untouched.** 461 of the 502 deployed
     * scenarios are in exactly this state, so this is the assertion that says the whole feature is
     * additive.
     */
    @Test
    fun aScenarioWithNoExtendedConditionsIsUnaffected() {
        val map = world()
        val scenario = scenarioWith(map) {}
        val hex = map.map!![4][4].apply { escapeGround = true }
        val unit = place(map, infantryEqid, 4, 4, 0)

        assertFalse(ExtendedVictory.withdraw(scenario, unit, hex), "no quota means no withdrawal")
        assertFalse(ExtendedVictory.retreatObjectiveMet(scenario, 0))
        assertFalse(ExtendedVictory.killObjectiveMet(scenario, 0))
        assertNull(ExtendedVictory.satisfiedSide(scenario, map), "and nobody wins by accident")
        assertNull(ExtendedVictory.defeatedSide(scenario, map), "and nobody loses by accident either")
    }

    /** An escape hex with no quota behind it is just a hex; the flag alone must not remove units. */
    @Test
    fun anEscapeHexWithoutAQuotaDoesNotSwallowFormations() {
        val map = world()
        val scenario = scenarioWith(map) { retreatUnitsPerSide = emptyList() }
        val hex = map.map!![4][4].apply { escapeGround = true }
        val unit = place(map, infantryEqid, 4, 4, 0)
        assertFalse(ExtendedVictory.withdraw(scenario, unit, hex))
        assertEquals(unit, hex.unit)
    }
}
