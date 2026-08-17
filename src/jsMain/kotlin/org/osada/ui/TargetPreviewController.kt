package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.model.getActiveLayerTarget
import org.osada.uiSettings

/**
 * Touch combat: preview first, attack second (spec §17).
 *
 * On desktop the attack forecast appears on hover, so a click is already an informed decision. A
 * finger has no hover, so the first tap on an attackable enemy must NOT launch the attack — it
 * shows the forecast (attacker, defender, expected losses, attack/defence values) with explicit
 * **Attack** and **Cancel** controls. The attack happens on the second tap on the same target, or
 * on the Attack button.
 *
 * Two invariants matter beyond ergonomics:
 * - The preview is local UI state. It is never serialised, never persisted, and never sent to a
 *   multiplayer peer (spec §54.1); only the confirmed attack becomes a game command.
 * - Eligibility is re-checked at confirmation time against the live map, so a preview that a
 *   remote update or an animation has invalidated can never commit a stale attack (spec §54.3).
 *   The predicates themselves stay in the rules layer — nothing is copied here.
 */
internal object TargetPreviewController {
    private const val ACTIONS_ID = "osadaForecastActions"
    private const val PREVIEW_CLASS = "bz--target-preview"

    private var pendingRow = -1
    private var pendingCol = -1
    private var pendingAttackerId = -1

    val hasPending: Boolean get() = pendingAttackerId != -1

    /** True when [row]/[col] is the hex a preview is currently open on. */
    fun isPendingCell(
        row: Int,
        col: Int,
    ): Boolean = hasPending && pendingRow == row && pendingCol == col

    /**
     * Confirmation defaults on for a coarse pointer and follows the player's explicit choice
     * otherwise, so desktop click-to-attack is untouched unless it is asked for.
     */
    fun isConfirmRequired(): Boolean =
        when (uiSettings.confirmAttacks) {
            ConfirmAttacks.ON -> true
            ConfirmAttacks.OFF -> false
            else -> MobileLayoutController.isCoarsePointer
        }

    /** True when this tap should open a preview rather than execute the attack. */
    fun shouldPreview(
        attacker: GameUnit,
        row: Int,
        col: Int,
    ): Boolean {
        if (!isConfirmRequired()) return false
        val isSecondTapOnSameTarget =
            pendingAttackerId == attacker.id && pendingRow == row && pendingCol == col
        return !isSecondTapOnSameTarget
    }

    fun preview(
        ui: UI,
        attacker: GameUnit,
        defender: GameUnit,
        row: Int,
        col: Int,
    ) {
        pendingRow = row
        pendingCol = col
        pendingAttackerId = attacker.id
        val ownSide =
            ui.game.scenario
                ?.map
                ?.currentPlayer
                ?.side ?: 0
        // The same forecast renderer the desktop hover path uses, so the numbers a phone player
        // sees are the numbers a mouse player sees — one implementation, no divergence.
        BottomZoneBuilder.renderForecast(attacker, defender, ownSide)
        ensureActions(ui)
        byId("osada-bottomzone")?.classList?.add(PREVIEW_CLASS)
    }

    /** Executes the previewed attack after re-validating it. Repeat activation is a no-op. */
    fun confirm(ui: UI) {
        // Repeated activation of a resolved preview must not send a second command.
        if (!hasPending) return
        val map = ui.game.scenario?.map
        val attacker = map?.currentUnit
        val hex = map?.map?.get(pendingRow)?.get(pendingCol)
        val target =
            if (attacker != null && hex != null) hex.getActiveLayerTarget(attacker, uiSettings.airMode) else null
        val stillValid =
            attacker != null &&
                target != null &&
                attacker.id == pendingAttackerId &&
                !attacker.hasFired &&
                hex?.isAttackSel == true &&
                !ui.game.waitUIAnimation
        clear()
        if (!stillValid) {
            // The position changed under an open preview: say so rather than firing blind.
            MobileStatusStrip.show(I18n.t("mobile.combat.stale"))
            BottomZoneBuilder.setState(if (attacker != null) "own" else "hidden")
            return
        }
        ui.uiUnitAttack(attacker, target)
    }

    /** Player cancelled, or the tap landed somewhere else — drop the preview, change nothing. */
    fun cancel(ui: UI) {
        val hadPending = hasPending
        clear()
        if (!hadPending) return
        val selected =
            ui.game.scenario
                ?.map
                ?.currentUnit
        BottomZoneBuilder.setState(if (selected != null) "own" else "hidden")
    }

    /** Drops the preview without touching the bottom zone (the caller is about to repaint it). */
    fun clear() {
        pendingRow = -1
        pendingCol = -1
        pendingAttackerId = -1
        byId("osada-bottomzone")?.classList?.remove(PREVIEW_CLASS)
    }

    /**
     * Attack/Cancel are built once and reused. They carry visible text, not glyphs: an attack is
     * the most expensive irreversible action in the game and must never be a guess (spec §43.3).
     */
    private fun ensureActions(ui: UI) {
        if (byId(ACTIONS_ID) != null) return
        val root = byId("osadaForecast") ?: return
        val actions = addTag(root, "div")
        actions.id = ACTIONS_ID
        actions.className = "osada-fc-actions"

        val attack = addTag(actions, "div")
        attack.id = "osadaForecastAttack"
        attack.className = "osada-btn osada-btn--danger"
        attack.textContent = I18n.t("mobile.combat.attack.label")
        attack.asButton(I18n.t("mobile.combat.attack.label")) { confirm(ui) }

        val cancelBtn = addTag(actions, "div")
        cancelBtn.id = "osadaForecastCancel"
        cancelBtn.className = "osada-btn osada-btn--secondary"
        cancelBtn.textContent = I18n.t("mobile.combat.cancel.label")
        cancelBtn.asButton(I18n.t("mobile.combat.cancel.label")) { cancel(ui) }
    }
}

/** Values of the "Confirm attacks" setting. */
internal object ConfirmAttacks {
    const val AUTO = "auto"
    const val ON = "on"
    const val OFF = "off"
}
