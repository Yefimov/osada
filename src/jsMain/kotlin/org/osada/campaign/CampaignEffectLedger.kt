package org.osada.campaign

/**
 * The idempotency ledger: which effect ids have already been applied in this campaign run, and
 * which are queued for a scenario that has not loaded yet.
 *
 * Split from [CampaignNarrativeState] so each collaborator stays within the project's
 * functions-per-class budget, following the same decomposition the codebase applies to `GameMap`
 * and `GameRules`.
 *
 * This is the ONLY place "has this already happened?" is answered for effects, which is what
 * makes the once-only guarantee hold across every path that can re-reach an effect.
 */
internal class CampaignEffectLedger {
    private val appliedEffects = mutableSetOf<String>()
    private val pendingEffects = mutableListOf<PendingEffect>()

    val applied: Set<String> get() = appliedEffects.toSet()

    val pending: List<PendingEffect> get() = pendingEffects.toList()

    val isEmpty: Boolean get() = appliedEffects.isEmpty() && pendingEffects.isEmpty()

    /** True when [effectId] had not been applied before; marks it applied as a side effect. */
    fun markApplied(effectId: String): Boolean = appliedEffects.add(effectId)

    fun isApplied(effectId: String): Boolean = effectId in appliedEffects

    /** Queues a next-scenario effect. Re-queuing an already-queued or already-applied id is a no-op. */
    fun queue(effect: PendingEffect) {
        val known = isApplied(effect.effect.id) || pendingEffects.any { it.effect.id == effect.effect.id }
        if (!known) pendingEffects += effect
    }

    /**
     * Removes and returns the effects targeted at [scenarioFile]. Effects queued for a DIFFERENT
     * scenario stay queued — a pending effect must never leak into the wrong battle.
     */
    fun takeFor(scenarioFile: String): List<CampaignEffect> {
        val (mine, others) = pendingEffects.partition { it.targetScenario == scenarioFile }
        pendingEffects.clear()
        pendingEffects += others
        return mine.map { it.effect }
    }

    fun restore(
        appliedIds: Set<String>,
        queued: List<PendingEffect>,
    ) {
        appliedEffects.clear()
        appliedEffects += appliedIds
        pendingEffects.clear()
        pendingEffects += queued
    }
}

/**
 * Optional objectives resolved from real end-of-scenario game state, per scenario.
 *
 * Ids are stored short (`airfield_held_at_end`) and read back qualified
 * (`n_kiel.xml.airfield_held_at_end`) so two scenarios may reuse a name without colliding and
 * dialogue conditions can name the scenario they mean.
 */
internal class ScenarioActionLog {
    private val actions = mutableMapOf<String, MutableSet<String>>()

    val isEmpty: Boolean get() = actions.isEmpty()

    fun record(
        scenarioFile: String,
        actionId: String,
    ) {
        actions.getOrPut(scenarioFile) { mutableSetOf() } += actionId
    }

    fun forScenario(scenarioFile: String): Set<String> = actions[scenarioFile]?.toSet() ?: emptySet()

    /** Every recorded action in qualified `scenario.action` form. */
    fun qualified(): Set<String> =
        actions.entries.flatMapTo(mutableSetOf()) { (scenario, ids) -> ids.map { "$scenario.$it" } }

    fun has(qualifiedId: String): Boolean = qualifiedId in qualified()

    fun scenarios(): Set<String> = actions.keys.toSet()

    fun restore(source: Map<String, Set<String>>) {
        actions.clear()
        source.forEach { (scenario, ids) -> actions[scenario] = ids.toMutableSet() }
    }
}
