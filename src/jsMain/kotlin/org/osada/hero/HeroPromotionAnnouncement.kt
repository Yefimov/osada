package org.osada.hero

import org.osada.model.Leaders

/**
 * The player-facing payload of a promotion event (§8.5, §14.1's sibling for progression rather
 * than emergence). Built in the hero layer and drained by the UI, same pattern as
 * [HeroEmergenceAnnouncement] — the presenter only shows data this object already carries and
 * calls back into [HeroCampaign.applyPromotionChoice] with the [Choice.traitId] the player picked.
 */
data class HeroPromotionAnnouncement(
    val heroId: HeroId,
    val formationName: String,
    val heroName: String,
    val newRankId: String,
    val choices: List<Choice>,
) {
    data class Choice(
        val traitId: String,
        val title: String,
        val effectDescription: String,
        val justification: String,
    )

    companion object {
        internal fun from(
            promotion: HeroPromotionService.Promotion,
            formation: CoreFormation,
            definition: HeroDefinition,
            hero: HeroState,
        ): HeroPromotionAnnouncement =
            HeroPromotionAnnouncement(
                heroId = promotion.heroId,
                formationName = formation.displayName,
                heroName = definition.displayName,
                newRankId = promotion.newRankId,
                choices = promotion.choices.map { choice(it, hero) },
            )

        private fun choice(
            def: HeroTraitDefinition,
            hero: HeroState,
        ): Choice {
            val evidence = hero.specializationEvidence[def.categoryId.name] ?: 0
            val justification =
                if (def.requiredEvidence.isEmpty()) {
                    "Available to any commander at this rank."
                } else {
                    "Available because of $evidence ${def.categoryId.title} evidence."
                }
            return Choice(
                traitId = def.id,
                title = def.title,
                effectDescription = Leaders.description[def.legacyTrait]?.second.orEmpty(),
                justification = justification,
            )
        }
    }
}
