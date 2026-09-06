package org.osada.hero

/**
 * Generates a coherent life path — §7.2's ordered walk, and the reason this replaces
 * the deleted `HeroBiographyPools`'s independent draws.
 *
 * The old generator rolled four facts from four fixed pools with four salts. Nothing connected
 * them, so a hero could be a mine surveyor who never went to school. This one walks the same seed
 * through an ordered sequence in which every step narrows the next, and each step is a weighted
 * draw over only the options [BiographyOption.allowedFor] admits given the facts already chosen.
 *
 * ## Determinism
 *
 * Every draw is `SeededRandom(seed + salt)` with a distinct constant salt per step, so the same
 * request reproduces the same life path exactly (§17.1) and no step can collapse onto another's
 * stream. Rendering performs no selection at all — the narrator reads stored ids.
 *
 * ## Omission over invention
 *
 * §7.2's closing rule: "The generator should retry or omit a fact when no compatible choice exists.
 * It must never emit an invalid fallback merely to fill every field." So [pick] returns null on an
 * empty candidate list and every caller is prepared for it. The visible consequence is a shorter
 * biography, never a wrong one — which is also why the ancient pack can leave whole fields empty
 * without any special case here.
 */
internal object HeroLifePath {
    /** Everything the walk needs that is not the seed. */
    data class Context(
        val seed: Int,
        val country: Int?,
        val serviceYear: Int?,
        val unitClass: Int,
        val rankId: String,
        val emergenceEventId: String,
    )

    private val SENIOR_RANKS = setOf("major", "colonel")

    /**
     * Runs §7.2 steps 3-12 and returns the facts to persist.
     *
     * Step 1-2 (resolve context, select pack) happen in the caller and in [BiographyPacks] because
     * the pack id is itself a stored fact: a save must remember which content family generated a
     * hero, or a later build that re-tables a country would re-render an existing officer against
     * someone else's pools.
     */
    fun generate(context: Context): HeroBiographyFacts {
        val pack = BiographyPacks.forCountry(context.country, context.serviceYear)
        val birthYear = birthYear(context)
        val age = age(birthYear, context.serviceYear)
        val facts = mutableSetOf<String>()

        val region = choose(pack.regions, context, age, facts, REGION_SALT, "region")
        val social = choose(pack.socialBackgrounds, context, age, facts, SOCIAL_SALT, "social")
        val civilian = choose(pack.civilianEducation, context, age, facts, CIVIL_EDUCATION_SALT, "education")
        val profession = choose(pack.professions, context, age, facts, PROFESSION_SALT, "profession")
        val serviceEntry = pickOption(pack.serviceEntries, context, age, facts, SERVICE_ENTRY_SALT, "entry")
        val serviceStartYear = serviceStartYear(pack, context, birthYear, serviceEntry)
        val political = political(pack, context, age, facts)
        // Deliberately after the political draw: `party_mobilization` requires `political:member`,
        // which only exists once the political status has been chosen and its tags folded in.
        val warEntry = choose(pack.warEntries, context, age, facts, WAR_ENTRY_SALT, "warentry")
        val militaryEducation = militaryEducation(pack, context, age, facts)
        val priorService = priorService(pack, context, age, facts)

        return HeroBiographyFacts(
            birthYear = birthYear,
            birthplaceId = region,
            biographyPackId = pack.id,
            socialBackgroundId = social,
            civilianEducationId = civilian,
            prewarProfessionId = profession,
            militaryEducationId = militaryEducation,
            serviceEntryId = serviceEntry?.id,
            serviceStartYear = serviceStartYear,
            warEntryId = warEntry,
            politicalStatusId = political?.first,
            politicalMembershipYear = political?.second,
            priorServiceIds = priorService,
            emergenceEventId = context.emergenceEventId,
        )
    }

    /** A plausible birth year: the campaign year minus a seeded age, or null when undated. */
    fun birthYear(context: Context): Int? {
        val year = context.serviceYear ?: return null
        val spread = if (context.rankId in SENIOR_RANKS) SENIOR_AGE_SPREAD else AGE_SPREAD
        val base = if (context.rankId in SENIOR_RANKS) MIN_SENIOR_AGE else MIN_AGE
        return year - (base + SeededRandom(context.seed).nextInt(spread))
    }

    private fun age(
        birthYear: Int?,
        serviceYear: Int?,
    ): Int? = if (birthYear == null || serviceYear == null) null else serviceYear - birthYear

    /**
     * One step of the walk: draw from [options], record `"<field>:<id>"` and the option's own
     * [BiographyOption.provides] into [facts], and return the id. Null when nothing is compatible.
     */
    private fun choose(
        options: List<BiographyOption>,
        context: Context,
        age: Int?,
        facts: MutableSet<String>,
        salt: Int,
        field: String,
    ): String? = pickOption(options, context, age, facts, salt, field)?.id

    /** [choose], but returning the option itself for the one step that needs its extra bounds. */
    private fun pickOption(
        options: List<BiographyOption>,
        context: Context,
        age: Int?,
        facts: MutableSet<String>,
        salt: Int,
        field: String,
    ): BiographyOption? {
        val chosen = pick(options, context, age, facts, salt) ?: return null
        facts += "$field:${chosen.id}"
        facts += chosen.provides
        return chosen
    }

    /** Weighted draw over the compatible subset. Weights are summed per call, never precomputed. */
    @Suppress("ReturnCount") // empty pool, the weighted hit, and the rounding tail
    private fun pick(
        options: List<BiographyOption>,
        context: Context,
        age: Int?,
        facts: Set<String>,
        salt: Int,
    ): BiographyOption? {
        val candidates =
            options.filter { it.allowedFor(context.serviceYear, age, context.unitClass, facts) }
        if (candidates.isEmpty()) return null
        val total = candidates.sumOf { it.weight.coerceAtLeast(1) }
        var roll = SeededRandom(context.seed + salt).nextInt(total)
        for (candidate in candidates) {
            roll -= candidate.weight.coerceAtLeast(1)
            if (roll < 0) return candidate
        }
        return candidates.last()
    }

    /**
     * The year the officer entered military service — strictly after birth and not after the
     * campaign (§10's first rule), which is enforced by construction rather than by a later check.
     *
     * Null for a pack that does not track it ([BiographyPack.tracksServiceStartYear]): the ancient
     * army had no enlistment date to record, and printing one would be exactly the modern
     * substitution §9.1 forbids.
     */
    @Suppress("ReturnCount") // four "this pack/campaign cannot date it" guards, then the value
    private fun serviceStartYear(
        pack: BiographyPack,
        context: Context,
        birthYear: Int?,
        entry: BiographyOption?,
    ): Int? {
        if (!pack.tracksServiceStartYear) return null
        val campaignYear = context.serviceYear ?: return null
        val born = birthYear ?: return null
        // Two floors, and the later one wins: old enough to enlist, and late enough that the route
        // taken actually existed ([BiographyOption.serviceNotBefore]).
        val earliest = maxOf(born + MIN_ENLISTMENT_AGE, entry?.serviceNotBefore ?: Int.MIN_VALUE)
        if (earliest > campaignYear) return null
        val span = campaignYear - earliest
        return earliest + if (span <= 0) 0 else SeededRandom(context.seed + SERVICE_YEAR_SALT).nextInt(span + 1)
    }

    /**
     * Rank-weighted military schooling, keeping the deleted `HeroBiographyPools`'s behaviour: a lieutenant is
     * commissioned from the ranks, a colonel attended an academy.
     */
    private fun militaryEducation(
        pack: BiographyPack,
        context: Context,
        age: Int?,
        facts: MutableSet<String>,
    ): String? {
        val pool =
            if (context.rankId in SENIOR_RANKS) pack.militaryEducationSenior else pack.militaryEducationJunior
        return choose(pool, context, age, facts, MILITARY_EDUCATION_SALT, "milEdu")
    }

    /**
     * Party/political status and, for a member, the year — §8.5.
     *
     * The year is bounded on both ends: never before the hero was old enough to join, never after
     * the campaign's own year. Both bounds are §10 rules and both are enforced here rather than
     * left to the renderer, because a stored impossible year would survive into every later save.
     */
    @Suppress("ReturnCount") // each guard is a distinct "this hero has no party fact" case
    private fun political(
        pack: BiographyPack,
        context: Context,
        age: Int?,
        facts: MutableSet<String>,
    ): Pair<String, Int?>? {
        if (pack.politicalStatuses.isEmpty()) return null
        if (!SeededRandom(context.seed + POLITICAL_GATE_SALT).roll(pack.politicalChance)) return null
        val status = choose(pack.politicalStatuses, context, age, facts, POLITICAL_SALT, "political") ?: return null
        if ("political:member" !in facts) return status to null
        return status to membershipYear(context, age)
    }

    @Suppress("ReturnCount") // undated campaign, unknown age, too young, then the year
    private fun membershipYear(
        context: Context,
        age: Int?,
    ): Int? {
        val campaignYear = context.serviceYear ?: return null
        val currentAge = age ?: return null
        val yearsSinceEligible = currentAge - MIN_PARTY_AGE
        if (yearsSinceEligible < 0) return null
        val offset = SeededRandom(context.seed + POLITICAL_YEAR_SALT).nextInt(yearsSinceEligible + 1)
        return campaignYear - offset
    }

    /**
     * Zero to two rare prior-service facts (§7.1's `priorServiceIds`), each validated by the same
     * [BiographyOption.allowedFor] guards as everything else.
     *
     * A junior officer more often has nothing to report; a senior one usually does. That weighting
     * is inherited from the deleted `HeroBiographyPools` and is the one part of the old generator worth keeping:
     * a biography that reads sparse for a fresh lieutenant is more convincing than one that invents
     * a campaign history for him.
     */
    @Suppress("ReturnCount") // empty pool and the failed first roll both mean "no prior service"
    private fun priorService(
        pack: BiographyPack,
        context: Context,
        age: Int?,
        facts: MutableSet<String>,
    ): List<String> {
        if (pack.priorService.isEmpty()) return emptyList()
        val senior = context.rankId in SENIOR_RANKS
        val rng = SeededRandom(context.seed + PRIOR_SERVICE_GATE_SALT)
        val first = if (senior) SENIOR_PRIOR_SERVICE_CHANCE else JUNIOR_PRIOR_SERVICE_CHANCE
        if (!rng.roll(first)) return emptyList()
        val chosen = mutableListOf<String>()
        choose(pack.priorService, context, age, facts, PRIOR_SERVICE_SALT, "prior")?.let(chosen::add)
        val second = if (senior) SENIOR_SECOND_PRIOR_CHANCE else JUNIOR_SECOND_PRIOR_CHANCE
        if (chosen.isNotEmpty() && rng.roll(second)) {
            val remaining = pack.priorService.filterNot { it.id in chosen }
            choose(remaining, context, age, facts, PRIOR_SERVICE_SECOND_SALT, "prior")?.let(chosen::add)
        }
        return chosen
    }

    private const val MIN_AGE = 24
    private const val AGE_SPREAD = 14
    private const val MIN_SENIOR_AGE = 32
    private const val SENIOR_AGE_SPREAD = 16
    private const val MIN_ENLISTMENT_AGE = 18
    private const val MIN_PARTY_AGE = 22
    private const val JUNIOR_PRIOR_SERVICE_CHANCE = 0.35
    private const val SENIOR_PRIOR_SERVICE_CHANCE = 0.85
    private const val JUNIOR_SECOND_PRIOR_CHANCE = 0.15
    private const val SENIOR_SECOND_PRIOR_CHANCE = 0.4

    // Distinct salts, so no two steps draw from the same SeededRandom stream.
    private const val REGION_SALT = 11
    private const val SOCIAL_SALT = 12
    private const val CIVIL_EDUCATION_SALT = 13
    private const val PROFESSION_SALT = 14
    private const val SERVICE_ENTRY_SALT = 15
    private const val SERVICE_YEAR_SALT = 16
    private const val WAR_ENTRY_SALT = 17
    private const val MILITARY_EDUCATION_SALT = 18
    private const val POLITICAL_GATE_SALT = 19
    private const val POLITICAL_SALT = 20
    private const val POLITICAL_YEAR_SALT = 21
    private const val PRIOR_SERVICE_GATE_SALT = 22
    private const val PRIOR_SERVICE_SALT = 23
    private const val PRIOR_SERVICE_SECOND_SALT = 24
}
