package org.osada.ui.campaignmap

import org.osada.Game
import org.osada.campaign.CampaignNarrativeSerializer
import org.osada.current
import org.osada.ui.StartMenuCampaignData

/**
 * Reads the stored run of a campaign, if there is one, for the map's "your path" highlight.
 *
 * Read-only: it takes the same best readable generation the hero desk does
 * (`GameState.readableGeneration`) and never restores, migrates or writes anything. A run whose
 * generations cannot be read simply shows no trace.
 */
internal object CampaignRunTraceLoader {
    fun load(data: CampaignMapData): CampaignRunTrace {
        val run = StartMenuCampaignData.campaignRunsByFile()[data.campaignFile] ?: return CampaignRunTrace.NONE
        val payload =
            Game.current
                ?.state
                ?.readableGeneration(run.campaignRunId)
                ?.payload
        val outcomes =
            try {
                val narrative: dynamic = payload?.let { JSON.parse<dynamic>(it) }?.campaign?.narrative
                CampaignNarrativeSerializer.deserialize(narrative).scenarioOutcomes.mapNotNull { record ->
                    OutcomeGrade
                        .byKey(
                            record.outcome,
                        )?.let { PlayedOutcome(record.scenarioFile, it, record.nextScenario) }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable,
            ) {
                console.warn("[OSADA] campaign map: run ${run.campaignRunId} unreadable, no trace shown", error)
                emptyList()
            }
        val ending =
            if (run.completed) CampaignRunTrace.finalOutcome(data.graph, run.campaignScenario, run.outcome) else null
        return CampaignRunTrace.of(
            data.graph,
            outcomes + listOfNotNull(ending),
            run.campaignScenario.takeUnless { run.completed },
        )
    }
}
