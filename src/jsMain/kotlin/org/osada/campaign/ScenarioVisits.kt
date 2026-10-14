package org.osada.campaign

/**
 * The [ordinal]-th time this campaign run entered [scenarioFile] (1-based).
 *
 * A campaign can enter a scenario more than once: a loss that replays the mission (`acampdf2`,
 * `rcampdfr`), or a loop back to an earlier operation (`forward` 43 -> 22, `simpob` 9 -> 0). Results
 * used to be keyed by the scenario file alone, so every later visit's result was dropped as a
 * "duplicate completion" -- with its optional objectives, its queued outcome effects and its place
 * on the map trace.
 */
internal data class ScenarioVisit(
    val scenarioFile: String,
    val ordinal: Int,
)

/**
 * Which visit the battle in play is, pinned when the battle BEGINS (`Game.handleCampaignScenarioLoaded`
 * on a fresh load) and carried by the save. A restored battle, a Restart Mission from its opening
 * checkpoint, or a second completion of the same battle therefore stays the same visit, so the
 * once-per-visit guard in [CampaignNarrativeState.recordOutcome] still catches duplicates.
 */
internal class ScenarioVisits {
    var current: ScenarioVisit? = null
        private set

    /** A fresh battle of [scenarioFile]: one past every visit of it already [recorded]. */
    fun begin(
        scenarioFile: String,
        recorded: List<ScenarioOutcomeRecord>,
    ): ScenarioVisit = ScenarioVisit(scenarioFile, lastRecorded(scenarioFile, recorded) + 1).also { current = it }

    /**
     * The visit a result of [scenarioFile] belongs to. A save written before visits has none pinned;
     * it then joins the latest recorded visit -- the old first-result-wins rule -- until the next
     * battle begins and pins one.
     */
    fun ordinalFor(
        scenarioFile: String,
        recorded: List<ScenarioOutcomeRecord>,
    ): Int =
        current?.takeIf { it.scenarioFile == scenarioFile }?.ordinal
            ?: lastRecorded(scenarioFile, recorded).coerceAtLeast(1)

    fun restore(visit: ScenarioVisit?) {
        current = visit
    }

    private fun lastRecorded(
        scenarioFile: String,
        recorded: List<ScenarioOutcomeRecord>,
    ): Int = recorded.filter { it.scenarioFile == scenarioFile }.maxOfOrNull { it.visit } ?: 0
}
