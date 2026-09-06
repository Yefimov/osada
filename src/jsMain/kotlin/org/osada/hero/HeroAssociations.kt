package org.osada.hero

/**
 * Creates and reads the §6 associations — the only place that writes a [HeroAssociation].
 *
 * Every entry point here refuses rather than invents. §6.3's rule ("If the current combat payload
 * cannot prove participation, create no endorsement") and §6.1's ("The first procedural hero in a
 * run normally has no hero-to-hero association ... must not be filled with invented history") are
 * both satisfied by the same discipline: a caller must HAND this object the other officer, and a
 * caller that has none simply does not call.
 */
internal object HeroAssociations {
    /** §6: "A hero may have at most two significant associations in the first version." */
    const val MAX_ASSOCIATIONS = 2

    /**
     * Records [type] from [hero] to [other], and its reciprocal on [other].
     *
     * Returns false, changing nothing, when either officer is already at the cap or the pair is
     * already linked. Both halves are written or neither is: a one-sided association would render
     * as a relationship on one dossier and nothing on the other.
     */
    @Suppress("ReturnCount") // five refusals, each a distinct reason an association is not created
    fun link(
        roster: HeroRoster,
        hero: HeroId,
        other: HeroId,
        type: HeroAssociation.Type,
        sourceEventId: String,
        scenarioId: String,
        date: String? = null,
        location: String? = null,
        formationId: FormationId? = null,
    ): Boolean {
        if (hero == other) return false
        val heroState = roster.state(hero) ?: return false
        val otherState = roster.state(other) ?: return false
        if (!canAccept(heroState, other) || !canAccept(otherState, hero)) return false
        val forward =
            HeroAssociation(type, other, sourceEventId, scenarioId, date, location, formationId)
        val backward =
            HeroAssociation(type.reciprocal(), hero, sourceEventId, scenarioId, date, location, formationId)
        roster.updateState(heroState.copy(associations = heroState.associations + forward))
        roster.updateState(otherState.copy(associations = otherState.associations + backward))
        return true
    }

    /** Whether [state] has room for another association and is not already linked to [other]. */
    private fun canAccept(
        state: HeroState,
        other: HeroId,
    ): Boolean =
        state.associations.size < MAX_ASSOCIATIONS &&
            state.associations.none { it.otherHeroId == other }

    /**
     * §6.5's death callback: one service event on each surviving officer linked to [fallen].
     *
     * Narrative memory only. §6.5 is explicit that this must not reduce attributes, suppress traits
     * or apply a morale penalty — "The loss of a developed commander is already a substantial
     * player cost, and an extra penalty would encourage save-scumming."
     */
    fun recordDeath(
        roster: HeroRoster,
        fallen: HeroId,
        scenarioId: String,
        turn: Int,
        date: String?,
        location: String?,
    ) {
        val fallenState = roster.state(fallen) ?: return
        fallenState.associations.forEach { association ->
            val survivor = roster.state(association.otherHeroId) ?: return@forEach
            if (survivor.status == HeroStatus.KILLED) return@forEach
            roster.updateState(
                survivor.copy(
                    serviceEvents =
                        survivor.serviceEvents +
                            HeroEvent(
                                // Phrased from the SURVIVOR's side. The type stored on the fallen
                                // officer describes the relationship as THEY held it, so the
                                // reciprocal is what the survivor's own record should say: an
                                // officer whose protege died is not told his endorser died.
                                eventId = eventIdFor(association.type.reciprocal()),
                                scenarioId = scenarioId,
                                turn = turn,
                                date = date,
                                location = location,
                                relatedHeroId = fallen,
                            ),
                ),
            )
        }
    }

    /**
     * The service-record line a survivor gets, phrased by what the dead officer was to them: being
     * told your endorser was killed is a different sentence from being told your predecessor was.
     */
    private fun eventIdFor(type: HeroAssociation.Type): String =
        when (type) {
            HeroAssociation.Type.ENDORSED_BY -> "associate_endorser_killed"
            HeroAssociation.Type.ENDORSED -> "associate_protege_killed"
            HeroAssociation.Type.PREDECESSOR -> "associate_predecessor_killed"
            HeroAssociation.Type.SUCCESSOR -> "associate_successor_killed"
        }

    /**
     * Scenarios in which both officers held a command — §6.2's `SERVED_IN_SAME_OPERATIONS`, kept a
     * COMPUTED dossier line rather than a stored relationship precisely so it cannot go stale.
     *
     * Derived from each officer's own service record, which already carries a scenario id on every
     * entry, so two commanders who fought the same battles are recognised without either of them
     * having to have noticed the other.
     */
    fun sharedOperations(
        first: HeroState,
        second: HeroState,
    ): Int {
        val mine =
            first.serviceEvents
                .map { it.scenarioId }
                .filter { it.isNotBlank() }
                .toSet()
        val theirs =
            second.serviceEvents
                .map { it.scenarioId }
                .filter { it.isNotBlank() }
                .toSet()
        return mine.intersect(theirs).size
    }
}
