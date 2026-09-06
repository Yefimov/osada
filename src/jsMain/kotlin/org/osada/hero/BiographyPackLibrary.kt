package org.osada.hero

/**
 * The non-Soviet content families of §9, and the country/era table that resolves a player side to
 * one of them.
 *
 * §9's rule is that no finished campaign may silently fall back to generic content, and its table
 * is explicitly NOT allowed to be the only coverage test — `HeroBiographyCoverageTest` derives the
 * real list from the shipped campaign data instead. This file is the content those campaigns
 * resolve to.
 *
 * These packs are shallower than [BiographyPackSoviet]'s two, and that is the design's own
 * priority order rather than an omission. Each is still internally correct: the entries a pack
 * carries are ones that era actually had, and the ones it does not carry are simply absent rather
 * than borrowed from a neighbouring century.
 */
@Suppress("LargeClass")
internal object BiographyPackLibrary {
    // ------------------------------------------ Central European revolutions 1918-1920

    private val REVOLUTION_REGIONS =
        listOf(
            BiographyOption("industrial_city", weight = 5, provides = setOf("region:urban")),
            BiographyOption("capital_district", weight = 4, provides = setOf("region:urban", "region:capital")),
            BiographyOption("mining_settlement", weight = 3, provides = setOf("region:urban", "region:industrial")),
            BiographyOption("river_port", weight = 3, provides = setOf("region:urban")),
            BiographyOption("provincial_town", weight = 4, provides = setOf("region:rural")),
            BiographyOption("farming_district", weight = 4, provides = setOf("region:rural")),
        )

    private val REVOLUTION_SOCIAL =
        listOf(
            BiographyOption("worker", weight = 6, provides = setOf("social:worker")),
            BiographyOption("peasant", weight = 3, provides = setOf("social:peasant")),
            BiographyOption("clerk", weight = 3, provides = setOf("social:clerical")),
            BiographyOption("teacher_family", weight = 2, provides = setOf("social:educated")),
            BiographyOption("professional_soldier", weight = 2, provides = setOf("social:military")),
        )

    private val REVOLUTION_EDUCATION =
        listOf(
            BiographyOption("elementary_school", weight = 5, provides = setOf("edu:basic")),
            BiographyOption("continuation_school", weight = 4, provides = setOf("edu:basic", "edu:trade")),
            BiographyOption("trade_apprenticeship", weight = 5, provides = setOf("edu:basic", "edu:trade")),
            BiographyOption("technical_school", weight = 3, provides = setOf("edu:secondary", "edu:technical")),
            BiographyOption("gymnasium", weight = 2, provides = setOf("edu:secondary", "edu:academic")),
            BiographyOption("university_incomplete", weight = 1, provides = setOf("edu:higher", "edu:academic")),
        )

    private val REVOLUTION_PROFESSIONS =
        listOf(
            BiographyOption("machine_shop_worker", weight = 5, requiresFacts = setOf("edu:trade")),
            BiographyOption("railway_worker", weight = 4),
            BiographyOption("miner", weight = 3),
            BiographyOption("typesetter", weight = 3, requiresFacts = setOf("edu:basic")),
            BiographyOption("shipyard_worker", weight = 3, requiresFacts = setOf("edu:trade")),
            BiographyOption("farm_labourer", weight = 3),
            BiographyOption("clerk", weight = 3, requiresFacts = setOf("edu:basic")),
            BiographyOption("teacher", weight = 2, requiresFacts = setOf("edu:academic")),
            BiographyOption("engineer", weight = 1, requiresFacts = setOf("edu:technical")),
            BiographyOption(
                "wartime_soldier",
                weight = 5,
                provides = setOf("prof:career", "prof:greatwar"),
            ),
        )

    private val REVOLUTION_MILITARY_JUNIOR =
        listOf(
            BiographyOption("front_experience", weight = 5, requiresFacts = setOf("prof:greatwar")),
            BiographyOption("nco_of_the_old_army", weight = 4),
            BiographyOption("council_militia_training", weight = 4),
            BiographyOption("no_formal_training", weight = 3),
        )

    private val REVOLUTION_MILITARY_SENIOR =
        listOf(
            BiographyOption("reserve_officer_of_the_old_army", weight = 4),
            BiographyOption("war_academy", weight = 2),
            BiographyOption("front_experience", weight = 4, requiresFacts = setOf("prof:greatwar")),
        )

    private val REVOLUTION_SERVICE_ENTRIES =
        listOf(
            BiographyOption(
                "great_war_conscript",
                weight = 6,
                provides = setOf("service:greatwar", "service:prewar"),
            ),
            BiographyOption("workers_council_guard", weight = 4, provides = setOf("service:council")),
            BiographyOption("volunteer_enlistment", weight = 4, provides = setOf("service:volunteer")),
            BiographyOption("union_mobilization", weight = 2, provides = setOf("service:mobilized")),
        )

    private val REVOLUTION_WAR_ENTRIES =
        listOf(
            BiographyOption("stayed_under_arms", weight = 4, requiresFacts = setOf("service:greatwar")),
            BiographyOption("joined_the_council_forces", weight = 4),
            BiographyOption("came_home_and_took_up_arms", weight = 4, requiresFacts = setOf("service:greatwar")),
            BiographyOption("raised_a_factory_company", weight = 3, requiresFacts = setOf("service:council")),
        )

    private val REVOLUTION_PRIOR_SERVICE =
        listOf(
            BiographyOption("great_war_front", weight = 5, requiresFacts = setOf("service:greatwar")),
            BiographyOption("great_war_prisoner", weight = 2, requiresFacts = setOf("service:greatwar")),
            BiographyOption("naval_mutiny", weight = 2),
            BiographyOption("strike_committee", weight = 3),
            BiographyOption("street_fighting", weight = 3),
        )

    val CENTRAL_EUROPE_REVOLUTION =
        BiographyPack(
            id = "central_europe_revolution_1918_1920",
            regions = REVOLUTION_REGIONS,
            socialBackgrounds = REVOLUTION_SOCIAL,
            civilianEducation = REVOLUTION_EDUCATION,
            professions = REVOLUTION_PROFESSIONS,
            militaryEducationJunior = REVOLUTION_MILITARY_JUNIOR,
            militaryEducationSenior = REVOLUTION_MILITARY_SENIOR,
            serviceEntries = REVOLUTION_SERVICE_ENTRIES,
            warEntries = REVOLUTION_WAR_ENTRIES,
            politicalStatuses =
                listOf(
                    BiographyOption("non_party", weight = 4),
                    BiographyOption(
                        "party_member",
                        minimumAge = MIN_AGE_PARTY,
                        weight = 5,
                        provides = setOf("political:member"),
                    ),
                ),
            priorService = REVOLUTION_PRIOR_SERVICE,
            politicalChance = COMMON_POLITICAL_CHANCE,
        )

    // ---------------------------------------------- Russian anti-Bolshevik forces 1917-1922

    val WHITE_RUSSIA =
        BiographyPack(
            id = "white_russia_1917_1922",
            regions =
                listOf(
                    BiographyOption("don_region", weight = 5, provides = setOf("region:rural")),
                    BiographyOption("kuban", weight = 4, provides = setOf("region:rural")),
                    BiographyOption("siberia", weight = 4, provides = setOf("region:rural")),
                    BiographyOption("urals", weight = 3, provides = setOf("region:urban")),
                    BiographyOption("capital_district", weight = 4, provides = setOf("region:urban", "region:capital")),
                    BiographyOption("provincial_town", weight = 4, provides = setOf("region:rural")),
                ),
            socialBackgrounds =
                listOf(
                    BiographyOption("officer_family", weight = 5, provides = setOf("social:military")),
                    BiographyOption("landowner_family", weight = 3, provides = setOf("social:gentry")),
                    BiographyOption("cossack_family", weight = 4, provides = setOf("social:cossack")),
                    BiographyOption("clerical_family", weight = 3, provides = setOf("social:clerical")),
                    BiographyOption("merchant_family", weight = 2, provides = setOf("social:clerical")),
                    BiographyOption("peasant", weight = 2, provides = setOf("social:peasant")),
                ),
            civilianEducation =
                listOf(
                    BiographyOption("parish_school", weight = 3, provides = setOf("edu:basic")),
                    BiographyOption("real_school", weight = 4, provides = setOf("edu:secondary", "edu:academic")),
                    BiographyOption("gymnasium", weight = 4, provides = setOf("edu:secondary", "edu:academic")),
                    BiographyOption("cadet_corps", weight = 4, provides = setOf("edu:secondary", "edu:military")),
                    BiographyOption("university_incomplete", weight = 2, provides = setOf("edu:higher")),
                ),
            professions =
                listOf(
                    BiographyOption("career_officer", weight = 6, provides = setOf("prof:career")),
                    BiographyOption("estate_manager", weight = 2, requiresFacts = setOf("social:gentry")),
                    BiographyOption("civil_servant", weight = 3, requiresFacts = setOf("edu:secondary")),
                    BiographyOption("engineer", weight = 2, requiresFacts = setOf("edu:higher")),
                    BiographyOption("student", weight = 3, requiresFacts = setOf("edu:secondary")),
                    BiographyOption("cossack_smallholder", weight = 3, requiresFacts = setOf("social:cossack")),
                ),
            militaryEducationJunior =
                listOf(
                    BiographyOption("ensign_school", weight = 5),
                    BiographyOption("military_school", weight = 4, requiresFacts = setOf("edu:military")),
                    BiographyOption("commissioned_in_the_field", weight = 3),
                ),
            militaryEducationSenior =
                listOf(
                    BiographyOption("military_school", weight = 4),
                    BiographyOption("nicholas_academy", weight = 2, minimumAge = MIN_AGE_STAFF_ACADEMY),
                    BiographyOption("cossack_officer_school", weight = 3, requiresFacts = setOf("social:cossack")),
                ),
            serviceEntries =
                listOf(
                    BiographyOption(
                        "imperial_career_service",
                        weight = 5,
                        provides = setOf("service:career", "service:prewar"),
                    ),
                    BiographyOption(
                        "great_war_conscript",
                        weight = 4,
                        provides = setOf("service:greatwar", "service:prewar"),
                    ),
                    BiographyOption(
                        "cossack_levy",
                        weight = 3,
                        requiresFacts = setOf("social:cossack"),
                        provides = setOf("service:levy", "service:prewar"),
                    ),
                    BiographyOption("volunteer_enlistment", weight = 3, provides = setOf("service:volunteer")),
                ),
            warEntries =
                listOf(
                    BiographyOption("joined_the_volunteer_army", weight = 5),
                    BiographyOption("stayed_with_the_regiment", weight = 3, requiresFacts = setOf("service:prewar")),
                    BiographyOption("came_out_of_hiding", weight = 3),
                    BiographyOption("rose_with_the_stanitsa", weight = 3, requiresFacts = setOf("service:levy")),
                ),
            priorService =
                listOf(
                    BiographyOption("great_war_front", weight = 5, requiresFacts = setOf("service:prewar")),
                    BiographyOption("great_war_prisoner", weight = 2),
                    BiographyOption("ice_march", yearFrom = 1919, weight = 2),
                    BiographyOption("garrison_duty", weight = 3),
                    BiographyOption("armoured_train_service", yearFrom = 1919, weight = 2),
                ),
        )

    // ------------------------------------------------ Spanish Republic 1936-1939

    val SPANISH_REPUBLIC =
        BiographyPack(
            id = "spanish_republic_1936_1939",
            regions =
                listOf(
                    BiographyOption("madrid", weight = 4, provides = setOf("region:urban", "region:capital")),
                    BiographyOption("catalonia", weight = 4, provides = setOf("region:urban", "region:industrial")),
                    BiographyOption(
                        "basque_country",
                        weight = 3,
                        provides = setOf("region:urban", "region:industrial"),
                    ),
                    BiographyOption("asturias", weight = 3, provides = setOf("region:urban", "region:industrial")),
                    BiographyOption("andalusia", weight = 4, provides = setOf("region:rural")),
                    BiographyOption("valencia", weight = 3, provides = setOf("region:urban")),
                    BiographyOption("aragon", weight = 3, provides = setOf("region:rural")),
                ),
            socialBackgrounds =
                listOf(
                    BiographyOption("worker", weight = 6, provides = setOf("social:worker")),
                    BiographyOption("landless_labourer", weight = 5, provides = setOf("social:peasant")),
                    BiographyOption("smallholder", weight = 3, provides = setOf("social:peasant")),
                    BiographyOption("clerk", weight = 2, provides = setOf("social:clerical")),
                    BiographyOption("teacher_family", weight = 2, provides = setOf("social:educated")),
                    BiographyOption("professional_soldier", weight = 2, provides = setOf("social:military")),
                ),
            civilianEducation =
                listOf(
                    BiographyOption("no_formal_schooling", weight = 4, provides = setOf("edu:none")),
                    BiographyOption("village_school", weight = 5, provides = setOf("edu:basic")),
                    BiographyOption("workers_atheneum", weight = 4, provides = setOf("edu:basic", "edu:trade")),
                    BiographyOption("trade_apprenticeship", weight = 4, provides = setOf("edu:basic", "edu:trade")),
                    BiographyOption("secondary_school", weight = 2, provides = setOf("edu:secondary", "edu:academic")),
                    BiographyOption(
                        "university_incomplete",
                        weight = 1,
                        provides = setOf("edu:higher", "edu:academic"),
                    ),
                ),
            professions =
                listOf(
                    BiographyOption("metalworker", weight = 5, requiresFacts = setOf("edu:trade")),
                    BiographyOption("miner", weight = 4),
                    BiographyOption("dock_worker", weight = 3),
                    BiographyOption("field_hand", weight = 5),
                    BiographyOption("mason", weight = 3),
                    BiographyOption("printer", weight = 2, requiresFacts = setOf("edu:basic")),
                    BiographyOption("schoolteacher", weight = 3, requiresFacts = setOf("edu:academic")),
                    BiographyOption("union_organizer", weight = 3, provides = setOf("prof:organizer")),
                    BiographyOption(
                        "career_soldier",
                        weight = 2,
                        requiresFacts = setOf("social:military"),
                        provides = setOf("prof:career"),
                    ),
                ),
            militaryEducationJunior =
                listOf(
                    BiographyOption("militia_column_experience", weight = 5),
                    BiographyOption("popular_army_officer_course", yearFrom = 1937, weight = 4),
                    BiographyOption("conscript_service", weight = 3),
                    BiographyOption("no_formal_training", weight = 3),
                ),
            militaryEducationSenior =
                listOf(
                    BiographyOption("popular_army_officer_course", yearFrom = 1937, weight = 4),
                    BiographyOption("army_officer_school", weight = 3, requiresFacts = setOf("prof:career")),
                    BiographyOption("militia_column_experience", weight = 3),
                ),
            serviceEntries =
                listOf(
                    BiographyOption("militia_column", weight = 6, provides = setOf("service:militia")),
                    BiographyOption("union_mobilization", weight = 4, provides = setOf("service:mobilized")),
                    BiographyOption(
                        "conscript_service",
                        weight = 3,
                        provides = setOf("service:conscript", "service:prewar"),
                    ),
                    BiographyOption(
                        "career_service",
                        weight = 2,
                        requiresFacts = setOf("prof:career"),
                        provides = setOf("service:career", "service:prewar"),
                    ),
                ),
            warEntries =
                listOf(
                    BiographyOption("out_on_the_first_day", weight = 5),
                    BiographyOption(
                        "stayed_loyal_to_the_republic",
                        weight = 3,
                        requiresFacts = setOf("service:prewar"),
                    ),
                    BiographyOption("joined_the_popular_army", yearFrom = 1937, weight = 4),
                    BiographyOption(
                        "came_from_the_union_local",
                        weight = 3,
                        requiresFacts = setOf("service:mobilized"),
                    ),
                ),
            politicalStatuses =
                listOf(
                    BiographyOption("non_party", weight = 4),
                    BiographyOption(
                        "party_member",
                        minimumAge = MIN_AGE_PARTY,
                        weight = 4,
                        provides = setOf("political:member"),
                    ),
                    BiographyOption("union_member", weight = 5, provides = setOf("political:union")),
                ),
            priorService =
                listOf(
                    BiographyOption("asturias_rising", minimumAge = MIN_AGE_RECENT_RISING, weight = 2),
                    BiographyOption("barracks_assault", weight = 3),
                    BiographyOption("madrid_defence", yearFrom = 1937, weight = 3),
                    BiographyOption("moroccan_campaigns", minimumAge = MIN_AGE_COLONIAL_VETERAN, weight = 2),
                    BiographyOption("street_fighting", weight = 3),
                ),
            politicalChance = SPANISH_POLITICAL_CHANCE,
        )

    // ------------------------------------------- Yugoslav resistance 1941-1945

    val YUGOSLAV_PARTISAN =
        BiographyPack(
            id = "yugoslav_partisan_1941_1945",
            regions =
                listOf(
                    BiographyOption("bosnian_highlands", weight = 5, provides = setOf("region:rural")),
                    BiographyOption("serbian_interior", weight = 4, provides = setOf("region:rural")),
                    BiographyOption("croatian_lowlands", weight = 3, provides = setOf("region:rural")),
                    BiographyOption("dalmatian_coast", weight = 3, provides = setOf("region:urban")),
                    BiographyOption("montenegrin_mountains", weight = 3, provides = setOf("region:rural")),
                    BiographyOption(
                        "industrial_town",
                        weight = 3,
                        provides = setOf("region:urban", "region:industrial"),
                    ),
                ),
            socialBackgrounds =
                listOf(
                    BiographyOption("peasant", weight = 6, provides = setOf("social:peasant")),
                    BiographyOption("worker", weight = 4, provides = setOf("social:worker")),
                    BiographyOption("teacher_family", weight = 2, provides = setOf("social:educated")),
                    BiographyOption("clerk", weight = 2, provides = setOf("social:clerical")),
                    BiographyOption("professional_soldier", weight = 2, provides = setOf("social:military")),
                ),
            civilianEducation =
                listOf(
                    BiographyOption("village_school", weight = 6, provides = setOf("edu:basic")),
                    BiographyOption("no_formal_schooling", weight = 3, provides = setOf("edu:none")),
                    BiographyOption("trade_apprenticeship", weight = 3, provides = setOf("edu:basic", "edu:trade")),
                    BiographyOption("secondary_school", weight = 3, provides = setOf("edu:secondary", "edu:academic")),
                    BiographyOption("teachers_college", weight = 2, provides = setOf("edu:higher", "edu:academic")),
                ),
            professions =
                listOf(
                    BiographyOption("smallholder", weight = 6),
                    BiographyOption("shepherd", weight = 3),
                    BiographyOption("forester", weight = 3),
                    BiographyOption("mechanic", weight = 3, requiresFacts = setOf("edu:trade")),
                    BiographyOption("railway_worker", weight = 3),
                    BiographyOption("miner", weight = 3),
                    BiographyOption("schoolteacher", weight = 3, requiresFacts = setOf("edu:academic")),
                    BiographyOption("student", weight = 2, requiresFacts = setOf("edu:secondary")),
                    BiographyOption(
                        "royal_army_soldier",
                        weight = 3,
                        provides = setOf("prof:career", "prof:royalarmy"),
                    ),
                ),
            militaryEducationJunior =
                listOf(
                    BiographyOption("detachment_experience", weight = 6),
                    BiographyOption("royal_army_service", weight = 3, requiresFacts = setOf("prof:royalarmy")),
                    BiographyOption("partisan_officer_course", yearFrom = 1943, weight = 3),
                    BiographyOption("no_formal_training", weight = 3),
                ),
            militaryEducationSenior =
                listOf(
                    BiographyOption("partisan_officer_course", yearFrom = 1943, weight = 4),
                    BiographyOption("royal_army_officer_school", weight = 3, requiresFacts = setOf("prof:royalarmy")),
                    BiographyOption("detachment_experience", weight = 4),
                ),
            serviceEntries =
                listOf(
                    BiographyOption(
                        "royal_army_conscript",
                        weight = 4,
                        provides = setOf("service:royalarmy", "service:prewar"),
                    ),
                    BiographyOption("joined_a_detachment", weight = 6, provides = setOf("service:partisan")),
                    BiographyOption("party_mobilization", weight = 3, provides = setOf("service:mobilized")),
                    BiographyOption("escaped_internment", weight = 2, provides = setOf("service:escapee")),
                ),
            warEntries =
                listOf(
                    BiographyOption("took_to_the_hills_in_1941", weight = 5),
                    BiographyOption("after_the_village_was_burned", weight = 4),
                    BiographyOption("broke_out_of_captivity", weight = 3, requiresFacts = setOf("service:escapee")),
                    BiographyOption(
                        "came_over_from_the_old_army",
                        weight = 3,
                        requiresFacts = setOf("service:royalarmy"),
                    ),
                ),
            politicalStatuses =
                listOf(
                    BiographyOption("non_party", weight = 4),
                    BiographyOption(
                        "party_member",
                        minimumAge = MIN_AGE_PARTY,
                        weight = 5,
                        provides = setOf("political:member"),
                    ),
                ),
            priorService =
                listOf(
                    BiographyOption("april_war", weight = 4, requiresFacts = setOf("service:royalarmy")),
                    BiographyOption("first_uprising", weight = 3),
                    BiographyOption("mountain_courier", weight = 3),
                    BiographyOption("sabotage_group", weight = 3),
                    BiographyOption("spain_volunteer", minimumAge = MIN_AGE_FOREIGN_VOLUNTEER, weight = 1),
                ),
            politicalChance = COMMON_POLITICAL_CHANCE,
        )

    // ------------------------------------------------------- Greece 1940-1949

    val GREEK =
        BiographyPack(
            id = "greece_1940_1949",
            regions =
                listOf(
                    BiographyOption("epirus", weight = 4, provides = setOf("region:rural")),
                    BiographyOption("macedonia", weight = 4, provides = setOf("region:rural")),
                    BiographyOption("thessaly", weight = 3, provides = setOf("region:rural")),
                    BiographyOption("peloponnese", weight = 3, provides = setOf("region:rural")),
                    BiographyOption("athens_piraeus", weight = 4, provides = setOf("region:urban", "region:capital")),
                    BiographyOption("aegean_islands", weight = 3, provides = setOf("region:rural")),
                ),
            socialBackgrounds =
                listOf(
                    BiographyOption("peasant", weight = 5, provides = setOf("social:peasant")),
                    BiographyOption("worker", weight = 4, provides = setOf("social:worker")),
                    BiographyOption("refugee_family", weight = 3, provides = setOf("social:refugee")),
                    BiographyOption("clerk", weight = 3, provides = setOf("social:clerical")),
                    BiographyOption("professional_soldier", weight = 2, provides = setOf("social:military")),
                ),
            civilianEducation =
                listOf(
                    BiographyOption("village_school", weight = 6, provides = setOf("edu:basic")),
                    BiographyOption("secondary_school", weight = 4, provides = setOf("edu:secondary", "edu:academic")),
                    BiographyOption("trade_apprenticeship", weight = 3, provides = setOf("edu:basic", "edu:trade")),
                    BiographyOption("teachers_college", weight = 2, provides = setOf("edu:higher", "edu:academic")),
                    BiographyOption(
                        "university_incomplete",
                        weight = 1,
                        provides = setOf("edu:higher", "edu:academic"),
                    ),
                ),
            professions =
                listOf(
                    BiographyOption("smallholder", weight = 5),
                    BiographyOption("shepherd", weight = 4),
                    BiographyOption("fisherman", weight = 3),
                    BiographyOption("dock_worker", weight = 3),
                    BiographyOption("mechanic", weight = 3, requiresFacts = setOf("edu:trade")),
                    BiographyOption("schoolteacher", weight = 3, requiresFacts = setOf("edu:academic")),
                    BiographyOption("clerk", weight = 2, requiresFacts = setOf("edu:secondary")),
                    BiographyOption("career_soldier", weight = 3, provides = setOf("prof:career")),
                ),
            militaryEducationJunior =
                listOf(
                    BiographyOption("reserve_officer_course", weight = 4),
                    BiographyOption("conscript_service", weight = 5),
                    BiographyOption("commissioned_in_the_field", weight = 3),
                    BiographyOption("andarte_detachment", yearFrom = 1942, weight = 3),
                ),
            militaryEducationSenior =
                listOf(
                    BiographyOption("military_academy", weight = 4, requiresFacts = setOf("prof:career")),
                    BiographyOption("reserve_officer_course", weight = 3),
                    BiographyOption("andarte_detachment", yearFrom = 1942, weight = 3),
                ),
            serviceEntries =
                listOf(
                    BiographyOption(
                        "conscript_service",
                        weight = 5,
                        provides = setOf("service:conscript", "service:prewar"),
                    ),
                    BiographyOption(
                        "career_service",
                        weight = 3,
                        requiresFacts = setOf("prof:career"),
                        provides = setOf("service:career", "service:prewar"),
                    ),
                    BiographyOption("volunteer_enlistment", weight = 3, provides = setOf("service:volunteer")),
                    BiographyOption(
                        "resistance_group",
                        yearFrom = 1942,
                        weight = 3,
                        provides = setOf("service:partisan"),
                    ),
                ),
            warEntries =
                listOf(
                    BiographyOption("mobilized_in_october_1940", weight = 5, requiresFacts = setOf("service:prewar")),
                    BiographyOption("volunteered_at_outbreak", weight = 4, excludesFacts = setOf("service:prewar")),
                    BiographyOption("went_to_the_mountains", yearFrom = 1942, weight = 3),
                    BiographyOption("already_serving", weight = 3, requiresFacts = setOf("service:career")),
                ),
            priorService =
                listOf(
                    BiographyOption("asia_minor_campaign", minimumAge = MIN_AGE_ASIA_MINOR, weight = 2),
                    BiographyOption("pindus_front", yearFrom = 1941, weight = 4),
                    BiographyOption("border_fortifications", weight = 3),
                    BiographyOption("mountain_courier", yearFrom = 1942, weight = 3),
                    BiographyOption("garrison_duty", weight = 3),
                ),
        )

    private const val COMMON_POLITICAL_CHANCE = 0.6
    private const val SPANISH_POLITICAL_CHANCE = 0.8
    private const val MIN_AGE_PARTY = 22
    private const val MIN_AGE_STAFF_ACADEMY = 34
    private const val MIN_AGE_RECENT_RISING = 24
    private const val MIN_AGE_COLONIAL_VETERAN = 32
    private const val MIN_AGE_FOREIGN_VOLUNTEER = 28
    private const val MIN_AGE_ASIA_MINOR = 40
}
