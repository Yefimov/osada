package org.osada.hero

import org.osada.hero.HeroMedals.award
import org.osada.i18n.I18n

/**
 * Hero decorations (§8.1, §10). A medal is a one-time career marker, not another combat modifier:
 * the actions that justify it already award XP, evidence and promotion choices. Most decorations
 * therefore use cumulative evidence thresholds rather than showering a hero with several medals
 * after one unusually rich combat.
 */
internal object HeroMedals {
    const val VALOR_MEDAL_ID = "valor_medal"
    const val STEADFAST_MEDAL_ID = "steadfast_medal"
    const val ARMOR_HUNTER_MEDAL_ID = "armor_hunter_medal"
    const val RIVER_OPERATIONS_MEDAL_ID = "river_operations_medal"
    const val URBAN_COMBAT_MEDAL_ID = "urban_combat_medal"
    const val FOREST_SERVICE_MEDAL_ID = "forest_service_medal"
    const val MOUNTAIN_SERVICE_MEDAL_ID = "mountain_service_medal"
    const val MANEUVER_MEDAL_ID = "maneuver_medal"
    const val RECONNAISSANCE_MEDAL_ID = "reconnaissance_medal"
    const val GROUND_ATTACK_MEDAL_ID = "ground_attack_medal"

    private val titleKeys =
        mapOf(
            VALOR_MEDAL_ID to "hero.medal.valor_medal.title",
            STEADFAST_MEDAL_ID to "hero.medal.steadfast_medal.title",
            ARMOR_HUNTER_MEDAL_ID to "hero.medal.armor_hunter_medal.title",
            RIVER_OPERATIONS_MEDAL_ID to "hero.medal.river_operations_medal.title",
            URBAN_COMBAT_MEDAL_ID to "hero.medal.urban_combat_medal.title",
            FOREST_SERVICE_MEDAL_ID to "hero.medal.forest_service_medal.title",
            MOUNTAIN_SERVICE_MEDAL_ID to "hero.medal.mountain_service_medal.title",
            MANEUVER_MEDAL_ID to "hero.medal.maneuver_medal.title",
            RECONNAISSANCE_MEDAL_ID to "hero.medal.reconnaissance_medal.title",
            GROUND_ATTACK_MEDAL_ID to "hero.medal.ground_attack_medal.title",
        )

    fun title(medalId: String): String? = titleKeys[medalId]?.let { I18n.t(it) }

    /** Awards medals earned by [achievements], if [hero] does not already hold them. */
    fun award(
        hero: HeroState,
        achievements: List<AchievementType>,
        scenarioId: String,
    ): HeroState {
        val earned =
            buildList {
                if (AchievementType.DESTROYED_STRONGER_ENEMY in achievements) add(VALOR_MEDAL_ID)
                threshold(hero, EvidenceCategory.DEFENSIVE_OPERATIONS, 50, STEADFAST_MEDAL_ID)?.let(::add)
                threshold(hero, EvidenceCategory.ARMORED_COMBAT, 60, ARMOR_HUNTER_MEDAL_ID)?.let(::add)
                threshold(hero, EvidenceCategory.RIVER_OPERATIONS, 40, RIVER_OPERATIONS_MEDAL_ID)?.let(::add)
                threshold(hero, EvidenceCategory.URBAN_COMBAT, 40, URBAN_COMBAT_MEDAL_ID)?.let(::add)
                threshold(hero, EvidenceCategory.FOREST_OPERATIONS, 45, FOREST_SERVICE_MEDAL_ID)?.let(::add)
                threshold(hero, EvidenceCategory.MOUNTAIN_OPERATIONS, 45, MOUNTAIN_SERVICE_MEDAL_ID)?.let(::add)
                threshold(hero, EvidenceCategory.MOBILE_WARFARE, 45, MANEUVER_MEDAL_ID)?.let(::add)
                threshold(hero, EvidenceCategory.RECONNAISSANCE, 40, RECONNAISSANCE_MEDAL_ID)?.let(::add)
                threshold(hero, EvidenceCategory.GROUND_ATTACK, 40, GROUND_ATTACK_MEDAL_ID)?.let(::add)
            }.filterNot { id -> hero.medals.any { it.medalId == id } }
        return if (earned.isEmpty()) {
            hero
        } else {
            hero.copy(medals = hero.medals + earned.map { HeroMedal(it, scenarioId) })
        }
    }

    private fun threshold(
        hero: HeroState,
        category: EvidenceCategory,
        amount: Int,
        medalId: String,
    ): String? = medalId.takeIf { (hero.specializationEvidence[category.name] ?: 0) >= amount }
}
