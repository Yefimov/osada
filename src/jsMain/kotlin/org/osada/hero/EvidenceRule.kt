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
 * Phase 3 wires only the categories a combat-resolution-time achievement can actually reach —
 * OFFENSIVE_OPERATIONS, DEFENSIVE_OPERATIONS, ARMORED_COMBAT, RIVER/URBAN/FOREST/MOUNTAIN
 * operations. The other thirteen [EvidenceCategory] entries exist for a later phase (recon
 * contacts, encirclement, air/naval branches…) that can reach signals this phase cannot — see
 * [EvidenceCategory] for why that is a safe, non-breaking omission.
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
        )

    fun forType(type: AchievementType): List<EvidenceRule> = table[type].orEmpty()
}
