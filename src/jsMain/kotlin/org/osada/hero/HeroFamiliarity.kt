package org.osada.hero

/**
 * How well an officer already knows a formation, and what that costs them on arrival — §5.1.
 *
 * ## Why this is derived, not stored
 *
 * The design is explicit: "Use appointment history, not an independently mutable affinity score."
 * A stored number would be a second source of truth that the transfer code has to remember to
 * update, and the first missed update is a commander who is permanently "familiar" with a brigade
 * they never served in. The appointment events are already written for the Service Record, so
 * asking them is free and cannot drift.
 *
 * ## The three cases
 *
 * | Relationship to the target | Settling | Why |
 * |---|---|---|
 * | Already commands it | 0 turns | not a transfer at all |
 * | Has commanded it before | 1 turn | the organization is familiar, some of the people are not |
 * | Has never commanded it | 3 turns | learns staff, subordinates and procedures from scratch |
 *
 * The three-turn cost for an unfamiliar formation is unchanged and deliberately so: §18's
 * acceptance criteria keep it, because it is the whole restraint on shuffling a favourite officer
 * into whichever brigade is about to fight. What §5.1 adds is that a RETURN is not the same act.
 *
 * Deliberately no bonus for long tenure (§5.1's closing paragraph): a tenure bonus makes a
 * favourite commander impossible to move, which is the opposite of what a transfer window is for.
 */
internal object HeroFamiliarity {
    /**
     * The three event ids that mean "took command of this formation" — the originating command,
     * a posting in, and a posting back. They are exactly the ids [HeroCampaign] and
     * [HeroTransferService] write with a formation id attached; anything else in a service record
     * happened WHILE commanding rather than being an appointment, and must not count as one.
     */
    private val APPOINTMENT_EVENTS = setOf("emerged", "transferred", "returned")

    /** §5.2's restrained tenure labels, derived from the same history. */
    enum class Tenure {
        NEWLY_APPOINTED,
        ESTABLISHED,
        LONG_SERVING,
        RETURNED,
    }

    /**
     * True when [hero] has commanded [formationId] at some earlier point and is not commanding it
     * now — the one case that earns the shortened settling period.
     */
    fun hasCommandedBefore(
        hero: HeroState,
        formationId: FormationId,
    ): Boolean =
        hero.assignedFormationId != formationId &&
            hero.serviceEvents.any { it.formationId == formationId && it.eventId in APPOINTMENT_EVENTS }

    /**
     * Turns of settling-in when [hero] is posted to [formationId].
     *
     * Zero when they already command it, so a no-op post cannot suppress a commander's own traits
     * for three turns — the bug that shape of code invites.
     */
    fun settlingTurnsFor(
        hero: HeroState,
        formationId: FormationId,
        balance: HeroBalance = HeroBalance.DEFAULT,
    ): Int =
        when {
            hero.assignedFormationId == formationId -> 0
            hasCommandedBefore(hero, formationId) -> balance.returnSettlingTurns
            else -> balance.transferSettlingTurns
        }

    /**
     * The label §5.2 shows for a commander's relationship with the formation they hold now.
     *
     * Counted in completed SCENARIOS rather than turns: a campaign's unit of time is the battle,
     * and a commander who fought four battles with a brigade reads as established whether those
     * battles ran eight turns or twenty.
     */
    @Suppress("ReturnCount") // unassigned and returned are answers, not branches into the tier table
    fun tenureFor(hero: HeroState): Tenure {
        val formationId = hero.assignedFormationId ?: return Tenure.NEWLY_APPOINTED
        val appointments = hero.serviceEvents.filter { it.formationId == formationId }
        if (appointments.count { it.eventId in APPOINTMENT_EVENTS } > 1) return Tenure.RETURNED
        val scenarios =
            appointments
                .map { it.scenarioId }
                .filter { it.isNotBlank() }
                .toSet()
                .size
        return when {
            scenarios >= LONG_SERVING_SCENARIOS -> Tenure.LONG_SERVING
            scenarios >= ESTABLISHED_SCENARIOS -> Tenure.ESTABLISHED
            else -> Tenure.NEWLY_APPOINTED
        }
    }

    /**
     * Every formation [hero] has ever been appointed to, oldest first, with the formation they hold
     * now excluded — §13.3's "previous formations" list.
     */
    fun previousFormations(hero: HeroState): List<FormationId> =
        hero.serviceEvents
            .filter { it.eventId in APPOINTMENT_EVENTS }
            .mapNotNull { it.formationId }
            .distinct()
            .filter { it != hero.assignedFormationId }

    private const val ESTABLISHED_SCENARIOS = 2
    private const val LONG_SERVING_SCENARIOS = 5
}
