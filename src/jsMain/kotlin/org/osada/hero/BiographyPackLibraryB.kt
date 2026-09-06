package org.osada.hero

/**
 * The remaining §9 content families: priority-3 twentieth-century packs, the Czechoslovak Legion,
 * the Forty-Eighters, and the ancient life path §9.1 insists must not borrow modern language.
 *
 * Split from [BiographyPackLibrary] only to keep either file from becoming the single place a
 * whole century of content lives; the two are one library and are joined in [BiographyPacks].
 */
@Suppress("LargeClass")
internal object BiographyPackLibraryB {
    // ------------------------------------------ Western/Central European WWII

    val WESTERN_EUROPE_WW2 =
        BiographyPack(
            id = "western_europe_ww2",
            regions =
                listOf(
                    BiographyOption(
                        "industrial_region",
                        weight = 5,
                        provides = setOf("region:urban", "region:industrial"),
                    ),
                    BiographyOption("capital_district", weight = 4, provides = setOf("region:urban", "region:capital")),
                    BiographyOption("provincial_town", weight = 5, provides = setOf("region:rural")),
                    BiographyOption("farming_district", weight = 5, provides = setOf("region:rural")),
                    BiographyOption("port_city", weight = 3, provides = setOf("region:urban")),
                    BiographyOption("mountain_district", weight = 2, provides = setOf("region:rural")),
                ),
            socialBackgrounds =
                listOf(
                    BiographyOption("worker", weight = 5, provides = setOf("social:worker")),
                    BiographyOption("farming_family", weight = 4, provides = setOf("social:peasant")),
                    BiographyOption("clerk", weight = 4, provides = setOf("social:clerical")),
                    BiographyOption("teacher_family", weight = 2, provides = setOf("social:educated")),
                    BiographyOption("professional_soldier", weight = 3, provides = setOf("social:military")),
                    BiographyOption("shopkeeper_family", weight = 3, provides = setOf("social:clerical")),
                ),
            civilianEducation =
                listOf(
                    BiographyOption("elementary_school", weight = 5, provides = setOf("edu:basic")),
                    BiographyOption("trade_apprenticeship", weight = 5, provides = setOf("edu:basic", "edu:trade")),
                    BiographyOption("technical_school", weight = 4, provides = setOf("edu:secondary", "edu:technical")),
                    BiographyOption("secondary_school", weight = 4, provides = setOf("edu:secondary", "edu:academic")),
                    BiographyOption(
                        "university_incomplete",
                        weight = 2,
                        provides = setOf("edu:higher", "edu:academic"),
                    ),
                    BiographyOption("university", weight = 2, provides = setOf("edu:higher", "edu:technical")),
                ),
            professions =
                listOf(
                    BiographyOption("machine_fitter", weight = 5, requiresFacts = setOf("edu:trade")),
                    BiographyOption("motor_mechanic", weight = 4, requiresFacts = setOf("edu:trade")),
                    BiographyOption("railway_worker", weight = 4),
                    BiographyOption("farmer", weight = 4),
                    BiographyOption("bank_clerk", weight = 3, requiresFacts = setOf("edu:secondary")),
                    BiographyOption("surveyor", weight = 2, requiresFacts = setOf("edu:technical")),
                    BiographyOption("schoolteacher", weight = 3, requiresFacts = setOf("edu:academic")),
                    BiographyOption("engineer", weight = 2, requiresFacts = setOf("edu:higher")),
                    BiographyOption("student", weight = 3, requiresFacts = setOf("edu:secondary")),
                    BiographyOption(
                        "career_soldier",
                        weight = 4,
                        requiresFacts = setOf("social:military"),
                        provides = setOf("prof:career"),
                    ),
                ),
            militaryEducationJunior =
                listOf(
                    BiographyOption("officer_candidate_school", weight = 5),
                    BiographyOption("reserve_officer_course", weight = 4),
                    BiographyOption("commissioned_from_the_ranks", weight = 3),
                    BiographyOption("flying_school", weight = 4, unitClasses = AIR_BRANCHES),
                ),
            militaryEducationSenior =
                listOf(
                    BiographyOption("war_academy", weight = 4),
                    BiographyOption("staff_college", weight = 4),
                    BiographyOption("armour_school", weight = 2, unitClasses = ARMOUR_BRANCHES),
                ),
            serviceEntries =
                listOf(
                    BiographyOption("career_service", weight = 4, provides = setOf("service:career", "service:prewar")),
                    BiographyOption("conscripted", weight = 5, provides = setOf("service:conscript", "service:prewar")),
                    BiographyOption("volunteer_enlistment", weight = 3, provides = setOf("service:volunteer")),
                    BiographyOption(
                        "reserve_commission",
                        weight = 3,
                        provides = setOf("service:reserve", "service:prewar"),
                    ),
                ),
            warEntries =
                listOf(
                    BiographyOption("already_serving", weight = 5, requiresFacts = setOf("service:prewar")),
                    BiographyOption("mobilized_reserve", weight = 4, requiresFacts = setOf("service:reserve")),
                    BiographyOption("volunteered_at_outbreak", weight = 3, excludesFacts = setOf("service:prewar")),
                    BiographyOption("called_up_with_the_class", weight = 3, requiresFacts = setOf("service:conscript")),
                ),
            priorService =
                listOf(
                    BiographyOption("great_war_front", minimumAge = MIN_AGE_GREAT_WAR_VETERAN, weight = 2),
                    BiographyOption("colonial_service", minimumAge = MIN_AGE_COLONIAL, weight = 2),
                    BiographyOption("garrison_duty", weight = 4),
                    BiographyOption("training_command", weight = 3),
                    BiographyOption("border_service", weight = 3),
                    BiographyOption("spain_observer", yearFrom = 1940, minimumAge = MIN_AGE_COLONIAL, weight = 1),
                ),
        )

    val UNITED_STATES_WW2 =
        WESTERN_EUROPE_WW2.copy(
            id = "united_states_ww2",
            regions =
                listOf(
                    BiographyOption("new_england", weight = 3, provides = setOf("region:urban")),
                    BiographyOption("midwest_farmland", weight = 5, provides = setOf("region:rural")),
                    BiographyOption(
                        "great_lakes_industry",
                        weight = 4,
                        provides = setOf("region:urban", "region:industrial"),
                    ),
                    BiographyOption(
                        "appalachian_coal",
                        weight = 3,
                        provides = setOf("region:rural", "region:industrial"),
                    ),
                    BiographyOption("deep_south", weight = 4, provides = setOf("region:rural")),
                    BiographyOption("west_coast", weight = 3, provides = setOf("region:urban")),
                    BiographyOption("plains_states", weight = 3, provides = setOf("region:rural")),
                ),
            professions =
                listOf(
                    BiographyOption("machine_fitter", weight = 4, requiresFacts = setOf("edu:trade")),
                    BiographyOption("motor_mechanic", weight = 5, requiresFacts = setOf("edu:trade")),
                    BiographyOption("railway_worker", weight = 3),
                    BiographyOption("farmer", weight = 5),
                    BiographyOption("bank_clerk", weight = 3, requiresFacts = setOf("edu:secondary")),
                    BiographyOption("surveyor", weight = 2, requiresFacts = setOf("edu:technical")),
                    BiographyOption("schoolteacher", weight = 3, requiresFacts = setOf("edu:academic")),
                    BiographyOption("engineer", weight = 2, requiresFacts = setOf("edu:higher")),
                    BiographyOption("student", weight = 4, requiresFacts = setOf("edu:secondary")),
                    BiographyOption("ccc_camp_worker", weight = 3),
                    BiographyOption(
                        "career_soldier",
                        weight = 3,
                        requiresFacts = setOf("social:military"),
                        provides = setOf("prof:career"),
                    ),
                ),
            serviceEntries =
                listOf(
                    BiographyOption("career_service", weight = 3, provides = setOf("service:career", "service:prewar")),
                    BiographyOption("selective_service", weight = 5, provides = setOf("service:conscript")),
                    BiographyOption("volunteer_enlistment", weight = 4, provides = setOf("service:volunteer")),
                    BiographyOption(
                        "national_guard",
                        weight = 4,
                        provides = setOf("service:reserve", "service:prewar"),
                    ),
                ),
            warEntries =
                listOf(
                    BiographyOption("already_serving", weight = 4, requiresFacts = setOf("service:prewar")),
                    BiographyOption("federalized_with_the_guard", weight = 4, requiresFacts = setOf("service:reserve")),
                    BiographyOption("enlisted_after_pearl_harbor", weight = 5, excludesFacts = setOf("service:prewar")),
                    BiographyOption("called_up_with_the_class", weight = 3, requiresFacts = setOf("service:conscript")),
                ),
        )

    // -------------------------------------------- East Asian revolutionary wars

    val EAST_ASIAN_REVOLUTION =
        BiographyPack(
            id = "east_asian_revolution",
            regions =
                listOf(
                    BiographyOption("rice_growing_delta", weight = 5, provides = setOf("region:rural")),
                    BiographyOption("upland_district", weight = 4, provides = setOf("region:rural")),
                    BiographyOption("river_town", weight = 3, provides = setOf("region:urban")),
                    BiographyOption("coastal_province", weight = 3, provides = setOf("region:urban")),
                    BiographyOption(
                        "mining_district",
                        weight = 3,
                        provides = setOf("region:rural", "region:industrial"),
                    ),
                    BiographyOption(
                        "provincial_capital",
                        weight = 3,
                        provides = setOf("region:urban", "region:capital"),
                    ),
                ),
            socialBackgrounds =
                listOf(
                    BiographyOption("tenant_farmer_family", weight = 6, provides = setOf("social:peasant")),
                    BiographyOption("worker", weight = 4, provides = setOf("social:worker")),
                    BiographyOption("village_schoolmaster_family", weight = 2, provides = setOf("social:educated")),
                    BiographyOption("shopkeeper_family", weight = 2, provides = setOf("social:clerical")),
                    BiographyOption("soldier_family", weight = 2, provides = setOf("social:military")),
                ),
            civilianEducation =
                listOf(
                    BiographyOption("no_formal_schooling", weight = 4, provides = setOf("edu:none")),
                    BiographyOption("village_school", weight = 6, provides = setOf("edu:basic")),
                    BiographyOption("literacy_class", weight = 4, provides = setOf("edu:basic")),
                    BiographyOption("middle_school", weight = 3, provides = setOf("edu:secondary", "edu:academic")),
                    BiographyOption("normal_school", weight = 2, provides = setOf("edu:higher", "edu:academic")),
                ),
            professions =
                listOf(
                    BiographyOption("tenant_farmer", weight = 6),
                    BiographyOption("porter", weight = 3),
                    BiographyOption("boatman", weight = 3),
                    BiographyOption("mine_labourer", weight = 3),
                    BiographyOption("village_teacher", weight = 3, requiresFacts = setOf("edu:academic")),
                    BiographyOption("printer", weight = 2, requiresFacts = setOf("edu:basic")),
                    BiographyOption("mechanic", weight = 2, requiresFacts = setOf("edu:basic")),
                    BiographyOption("student", weight = 3, requiresFacts = setOf("edu:secondary")),
                    BiographyOption("village_organizer", weight = 4, provides = setOf("prof:organizer")),
                ),
            militaryEducationJunior =
                listOf(
                    BiographyOption("detachment_experience", weight = 6),
                    BiographyOption("political_and_military_course", weight = 4),
                    BiographyOption("no_formal_training", weight = 3),
                ),
            militaryEducationSenior =
                listOf(
                    BiographyOption("political_and_military_course", weight = 5),
                    BiographyOption("staff_training_class", weight = 3),
                    BiographyOption("detachment_experience", weight = 4),
                ),
            serviceEntries =
                listOf(
                    BiographyOption("joined_a_detachment", weight = 6, provides = setOf("service:partisan")),
                    BiographyOption("village_militia", weight = 4, provides = setOf("service:militia")),
                    BiographyOption("party_mobilization", weight = 3, provides = setOf("service:mobilized")),
                    BiographyOption(
                        "former_regular_soldier",
                        weight = 2,
                        provides = setOf("service:regular", "service:prewar"),
                    ),
                ),
            warEntries =
                listOf(
                    BiographyOption("joined_the_liberation_forces", weight = 5),
                    BiographyOption("after_the_village_was_burned", weight = 4),
                    BiographyOption(
                        "came_over_from_the_old_army",
                        weight = 3,
                        requiresFacts = setOf("service:regular"),
                    ),
                    BiographyOption("sent_by_the_committee", weight = 3, requiresFacts = setOf("service:mobilized")),
                ),
            politicalStatuses =
                listOf(
                    BiographyOption("non_party", weight = 3),
                    BiographyOption(
                        "party_member",
                        minimumAge = MIN_AGE_PARTY,
                        weight = 6,
                        provides = setOf("political:member"),
                    ),
                ),
            priorService =
                listOf(
                    BiographyOption("anti_japanese_resistance", weight = 4),
                    BiographyOption("long_march", yearFrom = 1936, minimumAge = MIN_AGE_LONG_MARCH, weight = 2),
                    BiographyOption("base_area_defence", weight = 4),
                    BiographyOption("courier_network", weight = 3),
                    BiographyOption("sabotage_group", weight = 3),
                ),
            politicalChance = EAST_ASIAN_POLITICAL_CHANCE,
        )

    // -------------------------------------------- Czechoslovak Legion 1917-1920

    val CZECHOSLOVAK_LEGION =
        BiographyPack(
            id = "czechoslovak_legion_1917_1920",
            regions =
                listOf(
                    BiographyOption(
                        "bohemian_industry",
                        weight = 5,
                        provides = setOf("region:urban", "region:industrial"),
                    ),
                    BiographyOption("moravian_countryside", weight = 4, provides = setOf("region:rural")),
                    BiographyOption("slovak_uplands", weight = 3, provides = setOf("region:rural")),
                    BiographyOption("prague", weight = 3, provides = setOf("region:urban", "region:capital")),
                    BiographyOption("provincial_town", weight = 4, provides = setOf("region:rural")),
                ),
            socialBackgrounds =
                listOf(
                    BiographyOption("worker", weight = 5, provides = setOf("social:worker")),
                    BiographyOption("peasant", weight = 4, provides = setOf("social:peasant")),
                    BiographyOption("clerk", weight = 3, provides = setOf("social:clerical")),
                    BiographyOption("teacher_family", weight = 3, provides = setOf("social:educated")),
                    BiographyOption("tradesman", weight = 3, provides = setOf("social:clerical")),
                ),
            civilianEducation =
                listOf(
                    BiographyOption("elementary_school", weight = 5, provides = setOf("edu:basic")),
                    BiographyOption("trade_apprenticeship", weight = 5, provides = setOf("edu:basic", "edu:trade")),
                    BiographyOption("technical_school", weight = 3, provides = setOf("edu:secondary", "edu:technical")),
                    BiographyOption("gymnasium", weight = 3, provides = setOf("edu:secondary", "edu:academic")),
                    BiographyOption(
                        "university_incomplete",
                        weight = 2,
                        provides = setOf("edu:higher", "edu:academic"),
                    ),
                ),
            professions =
                listOf(
                    BiographyOption("machine_fitter", weight = 5, requiresFacts = setOf("edu:trade")),
                    BiographyOption("railway_worker", weight = 4),
                    BiographyOption("brewery_worker", weight = 2, requiresFacts = setOf("edu:trade")),
                    BiographyOption("farmer", weight = 4),
                    BiographyOption("clerk", weight = 3, requiresFacts = setOf("edu:basic")),
                    BiographyOption("schoolteacher", weight = 3, requiresFacts = setOf("edu:academic")),
                    BiographyOption("engineer", weight = 2, requiresFacts = setOf("edu:technical")),
                    BiographyOption("student", weight = 3, requiresFacts = setOf("edu:secondary")),
                ),
            militaryEducationJunior =
                listOf(
                    BiographyOption("austro_hungarian_nco", weight = 5, provides = setOf("edu:oldarmy")),
                    BiographyOption("legion_officer_course", weight = 4),
                    BiographyOption("commissioned_in_the_field", weight = 4),
                ),
            militaryEducationSenior =
                listOf(
                    BiographyOption("austro_hungarian_reserve_officer", weight = 4, provides = setOf("edu:oldarmy")),
                    BiographyOption("legion_officer_course", weight = 4),
                    BiographyOption("russian_military_school", weight = 2),
                ),
            serviceEntries =
                listOf(
                    BiographyOption(
                        "austro_hungarian_conscript",
                        weight = 6,
                        provides = setOf("service:oldarmy", "service:prewar"),
                    ),
                    BiographyOption("volunteer_druzhina", weight = 3, provides = setOf("service:volunteer")),
                    BiographyOption("emigre_volunteer", weight = 2, provides = setOf("service:volunteer")),
                ),
            warEntries =
                listOf(
                    BiographyOption(
                        "taken_prisoner_and_volunteered",
                        weight = 6,
                        requiresFacts = setOf("service:oldarmy"),
                    ),
                    BiographyOption("crossed_the_lines", weight = 4, requiresFacts = setOf("service:oldarmy")),
                    BiographyOption("joined_from_the_colony", weight = 2, requiresFacts = setOf("service:volunteer")),
                    BiographyOption("stayed_under_arms", weight = 3),
                ),
            priorService =
                listOf(
                    BiographyOption("great_war_front", weight = 5, requiresFacts = setOf("service:prewar")),
                    BiographyOption("great_war_prisoner", weight = 5, requiresFacts = setOf("service:oldarmy")),
                    BiographyOption("zborov", yearFrom = 1918, weight = 3),
                    BiographyOption("armoured_train_service", yearFrom = 1918, weight = 3),
                    BiographyOption("railway_guard", weight = 4),
                ),
        )

    // ------------------------------------------------- Forty-Eighters 1848-1865

    val FORTY_EIGHTER =
        BiographyPack(
            id = "forty_eighter_1848_1865",
            regions =
                listOf(
                    BiographyOption("rhineland", weight = 4, provides = setOf("region:urban")),
                    BiographyOption("baden", weight = 5, provides = setOf("region:rural")),
                    BiographyOption("saxony", weight = 3, provides = setOf("region:urban", "region:industrial")),
                    BiographyOption("palatinate", weight = 4, provides = setOf("region:rural")),
                    BiographyOption("free_city", weight = 3, provides = setOf("region:urban")),
                    BiographyOption("emigre_settlement", yearFrom = 1852, weight = 3, provides = setOf("region:urban")),
                ),
            socialBackgrounds =
                listOf(
                    BiographyOption("artisan_family", weight = 5, provides = setOf("social:worker")),
                    BiographyOption("peasant", weight = 4, provides = setOf("social:peasant")),
                    BiographyOption("burgher_family", weight = 4, provides = setOf("social:clerical")),
                    BiographyOption("teacher_family", weight = 3, provides = setOf("social:educated")),
                    BiographyOption("officer_family", weight = 2, provides = setOf("social:military")),
                ),
            civilianEducation =
                listOf(
                    BiographyOption("village_school", weight = 5, provides = setOf("edu:basic")),
                    BiographyOption("guild_apprenticeship", weight = 5, provides = setOf("edu:basic", "edu:trade")),
                    BiographyOption("gymnasium", weight = 4, provides = setOf("edu:secondary", "edu:academic")),
                    BiographyOption("polytechnic", weight = 2, provides = setOf("edu:secondary", "edu:technical")),
                    BiographyOption("university", weight = 3, provides = setOf("edu:higher", "edu:academic")),
                ),
            professions =
                listOf(
                    BiographyOption("journeyman_artisan", weight = 5, requiresFacts = setOf("edu:trade")),
                    BiographyOption("printer", weight = 3, requiresFacts = setOf("edu:trade")),
                    BiographyOption("farmer", weight = 4),
                    BiographyOption("lawyer", weight = 2, requiresFacts = setOf("edu:higher")),
                    BiographyOption("journalist", weight = 3, requiresFacts = setOf("edu:academic")),
                    BiographyOption("schoolteacher", weight = 3, requiresFacts = setOf("edu:academic")),
                    BiographyOption("surveyor", weight = 2, requiresFacts = setOf("edu:technical")),
                    BiographyOption("student", weight = 4, requiresFacts = setOf("edu:secondary")),
                    BiographyOption(
                        "line_officer",
                        weight = 3,
                        requiresFacts = setOf("social:military"),
                        provides = setOf("prof:career"),
                    ),
                ),
            militaryEducationJunior =
                listOf(
                    BiographyOption("militia_drill", weight = 5),
                    BiographyOption("conscript_service", weight = 4),
                    BiographyOption("no_formal_training", weight = 3),
                ),
            militaryEducationSenior =
                listOf(
                    BiographyOption("cadet_institute", weight = 3, requiresFacts = setOf("prof:career")),
                    BiographyOption(
                        "artillery_and_engineering_school",
                        weight = 2,
                        requiresFacts = setOf("edu:technical"),
                    ),
                    BiographyOption("militia_drill", weight = 4),
                ),
            serviceEntries =
                listOf(
                    BiographyOption("civic_guard", weight = 5, provides = setOf("service:militia")),
                    BiographyOption(
                        "line_regiment_conscript",
                        weight = 4,
                        provides = setOf("service:conscript", "service:prewar"),
                    ),
                    BiographyOption("freischar_volunteer", weight = 5, provides = setOf("service:volunteer")),
                ),
            warEntries =
                listOf(
                    BiographyOption("out_with_the_committee", weight = 5),
                    BiographyOption("regiment_went_over", weight = 3, requiresFacts = setOf("service:prewar")),
                    BiographyOption("came_back_from_exile", yearFrom = 1852, weight = 3),
                    BiographyOption("volunteered_at_outbreak", weight = 4),
                ),
            priorService =
                listOf(
                    BiographyOption("march_days", weight = 4),
                    BiographyOption("barricades", weight = 4),
                    BiographyOption("baden_campaign", yearFrom = 1850, weight = 3),
                    BiographyOption("garrison_duty", weight = 3),
                    BiographyOption("emigre_drill_company", yearFrom = 1855, weight = 2),
                ),
        )

    // ------------------------------------------------------- Ancient rebel army

    /**
     * §9.1: no schooling, no party, no conscription, no dated enlistment — a path that is short but
     * internally correct rather than a twentieth-century one with the nouns swapped. The pack
     * therefore leaves [BiographyPack.civilianEducation] and [BiographyPack.politicalStatuses]
     * empty and turns [BiographyPack.tracksServiceStartYear] off; the narrator omits those clauses
     * rather than substituting anything.
     */
    val ANCIENT_REBEL =
        BiographyPack(
            id = "ancient_rebel",
            regions =
                listOf(
                    BiographyOption("thrace", weight = 5, provides = setOf("region:barbaricum")),
                    BiographyOption("gaul", weight = 4, provides = setOf("region:barbaricum")),
                    BiographyOption("germania", weight = 3, provides = setOf("region:barbaricum")),
                    BiographyOption("greek_east", weight = 3, provides = setOf("region:hellenic")),
                    BiographyOption("italian_countryside", weight = 4, provides = setOf("region:italy")),
                    BiographyOption("north_africa", weight = 2, provides = setOf("region:africa")),
                    BiographyOption("iberia", weight = 3, provides = setOf("region:barbaricum")),
                ),
            socialBackgrounds =
                listOf(
                    BiographyOption("born_free", weight = 5, provides = setOf("social:freeborn")),
                    BiographyOption("born_into_slavery", weight = 4, provides = setOf("social:slave")),
                    BiographyOption(
                        "prisoner_of_war",
                        weight = 5,
                        requiresFacts = setOf("region:barbaricum"),
                        provides = setOf("social:captive", "social:martial"),
                    ),
                    BiographyOption("sold_for_debt", weight = 3, provides = setOf("social:slave")),
                    BiographyOption(
                        "deserter_from_the_legions",
                        weight = 2,
                        provides = setOf("social:freeborn", "social:martial"),
                    ),
                ),
            professions =
                listOf(
                    BiographyOption("herdsman", weight = 5),
                    BiographyOption("field_slave", weight = 5, requiresFacts = setOf("social:slave")),
                    BiographyOption("household_slave", weight = 3, requiresFacts = setOf("social:slave")),
                    BiographyOption("smith", weight = 3),
                    BiographyOption("quarry_labourer", weight = 3),
                    BiographyOption("tribal_warrior", weight = 5, requiresFacts = setOf("social:martial")),
                    BiographyOption("auxiliary_soldier", weight = 3, requiresFacts = setOf("social:martial")),
                    BiographyOption("hunter", weight = 3),
                ),
            militaryEducationJunior =
                listOf(
                    BiographyOption(
                        "gladiatorial_school",
                        excludesFacts = setOf("entry:sold_to_the_ludus"),
                        weight = 6,
                        provides = setOf("edu:ludus"),
                    ),
                    BiographyOption("tribal_war_band", weight = 4, requiresFacts = setOf("social:martial")),
                    BiographyOption("learned_in_the_revolt", weight = 4),
                ),
            militaryEducationSenior =
                listOf(
                    BiographyOption(
                        "gladiatorial_school",
                        excludesFacts = setOf("entry:sold_to_the_ludus"),
                        weight = 5,
                        provides = setOf("edu:ludus"),
                    ),
                    BiographyOption("auxiliary_service", weight = 3, requiresFacts = setOf("social:martial")),
                    BiographyOption("learned_in_the_revolt", weight = 4),
                ),
            serviceEntries =
                listOf(
                    BiographyOption("sold_to_the_ludus", weight = 5, provides = setOf("service:ludus")),
                    BiographyOption("taken_in_war", weight = 4, requiresFacts = setOf("social:captive")),
                    BiographyOption("fled_the_estate", weight = 5, requiresFacts = setOf("social:slave")),
                    BiographyOption("came_down_from_the_hills", weight = 3, requiresFacts = setOf("social:freeborn")),
                ),
            warEntries =
                listOf(
                    BiographyOption("broke_out_of_the_school", weight = 5, requiresFacts = setOf("service:ludus")),
                    BiographyOption("joined_on_the_march", weight = 5),
                    BiographyOption("rose_with_the_estate", weight = 4, requiresFacts = setOf("social:slave")),
                    BiographyOption(
                        "came_over_from_the_auxiliaries",
                        weight = 2,
                        requiresFacts = setOf("social:martial"),
                    ),
                ),
            priorService =
                listOf(
                    BiographyOption("fought_in_the_arena", weight = 5, requiresFacts = setOf("edu:ludus")),
                    BiographyOption("fought_rome_before", weight = 4, requiresFacts = setOf("social:martial")),
                    BiographyOption("survived_a_decimation", weight = 1),
                    BiographyOption("led_a_band_of_runaways", weight = 3),
                ),
            tracksServiceStartYear = false,
            narrationVariant = "ancient",
        )

    private const val EAST_ASIAN_POLITICAL_CHANCE = 0.8
    private const val MIN_AGE_PARTY = 22
    private const val MIN_AGE_GREAT_WAR_VETERAN = 40
    private const val MIN_AGE_COLONIAL = 30
    private const val MIN_AGE_LONG_MARCH = 26
}
