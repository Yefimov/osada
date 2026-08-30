package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
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
