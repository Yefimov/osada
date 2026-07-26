package org.osada.campaign

import kotlin.js.json

/**
 * Serializes [CampaignNarrativeState] into the save's `campaign.narrative` block and back.
 *
 * Manual key-by-key emission (never `JSON.stringify` of a Kotlin object) because Kotlin/JS IR
 * mangles property names — see AGENTS.md, "Porting gotchas". The keys written here are the
 * save-file contract.
 *
 * Backward compatibility is by absence: a save written before this system has no `narrative`
 * key, [deserialize] gets null, and returns an empty state. Nothing else in the campaign block
 * changed, so old saves keep loading through the untouched path.
 *
 * Forward compatibility is by tolerance: unknown keys are ignored, and any individual field that
 * is corrupt degrades to its empty value with a warning rather than failing the whole load. A
 * player with a damaged save loses narrative callbacks, not their campaign.
 */
internal object CampaignNarrativeSerializer {
    fun serialize(state: CampaignNarrativeState): dynamic {
        val obj: dynamic =
            json(
                Pair("version", NARRATIVE_VERSION),
                Pair("outcomes", state.scenarioOutcomes.map(::serializeOutcome).toTypedArray()),
                Pair("choices", serializeStringMap(state.selectedChoices)),
                Pair("flags", state.flags.toTypedArray()),
                Pair("actions", serializeActions(state)),
                Pair("applied", state.effects.applied.toTypedArray()),
                Pair(
                    "pending",
                    state.effects.pending
                        .map(::serializePending)
                        .toTypedArray(),
                ),
            )
        state.route.peek()?.let { obj.route = it }
        return obj
    }

    @Suppress("TooGenericExceptionCaught")
    fun deserialize(value: dynamic): CampaignNarrativeState =
        if (!BriefingDynamic.isObject(value)) {
            CampaignNarrativeState()
        } else {
            try {
                CampaignNarrativeState().apply {
                    restoreFrom(
                        outcomeRecords = readOutcomes(value.outcomes),
                        choiceMap = readStringMap(value.choices),
                        flagValues = BriefingDynamic.strList(value.flags).toSet(),
                        actionMap = readActions(value.actions),
                        applied = BriefingDynamic.strList(value.applied).toSet(),
                        queued = readPending(value.pending),
                        route = BriefingDynamic.int(value.route),
                    )
                }
            } catch (e: Throwable) {
                console.warn("[OSADA] campaign narrative state corrupt, starting from empty narrative", e)
                CampaignNarrativeState()
            }
        }

    // --------------------------------------------------------------- outcomes

    private fun serializeOutcome(record: ScenarioOutcomeRecord): dynamic =
        json(
            Pair("scenario", record.scenarioFile),
            Pair("outcome", record.outcome),
            Pair("next", record.nextScenario ?: ""),
        )

    /** Only the four engine outcome grades are accepted; a tampered grade drops the record. */
    private fun readOutcomes(value: dynamic): List<ScenarioOutcomeRecord> =
        BriefingDynamic.mapArray(value) { item ->
            val scenario = BriefingDynamic.str(item?.scenario)?.takeIf { it.isNotBlank() }
            val outcome = BriefingDynamic.str(item?.outcome)?.takeIf { it in VALID_OUTCOMES }
            if (scenario == null || outcome == null) {
                null
            } else {
                ScenarioOutcomeRecord(
                    scenarioFile = scenario,
                    outcome = outcome,
                    nextScenario = BriefingDynamic.str(item?.next)?.takeIf { it.isNotBlank() },
                )
            }
        }

    // ---------------------------------------------------------------- pending

    private fun serializePending(pending: PendingEffect): dynamic =
        json(
            Pair("target", pending.targetScenario),
            Pair("effect", CampaignEffectSerializer.serialize(pending.effect)),
        )

    private fun readPending(value: dynamic): List<PendingEffect> =
        BriefingDynamic.mapArray(value) { item ->
            val target = BriefingDynamic.str(item?.target)?.takeIf { it.isNotBlank() }
            val effect = CampaignEffectParser.parseSingle(item?.effect)
            if (target == null || effect == null) null else PendingEffect(target, effect)
        }

    // ----------------------------------------------------------------- shared

    private fun serializeStringMap(map: Map<String, String>): dynamic {
        val obj = js("{}")
        map.forEach { (key, value) -> obj[key] = value }
        return obj
    }

    private fun readStringMap(value: dynamic): Map<String, String> {
        if (!BriefingDynamic.isObject(value)) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (key in js("Object.keys")(value).unsafeCast<Array<String>>()) {
            BriefingDynamic.str(value[key])?.let { out[key] = it }
        }
        return out
    }

    /** Keyed by scenario. Driven by the action log itself, not by the outcome list, so an action
     *  recorded for a scenario is never dropped because of how outcomes were ordered. */
    private fun serializeActions(state: CampaignNarrativeState): dynamic {
        val obj = js("{}")
        state.actions.scenarios().forEach { scenario ->
            val ids = state.actions.forScenario(scenario)
            if (ids.isNotEmpty()) obj[scenario] = ids.toTypedArray()
        }
        return obj
    }

    private fun readActions(value: dynamic): Map<String, Set<String>> {
        if (!BriefingDynamic.isObject(value)) return emptyMap()
        val out = mutableMapOf<String, Set<String>>()
        for (key in js("Object.keys")(value).unsafeCast<Array<String>>()) {
            val ids = BriefingDynamic.strList(value[key]).toSet()
            if (ids.isNotEmpty()) out[key] = ids
        }
        return out
    }

    private const val NARRATIVE_VERSION = 1

    private val VALID_OUTCOMES = setOf("briliant", "victory", "tactical", "lose")
}

/**
 * Round-trips a [CampaignEffect] so queued next-scenario effects survive a save.
 *
 * The emitted shape is exactly the authored campaign-JSON shape, so a serialized effect is
 * re-read by the ordinary [CampaignEffectParser] — one parser, one contract, no second format
 * to keep in sync.
 */
internal object CampaignEffectSerializer {
    fun serialize(effect: CampaignEffect): dynamic {
        val obj = base(effect)
        when (effect) {
            is CampaignEffect.SetFlag -> obj.flag = effect.flag
            is CampaignEffect.ClearFlag -> obj.flag = effect.flag
            is CampaignEffect.Prestige -> obj.amount = effect.amount
            is CampaignEffect.GrantUnit -> {
                obj.eqid = effect.eqid
                obj.experience = effect.experience
                obj.strength = effect.strength
            }
            is CampaignEffect.GrantExperience -> {
                obj.amount = effect.amount
                effect.unitClass?.let { obj.unitClass = it }
            }
            is CampaignEffect.Resupply -> {
                effect.strength?.let { obj.strength = it }
                obj.refuel = effect.refuel
                obj.rearm = effect.rearm
            }
            is CampaignEffect.ShiftReinforcements -> {
                obj.side = effect.side
                obj.turns = effect.turns
            }
            is CampaignEffect.UnlockEquipment -> obj.eqid = effect.eqid
            is CampaignEffect.DeploymentSlots -> obj.delta = effect.delta
            is CampaignEffect.Route -> obj.scenarioIndex = effect.scenarioIndex
        }
        return obj
    }

    private fun base(effect: CampaignEffect): dynamic {
        val obj = js("{}")
        obj.id = effect.id
        obj.type = typeOf(effect)
        return obj
    }

    private fun typeOf(effect: CampaignEffect): String =
        when (effect) {
            is CampaignEffect.SetFlag -> "setFlag"
            is CampaignEffect.ClearFlag -> "clearFlag"
            is CampaignEffect.Prestige -> "prestige"
            is CampaignEffect.GrantUnit -> "grantUnit"
            is CampaignEffect.GrantExperience -> "experience"
            is CampaignEffect.Resupply -> "resupply"
            is CampaignEffect.ShiftReinforcements -> "shiftReinforcements"
            is CampaignEffect.UnlockEquipment -> "unlockEquipment"
            is CampaignEffect.DeploymentSlots -> "deploymentSlots"
            is CampaignEffect.Route -> "route"
        }
}
