package org.osada.hero

/**
 * The two commendations a combat can produce — §6.3's grounded endorsement and §12's highest
 * distinction.
 *
 * Split out of [HeroCampaign], which the biography design pushed past detekt's class-size budget.
 * The seam is a real one: everything here is a RULE about whether an honour is earned, expressed
 * as a pure function of facts handed to it, while [HeroCampaign] remains the single writer of
 * roster state and the only thing that knows what turn it is.
 *
 * Both entry points refuse by default. That is the design's own discipline, not caution for its own
 * sake: §6.3 forbids inventing an endorser and §12.3 forbids conferring a title on an accumulated
 * total, so "no proof, no honour" is the behaviour both sections ask for.
 */
internal object HeroCommendations {
    /**
     * §6.3's grounded endorsement: the new officer is endorsed by the commander of a formation the
     * combat payload records as having FIRED IN SUPPORT of them.
     *
     * Everything about this refuses rather than invents. No supporting formation means no
     * endorsement — which is the normal case, and specifically the case for a run's first commander
     * (§6.1). A supporting formation with no commander of its own means no endorsement either: an
     * unled brigade cannot recommend anybody. Only the first eligible supporter is used, because
     * §6 caps a hero at two associations and an emergence should not spend both at once.
     */
    @Suppress("LongParameterList") // the emergence's own facts, passed rather than reached for
    fun recordEndorsement(
        roster: HeroRoster,
        emerged: HeroId,
        supportingFormationIds: List<String>,
        eventId: String,
        scenarioId: String,
        date: String?,
        location: String?,
    ) {
        val endorser =
            supportingFormationIds
                .asSequence()
                .mapNotNull { roster.formation(FormationId(it)) }
                .mapNotNull { formation -> formation.assignedHeroId?.let { it to formation.id } }
                .firstOrNull { (heroId, _) -> roster.state(heroId)?.status == HeroStatus.ACTIVE }
                ?: return
        HeroAssociations.link(
            roster = roster,
            hero = emerged,
            other = endorser.first,
            type = HeroAssociation.Type.ENDORSED_BY,
            sourceEventId = eventId,
            scenarioId = scenarioId,
            date = date,
            location = location,
            formationId = endorser.second,
        )
    }

    /**
     * §12's highest distinction, evaluated against this combat's deeds.
     *
     * Returns [hero] unchanged in every case but the rare one, which is most of them: the date, the
     * player side and the deed all have to line up, and [HeroDistinctions] refuses rather than
     * approximates on each. The award adds a service-record line and nothing else — §12.6 forbids
     * any combat modifier, so nothing here touches attributes, traits or experience.
     */
    @Suppress("LongParameterList") // the conferral's own facts; bundling them would hide the gate
    fun conferDistinction(
        hero: HeroState,
        achievements: List<AchievementType>,
        country: Int?,
        serviceYear: Int?,
        scenarioId: String,
        turn: Int,
        date: String?,
        location: String?,
    ): HeroState {
        val conferral =
            HeroDistinctions.evaluate(
                HeroDistinctions.Context(
                    hero = hero,
                    country = country,
                    serviceYear = serviceYear,
                    date = date,
                    scenarioId = scenarioId,
                    turn = turn,
                    location = location,
                    achievements = achievements,
                ),
            ) ?: return hero
        return hero.copy(
            distinctions = hero.distinctions + conferral,
            serviceEvents =
                hero.serviceEvents +
                    HeroEvent(
                        "distinction_${conferral.distinctionId}",
                        conferral.scenarioId,
                        conferral.turn,
                        conferral.date,
                        conferral.location,
                        formationId = hero.assignedFormationId,
                    ),
        )
    }
}
