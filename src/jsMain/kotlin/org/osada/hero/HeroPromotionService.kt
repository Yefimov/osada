package org.osada.hero

/**
 * Decides whether a milestone fires and, if so, what two choices to offer (§8.5).
 *
 * Pure and evidence-only: no randomness is involved in which two traits are offered, so nothing
 * here needs seeding — the same hero state always produces the same pair, which is a stronger form
 * of §29.17's determinism than a seeded roll would be (a reload cannot even reorder the choices).
 */
internal object HeroPromotionService {
    data class Promotion(
        val heroId: HeroId,
        val newRankId: String,
        val choices: List<HeroTraitDefinition>,
    )

    fun evaluate(
        hero: HeroState,
        definition: HeroDefinition,
        formation: CoreFormation,
        balance: HeroBalance = HeroBalance.DEFAULT,
    ): Promotion? {
        val thresholds = balance.promotionThresholds
        val milestoneReached =
            hero.promotionsAwarded < thresholds.size && hero.experience >= thresholds[hero.promotionsAwarded]
        if (!milestoneReached) return null

        val backgroundTrait = HeroBackgrounds.byId(definition.backgroundId)?.grantedTrait
        val choices = HeroTraitCatalog.choose(hero, backgroundTrait, formation.unitClass)
        return if (choices.size < 2) null else Promotion(hero.heroId, HeroNaming.nextRank(hero.rankId), choices)
    }
}
