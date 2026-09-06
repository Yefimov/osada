package org.osada.hero

/**
 * The §10 chronology checker: an independent audit of a finished life path.
 *
 * [HeroLifePath] already enforces most of these rules by construction — it draws a service-start
 * year inside its own bounds rather than drawing one and checking it. This exists anyway, for two
 * reasons the design states outright:
 *
 * - §10's last rule: "authored legendary biographies bypass random generation but must pass the
 *   same chronology checker". A hand-written hero has no generator to be correct by construction,
 *   so the checker is the only thing standing between an authored typo and a 1919 officer who
 *   fought at Khalkhin Gol.
 * - §17.2 runs large seeded samples over every pack and asserts no impossible date. A property test
 *   needs a predicate to assert, and duplicating the generator's internal bounds inside the test
 *   would only prove the test agrees with itself.
 *
 * It returns REASONS rather than a boolean. A failing sample that says which rule broke is worth
 * the extra type: the alternative is a red test that names a seed and leaves the reader to work
 * out which of eleven rules it violated.
 */
internal object HeroChronology {
    /** One broken rule, named by the fact it concerns and why it cannot be true. */
    data class Violation(
        val field: String,
        val reason: String,
    ) {
        override fun toString(): String = "$field: $reason"
    }

    /**
     * Every §10 rule that can be checked from the stored facts alone.
     *
     * [campaignYear] is the scenario year the hero is being rendered in. When it is unknown the
     * date-relative rules are skipped rather than assumed — the same conservatism
     * [BiographyOption.allowedFor] applies, and for the same reason: an unverifiable fact is not a
     * failed one.
     */
    fun validate(
        facts: HeroBiographyFacts,
        campaignYear: Int?,
    ): List<Violation> {
        val violations = mutableListOf<Violation>()
        val birth = facts.birthYear
        if (birth != null && campaignYear != null && birth > campaignYear) {
            violations += Violation("birthYear", "born $birth, after the campaign year $campaignYear")
        }
        violations += validateServiceStart(facts, campaignYear, birth)
        violations += validatePolitical(facts, campaignYear, birth)
        violations += validatePriorService(facts, campaignYear, birth)
        violations += validatePackMembership(facts)
        return violations
    }

    /** `birthYear < serviceStartYear <= campaignServiceYear`, plus a minimum enlistment age. */
    private fun validateServiceStart(
        facts: HeroBiographyFacts,
        campaignYear: Int?,
        birth: Int?,
    ): List<Violation> {
        val start = facts.serviceStartYear ?: return emptyList()
        val out = mutableListOf<Violation>()
        if (birth != null && start <= birth) {
            out += Violation("serviceStartYear", "entered service in $start, born $birth")
        }
        if (birth != null && start - birth < MIN_ENLISTMENT_AGE) {
            out += Violation("serviceStartYear", "entered service aged ${start - birth}")
        }
        if (campaignYear != null && start > campaignYear) {
            out += Violation("serviceStartYear", "entered service in $start, after the campaign year $campaignYear")
        }
        return out
    }

    /** §8.5: not before a reasonable adult age, and never in the future. */
    private fun validatePolitical(
        facts: HeroBiographyFacts,
        campaignYear: Int?,
        birth: Int?,
    ): List<Violation> {
        val year = facts.politicalMembershipYear ?: return emptyList()
        val out = mutableListOf<Violation>()
        if (facts.politicalStatusId == null) {
            out += Violation("politicalMembershipYear", "a membership year with no political status")
        }
        if (birth != null && year - birth < MIN_PARTY_AGE) {
            out += Violation("politicalMembershipYear", "joined aged ${year - birth}")
        }
        if (campaignYear != null && year > campaignYear) {
            out += Violation("politicalMembershipYear", "joined in $year, after the campaign year $campaignYear")
        }
        return out
    }

    /**
     * §10: "prior conflicts must end before the current campaign scenario" and "the hero must meet
     * the minimum age during every prior-service fact".
     *
     * Both are re-derived from the option's own [BiographyOption.yearFrom] / [BiographyOption.minimumAge]
     * rather than from a second table of conflict dates. One source for a fact's dates means an
     * authored correction to a pack cannot leave the checker disagreeing with the generator.
     */
    private fun validatePriorService(
        facts: HeroBiographyFacts,
        campaignYear: Int?,
        birth: Int?,
    ): List<Violation> {
        val pack = facts.biographyPackId?.let(BiographyPacks::byId) ?: return emptyList()
        val age = if (birth != null && campaignYear != null) campaignYear - birth else null
        val out = mutableListOf<Violation>()
        if (facts.priorServiceIds.size != facts.priorServiceIds.toSet().size) {
            out += Violation("priorServiceIds", "the same prior conflict is recorded twice")
        }
        if (facts.priorServiceIds.size > MAX_PRIOR_SERVICE) {
            out +=
                Violation(
                    "priorServiceIds",
                    "${facts.priorServiceIds.size} prior conflicts, the cap is $MAX_PRIOR_SERVICE",
                )
        }
        facts.priorServiceIds.forEach { id ->
            val option = pack.priorService.firstOrNull { it.id == id }
            if (option == null) {
                out += Violation("priorServiceIds", "'$id' is not in pack '${pack.id}'")
                return@forEach
            }
            if (option.yearFrom != null && campaignYear != null && campaignYear < option.yearFrom) {
                out += Violation("priorServiceIds", "'$id' cannot have happened before $campaignYear")
            }
            if (option.minimumAge != null && age != null && age < option.minimumAge) {
                out += Violation("priorServiceIds", "'$id' needs age ${option.minimumAge}, the hero is $age")
            }
        }
        return out
    }

    /**
     * Every stored id must belong to the stored pack. This is what catches a fact copied between
     * content families — a Soviet `tekhnikum` on a Spanish officer — which no date rule would see.
     */
    private fun validatePackMembership(facts: HeroBiographyFacts): List<Violation> {
        val pack = facts.biographyPackId?.let(BiographyPacks::byId) ?: return emptyList()
        val checks =
            listOf(
                Triple("birthplaceId", facts.birthplaceId, pack.regions),
                Triple("socialBackgroundId", facts.socialBackgroundId, pack.socialBackgrounds),
                Triple("civilianEducationId", facts.civilianEducationId, pack.civilianEducation),
                Triple("prewarProfessionId", facts.prewarProfessionId, pack.professions),
                Triple("serviceEntryId", facts.serviceEntryId, pack.serviceEntries),
                Triple("warEntryId", facts.warEntryId, pack.warEntries),
                Triple("politicalStatusId", facts.politicalStatusId, pack.politicalStatuses),
            )
        val out =
            checks.mapNotNull { (field, id, options) ->
                if (id != null && options.none { it.id == id }) {
                    Violation(field, "'$id' is not in pack '${pack.id}'")
                } else {
                    null
                }
            }
        val militaryEducation = facts.militaryEducationId
        val allMilitary = pack.militaryEducationJunior + pack.militaryEducationSenior
        return if (militaryEducation != null && allMilitary.none { it.id == militaryEducation }) {
            out + Violation("militaryEducationId", "'$militaryEducation' is not in pack '${pack.id}'")
        } else {
            out
        }
    }

    private const val MIN_ENLISTMENT_AGE = 15
    private const val MIN_PARTY_AGE = 18
    private const val MAX_PRIOR_SERVICE = 2
}
