package org.osada.hero

/** One achievement's contribution to a category's evidence total (§21). */
data class EvidenceRule(
    val categoryId: EvidenceCategory,
    val baseAmount: Int,
)

/**
 * The data-driven table of [EvidenceRule]s (§21) — which [AchievementType]s feed which
 * [EvidenceCategory], and by how much.
 *
 * Phase 3 wired only the categories a combat-resolution-time achievement could reach —
 * OFFENSIVE_OPERATIONS, DEFENSIVE_OPERATIONS, ARMORED_COMBAT, RIVER/URBAN/FOREST/MOUNTAIN
 * operations. **§7.43 adds the three the promotion catalogue actually needed**: MOBILE_WARFARE,
 * RECONNAISSANCE and GROUND_ATTACK. Those three were the reason §7.42's new catalogue entries had to
 * be zero-evidence, and being zero-evidence is what let them displace the intended §8.5.4
 * class-general fallbacks — an unfed category is not a neutral omission once something depends on it.
 *
 * The remaining ten entries (FIRE_SUPPORT, AIR_DEFENSE, ENCIRCLEMENT, the naval branches…) are still
 * unfed and still safe to leave so, because nothing is gated on them — see [EvidenceCategory].
 *
 * One achievement may feed more than one rule (a stronger-enemy tank kill is both
 * `OFFENSIVE_OPERATIONS` and `ARMORED_COMBAT`), which is exactly §4.2's point: a specialist should
 * not depend on one narrow trigger when a broader one already implies it.
 */
internal object EvidenceRules {
    private val table: Map<AchievementType, List<EvidenceRule>> =
        mapOf(
            AchievementType.DESTROYED_ENEMY to
                listOf(EvidenceRule(EvidenceCategory.OFFENSIVE_OPERATIONS, 15)),
            AchievementType.DESTROYED_STRONGER_ENEMY to
                listOf(EvidenceRule(EvidenceCategory.OFFENSIVE_OPERATIONS, 25)),
            AchievementType.ARMORED_KILL to
                listOf(EvidenceRule(EvidenceCategory.ARMORED_COMBAT, 20)),
            AchievementType.HELD_UNDER_ATTACK to
                listOf(EvidenceRule(EvidenceCategory.DEFENSIVE_OPERATIONS, 25)),
            AchievementType.SURVIVED_CRITICAL_DAMAGE to
                listOf(EvidenceRule(EvidenceCategory.DEFENSIVE_OPERATIONS, 15)),
            AchievementType.RIVER_ASSAULT to
                listOf(EvidenceRule(EvidenceCategory.RIVER_OPERATIONS, 20)),
            AchievementType.URBAN_ASSAULT to
                listOf(EvidenceRule(EvidenceCategory.URBAN_COMBAT, 20)),
            AchievementType.FOREST_ASSAULT to
                listOf(EvidenceRule(EvidenceCategory.FOREST_OPERATIONS, 15)),
            AchievementType.MOUNTAIN_ASSAULT to
                listOf(EvidenceRule(EvidenceCategory.MOUNTAIN_OPERATIONS, 15)),
            AchievementType.GROUND_ATTACK_KILL to
                listOf(EvidenceRule(EvidenceCategory.GROUND_ATTACK, 20)),
            AchievementType.MANEUVER_KILL to
                listOf(EvidenceRule(EvidenceCategory.MOBILE_WARFARE, 15)),
            // Deliberately the smallest amount in the table: one contact is the least notable thing
            // here, and `sharp_eyes` is gated at 30 so it takes four of them plus a fifth to spare.
            AchievementType.RECON_CONTACT to
                listOf(EvidenceRule(EvidenceCategory.RECONNAISSANCE, 8)),
        )

    fun forType(type: AchievementType): List<EvidenceRule> = table[type].orEmpty()

    /**
     * [current] evidence with every rule for [achievements] applied — the one place a hero's
     * evidence map grows, shared by [HeroProgressionProcessor] (combat) and [HeroCampaign]
     * ([AchievementType.RECON_CONTACT], which does not come from combat at all).
     */
    fun accrue(
        current: Map<String, Int>,
        achievements: List<AchievementType>,
    ): Map<String, Int> {
        val next = current.toMutableMap()
        achievements.forEach { type ->
            forType(type).forEach { rule ->
                next[rule.categoryId.name] = (next[rule.categoryId.name] ?: 0) + rule.baseAmount
            }
        }
        return next
    }
}
