package org.osada.hero

/**
 * Turns one combat's [RecognitionService.Contribution] into progression for an already-led
 * formation — design brief §19's processor, realised for Phase 3's scope.
 *
 * This is the counterpart to [LeaderAcquisitionService]: that decides whether a leaderless
 * formation gets a commander, this decides what happens to a formation that already has one. Both
 * are pure — [HeroCampaign] is the sole writer of roster state, same division of responsibility as
 * Phase 2.
 *
 * A routine action (nothing [HeroAchievements] classifies as notable) changes nothing: no XP, no
 * evidence, no history entries. Progression stays justified the same way emergence is (§4.1).
 */
internal object HeroProgressionProcessor {
    data class Result(
        val hero: HeroState,
        val formation: CoreFormation,
        val promotion: HeroPromotionService.Promotion?,
    )

    fun process(
        contribution: RecognitionService.Contribution,
        hero: HeroState,
        definition: HeroDefinition,
        formation: CoreFormation,
        campaignId: String,
        scenarioId: String,
        turn: Int,
        eventDate: String? = null,
        eventLocation: String? = null,
        balance: HeroBalance = HeroBalance.DEFAULT,
    ): Result {
        val achievements = HeroAchievements.derive(contribution)
        if (achievements.isEmpty()) return Result(hero, formation, null)
        val events =
            achievements.map {
                CombatAchievementEvent(campaignId, scenarioId, turn, formation.id, it, eventDate, eventLocation)
            }

        var state = progressExperience(hero, achievements, events, balance)
        state = HeroMedals.award(state, achievements, scenarioId)
        state = HeroNicknames.evaluate(state)
        var updatedFormation = appendFormationHistory(formation, events)

        val promotion = HeroPromotionService.evaluate(state, definition, formation, balance)
        if (promotion != null) {
            state = applyPromotion(state, promotion, scenarioId, turn, eventDate, eventLocation)
            updatedFormation =
                appendPromotionHistory(updatedFormation, promotion, scenarioId, turn, eventDate, eventLocation)
        }
        return Result(state, updatedFormation, promotion)
    }

    private fun progressExperience(
        hero: HeroState,
        achievements: List<AchievementType>,
        events: List<CombatAchievementEvent>,
        balance: HeroBalance,
    ): HeroState {
        val updated =
            hero.copy(
                experience = hero.experience + balance.leaderXpPerCombat,
                specializationEvidence = applyEvidence(hero.specializationEvidence, achievements),
                serviceEvents = hero.serviceEvents + events.map(::serviceEventFor),
            )
        return updated.copy(renown = HeroRenownService.forExperience(updated.experience, balance))
    }

    private fun applyEvidence(
        current: Map<String, Int>,
        achievements: List<AchievementType>,
    ): Map<String, Int> {
        val next = current.toMutableMap()
        achievements.forEach { type ->
            EvidenceRules.forType(type).forEach { rule ->
                next[rule.categoryId.name] = (next[rule.categoryId.name] ?: 0) + rule.baseAmount
            }
        }
        return next
    }

    private fun appendFormationHistory(
        formation: CoreFormation,
        events: List<CombatAchievementEvent>,
    ): CoreFormation = formation.copy(history = formation.history + events.map(::formationEventFor))

    private fun applyPromotion(
        hero: HeroState,
        promotion: HeroPromotionService.Promotion,
        scenarioId: String,
        turn: Int,
        eventDate: String?,
        eventLocation: String?,
    ): HeroState =
        hero.copy(
            rankId = promotion.newRankId,
            promotionsAwarded = hero.promotionsAwarded + 1,
            serviceEvents =
                hero.serviceEvents +
                    HeroEvent("promoted_to_${promotion.newRankId}", scenarioId, turn, eventDate, eventLocation),
        )

    private fun appendPromotionHistory(
        formation: CoreFormation,
        promotion: HeroPromotionService.Promotion,
        scenarioId: String,
        turn: Int,
        eventDate: String?,
        eventLocation: String?,
    ): CoreFormation =
        formation.copy(
            history =
                formation.history +
                    FormationEvent(
                        "commander_promoted_to_${promotion.newRankId}",
                        scenarioId,
                        turn,
                        eventDate,
                        eventLocation,
                    ),
        )

    private fun serviceEventFor(event: CombatAchievementEvent) =
        HeroEvent(event.achievementType.name.lowercase(), event.scenarioId, event.turn, event.date, event.location)

    private fun formationEventFor(event: CombatAchievementEvent) =
        FormationEvent(event.achievementType.name.lowercase(), event.scenarioId, event.turn, event.date, event.location)
}
