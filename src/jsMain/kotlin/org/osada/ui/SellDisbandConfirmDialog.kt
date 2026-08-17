package org.osada.ui

import org.osada.hero.HeroCampaign
import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.rules.GameRules
import org.osada.rules.calculateUnitSellCost

/**
 * Confirmation card for permanently selling (from reserve) or disbanding (on the map) a unit.
 * `docs/design/action-affordances-and-objectives.md` section 5: name the unit and commander, show
 * the exact refund, default focus on Cancel, Escape cancels, Enter only confirms while the
 * destructive button itself has focus, no Delete-key shortcut, and the caller rechecks the
 * selection/refund are still current before actually committing. Presentation itself lives in the
 * shared [ConfirmCard].
 */
internal object SellDisbandConfirmDialog {
    /** [isDisband] selects wording: a reserve unit is "sold", a deployed unit is "disbanded" --
     *  same consequence, existing player-facing vocabulary from the equipment window's own button.
     *  [onConfirm] is only invoked if the player actually activates the destructive button; the
     *  caller re-validates the unit/refund are still current before calling it. */
    fun open(
        unit: GameUnit,
        isDisband: Boolean,
        onConfirm: () -> Unit,
    ) {
        val refund = GameRules.calculateUnitSellCost(unit)
        val unitName = unit.customName ?: unit.unitData(true).name
        val commanderName = HeroCampaign.dossier(unit)?.name

        val title =
            I18n.t(
                if (isDisband) "equipment.action.disband.confirm.title" else "equipment.action.sell.confirm.title",
                mapOf("unit" to unitName),
            )
        val body =
            if (commanderName != null) {
                I18n.t(
                    "equipment.action.sell_disband.confirm.body_commander",
                    mapOf("refund" to refund.toString(), "commander" to commanderName),
                )
            } else {
                I18n.t("equipment.action.sell_disband.confirm.body", mapOf("refund" to refund.toString()))
            }
        val confirmLabelKey =
            if (isDisband) {
                "equipment.action.disband.confirm.confirm_button"
            } else {
                "equipment.action.sell.confirm.confirm_button"
            }
        val confirmLabel = I18n.t(confirmLabelKey)

        ConfirmCard.open(title, body, confirmLabel, onConfirm)
    }
}
