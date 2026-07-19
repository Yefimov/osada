package org.osada.rules

/**
 * Backwards-compatibility facade over the focused rule objects.
 *
 * The combat/movement/supply/cost/geometry logic used to live here as one ~1000-line
 * god-object. It has been split (Single Responsibility) into [CombatResolver],
 * [MovementRules], [SupplyRules], [CostCalculator], [UnitPredicates], [HexGeometry] and
 * [Dice]. This object now only forwards calls, so existing Kotlin call sites and the
 * `window.GameRules` global keep working while the real logic lives in cohesive units.
 *
 * The forwarders themselves live as extension functions in the sibling
 * `GameRulesCombat.kt`, `GameRulesCombatEligibility.kt`, `GameRulesMovement.kt`,
 * `GameRulesSupply.kt`, `GameRulesPredicates.kt`, `GameRulesFuelAmmo.kt`,
 * `GameRulesCost.kt` and `GameRulesGeometry.kt` files (same package), grouped by
 * concern so no single file/class exceeds the function-count limit. Call sites are
 * unaffected: `GameRules.foo(...)` resolves the same whether `foo` is a member or an
 * extension function in the same package.
 *
 * Prefer calling the specific rule object directly in new code; this facade exists to
 * keep the migration incremental and reviewable.
 */
object GameRules {
    // --- Dice ---

    fun rollDice(
        min: Int,
        max: Int,
    ): Int = Dice.roll(min, max)
}
