package org.osada.ui

import org.osada.Game
import org.osada.i18n.GameText
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.getCountriesBySide
import org.osada.model.getCountryName
import org.osada.resolveScenarioBriefing
import org.osada.scenario.objectiveReport
import org.osada.ui.briefing.OwedBriefingDecision
import org.osada.ui.briefing.ScenarioBriefingController
import org.osada.ui.briefing.ScenarioFacts

// Split from UI.kt (function-count limit): what the opening ceremony shows of the loaded battle,
// and the owed-decision sheet that reuses it.

/** The header and orders of the battle now loaded, as its opening ceremony shows them. */
internal fun Game.ceremonyFacts(): ScenarioFacts {
    val tutorial = scenario?.file == Game.defaultScenario
    return ScenarioFacts(
        title = if (tutorial) I18n.t("tutorial.welcome.title") else scenario?.name ?: "",
        // Not `toDateString()`: its expanded-year form for a BC date is implementation
        // defined, and it is not localized either.
        dateLabel =
            scenario
                ?.date
                ?.let(GameplayLocalization::scenarioDateLabel)
                .orEmpty(),
        sidesLabel = sidesLabel(),
        ordersText = if (tutorial) I18n.t("tutorial.welcome.body") else scenario?.getDescription() ?: "",
        extendedObjectives =
            scenario
                ?.objectiveReport(spotSide, revealHidden = false)
                ?.extended
                .orEmpty(),
    )
}

/**
 * Opens the orders sheet on the decision this campaign battle still owes, if any, holding the turn
 * loop until [onAnswered] runs. Returns false, and does nothing, when nothing is owed.
 */
internal fun UI.showOwedDecision(
    beginKey: String,
    onAnswered: () -> Unit,
): Boolean {
    val campaign = game.campaign
    val scenarioFile = game.scenario?.file
    val owed = OwedBriefingDecision.forCurrentBattle(game)
    if (campaign == null || scenarioFile == null || owed == null) return false
    val raw = game.resolveScenarioBriefing(campaign.getCurrentScenario(), scenarioFile)
    game.uiMessageClicked = false
    ScenarioBriefingController.show(campaign.file, scenarioFile, game.ceremonyFacts(), raw, owedBeginKey = beginKey) {
        game.uiMessageClicked = true
        onAnswered()
    }
    return true
}

/** What a restored battle does once its map is drawable (`UI.setNewScenario`). */
internal enum class RestoredResume {
    /** Play resumes at once. */
    RESUME,

    /** The battle owes a briefing decision: the orders sheet asks it first. */
    ASK_OWED,

    /** An outcome already waits on the player (defeat question, owed route); leave its prompt up. */
    OUTCOME_PENDING,
}

internal fun Game.restoredResume(): RestoredResume =
    when {
        awaitingOutcomeAnswer -> RestoredResume.OUTCOME_PENDING
        OwedBriefingDecision.forCurrentBattle(this) != null -> RestoredResume.ASK_OWED
        else -> RestoredResume.RESUME
    }

/**
 * The restore half of `UI.setNewScenario`, once the restored battle's map is drawable.
 *
 * A battle saved without its briefing's decision (an earlier save loaded after the briefing was
 * marked seen) gets that decision back before play resumes, so its victory cannot fall through to
 * the authored route (`OwedBriefingDecision`); [askOwed] opens it and returns false when it could
 * not. Only those two branches drop the previous battle's briefing: an outcome prompt already on
 * its way (`offerOwedRouteDecision`) may still be loading its translations, and clearing the
 * briefing source cancels that load's callback, leaving the battle paused with no dialog at all
 * (found in review, 2026-10-14). Its answer continues the campaign, so nothing else runs here.
 */
internal fun Game.settleRestoredBattle(
    askOwed: () -> Boolean,
    resume: () -> Unit,
) {
    when (restoredResume()) {
        RestoredResume.RESUME -> {
            UIBuilder.clearScenarioBriefing()
            resume()
        }
        RestoredResume.ASK_OWED -> {
            UIBuilder.clearScenarioBriefing()
            if (!askOwed()) resume()
        }
        RestoredResume.OUTCOME_PENDING -> Unit
    }
}

// Prefer real country names ("Romania vs Soviet Union") over the generic side labels; each side's
// first country is its primary belligerent. Falls back to Axis/Allies when the map has no players.
private fun Game.sidesLabel(): String {
    val map = scenario?.map
    val name0 = map?.getCountriesBySide(0)?.firstOrNull()?.let { Equipment.getCountryName(it) }
    val name1 = map?.getCountriesBySide(1)?.firstOrNull()?.let { Equipment.getCountryName(it) }
    val real = { name: String? -> !name.isNullOrBlank() && name != "Unknown" }
    if (real(name0) && real(name1)) {
        return I18n.t("briefing.sides", mapOf("left" to name0, "right" to name1))
    }
    val axis = GameText.side(0)
    val allies = GameText.side(1)
    val commanded = campaignPlayer?.getSideName()
    return if (commanded != null) {
        I18n.t(
            "briefing.sides.commanded",
            mapOf("left" to axis, "right" to allies, "commanded" to commanded),
        )
    } else {
        I18n.t("briefing.sides", mapOf("left" to axis, "right" to allies))
    }
}
