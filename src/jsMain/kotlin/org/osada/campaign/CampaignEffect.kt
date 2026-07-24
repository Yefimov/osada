package org.osada.campaign

/** Bounds that keep authored consequences from destabilising the campaign economy. */
internal object EffectLimits {
    /** Per-effect prestige swing. Scenario awards are 700-2300, so one choice must stay well under. */
    const val MAX_PRESTIGE_DELTA = 400

    /** Per-effect experience swing, against `UNIT_MAX_EXPERIENCE` = 500 (~1 star = 100). */
    const val MAX_EXPERIENCE_DELTA = 150

    /** Per-effect reinforcement-turn shift, in turns, either direction. */
    const val MAX_TURN_SHIFT = 3

    /** Per-effect deployment-slot change. */
    const val MAX_SLOT_DELTA = 4

    const val MIN_STRENGTH = 0
    const val MAX_STRENGTH = 10
}

/**
 * A declarative, typed campaign consequence.
 *
 * Every effect carries a stable [id] — the idempotency key. An effect is applied at most once per
 * campaign run no matter how many times its source is re-read (briefing reopened, dialogue
 * reviewed, save restored, transition callback fired twice, player double-clicked).
 *
 * There is deliberately no "run this script" variant: campaign JSON never contains executable
 * code, and nothing here goes near `eval`.
 */
internal sealed class CampaignEffect {
    abstract val id: String

    /** Narrative marker read back by dialogue conditions. Applied immediately on commit. */
    data class SetFlag(
        override val id: String,
        val flag: String,
    ) : CampaignEffect()

    data class ClearFlag(
        override val id: String,
        val flag: String,
    ) : CampaignEffect()

    /** Campaign prestige, clamped to [EffectLimits.MAX_PRESTIGE_DELTA]. */
    data class Prestige(
        override val id: String,
        val amount: Int,
    ) : CampaignEffect()

    /** Adds a unit to the core roster. Silently skipped when [eqid] is absent from the active efile. */
    data class GrantUnit(
        override val id: String,
        val eqid: Int,
        val experience: Int,
        val strength: Int,
    ) : CampaignEffect()

    /** Experience for core units, optionally narrowed to one unit class. */
    data class GrantExperience(
        override val id: String,
        val amount: Int,
        val unitClass: Int?,
    ) : CampaignEffect()

    /** Restores strength / fuel / ammo on the core going into the next battle. */
    data class Resupply(
        override val id: String,
        val strength: Int?,
        val refuel: Boolean,
        val rearm: Boolean,
    ) : CampaignEffect()

    /** Shifts scripted reinforcement arrival. Negative = earlier. */
    data class ShiftReinforcements(
        override val id: String,
        val side: Int,
        val turns: Int,
    ) : CampaignEffect()

    /** Makes an equipment id purchasable that country/date filtering would otherwise hide. */
    data class UnlockEquipment(
        override val id: String,
        val eqid: Int,
    ) : CampaignEffect()

    /** Changes how many units may be deployed at scenario start. */
    data class DeploymentSlots(
        override val id: String,
        val delta: Int,
    ) : CampaignEffect()

    /**
     * Re-routes the campaign to an existing scenario index. Engine-supported but NOT authored by
     * either shipping campaign — both are strictly linear (all wins -> i+1, lose -> campaign end),
     * so there is no alternate or recovery scenario to target. See the design doc, section 7.
     */
    data class Route(
        override val id: String,
        val scenarioIndex: Int,
    ) : CampaignEffect()
}

/**
 * Parses effect arrays out of campaign JSON. Never throws and never blocks a campaign: an
 * unknown `type`, a missing `id`, or a malformed field drops that ONE effect with a console
 * warning and leaves the rest of the list intact.
 */
internal object CampaignEffectParser {
    fun parseList(value: dynamic): List<CampaignEffect> {
        val seen = mutableSetOf<String>()
        return BriefingDynamic.mapArray(value) { item ->
            val effect = parseOne(item)
            when {
                effect == null -> null
                seen.add(effect.id) -> effect
                else -> {
                    console.warn("[OSADA] duplicate campaign effect id '${effect.id}' ignored")
                    null
                }
            }
        }
    }

    /** Parses a single effect object. Used when re-reading a serialized pending effect. */
    fun parseSingle(item: dynamic): CampaignEffect? = parseOne(item)

    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")
    private fun parseOne(item: dynamic): CampaignEffect? =
        try {
            val id = BriefingDynamic.str(item?.id)?.trim()?.takeIf { it.isNotBlank() }
            val type = BriefingDynamic.str(item?.type)?.trim()
            when {
                id == null -> warnDrop("effect without a stable id", item)
                type == null -> warnDrop("effect '$id' without a type", item)
                else -> build(id, type, item)
            }
        } catch (e: Throwable) {
            console.warn("[OSADA] campaign effect parse failed, effect dropped", e)
            null
        }

    private fun build(
        id: String,
        type: String,
        item: dynamic,
    ): CampaignEffect? =
        (buildNarrativeOrResource(id, type, item) ?: buildUnitOrSetup(id, type, item))
            ?: warnDrop("effect '$id' of type '$type' is unknown or missing a required field", item)

    /** Flags and campaign-resource effects. */
    private fun buildNarrativeOrResource(
        id: String,
        type: String,
        item: dynamic,
    ): CampaignEffect? =
        when (type) {
            "setFlag" -> BriefingDynamic.str(item.flag)?.let { CampaignEffect.SetFlag(id, it) }
            "clearFlag" -> BriefingDynamic.str(item.flag)?.let { CampaignEffect.ClearFlag(id, it) }
            "prestige" -> CampaignEffect.Prestige(id, clampInt(item.amount, EffectLimits.MAX_PRESTIGE_DELTA))
            "unlockEquipment" -> BriefingDynamic.int(item.eqid)?.let { CampaignEffect.UnlockEquipment(id, it) }
            "route" -> BriefingDynamic.int(item.scenarioIndex)?.let { CampaignEffect.Route(id, it) }
            else -> null
        }

    /** Unit-roster and next-battle setup effects. */
    private fun buildUnitOrSetup(
        id: String,
        type: String,
        item: dynamic,
    ): CampaignEffect? =
        when (type) {
            "grantUnit" -> buildGrantUnit(id, item)
            "experience" ->
                CampaignEffect.GrantExperience(
                    id,
                    clampInt(item.amount, EffectLimits.MAX_EXPERIENCE_DELTA),
                    BriefingDynamic.int(item.unitClass),
                )
            "resupply" ->
                CampaignEffect.Resupply(
                    id,
                    BriefingDynamic.int(item.strength)?.coerceIn(EffectLimits.MIN_STRENGTH, EffectLimits.MAX_STRENGTH),
                    BriefingDynamic.bool(item.refuel) ?: true,
                    BriefingDynamic.bool(item.rearm) ?: true,
                )
            "shiftReinforcements" ->
                CampaignEffect.ShiftReinforcements(
                    id,
                    BriefingDynamic.int(item.side) ?: 0,
                    clampInt(item.turns, EffectLimits.MAX_TURN_SHIFT),
                )
            "deploymentSlots" -> CampaignEffect.DeploymentSlots(id, clampInt(item.delta, EffectLimits.MAX_SLOT_DELTA))
            else -> null
        }

    private fun buildGrantUnit(
        id: String,
        item: dynamic,
    ): CampaignEffect? =
        BriefingDynamic.int(item.eqid)?.let { eqid ->
            CampaignEffect.GrantUnit(
                id = id,
                eqid = eqid,
                experience =
                    (BriefingDynamic.int(item.experience) ?: 0)
                        .coerceIn(0, EffectLimits.MAX_EXPERIENCE_DELTA),
                strength =
                    (BriefingDynamic.int(item.strength) ?: EffectLimits.MAX_STRENGTH)
                        .coerceIn(1, EffectLimits.MAX_STRENGTH),
            )
        }

    private fun clampInt(
        value: dynamic,
        limit: Int,
    ): Int = (BriefingDynamic.int(value) ?: 0).coerceIn(-limit, limit)

    private fun warnDrop(
        reason: String,
        item: dynamic,
    ): CampaignEffect? {
        console.warn("[OSADA] campaign effect dropped: $reason", item)
        return null
    }
}
