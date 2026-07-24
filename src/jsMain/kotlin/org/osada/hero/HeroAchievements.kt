package org.osada.hero

import org.osada.TerrainType
import org.osada.UnitClass

/**
 * Classifies one combat [RecognitionService.Contribution] into the [AchievementType]s it argues
 * for (§4.1, §19). Shared by [RecognitionService] (which only needs the single best story for the
 * new-leader event) and [HeroProgressionProcessor] (which needs every category the action touched).
 */
internal object HeroAchievements {
    fun derive(contribution: RecognitionService.Contribution): List<AchievementType> {
        val notable = contribution.destroyedEnemy || contribution.survivedCriticalDamage
        if (!notable) return emptyList()

        val types = mutableListOf<AchievementType>()
        if (contribution.destroyedEnemy) {
            types +=
                if (contribution.enemyStronger) {
                    AchievementType.DESTROYED_STRONGER_ENEMY
                } else {
                    AchievementType.DESTROYED_ENEMY
                }
            if (contribution.enemyUnitClass == UnitClass.TANK.value) types += AchievementType.ARMORED_KILL
        }
        if (contribution.survivedCriticalDamage) {
            types +=
                if (contribution.role == RecognitionService.Contribution.Role.DEFENDER) {
                    AchievementType.HELD_UNDER_ATTACK
                } else {
                    AchievementType.SURVIVED_CRITICAL_DAMAGE
                }
        }
        terrainAchievement(contribution.terrain)?.let { types += it }
        return types
    }

    private fun terrainAchievement(terrain: Int?): AchievementType? =
        when (terrain) {
            TerrainType.RIVER.value, TerrainType.STREAM.value -> AchievementType.RIVER_ASSAULT
            TerrainType.CITY.value -> AchievementType.URBAN_ASSAULT
            TerrainType.FOREST.value -> AchievementType.FOREST_ASSAULT
            TerrainType.MOUNTAIN.value -> AchievementType.MOUNTAIN_ASSAULT
            else -> null
        }
}
