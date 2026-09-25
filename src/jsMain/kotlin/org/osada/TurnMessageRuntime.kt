package org.osada

import org.osada.i18n.I18n
import org.osada.scenario.ScenarioTextLocalization
import org.osada.ui.HudLog
import org.osada.ui.UIBuilder
import org.osada.ui.messageDynamic

/**
 * Shows the scenario author's message for the turn that has just begun
 * ([org.osada.scenario.ScenarioTurnMessages]) — OG's *"turn start window"* text.
 *
 * Only to a player sitting at THIS screen: an AI turn has nobody to read it, and a remote human
 * gets it on their own client. Each turn's message is shown once, to the first local human whose
 * turn it is; OG's messages are written to "the player", and a hot-seat opponent reading the other
 * side's narration twice would be noise rather than fidelity.
 *
 * Queued with [UIBuilder.messageDynamic] rather than the shared `ui-message` box, which the same
 * turn's `<reinforce message>` may already be using, and logged to the HUD so it can be re-read.
 */
internal fun Game.announceTurnMessage() {
    val current = scenario ?: return
    val turn = current.map.turn
    val localHuman = current.map.currentPlayer?.type == PlayerType.HUMAN_LOCAL
    if (localHuman && turn > current.turnMessageShownThrough) {
        current.turnMessageShownThrough = turn
        current.turnMessages?.get(turn)?.let { authored ->
            val text = ScenarioTextLocalization.turnMessage(current.file, turn, authored)
            UIBuilder.messageDynamic(
                I18n.t("game.turn_message.title", mapOf("turn" to turn)),
                "<p>" + escapeTurnMessage(text) + "</p>",
            )
            HudLog.add(text)
        }
    }
}

private fun escapeTurnMessage(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
