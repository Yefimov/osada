package org.osada

import org.osada.i18n.I18n
import org.osada.ui.ConfirmCard
import org.osada.ui.HeroPromotionPresenter
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

/**
 * Forgets every question the battle being torn down still had open, WITHOUT answering it.
 *
 * None of these was reset by a teardown. The defeat card has no backdrop, so a player could open the
 * menu from under it and load a save: the loaded battle inherited `awaitingDefeatChoice` (its turn
 * loop never ran again), and the old card's buttons stayed live -- "Restart mission" or "accept
 * defeat" acting on whatever was loaded. A pending campaign transition (`nextScenarioData`) likewise
 * outlived a load and carried the loaded game off into the old campaign's next mission. The
 * promotion dialog and the OK message would have applied an old battle's choice or callback.
 */
internal fun Game.discardBattleQuestions() {
    awaitingDefeatChoice = false
    defeatAccepted = false
    awaitingRouteDecision = false
    nextScenarioData = null
    continueCampaignFlag = false
    ConfirmCard.close()
    HeroPromotionPresenter.discard()
}
