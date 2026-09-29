package org.osada

import org.osada.i18n.I18n
import org.osada.ui.ConfirmCard
import org.osada.ui.UIBuilder
import org.osada.ui.message

// Split from GameEndgame.kt (function-count limit): the question a campaign defeat asks first.

/**
 * A campaign defeat is offered as a choice before it is committed: restart the mission from its
 * opening checkpoint, or accept the loss and let the campaign go on. Everything [continueCampaign]
 * does after this point is irreversible for the battle -- prestige, dossier, archive, the next
 * scenario's load clears the checkpoint -- so the question has to come first. Returns true while
 * the choice is pending; the accept branch re-enters [continueCampaign] with the question answered.
 */
internal fun Game.offerRestartBeforeDefeat(
    outcome: String,
    reason: EndGameType,
): Boolean {
    if (outcome != "lose" || defeatAccepted || !missionRestartCheckpoint.isAvailable()) {
        defeatAccepted = false
        return false
    }
    awaitingDefeatChoice = true
    ConfirmCard.openChoice(
        I18n.t("game.outcome.lose"),
        lossReason(reason) + I18n.t("game.defeat_choice.body"),
        I18n.t("menu.main.restart_mission.label"),
        I18n.t("game.defeat_choice.accept"),
        {
            awaitingDefeatChoice = false
            defeatAccepted = true
            continueCampaign(outcome, reason)
        },
    ) {
        awaitingDefeatChoice = false
        if (!missionRestartCheckpoint.restart()) {
            UIBuilder.message(I18n.t("game.error.title"), I18n.t("menu.main.restart_mission.failed")) {
                defeatAccepted = true
                continueCampaign(outcome, reason)
            }
        }
    }
    return true
}
