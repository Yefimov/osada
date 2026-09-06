package org.osada.hero

import org.osada.model.GameUnit

/**
 * Resolves and records what happens to a commander whose unit was destroyed (§11).
 *
 * Split out of [HeroCampaign], which the biography design's additions pushed past detekt's
 * class-size budget. The seam is honest: this is one self-contained rule — resolve the fate, write
 * the two records it produces, and hand the announcement back — while [HeroCampaign] keeps owning
 * when it runs and what the UI is told.
 *
 * Everything the rule needs is passed in, including the scenario id, date and location, so it can
 * be exercised without a live campaign.
 */
internal object HeroCasualtyRecorder {
    /**
     * Resolves the fate of [formation]'s commander after its unit was destroyed (§11): sets the new
     * status, records any wound, detaches the leader, leaves a restrained memorial tradition on death
     * (§11.2), and queues the event for the UI.
     *
     * The detach is unconditional because this only runs on a destroyed unit, and a destroyed unit is
     * not campaign-persistent — the formation itself does not reach the next scenario. Keeping a
     * lightly wounded commander "with his formation" therefore stranded him: still `ACTIVE`, still
     * pointing at a formation no unit would ever carry again, and unreachable by any reassignment
     * (transfers are still deferred, see `docs/hero-leader-implementation-phases.md` Phase 4).
     */
    @Suppress("LongParameterList", "ReturnCount") // the unit's own facts; a missing hero is not an outcome
    fun apply(
        roster: HeroRoster,
        unit: GameUnit,
        formation: CoreFormation,
        heroId: HeroId,
        turn: Int,
        scenarioId: String,
        date: String?,
        location: String?,
    ): HeroCasualtyAnnouncement? {
        val hero = roster.state(heroId) ?: return null
        val definition = roster.definition(heroId) ?: return null
        val casualtyContext =
            HeroCasualtyService.Context(
                surrendered = unit.surrendered,
                safeSupply = !unit.surrendered,
                seed = SeededRandom.seedFrom(heroId.value, scenarioId, turn.toString()),
            )
        val outcome = HeroCasualtyService.resolve(casualtyContext, scenarioId)
        val event =
            HeroEvent(
                outcome.disposition.name.lowercase(),
                scenarioId,
                turn,
                date,
                location,
            )
        roster.updateState(
            hero.copy(
                status = outcome.disposition.status,
                injuries = hero.injuries + listOfNotNull(outcome.injury),
                serviceEvents = hero.serviceEvents + event,
                assignedFormationId = null,
            ),
        )
        val killed = outcome.disposition == HeroCasualtyService.Disposition.KILLED
        // A TOKEN, not a sentence. This string is stored in the save and shown as a battle honour,
        // and it used to be built as English prose -- so a Russian player read "Tradition of Nadya
        // Sokolova" on their own formation. `HeroHonours` renders it; the commander's name is the
        // only variable part, so it travels as the token's payload.
        val memorial = if (killed) HeroHonours.memorialToken(definition.displayName) else null
        val updatedFormation =
            formation.copy(
                assignedHeroId = null,
                battleHonors = if (memorial != null) formation.battleHonors + memorial else formation.battleHonors,
                history =
                    formation.history +
                        FormationEvent(
                            "commander_${outcome.disposition.name.lowercase()}",
                            scenarioId,
                            turn,
                            date,
                            location,
                            heroId = hero.heroId,
                        ),
            )
        roster.putFormation(updatedFormation)
        // §6.5 of the biography design: a linked officer's death returns to their associates'
        // service records as MEMORY and nothing else. No attribute loss, no suppressed trait, no
        // morale penalty -- losing a developed commander is already the cost, and doubling it would
        // only reward save-scumming.
        if (killed) {
            HeroAssociations.recordDeath(roster, hero.heroId, scenarioId, turn, date, location)
        }
        return HeroCasualtyAnnouncement.from(outcome, updatedFormation, definition, hero.rankId, memorial)
    }
}
