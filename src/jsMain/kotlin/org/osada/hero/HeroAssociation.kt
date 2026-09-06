package org.osada.hero

/**
 * A sparse, event-grounded link between two officers — the biography design's §6.
 *
 * ## What this deliberately is not
 *
 * §4.2 rules out mood meters, friendship points, families, adjacency bonuses and grief penalties.
 * What is left is the small set of relationships an operational wargame can actually PROVE from
 * what it observed: one officer's formation materially helped another's, or one officer succeeded
 * another in a command. Everything else — "served in the same operations" — is computed from
 * campaign history at display time (§6.2) rather than stored, so it cannot become inconsistent.
 *
 * ## Grounding
 *
 * §6.3 forbids choosing an endorser "merely because an established hero exists somewhere in the
 * roster". [sourceEventId] and [formationId] are what make that impossible to do by accident: an
 * association records the event that created it, and the code that creates one is handed the
 * participating formation by the combat payload rather than searching the roster for a candidate.
 *
 * ## The cap
 *
 * §6 allows at most two formal associations per hero, and §6.1 says the first officer of a run
 * normally has none. Both are enforced in [HeroAssociations], not here — this is the record.
 */
data class HeroAssociation(
    val type: Type,
    val otherHeroId: HeroId,
    /** The achievement/appointment event that proves the link, for the dossier's wording. */
    val sourceEventId: String,
    val scenarioId: String,
    val date: String? = null,
    val location: String? = null,
    /** The formation the link happened around, where one applies. */
    val formationId: FormationId? = null,
) {
    /**
     * The two initial types of §6.2, each stored on both officers with the reciprocal direction so
     * either dossier can be rendered without searching the other's record.
     */
    enum class Type {
        /** This hero's appointment was endorsed by [otherHeroId]. */
        ENDORSED_BY,

        /** This hero endorsed [otherHeroId]'s appointment. */
        ENDORSED,

        /** [otherHeroId] commanded this hero's formation before them. */
        PREDECESSOR,

        /** [otherHeroId] took over the formation this hero left. */
        SUCCESSOR,
        ;

        /** The record written on the OTHER officer when this one is created. */
        fun reciprocal(): Type =
            when (this) {
                ENDORSED_BY -> ENDORSED
                ENDORSED -> ENDORSED_BY
                PREDECESSOR -> SUCCESSOR
                SUCCESSOR -> PREDECESSOR
            }
    }
}
