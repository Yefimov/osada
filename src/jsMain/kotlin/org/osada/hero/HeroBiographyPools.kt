package org.osada.hero

/**
 * Fills [HeroBiographyFacts]' four fields `ProceduralHeroGenerator` never wrote
 * (`docs/design/hero-presentation.md` §2.2/§2.3(a)): `birthplaceId`, `socialBackgroundId`,
 * `militaryEducationId`, `priorServiceId`. Seeded from the same `request.seed` [ProceduralHeroGenerator]
 * already uses, so determinism (§29.17) holds unchanged.
 *
 * **Deliberately nation-neutral, not nation-specific geography or campaign history.** The obvious
 * design would key birthplace/prior-service off `country` the way [HeroNamePools] keys names — but
 * this game imports 22 campaigns across ten centuries and every continent (Spartacus's slave army,
 * 73 BC, is a real shipped campaign). Inventing real place names or specific battle honours per
 * nation risks the exact failure `docs/design/hero-presentation.md` §3.1 calls out for portraits
 * ("every commander wears a pilotka, including Spartacus's army") — a fabricated fact that is
 * *wrong* for the nation/era is worse than a generic one that is merely unspecific. These pools are
 * generic geographic/service descriptors that read naturally for any nation or period; only the
 * NAME pools ([HeroNamePools]) and portrait gear (still open, see DEFERRED.md §6.6 item 6a) are
 * nation-specific, because names and uniforms cannot be genericised the same way.
 */
internal object HeroBiographyPools {
    private val birthplaces =
        listOf(
            "provincial_town",
            "regional_capital",
            "farming_district",
            "frontier_settlement",
            "industrial_city",
            "coastal_town",
            "mountain_village",
        )

    private val socialBackgrounds =
        listOf("worker", "peasant", "clerk", "student", "professional_soldier", "tradesman")

    private val juniorMilitaryEducation = listOf("commissioned_from_the_ranks", "reserve_officer_course")
    private val seniorMilitaryEducation = listOf("military_academy", "staff_college")

    private val priorServicePool =
        listOf("border_skirmishes", "garrison_duty", "training_command", "colonial_policing")

    // A junior officer more often has nothing notable to report yet; a senior one almost always
    // does. `INFERENCE`: no OG/design-brief source states this ratio, weighted for a biography that
    // reads more sparsely for a fresh lieutenant than a veteran colonel.
    private const val JUNIOR_PRIOR_SERVICE_CHANCE = 0.35
    private const val SENIOR_PRIOR_SERVICE_CHANCE = 0.85
    private val seniorRanks = setOf("major", "colonel")

    fun birthplaceId(seed: Int): String = SeededRandom(seed + BIRTHPLACE_SALT).pick(birthplaces) ?: birthplaces[0]

    fun socialBackgroundId(seed: Int): String =
        SeededRandom(seed + SOCIAL_SALT).pick(socialBackgrounds) ?: socialBackgrounds[0]

    /** Weighted by rank at emergence (§2.3(a)): a colonel plausibly attended a staff college; a
     *  lieutenant was commissioned from the ranks. */
    fun militaryEducationId(
        seed: Int,
        rankId: String,
    ): String {
        val pool = if (rankId in seniorRanks) seniorMilitaryEducation else juniorMilitaryEducation
        return SeededRandom(seed + EDUCATION_SALT).pick(pool) ?: pool[0]
    }

    /** Weighted by rank (a stand-in for "years of service", since [ProceduralHeroGenerator] does
     *  not track a service-start date): null more often for a junior officer, so the biography
     *  reads sparse for a fresh commission rather than claiming an invented history. */
    fun priorServiceId(
        seed: Int,
        rankId: String,
    ): String? {
        val chance = if (rankId in seniorRanks) SENIOR_PRIOR_SERVICE_CHANCE else JUNIOR_PRIOR_SERVICE_CHANCE
        val rng = SeededRandom(seed + SERVICE_SALT)
        return if (rng.roll(chance)) rng.pick(priorServicePool) else null
    }

    // Distinct salts so the four facts don't all collapse onto the same SeededRandom draw when
    // given the same base seed (each field needs its own independent pick).
    private const val BIRTHPLACE_SALT = 1
    private const val SOCIAL_SALT = 2
    private const val EDUCATION_SALT = 3
    private const val SERVICE_SALT = 4
}
