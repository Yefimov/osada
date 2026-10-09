package org.osada.ui

import org.osada.model.GameUnit
import org.osada.uiSettings

/**
 * OG's *"Hide unit strength & combat estimated"* (manual §3.9.2;
 * `docs/design/og-parity-followups.md` §3, built 2026-10-09), the Gameplay setting
 * [org.osada.UiSettings.hideNumbers].
 *
 * The one place every display asks *"may I print this number?"*, so the policy cannot drift between
 * the counter, the cursor, the forecast strip and the enemy card:
 *
 * - **counters** ([UnitRenderer]): no strength figure on any unit, and a fixed plaque width, since a
 *   wider plaque would still say "10 or more";
 * - **casualty estimates** (cursor and `#osadaForecast`): none. The touch preview still opens and
 *   still carries its Attack button ([TargetPreviewController]) -- only the numbers go;
 * - **other sides' strength** (enemy card, forecast, location line): `?`. The player's OWN strength
 *   stays in their own card: they need it to decide on reinforcement, and it is no secret.
 *
 * View only. It changes no rule, is never sent to a peer, and leaves supply, fuel and ammunition
 * warnings alone.
 */
internal object HiddenNumbers {
    const val PLACEHOLDER = "?"

    val active: Boolean get() = uiSettings.hideNumbers

    /** Whether a counter's strength figure is drawn. */
    val showCounterStrength: Boolean get() = !active

    /** Whether a combat casualty estimate is shown. */
    val showCombatEstimate: Boolean get() = !active

    /** [unit]'s strength as the player on [viewerSide] may read it. */
    fun strengthFor(
        unit: GameUnit,
        viewerSide: Int?,
    ): String = if (active && unit.player?.side != viewerSide) PLACEHOLDER else unit.strength.toString()
}
