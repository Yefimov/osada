package org.osada.hero

/**
 * Resolves a player side and a campaign year to the content family that supplies its life paths —
 * §9's coverage contract.
 *
 * ## Why (country, year) and not country alone
 *
 * Three of the shipped country ids mean different armies in different decades, and one of them
 * means different *centuries*. Country 19 is the Soviet Union in 1936 and in 1945; country 7 is
 * revolutionary Germany in 1918 and the Wehrmacht in 1942; country 103 is Red Russia only during
 * the Civil War. A country-keyed map would have to pick one and be wrong about the other, so the
 * key is the pair and the table is a list of ranges searched in order.
 *
 * ## The fallback is a real pack, not a shrug
 *
 * [GENERIC] exists for a modded or standalone-scenario country nobody authored, and it is
 * deliberately era-neutral rather than quietly Soviet. §9's acceptance gate does NOT accept it for
 * a visible finished campaign — `HeroBiographyCoverageTest` enumerates those campaigns from the
 * shipped data and fails if any of them lands here. That is the point of separating "we have no
 * content for this" from "we have content and it is generic".
 */
internal object BiographyPacks {
    /** One row of the resolution table: a country set, an inclusive year window, and the pack. */
    private data class Rule(
        val countries: Set<Int>,
        val yearFrom: Int,
        val yearTo: Int,
        val pack: BiographyPack,
    )

    /**
     * Era-neutral last resort. It carries only facts that are true of practically any modern army,
     * so an unmapped country reads as unspecific rather than as someone else's history.
     */
    val GENERIC =
        BiographyPack(
            id = "generic_v1",
            regions =
                listOf(
                    BiographyOption("provincial_town", weight = 4, provides = setOf("region:rural")),
                    BiographyOption("regional_capital", weight = 3, provides = setOf("region:urban")),
                    BiographyOption("farming_district", weight = 4, provides = setOf("region:rural")),
                    BiographyOption("frontier_settlement", weight = 2, provides = setOf("region:frontier")),
                    BiographyOption(
                        "industrial_city",
                        weight = 4,
                        provides = setOf("region:urban", "region:industrial"),
                    ),
                    BiographyOption("coastal_town", weight = 3, provides = setOf("region:urban")),
                    BiographyOption("mountain_village", weight = 2, provides = setOf("region:rural")),
                ),
            socialBackgrounds =
                listOf(
                    BiographyOption("worker", weight = 4, provides = setOf("social:worker")),
                    BiographyOption("peasant", weight = 4, provides = setOf("social:peasant")),
                    BiographyOption("clerk", weight = 3, provides = setOf("social:clerical")),
                    BiographyOption("student", weight = 2, provides = setOf("social:educated")),
                    BiographyOption("professional_soldier", weight = 3, provides = setOf("social:military")),
                    BiographyOption("tradesman", weight = 3, provides = setOf("social:clerical")),
                ),
            civilianEducation =
                listOf(
                    BiographyOption("elementary_school", weight = 5, provides = setOf("edu:basic")),
                    BiographyOption("trade_apprenticeship", weight = 4, provides = setOf("edu:basic", "edu:trade")),
                    BiographyOption("secondary_school", weight = 3, provides = setOf("edu:secondary", "edu:academic")),
                    BiographyOption("technical_school", weight = 2, provides = setOf("edu:secondary", "edu:technical")),
                ),
            professions =
                listOf(
                    BiographyOption("farmer", weight = 4),
                    BiographyOption("machine_fitter", weight = 4, requiresFacts = setOf("edu:trade")),
                    BiographyOption("railway_worker", weight = 3),
                    BiographyOption("clerk", weight = 3, requiresFacts = setOf("edu:basic")),
                    BiographyOption("schoolteacher", weight = 2, requiresFacts = setOf("edu:academic")),
                    BiographyOption("surveyor", weight = 2, requiresFacts = setOf("edu:technical")),
                    BiographyOption("student", weight = 2, requiresFacts = setOf("edu:secondary")),
                    BiographyOption(
                        "career_soldier",
                        weight = 3,
                        requiresFacts = setOf("social:military"),
                        provides = setOf("prof:career"),
                    ),
                ),
            militaryEducationJunior =
                listOf(
                    BiographyOption("commissioned_from_the_ranks", weight = 4),
                    BiographyOption("reserve_officer_course", weight = 4),
                    BiographyOption("officer_candidate_school", weight = 3),
                ),
            militaryEducationSenior =
                listOf(
                    BiographyOption("military_academy", weight = 4),
                    BiographyOption("staff_college", weight = 3),
                ),
            serviceEntries =
                listOf(
                    BiographyOption("career_service", weight = 3, provides = setOf("service:career", "service:prewar")),
                    BiographyOption("conscripted", weight = 4, provides = setOf("service:conscript", "service:prewar")),
                    BiographyOption("volunteer_enlistment", weight = 3, provides = setOf("service:volunteer")),
                ),
            warEntries =
                listOf(
                    BiographyOption("already_serving", weight = 4, requiresFacts = setOf("service:prewar")),
                    BiographyOption("volunteered_at_outbreak", weight = 3, excludesFacts = setOf("service:prewar")),
                    BiographyOption("mobilized_reserve", weight = 3, requiresFacts = setOf("service:prewar")),
                ),
            priorService =
                listOf(
                    BiographyOption("garrison_duty", weight = 4),
                    BiographyOption("training_command", weight = 3),
                    BiographyOption("border_service", weight = 3),
                ),
        )

    /**
     * Searched top to bottom, first match wins. Ordering matters where windows overlap: the
     * revolutionary and Civil War rows are written above their peacetime successors so a 1919
     * German or a 1919 Russian never falls into a WWII pack.
     */
    private val RULES: List<Rule> =
        listOf(
            // --- Priority 1: Soviet and Red Russia -------------------------------------------
            Rule(setOf(103), 1917, 1922, BiographyPackSoviet.RED_RUSSIA_1917_1922),
            Rule(setOf(19, 61, 89), 1917, 1922, BiographyPackSoviet.RED_RUSSIA_1917_1922),
            Rule(setOf(19, 61, 89), 1923, 1960, BiographyPackSoviet.SOVIET_1930_1945),
            // --- Priority 2 -------------------------------------------------------------------
            Rule(setOf(100), 1917, 1922, BiographyPackLibrary.WHITE_RUSSIA),
            Rule(setOf(144), 1914, 1922, BiographyPackLibraryB.CZECHOSLOVAK_LEGION),
            Rule(setOf(187, 188), 1917, 1923, BiographyPackLibrary.CENTRAL_EUROPE_REVOLUTION),
            Rule(setOf(4, 7, 12), 1917, 1923, BiographyPackLibrary.CENTRAL_EUROPE_REVOLUTION),
            Rule(setOf(196), 1840, 1870, BiographyPackLibraryB.FORTY_EIGHTER),
            Rule(setOf(7), 1840, 1870, BiographyPackLibraryB.FORTY_EIGHTER),
            Rule(setOf(226), 1931, 1945, BiographyPackLibrary.SPANISH_REPUBLIC),
            Rule(setOf(43), 1941, 1946, BiographyPackLibrary.YUGOSLAV_PARTISAN),
            Rule(setOf(39), 1939, 1950, BiographyPackLibrary.GREEK),
            // --- Priority 3 -------------------------------------------------------------------
            Rule(setOf(9), 1900, 1960, BiographyPackLibraryB.UNITED_STATES_WW2),
            Rule(setOf(4, 6, 7, 12, 13, 20, 22, 38), 1924, 1960, BiographyPackLibraryB.WESTERN_EUROPE_WW2),
            Rule(setOf(21, 25, 276), 1900, 1980, BiographyPackLibraryB.EAST_ASIAN_REVOLUTION),
            // --- Last: antiquity ---------------------------------------------------------------
            Rule(setOf(310), MIN_YEAR, 1, BiographyPackLibraryB.ANCIENT_REBEL),
        )

    /** Every authored pack, for the coverage and content tests. [GENERIC] is included last. */
    val ALL: List<BiographyPack> = RULES.map { it.pack }.distinct() + GENERIC

    /**
     * The pack for this player side, or [GENERIC] when nothing is authored for it.
     *
     * A null [year] cannot match any dated rule, so it falls through to [GENERIC] rather than
     * guessing an era — which is the same conservatism the fact-level chronology checks apply.
     */
    fun forCountry(
        country: Int?,
        year: Int?,
    ): BiographyPack {
        if (country == null || year == null) return GENERIC
        return RULES.firstOrNull { country in it.countries && year >= it.yearFrom && year <= it.yearTo }?.pack
            ?: GENERIC
    }

    /** True when [forCountry] found real content — §9's gate asserts this for finished campaigns. */
    fun isAuthored(
        country: Int?,
        year: Int?,
    ): Boolean = forCountry(country, year).id != GENERIC.id

    fun byId(id: String): BiographyPack? = ALL.firstOrNull { it.id == id }

    /** Below any campaign this game ships, so the ancient rule's window is open-ended downwards. */
    private const val MIN_YEAR = -3000
}
