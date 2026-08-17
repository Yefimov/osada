package org.osada

/**
 * Phase-aware validation for a candidate autosave/recovery generation, per
 * `docs/design/save-recovery.md` section 7. Runs one layer above [SaveValidation]'s shape-only
 * check: this one actually looks at whether the parsed payload is a legal state for its declared
 * phase, using the same `hasAnyUnits` definition the existing stale/empty-save guard uses.
 *
 * Scoping note: this deliberately does not attempt full formation-to-reserve/map cross-resolution
 * ("every serialized core formation resolves to exactly one live representation") -- that needs a
 * per-formation-id reconciliation this change did not have budget to add. It checks the cheaper,
 * still-real signal that already exists: a preparation/deployment save must still have a non-empty
 * core roster recorded at all, which is exactly the failure mode (a truncated split-key write) this
 * workstream exists to catch.
 *
 * Scoping note 2: OSADA's save payload has no distinct "scenario ended with outcome X" marker today
 * (`GameEndgame` transitions campaign state rather than persisting a completed-outcome save shape),
 * so the `scenarioEnd` phase case below is a pass-through rather than the outcome check the design
 * doc describes; adding that marker is future work, not invented here.
 */
internal object SavePhaseValidation {
    const val PHASE_PREPARATION = "preparation"
    const val PHASE_DEPLOYMENT = "deployment"
    const val PHASE_PLAYER_TURN = "playerTurn"
    const val PHASE_AI_TURN = "aiTurn"
    const val PHASE_SCENARIO_END = "scenarioEnd"

    fun isValidForPhase(
        phase: String,
        scenarioData: dynamic,
        playersData: dynamic,
    ): Boolean =
        when (phase) {
            PHASE_PREPARATION, PHASE_DEPLOYMENT -> hasCoreUnits(playersData)
            PHASE_PLAYER_TURN, PHASE_AI_TURN ->
                hasValidTurnBounds(scenarioData) && hasAnyUnitsWeak(scenarioData, playersData)
            PHASE_SCENARIO_END -> true
            else -> hasAnyUnitsWeak(scenarioData, playersData)
        }

    /** Derives the phase label to store alongside a snapshot from the same fields the loader
     *  already reads, never invented from anything else. */
    fun derivePhase(
        scenarioData: dynamic,
        deployPhaseActive: Boolean,
    ): String =
        when {
            deployPhaseActive -> PHASE_DEPLOYMENT
            scenarioData == null -> PHASE_PREPARATION
            else -> PHASE_PLAYER_TURN
        }

    @Suppress("ReturnCount")
    private fun hasValidTurnBounds(scenarioData: dynamic): Boolean {
        if (scenarioData == null) return false
        val turn = readTurn(scenarioData) ?: return false
        val maxTurns = readMaxTurns(scenarioData)
        return if (maxTurns == null) turn >= 0 else turn in 0..maxTurns
    }

    /**
     * The turn counter, from either place a save may carry it.
     *
     * [GameStateSerializer] writes it BOTH at `scenario.turn` and at `scenario.map.turn`; saves
     * predating the former only have the latter. Reading only `scenario.turn` made every such save
     * look like it had no turn at all -- and therefore no valid bounds -- which is a rejection, not
     * a recovery.
     */
    private fun readTurn(scenarioData: dynamic): Int? = scenarioData.turn as? Int ?: scenarioData.map?.turn as? Int

    private fun readMaxTurns(scenarioData: dynamic): Int? =
        scenarioData.maxTurns as? Int ?: scenarioData.map?.maxTurns as? Int

    /**
     * The hex grid, under either name the LOADER accepts.
     *
     * `GameStateRestore` reads `mapData.hexes` when present and falls back to `mapData.map`, so a
     * save using the older key restores perfectly well. Validation that recognised only `hexes`
     * therefore reported "no units on the map" for a map full of them, and this predicate is what
     * decides whether a generation is discarded in favour of an older one. A validator must never
     * be stricter about a shape than the code that consumes it.
     */
    private fun hexGrid(scenarioData: dynamic): dynamic {
        val map = scenarioData?.map ?: return null
        val hexes = map.hexes
        return if (hexes != null && hexes != undefined) hexes else map.map
    }

    @Suppress("ReturnCount")
    private fun hasCoreUnits(playersData: dynamic): Boolean {
        if (playersData == null) return false
        val len = (playersData.length as? Int) ?: 0
        for (i in 0 until len) {
            val core = playersData[i]?.coreUnits
            if (core != null && (core.length as? Int ?: 0) > 0) return true
        }
        return false
    }

    private fun hasAnyUnitsWeak(
        scenarioData: dynamic,
        playersData: dynamic,
    ): Boolean = hasCoreUnits(playersData) || hasUnitOnMap(scenarioData) || hasPendingReinforcement(scenarioData)

    @Suppress("ReturnCount")
    private fun hasUnitOnMap(scenarioData: dynamic): Boolean {
        val hexes = hexGrid(scenarioData) ?: return false
        val rows = (hexes.length as? Int) ?: 0
        for (r in 0 until rows) {
            val row = hexes[r] ?: continue
            val cols = (row.length as? Int) ?: 0
            for (c in 0 until cols) {
                val hex = row[c]
                if (hex != null && (hex.unit != null || hex.airunit != null)) return true
            }
        }
        return false
    }

    private fun hasPendingReinforcement(scenarioData: dynamic): Boolean {
        val reinf = scenarioData?.reinforcements ?: return false
        return (js("Object.keys(reinf).length") as? Int ?: 0) > 0
    }
}
