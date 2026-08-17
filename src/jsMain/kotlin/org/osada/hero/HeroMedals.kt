package org.osada.hero

import org.osada.hero.HeroMedals.award

/**
 * The (currently small) medal catalogue (§8.1, §10) and the rule that awards from it.
 *
 * Phase 3 wires exactly one: a hero is decorated the first time they destroy a stronger enemy —
 * the same action §4.1 uses as its own headline example of a justified improvement. Nothing stops
 * a later phase from adding more entries; [award] already re-checks "does the hero have it yet"
 * per medal id, so a richer catalogue does not need this function to change shape.
 */
internal object HeroMedals {
    const val VALOR_MEDAL_ID = "valor_medal"

    private val titles: Map<String, String> = mapOf(VALOR_MEDAL_ID to "Medal of Valor")

    fun title(medalId: String): String? = titles[medalId]

    /** Awards medals earned by [achievements], if [hero] does not already hold them. */
    fun award(
        hero: HeroState,
        achievements: List<AchievementType>,
        scenarioId: String,
    ): HeroState {
        val earnsValor =
            AchievementType.DESTROYED_STRONGER_ENEMY in achievements &&
                hero.medals.none { it.medalId == VALOR_MEDAL_ID }
        return if (earnsValor) hero.copy(medals = hero.medals + HeroMedal(VALOR_MEDAL_ID, scenarioId)) else hero
    }
}
