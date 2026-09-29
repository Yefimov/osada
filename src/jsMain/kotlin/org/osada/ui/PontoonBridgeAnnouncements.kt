package org.osada.ui

import org.osada.Game
import org.osada.PlayerType
import org.osada.i18n.I18n
import org.osada.model.GameUnit

/**
 * The two moments of a pontoon crossing (`rules/PontoonBridges`), said on the hex itself.
 *
 * Both are said, not merely drawn, because the rule has a delay the map cannot show on its own: a
 * bridge unit standing in a river looks the same whether its crossing opens now or next turn, and
 * a player who tries to cross on the laying turn would otherwise just find the river still closed.
 * Only to a local human on the owning side -- the enemy learns of the crossing by seeing it.
 */
internal object PontoonBridgeAnnouncements {
    /** The formation has entered the river and started laying its crossing. */
    fun laying(
        ui: UI,
        unit: GameUnit,
    ) {
        if (unit.player?.type != PlayerType.HUMAN_LOCAL) return
        val pos = unit.getPos() ?: return
        ui.showAlert(pos.row, pos.col, I18n.t("bridge.laying.alert"), true)
        HudLog.addAt(pos.row, pos.col, I18n.t("bridge.laying", mapOf("unit" to unit.unitData(true).name)))
    }

    /** Every crossing that opened at the start of the turn that has just begun. */
    fun opened(game: Game) {
        val ui = game.ui ?: return
        val map = game.scenario?.map?.takeIf { it.currentPlayer?.type == PlayerType.HUMAN_LOCAL } ?: return
        map.lastBridgesOpened.forEach { unit ->
            val pos = unit.getPos() ?: return@forEach
            ui.showAlert(pos.row, pos.col, I18n.t("bridge.ready.alert"), true)
            HudLog.addAt(pos.row, pos.col, I18n.t("bridge.ready", mapOf("unit" to unit.unitData(true).name)))
        }
    }
}
