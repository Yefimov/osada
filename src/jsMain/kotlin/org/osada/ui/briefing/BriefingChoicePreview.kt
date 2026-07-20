package org.osada.ui.briefing

import org.osada.campaign.CampaignEffect
import org.osada.model.Equipment
import org.osada.unitClassNames

/**
 * The one-line preview shown under a dialogue choice, so the player is not deciding blind.
 *
 * Two sources, in order:
 *  1. the authored [BriefingChoice.hint] — qualitative and free to gesture at long-term standing
 *     ("Command will note your caution");
 *  2. failing that, a generated summary of the choice's **immediate game-mechanic** effects.
 *
 * **Narrative consequences are never disclosed.** `setFlag` / `clearFlag` / `route` are
 * deliberately omitted from the generated summary: they are what later scenarios and campaign
 * branching turn on, and naming them would both spoil the story and turn the decision into an
 * optimisation problem. A choice whose only effects are narrative therefore previews as nothing
 * at all unless an author writes a hint for it — which is the intended prompt to write one.
 */
internal object BriefingChoicePreview {
    fun of(choice: BriefingChoice): String = choice.hint.trim().ifBlank { describeMechanics(choice.effects) }

    private fun describeMechanics(effects: List<CampaignEffect>): String =
        effects.mapNotNull { describe(it) }.joinToString(" · ")

    private fun describe(effect: CampaignEffect): String? =
        when (effect) {
            is CampaignEffect.Prestige -> "${signed(effect.amount)} prestige"
            is CampaignEffect.GrantExperience -> describeExperience(effect)
            is CampaignEffect.Resupply -> describeResupply(effect)
            is CampaignEffect.GrantUnit -> "Reinforcement: ${equipmentName(effect.eqid)}"
            // Narrative state — never previewed. See the class doc.
            is CampaignEffect.SetFlag, is CampaignEffect.ClearFlag -> null
            // Parsed, clamped and persisted, but `CampaignEffectApplier` still no-ops them (see
            // the effect catalogue in docs/campaign-dialogue-and-consequences.md §7). Previewing
            // them would promise the player something that does not happen — worse than silence.
            // Re-enable each line here as its applier lands.
            is CampaignEffect.UnlockEquipment,
            is CampaignEffect.DeploymentSlots,
            is CampaignEffect.ShiftReinforcements,
            is CampaignEffect.Route,
            -> null
        }

    private fun describeExperience(effect: CampaignEffect.GrantExperience): String {
        val scope = effect.unitClass?.let { unitClassNames.getOrNull(it) }
        return if (scope.isNullOrBlank()) {
            "${signed(effect.amount)} experience"
        } else {
            "${signed(effect.amount)} experience ($scope)"
        }
    }

    private fun describeResupply(effect: CampaignEffect.Resupply): String {
        val parts = mutableListOf<String>()
        effect.strength?.takeIf { it != 0 }?.let { parts += "${signed(it)} strength" }
        if (effect.refuel) parts += "refuel"
        if (effect.rearm) parts += "rearm"
        return if (parts.isEmpty()) "Resupply" else "Resupply: ${parts.joinToString(", ")}"
    }

    /** Falls back to the raw id when the efile has no such equipment — the effect itself is
     *  silently skipped at apply time, but the preview must never render "null". */
    private fun equipmentName(eqid: Int): String =
        Equipment.getEquipment(eqid)?.name?.takeIf { it.isNotBlank() } ?: "equipment #$eqid"

    private fun signed(value: Int): String = if (value >= 0) "+$value" else "$value"
}
