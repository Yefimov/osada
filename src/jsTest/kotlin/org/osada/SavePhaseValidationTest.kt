package org.osada

import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `docs/design/save-recovery.md` section 7's phase-aware validation. */
class SavePhaseValidationTest {
    private fun scenarioData(
        turn: Int = 1,
        maxTurns: Int = 10,
        hasMapUnit: Boolean = false,
        hasReinforcement: Boolean = false,
    ): dynamic {
        val hex = if (hasMapUnit) json(Pair("unit", json(Pair("id", 1)))) else json()
        val row = arrayOf(hex)
        val hexes = arrayOf(row)
        val map = json(Pair("hexes", hexes))
        val reinforcements = if (hasReinforcement) json(Pair("0", arrayOf(json()))) else json()
        return json(
            Pair("turn", turn),
            Pair("maxTurns", maxTurns),
            Pair("map", map),
            Pair("reinforcements", reinforcements),
        )
    }

    private fun playersData(coreUnitCount: Int): dynamic =
        arrayOf(json(Pair("coreUnits", (0 until coreUnitCount).map { json() }.toTypedArray())))

    /**
     * The pre-`hexes` save shape: the grid lives at `scenario.map.map` and the turn counter only at
     * `scenario.map.turn`. `GameStateRestore` reads both shapes (it prefers `hexes` and falls back
     * to `map`), so validation must accept both too -- otherwise a save that would restore
     * perfectly is judged to have no units and gets discarded in favour of an older generation.
     */
    private fun legacyShapedScenarioData(
        turn: Int = 1,
        maxTurns: Int = 16,
        hasMapUnit: Boolean = true,
    ): dynamic {
        val hex = if (hasMapUnit) json(Pair("unit", json(Pair("id", 1)))) else json()
        val map =
            json(
                Pair("map", arrayOf(arrayOf(hex))),
                Pair("turn", turn),
                Pair("maxTurns", maxTurns),
            )
        return json(Pair("map", map), Pair("reinforcements", json()))
    }

    @Test
    fun playerTurnAcceptsTheLegacyMapMapGridAndMapLevelTurn() {
        val valid =
            SavePhaseValidation.isValidForPhase(
                SavePhaseValidation.PHASE_PLAYER_TURN,
                legacyShapedScenarioData(),
                playersData(coreUnitCount = 0),
            )
        assertTrue(valid, "a save whose grid is under map.map still has units and must be accepted")
    }

    @Test
    fun playerTurnStillRejectsALegacyShapedSaveWithNoUnitsAnywhere() {
        val valid =
            SavePhaseValidation.isValidForPhase(
                SavePhaseValidation.PHASE_PLAYER_TURN,
                legacyShapedScenarioData(hasMapUnit = false),
                playersData(coreUnitCount = 0),
            )
        assertFalse(valid, "shape tolerance must not weaken the empty-save check itself")
    }

    @Test
    fun turnBoundsAreReadFromTheMapWhenTheScenarioLevelTurnIsAbsent() {
        val outOfBounds =
            SavePhaseValidation.isValidForPhase(
                SavePhaseValidation.PHASE_PLAYER_TURN,
                legacyShapedScenarioData(turn = 99, maxTurns = 16),
                playersData(coreUnitCount = 0),
            )
        assertFalse(outOfBounds, "a map-level turn past maxTurns is still out of bounds")
    }

    @Test
    fun preparationPhaseAcceptsZeroMapUnitsWithNonEmptyCore() {
        val valid =
            SavePhaseValidation.isValidForPhase(
                SavePhaseValidation.PHASE_PREPARATION,
                scenarioData(hasMapUnit = false),
                playersData(coreUnitCount = 6),
            )
        assertTrue(valid, "a deploy-phase save with everyone still in reserve must be accepted")
    }

    @Test
    fun preparationPhaseRejectsEmptyCoreRoster() {
        val valid =
            SavePhaseValidation.isValidForPhase(
                SavePhaseValidation.PHASE_PREPARATION,
                scenarioData(hasMapUnit = false),
                playersData(coreUnitCount = 0),
            )
        assertFalse(valid, "an empty core roster is exactly the truncated-write failure mode this guards")
    }

    @Test
    fun playerTurnRequiresUnitsAndValidTurnBounds() {
        val validWithMapUnit =
            SavePhaseValidation.isValidForPhase(
                SavePhaseValidation.PHASE_PLAYER_TURN,
                scenarioData(turn = 4, maxTurns = 10, hasMapUnit = true),
                playersData(coreUnitCount = 0),
            )
        assertTrue(validWithMapUnit)

        val invalidOutOfBounds =
            SavePhaseValidation.isValidForPhase(
                SavePhaseValidation.PHASE_PLAYER_TURN,
                scenarioData(turn = 99, maxTurns = 10, hasMapUnit = true),
                playersData(coreUnitCount = 0),
            )
        assertFalse(invalidOutOfBounds, "turn beyond maxTurns must fail validation")

        val invalidNoUnits =
            SavePhaseValidation.isValidForPhase(
                SavePhaseValidation.PHASE_PLAYER_TURN,
                scenarioData(turn = 4, maxTurns = 10, hasMapUnit = false),
                playersData(coreUnitCount = 0),
            )
        assertFalse(invalidNoUnits, "a running-turn save with no unit anywhere is the stale/empty-save case")
    }

    @Test
    fun playerTurnAcceptsPendingReinforcementAloneAsPresenceSignal() {
        val valid =
            SavePhaseValidation.isValidForPhase(
                SavePhaseValidation.PHASE_PLAYER_TURN,
                scenarioData(turn = 1, maxTurns = 10, hasMapUnit = false, hasReinforcement = true),
                playersData(coreUnitCount = 0),
            )
        assertTrue(valid)
    }

    @Test
    fun scenarioEndIsAlwaysValid() {
        val valid =
            SavePhaseValidation.isValidForPhase(
                SavePhaseValidation.PHASE_SCENARIO_END,
                scenarioData(hasMapUnit = false),
                playersData(coreUnitCount = 0),
            )
        assertTrue(valid, "a scenario-end snapshot may legitimately have no surviving units")
    }

    @Test
    fun derivePhasePicksDeploymentOnlyWhenDeployPhaseIsActive() {
        val deploying = SavePhaseValidation.derivePhase(scenarioData(), deployPhaseActive = true)
        val playing = SavePhaseValidation.derivePhase(scenarioData(), deployPhaseActive = false)
        assertTrue(deploying == SavePhaseValidation.PHASE_DEPLOYMENT)
        assertTrue(playing == SavePhaseValidation.PHASE_PLAYER_TURN)
    }
}
