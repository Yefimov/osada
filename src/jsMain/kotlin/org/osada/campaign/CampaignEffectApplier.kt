package org.osada.campaign

import org.osada.UNIT_MAX_EXPERIENCE
import org.osada.campaign.CampaignEffectApplier.apply
import org.osada.model.Equipment
import org.osada.model.Player
import org.osada.model.acquireUnit
import org.osada.model.awardPrestige

/**
 * Applies typed campaign effects to real game objects.
 *
 * Idempotency is enforced HERE and nowhere else: [apply] consults
 * [CampaignEffectLedger.markApplied] before touching anything, so an effect whose id has
 * already been applied in this campaign run is a no-op regardless of how it was reached
 * (briefing reopened, dialogue reviewed, save restored, double-click, transition fired twice).
 *
 * Missing references fail safe. A [CampaignEffect.GrantUnit] naming an eqid that is absent from
 * the active equipment file is skipped with a warning and — importantly — is still marked
 * applied, so a broken reference cannot re-warn on every reload.
 */
internal object CampaignEffectApplier {
    /**
     * Applies [effects] to [player], skipping any already applied.
     *
     * [state] is both the idempotency ledger and the target for flag effects. Returns the ids
     * actually applied on this call, which is what the tests assert against.
     */
    fun apply(
        effects: List<CampaignEffect>,
        player: Player?,
        state: CampaignNarrativeState,
    ): List<String> {
        val applied = mutableListOf<String>()
        effects.forEach { effect ->
            if (!state.effects.markApplied(effect.id)) return@forEach
            applyOne(effect, player, state)
            applied += effect.id
        }
        return applied
    }

    @Suppress("TooGenericExceptionCaught")
    private fun applyOne(
        effect: CampaignEffect,
        player: Player?,
        state: CampaignNarrativeState,
    ) {
        try {
            when (effect) {
                is CampaignEffect.SetFlag -> state.setFlag(effect.flag)
                is CampaignEffect.ClearFlag -> state.clearFlag(effect.flag)
                is CampaignEffect.Prestige -> applyPrestige(effect, player)
                is CampaignEffect.GrantUnit -> applyGrantUnit(effect, player)
                is CampaignEffect.GrantExperience -> applyExperience(effect, player)
                is CampaignEffect.Resupply -> applyResupply(effect, player)
                is CampaignEffect.Route -> state.route.set(effect.scenarioIndex)
                // Setup effects consumed by the scenario loader rather than the player object.
                is CampaignEffect.ShiftReinforcements,
                is CampaignEffect.UnlockEquipment,
                is CampaignEffect.DeploymentSlots,
                    -> Unit
            }
        } catch (e: Throwable) {
            console.warn("[OSADA] campaign effect '${effect.id}' failed to apply; campaign continues", e)
        }
    }

    /** Prestige never goes negative: a penalty may empty the treasury, not create debt. */
    private fun applyPrestige(
        effect: CampaignEffect.Prestige,
        player: Player?,
    ) {
        val target = player ?: return
        target.awardPrestige(effect.amount)
    }

    private fun applyGrantUnit(
        effect: CampaignEffect.GrantUnit,
        player: Player?,
    ) {
        val known = Equipment.getEquipment(effect.eqid) != null
        if (!known) {
            console.warn("[OSADA] grantUnit '${effect.id}' references eqid ${effect.eqid} absent from the efile")
        }
        val acquired = known && player != null && player.acquireUnit(effect.eqid, 0)
        if (acquired) {
            player.getCoreUnitList().lastOrNull()?.let { unit ->
                unit.experience = effect.experience.coerceIn(0, UNIT_MAX_EXPERIENCE)
                unit.strength = effect.strength.coerceIn(1, EffectLimits.MAX_STRENGTH)
            }
        }
    }

    /** Experience is clamped to the engine cap; a bonus can top a unit up but never exceed it. */
    private fun applyExperience(
        effect: CampaignEffect.GrantExperience,
        player: Player?,
    ) {
        val target = player ?: return
        target
            .getCoreUnitList()
            .filter { unit ->
                effect.unitClass == null || Equipment.getEquipment(unit.eqid)?.uclass == effect.unitClass
            }.forEach { unit ->
                unit.experience = (unit.experience + effect.amount).coerceIn(0, UNIT_MAX_EXPERIENCE)
            }
    }

    private fun applyResupply(
        effect: CampaignEffect.Resupply,
        player: Player?,
    ) {
        val target = player ?: return
        target.getCoreUnitList().forEach { unit ->
            val data = unit.unitData(useReal = true)
            effect.strength?.let { unit.strength = unit.strength.coerceAtLeast(it) }
            if (effect.refuel) unit.fuel = data.fuel
            if (effect.rearm) unit.ammo = data.ammo
        }
    }
}
