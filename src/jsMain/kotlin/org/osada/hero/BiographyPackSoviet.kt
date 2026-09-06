package org.osada.hero

import org.osada.UnitClass

/**
 * The two priority-1 content packs — §8 (Soviet 1930s-1945) and its Civil War predecessor.
 *
 * These are the deepest packs on purpose: §9 ranks them first, and between them they cover eleven
 * of the shipped campaigns. Everything else is authored to the same shape but shorter.
 *
 * ## Regional breadth without a nationality field
 *
 * §8.1 asks for the USSR's geographic spread and §2.2 forbids a player-facing nationality. Both
 * are satisfied by the same decision: [SOVIET_REGIONS] are PLACES, and the only thing a place is
 * allowed to influence is which places and institutions the rest of the path can mention. No
 * region carries a weight, potential, loyalty or trait consequence, and nothing downstream reads
 * the region id except the narrator. A hero born in Central Asia and one born near Leningrad are
 * mechanically the same officer.
 *
 * ## What the tags are actually doing
 *
 * The `edu:*` tokens are the load-bearing ones. `technical` and `higher` gate the professions that
 * cannot exist without schooling (mine surveyor, agronomist, civil engineer), `basic` deliberately
 * gates nothing, and `prerevolutionary` marks the schooling that only an officer born early enough
 * could have had. `service:*` tokens then gate the war-entry routes: a career officer cannot
 * "volunteer after the outbreak", and a mobilized reservist requires prior service to be mobilized
 * FROM (§10).
 */
internal object BiographyPackSoviet {
    private val LAND = COMMON_BRANCHES

    // ------------------------------------------------------------------ regions

    /**
     * Birth regions at the level an encyclopedia actually printed: governorate, oblast, republic.
     * No settlements — the entry names where someone is from, not a gazetteer.
     *
     * Each is written in SIE's `<unit at birth> (now <Soviet unit>)` form. That is what keeps the
     * line honest for a hero born in 1906: the Uzbek SSR did not exist until 1924, but Turkestan
     * did, and the reader still needs to know where that is.
     *
     * Weighted by where people actually lived, which is the one honest reason to make one commoner
     * than another. `region:*` tags let a social origin that belongs to one place — a dekhkan, a
     * Cossack — appear only there.
     */
    private val SOVIET_REGIONS =
        listOf(
            BiographyOption("tula_governorate", weight = 4, provides = setOf("region:russia", "region:industrial")),
            BiographyOption("moscow_governorate", weight = 4, provides = setOf("region:russia", "region:capital")),
            BiographyOption(
                "petrograd_governorate",
                weight = 3,
                provides = setOf("region:russia", "region:capital"),
            ),
            BiographyOption("vladimir_governorate", weight = 3, provides = setOf("region:russia")),
            BiographyOption("samara_governorate", weight = 4, provides = setOf("region:russia", "region:volga")),
            BiographyOption("saratov_governorate", weight = 4, provides = setOf("region:russia", "region:volga")),
            BiographyOption("vyatka_governorate", weight = 3, provides = setOf("region:russia")),
            BiographyOption("perm_governorate", weight = 4, provides = setOf("region:russia", "region:urals")),
            BiographyOption("tobolsk_governorate", weight = 3, provides = setOf("region:russia", "region:siberia")),
            BiographyOption("yenisei_governorate", weight = 2, provides = setOf("region:russia", "region:siberia")),
            BiographyOption("primorye_oblast", weight = 2, provides = setOf("region:russia", "region:frontier")),
            BiographyOption("kiev_governorate", weight = 4, provides = setOf("region:ukraine")),
            BiographyOption(
                "ekaterinoslav_governorate",
                weight = 4,
                provides = setOf("region:ukraine", "region:industrial"),
            ),
            BiographyOption("minsk_governorate", weight = 3, provides = setOf("region:belarus")),
            BiographyOption("don_host_oblast", weight = 3, provides = setOf("region:russia", "region:cossack")),
            BiographyOption("kuban_oblast", weight = 3, provides = setOf("region:russia", "region:cossack")),
            BiographyOption("tiflis_governorate", weight = 2, provides = setOf("region:caucasus")),
            BiographyOption("baku_governorate", weight = 2, provides = setOf("region:caucasus", "region:industrial")),
            BiographyOption("turkestan_krai", weight = 3, provides = setOf("region:turkestan")),
            BiographyOption("samarkand_oblast", weight = 2, provides = setOf("region:turkestan")),
            BiographyOption("semirechye_oblast", weight = 2, provides = setOf("region:turkestan", "region:frontier")),
        )

    /**
     * Social origin in the encyclopedia's own formula — `в семье <кого>`.
     *
     * SIE did not print a profession here. It printed origin and occupation together, which is why
     * "крестьянина" and "рабочего" stand beside "железнодорожника" and "мелкого торговца" in the
     * same slot: Nariman Narimanov is "в семье мелкого торговца", Voroshilov "в семье
     * рабочего-железнодорожника", Sultan Segizbaev "в семье дехканина".
     *
     * `дехканина` and `казака` are gated on the region that produced them rather than left to the
     * roll — a Cossack family in Vladimir Governorate is the kind of detail that reads as a bug.
     */
    private val SOVIET_SOCIAL =
        listOf(
            BiographyOption("peasant", weight = 8, provides = setOf("social:peasant")),
            BiographyOption("poor_peasant", weight = 5, provides = setOf("social:peasant")),
            BiographyOption("farm_labourer", weight = 3, provides = setOf("social:peasant")),
            BiographyOption(
                "dekhkan",
                weight = 6,
                requiresFacts = setOf("region:turkestan"),
                provides = setOf("social:peasant"),
            ),
            BiographyOption(
                "cossack",
                weight = 5,
                requiresFacts = setOf("region:cossack"),
                provides = setOf("social:peasant", "social:cossack"),
            ),
            BiographyOption("worker", weight = 8, provides = setOf("social:worker")),
            BiographyOption("railway_worker_family", weight = 3, provides = setOf("social:worker", "social:railway")),
            BiographyOption("miner_family", weight = 2, provides = setOf("social:worker")),
            BiographyOption("artisan", weight = 3, provides = setOf("social:worker")),
            BiographyOption("handicraftsman", weight = 2, provides = setOf("social:worker")),
            BiographyOption("employee", weight = 3, provides = setOf("social:clerical")),
            BiographyOption("small_trader", weight = 2, provides = setOf("social:clerical")),
            BiographyOption("tradesman", weight = 2, provides = setOf("social:clerical")),
            BiographyOption("shop_assistant", weight = 2, provides = setOf("social:clerical")),
            BiographyOption("teacher_family", weight = 2, provides = setOf("social:educated")),
            BiographyOption("professional_soldier", weight = 2, provides = setOf("social:military")),
        )

    // ------------------------------------------------------- civilian education

    /**
     * §8.2 verbatim, plus the chronology each entry actually needs. `parish_school` carries
     * [BiographyOption.minimumAge] rather than a year bound because what dates it is the hero's own
     * childhood, not the campaign: only someone schooled before 1917 could have attended one.
     */
    private val SOVIET_CIVILIAN_EDUCATION =
        listOf(
            BiographyOption("primary_school", weight = 5, provides = setOf("edu:basic")),
            BiographyOption(
                "parish_school",
                minimumAge = MIN_AGE_PREREVOLUTIONARY_SCHOOLING,
                weight = 2,
                provides = setOf("edu:basic", "edu:prerevolutionary"),
            ),
            BiographyOption("seven_year_school", weight = 6, provides = setOf("edu:basic")),
            BiographyOption("factory_apprenticeship", weight = 4, provides = setOf("edu:basic", "edu:trade")),
            BiographyOption("workers_faculty", weight = 3, provides = setOf("edu:secondary", "edu:trade")),
            BiographyOption("trade_school", weight = 4, provides = setOf("edu:basic", "edu:trade")),
            BiographyOption("tekhnikum", weight = 4, provides = setOf("edu:secondary", "edu:technical")),
            BiographyOption(
                "pedagogical_institute",
                weight = 2,
                provides = setOf("edu:higher", "edu:academic"),
            ),
            BiographyOption("incomplete_higher", weight = 2, provides = setOf("edu:secondary", "edu:academic")),
            BiographyOption("higher_education", weight = 2, provides = setOf("edu:higher", "edu:technical")),
        )

    /**
     * §8.3's occupations. `requiresFacts` is what keeps the path honest: a mine surveyor needs a
     * technical school, a teacher needs a pedagogical one, and a professional soldier is excluded
     * from being anything else because he never had a civilian trade to begin with.
     */
    private val SOVIET_PROFESSIONS =
        listOf(
            BiographyOption("railway_worker", weight = 4),
            BiographyOption("locomotive_assistant", weight = 3, requiresFacts = setOf("edu:trade")),
            BiographyOption("depot_worker", weight = 3),
            BiographyOption("fitter", weight = 5, requiresFacts = setOf("edu:trade")),
            BiographyOption("turner", weight = 4, requiresFacts = setOf("edu:trade")),
            BiographyOption("machinist", weight = 3, requiresFacts = setOf("edu:trade")),
            // The 1939 census put 43.5% of the employed population in collective farms and counted
            // servicemen by their occupation BEFORE call-up, so this is the single most likely
            // pre-war answer for a Red Army man and is weighted accordingly.
            BiographyOption("collective_farmer", weight = 12),
            BiographyOption("tractor_driver", weight = 5),
            BiographyOption("combine_operator", weight = 3),
            BiographyOption("collective_farm_brigade", weight = 4),
            BiographyOption("driver", weight = 5, requiresFacts = setOf("edu:trade")),
            BiographyOption("blacksmith", weight = 3),
            BiographyOption("miner", weight = 3),
            BiographyOption("mine_surveyor", weight = 1, requiresFacts = setOf("edu:technical")),
            BiographyOption("telegraph_operator", weight = 3),
            BiographyOption("radio_technician", weight = 2, requiresFacts = setOf("edu:technical")),
            BiographyOption("bookkeeper", weight = 2, requiresFacts = setOf("edu:secondary")),
            BiographyOption("village_clerk", weight = 2),
            BiographyOption("teacher", weight = 3, requiresFacts = setOf("edu:academic")),
            BiographyOption("agronomist", weight = 2, requiresFacts = setOf("edu:higher")),
            BiographyOption("factory_foreman", weight = 3, requiresFacts = setOf("edu:secondary")),
            BiographyOption("automobile_mechanic", weight = 3, requiresFacts = setOf("edu:trade")),
            BiographyOption("civil_engineer", weight = 1, requiresFacts = setOf("edu:higher")),
            BiographyOption("student", weight = 2, requiresFacts = setOf("edu:secondary")),
            BiographyOption(
                "professional_soldier",
                weight = 3,
                requiresFacts = setOf("social:military"),
                provides = setOf("prof:career"),
            ),
        )

    private val SOVIET_MILITARY_EDUCATION_JUNIOR =
        listOf(
            BiographyOption(
                "commissioned_from_the_ranks",
                excludesFacts = setOf("entry:promoted_from_ranks"),
                weight = 4,
            ),
            BiographyOption("reserve_officer_course", weight = 3),
            BiographyOption("infantry_school", weight = 4, unitClasses = LAND),
            BiographyOption(
                "accelerated_wartime_course",
                yearFrom = YEAR_GREAT_PATRIOTIC_WAR,
                weight = 4,
            ),
            BiographyOption("flying_school", weight = 4, unitClasses = AIR_BRANCHES),
        )

    private val SOVIET_MILITARY_EDUCATION_SENIOR =
        listOf(
            BiographyOption("military_academy", weight = 4),
            BiographyOption("staff_college", weight = 3),
            BiographyOption("frunze_academy", weight = 3, minimumAge = MIN_AGE_SENIOR_ACADEMY),
            BiographyOption("armour_academy", weight = 2, unitClasses = ARMOUR_BRANCHES),
        )

    // ------------------------------------------------------------ entry to service

    /**
     * §8.4's routes, and its explicit instruction to keep "entered military service" separate from
     * "entered the current war". These are the first half; [SOVIET_WAR_ENTRIES] is the second, and
     * the `service:*` tokens are the join between them.
     */
    private val SOVIET_SERVICE_ENTRIES =
        listOf(
            BiographyOption(
                "career_service",
                serviceNotBefore = YEAR_RED_ARMY,
                weight = 3,
                provides = setOf("service:career", "service:prewar"),
            ),
            BiographyOption(
                "conscripted",
                serviceNotBefore = YEAR_RED_ARMY,
                weight = 5,
                provides = setOf("service:conscript", "service:prewar"),
            ),
            BiographyOption(
                "promoted_from_ranks",
                serviceNotBefore = YEAR_RED_ARMY,
                weight = 4,
                provides = setOf("service:ranks", "service:prewar"),
            ),
            BiographyOption(
                "border_troops",
                serviceNotBefore = YEAR_BORDER_TROOPS,
                weight = 2,
                provides = setOf("service:border", "service:prewar"),
            ),
            BiographyOption(
                "volunteer_enlistment",
                serviceNotBefore = YEAR_RED_ARMY,
                weight = 3,
                provides = setOf("service:volunteer"),
            ),
            BiographyOption(
                "party_mobilization",
                serviceNotBefore = YEAR_RED_ARMY,
                weight = 2,
                requiresFacts = setOf("political:member"),
                provides = setOf("service:mobilized"),
            ),
        )

    /**
     * How the officer reached THIS war. `mobilized_reserve` requires a pre-war service token
     * because §10 forbids mobilizing a reservist who never served, and `already_serving` requires
     * the same for the same reason.
     */
    private val SOVIET_WAR_ENTRIES =
        listOf(
            BiographyOption(
                "already_serving",
                weight = 4,
                requiresFacts = setOf("service:prewar"),
            ),
            BiographyOption(
                "mobilized_reserve",
                weight = 4,
                requiresFacts = setOf("service:prewar"),
                excludesFacts = setOf("service:career"),
            ),
            BiographyOption(
                "volunteered_at_outbreak",
                weight = 3,
                excludesFacts = setOf("service:prewar"),
            ),
            BiographyOption(
                "peoples_militia",
                yearFrom = YEAR_GREAT_PATRIOTIC_WAR,
                weight = 2,
                excludesFacts = setOf("service:prewar"),
            ),
            BiographyOption(
                "transferred_from_border_troops",
                weight = 2,
                requiresFacts = setOf("service:border"),
            ),
        )

    /** §8.5: two rendered forms, no institutional history, and never a bonus. */
    private val SOVIET_POLITICAL =
        listOf(
            BiographyOption("non_party", weight = 5),
            BiographyOption(
                "party_member",
                minimumAge = MIN_AGE_PARTY,
                weight = 5,
                provides = setOf("political:member"),
            ),
        )

    /** §8.6's table, each entry carrying exactly the guard that table specifies. */
    private val SOVIET_PRIOR_SERVICE =
        listOf(
            BiographyOption("garrison_duty", weight = 4),
            BiographyOption("training_command", weight = 3),
            BiographyOption(
                "border_service",
                weight = 3,
                requiresFacts = setOf("service:border"),
            ),
            BiographyOption(
                "red_guard",
                yearFrom = 1918,
                minimumAge = MIN_AGE_RED_GUARD_VETERAN,
                weight = 1,
            ),
            BiographyOption(
                "civil_war_veteran",
                yearFrom = 1919,
                minimumAge = MIN_AGE_CIVIL_WAR_VETERAN,
                weight = 2,
            ),
            BiographyOption(
                "imperial_army_nco",
                yearFrom = 1918,
                minimumAge = MIN_AGE_IMPERIAL_VETERAN,
                weight = 1,
            ),
            BiographyOption("spain_volunteer", yearFrom = 1937, minimumAge = MIN_AGE_FOREIGN_VOLUNTEER, weight = 1),
            BiographyOption("lake_khasan", yearFrom = 1939, minimumAge = MIN_AGE_RECENT_CONFLICT, weight = 2),
            BiographyOption("khalkhin_gol", yearFrom = 1940, minimumAge = MIN_AGE_RECENT_CONFLICT, weight = 2),
            BiographyOption("soviet_finnish_war", yearFrom = 1940, minimumAge = MIN_AGE_RECENT_CONFLICT, weight = 3),
        )

    val SOVIET_1930_1945 =
        BiographyPack(
            id = "soviet_1930_1945",
            regions = SOVIET_REGIONS,
            socialBackgrounds = SOVIET_SOCIAL,
            civilianEducation = SOVIET_CIVILIAN_EDUCATION,
            professions = SOVIET_PROFESSIONS,
            militaryEducationJunior = SOVIET_MILITARY_EDUCATION_JUNIOR,
            militaryEducationSenior = SOVIET_MILITARY_EDUCATION_SENIOR,
            serviceEntries = SOVIET_SERVICE_ENTRIES,
            warEntries = SOVIET_WAR_ENTRIES,
            politicalStatuses = SOVIET_POLITICAL,
            priorService = SOVIET_PRIOR_SERVICE,
            politicalChance = SOVIET_POLITICAL_CHANCE,
            birthplaceCarriesModernName = true,
        )

    // ------------------------------------------------------- Red Russia 1917-1922

    // The Civil War pack: its own governorates, a schooling ladder that stops where the Soviet one
    // begins (no `tekhnikum` or workers' faculty exists yet in 1919), and routes into service that
    // belong to a revolution rather than to a peacetime conscription cycle.
    //
    // HERO OF THE SOVIET UNION IS IMPOSSIBLE HERE, and that is enforced elsewhere (§12.1,
    // HeroDistinctions); this pack simply has no fact that would imply it.

    /**
     * Governorates and oblasts as they stood in 1917, named alone: a Civil War entry has no later
     * name to append, and the Soviet pack's `(now ...)` form would put a commander of 1919 in an
     * oblast that did not exist until 1929.
     */
    private val RED_REGIONS =
        listOf(
            BiographyOption("tula_governorate", weight = 4, provides = setOf("region:russia", "region:industrial")),
            BiographyOption("moscow_governorate", weight = 4, provides = setOf("region:russia", "region:capital")),
            BiographyOption(
                "petrograd_governorate",
                weight = 4,
                provides = setOf("region:russia", "region:capital"),
            ),
            BiographyOption("vladimir_governorate", weight = 3, provides = setOf("region:russia")),
            BiographyOption("tver_governorate", weight = 3, provides = setOf("region:russia")),
            BiographyOption("samara_governorate", weight = 4, provides = setOf("region:russia", "region:volga")),
            BiographyOption("saratov_governorate", weight = 4, provides = setOf("region:russia", "region:volga")),
            BiographyOption("vyatka_governorate", weight = 3, provides = setOf("region:russia")),
            BiographyOption("perm_governorate", weight = 4, provides = setOf("region:russia", "region:urals")),
            BiographyOption("tobolsk_governorate", weight = 3, provides = setOf("region:russia", "region:siberia")),
            BiographyOption("yenisei_governorate", weight = 2, provides = setOf("region:russia", "region:siberia")),
            BiographyOption("kiev_governorate", weight = 4, provides = setOf("region:ukraine")),
            BiographyOption(
                "ekaterinoslav_governorate",
                weight = 4,
                provides = setOf("region:ukraine", "region:industrial"),
            ),
            BiographyOption("minsk_governorate", weight = 3, provides = setOf("region:belarus")),
            BiographyOption("don_host_oblast", weight = 3, provides = setOf("region:russia", "region:cossack")),
            BiographyOption("kuban_oblast", weight = 3, provides = setOf("region:russia", "region:cossack")),
            BiographyOption("tiflis_governorate", weight = 2, provides = setOf("region:caucasus")),
            BiographyOption("baku_governorate", weight = 2, provides = setOf("region:caucasus", "region:industrial")),
            BiographyOption("turkestan_krai", weight = 3, provides = setOf("region:turkestan")),
            BiographyOption("samarkand_oblast", weight = 2, provides = setOf("region:turkestan")),
        )

    /**
     * The same encyclopedic formula as the Soviet pack, minus what the Civil War does not yet have:
     * no `служащего` in the Soviet sense, and `мещанина` back in, because in 1919 a man's estate is
     * still a thing an entry would state.
     */
    private val RED_SOCIAL =
        listOf(
            BiographyOption("peasant", weight = 9, provides = setOf("social:peasant")),
            BiographyOption("poor_peasant", weight = 6, provides = setOf("social:peasant")),
            BiographyOption("farm_labourer", weight = 4, provides = setOf("social:peasant")),
            BiographyOption(
                "dekhkan",
                weight = 6,
                requiresFacts = setOf("region:turkestan"),
                provides = setOf("social:peasant"),
            ),
            BiographyOption(
                "cossack",
                weight = 5,
                requiresFacts = setOf("region:cossack"),
                provides = setOf("social:peasant", "social:cossack"),
            ),
            BiographyOption("worker", weight = 8, provides = setOf("social:worker")),
            BiographyOption("railway_worker_family", weight = 4, provides = setOf("social:worker", "social:railway")),
            BiographyOption("miner_family", weight = 2, provides = setOf("social:worker")),
            BiographyOption("artisan", weight = 4, provides = setOf("social:worker")),
            BiographyOption("handicraftsman", weight = 3, provides = setOf("social:worker")),
            BiographyOption("townsman", weight = 3, provides = setOf("social:clerical")),
            BiographyOption("small_trader", weight = 3, provides = setOf("social:clerical")),
            BiographyOption("shop_assistant", weight = 2, provides = setOf("social:clerical")),
            BiographyOption("teacher_family", weight = 2, provides = setOf("social:educated")),
            BiographyOption("professional_soldier", weight = 2, provides = setOf("social:military")),
        )

    private val RED_CIVILIAN_EDUCATION =
        listOf(
            BiographyOption("no_formal_schooling", weight = 4, provides = setOf("edu:none")),
            BiographyOption("parish_school", weight = 5, provides = setOf("edu:basic", "edu:prerevolutionary")),
            BiographyOption("primary_school", weight = 5, provides = setOf("edu:basic")),
            BiographyOption("city_school", weight = 3, provides = setOf("edu:basic")),
            BiographyOption("trade_school", weight = 3, provides = setOf("edu:basic", "edu:trade")),
            BiographyOption("real_school", weight = 2, provides = setOf("edu:secondary", "edu:academic")),
            BiographyOption("gymnasium", weight = 2, provides = setOf("edu:secondary", "edu:academic")),
            BiographyOption(
                "university_incomplete",
                minimumAge = MIN_AGE_UNIVERSITY,
                weight = 1,
                provides = setOf("edu:higher", "edu:academic"),
            ),
        )

    private val RED_PROFESSIONS =
        listOf(
            BiographyOption("railway_worker", weight = 5),
            BiographyOption("depot_worker", weight = 4),
            BiographyOption("fitter", weight = 4, requiresFacts = setOf("edu:trade")),
            BiographyOption("factory_hand", weight = 5),
            BiographyOption("peasant_smallholder", weight = 5),
            BiographyOption("farm_labourer", weight = 4),
            BiographyOption("miner", weight = 3),
            BiographyOption("telegraph_operator", weight = 2, requiresFacts = setOf("edu:basic")),
            BiographyOption("printer", weight = 2, requiresFacts = setOf("edu:basic")),
            BiographyOption("village_teacher", weight = 2, requiresFacts = setOf("edu:academic")),
            BiographyOption("clerk", weight = 2, requiresFacts = setOf("edu:basic")),
            BiographyOption("seaman", weight = 2, provides = setOf("prof:sailor")),
            BiographyOption("student", weight = 2, requiresFacts = setOf("edu:secondary")),
            BiographyOption(
                "imperial_army_soldier",
                weight = 4,
                provides = setOf("prof:career", "prof:imperial"),
            ),
        )

    private val RED_MILITARY_EDUCATION_JUNIOR =
        listOf(
            BiographyOption(
                "commissioned_from_the_ranks",
                excludesFacts = setOf("entry:promoted_from_ranks"),
                weight = 5,
            ),
            BiographyOption("red_command_courses", weight = 5),
            BiographyOption("detachment_experience", weight = 4),
            BiographyOption("imperial_nco_training", weight = 2, requiresFacts = setOf("prof:imperial")),
        )

    private val RED_MILITARY_EDUCATION_SENIOR =
        listOf(
            BiographyOption("red_command_courses", weight = 4),
            BiographyOption("general_staff_academy_rkka", yearFrom = 1919, weight = 2),
            BiographyOption(
                "imperial_officer_school",
                minimumAge = MIN_AGE_IMPERIAL_VETERAN,
                weight = 3,
                provides = setOf("edu:voenspets"),
            ),
        )

    private val RED_SERVICE_ENTRIES =
        listOf(
            BiographyOption(
                "red_guard_detachment",
                serviceNotBefore = YEAR_RED_GUARD,
                weight = 5,
                provides = setOf("service:redguard", "service:volunteer"),
            ),
            BiographyOption(
                "volunteered_red_army",
                serviceNotBefore = YEAR_RED_ARMY,
                weight = 5,
                provides = setOf("service:volunteer"),
            ),
            BiographyOption(
                "imperial_army_conscript",
                minimumAge = MIN_AGE_IMPERIAL_VETERAN,
                weight = 4,
                provides = setOf("service:imperial", "service:prewar"),
            ),
            BiographyOption(
                "baltic_fleet",
                weight = 2,
                requiresFacts = setOf("prof:sailor"),
                provides = setOf("service:sailor", "service:prewar"),
            ),
            BiographyOption(
                "party_mobilization",
                serviceNotBefore = YEAR_RED_GUARD,
                weight = 3,
                provides = setOf("service:mobilized"),
            ),
            BiographyOption(
                "mobilized_by_levy",
                yearFrom = 1919,
                serviceNotBefore = YEAR_RED_ARMY,
                weight = 3,
                provides = setOf("service:levy"),
            ),
        )

    private val RED_WAR_ENTRIES =
        listOf(
            BiographyOption("from_the_first_days", weight = 4),
            BiographyOption("joined_after_the_rising", weight = 3),
            BiographyOption(
                "came_over_from_the_old_army",
                weight = 3,
                requiresFacts = setOf("service:imperial"),
            ),
            BiographyOption(
                "sent_by_the_committee",
                weight = 2,
                requiresFacts = setOf("service:mobilized"),
            ),
            BiographyOption(
                "raised_a_local_detachment",
                weight = 3,
                excludesFacts = setOf("service:imperial"),
            ),
        )

    private val RED_POLITICAL =
        listOf(
            BiographyOption("non_party", weight = 5),
            BiographyOption(
                "party_member",
                minimumAge = MIN_AGE_PARTY,
                weight = 4,
                provides = setOf("political:member"),
            ),
        )

    private val RED_PRIOR_SERVICE =
        listOf(
            BiographyOption("great_war_front", minimumAge = MIN_AGE_IMPERIAL_VETERAN, weight = 4),
            BiographyOption(
                "great_war_prisoner",
                minimumAge = MIN_AGE_IMPERIAL_VETERAN,
                weight = 2,
            ),
            BiographyOption("factory_red_guard", weight = 3, requiresFacts = setOf("service:redguard")),
            BiographyOption("petrograd_october", minimumAge = MIN_AGE_RED_GUARD_VETERAN, weight = 2),
            BiographyOption("armoured_train_service", yearFrom = 1919, weight = 2),
            BiographyOption("partisan_detachment", yearFrom = 1919, weight = 3),
            BiographyOption("food_requisition_detachment", yearFrom = 1919, weight = 2),
        )

    val RED_RUSSIA_1917_1922 =
        BiographyPack(
            id = "red_russia_1917_1922",
            regions = RED_REGIONS,
            socialBackgrounds = RED_SOCIAL,
            civilianEducation = RED_CIVILIAN_EDUCATION,
            professions = RED_PROFESSIONS,
            militaryEducationJunior = RED_MILITARY_EDUCATION_JUNIOR,
            militaryEducationSenior = RED_MILITARY_EDUCATION_SENIOR,
            serviceEntries = RED_SERVICE_ENTRIES,
            warEntries = RED_WAR_ENTRIES,
            politicalStatuses = RED_POLITICAL,
            priorService = RED_PRIOR_SERVICE,
            politicalChance = RED_POLITICAL_CHANCE,
        )

    private const val SOVIET_POLITICAL_CHANCE = 0.75
    private const val RED_POLITICAL_CHANCE = 0.7
    private const val YEAR_GREAT_PATRIOTIC_WAR = 1941
    private const val MIN_AGE_PREREVOLUTIONARY_SCHOOLING = 30
    private const val MIN_AGE_SENIOR_ACADEMY = 34
    private const val MIN_AGE_PARTY = 22
    private const val MIN_AGE_RED_GUARD_VETERAN = 40
    private const val MIN_AGE_CIVIL_WAR_VETERAN = 38
    private const val MIN_AGE_IMPERIAL_VETERAN = 42
    private const val MIN_AGE_FOREIGN_VOLUNTEER = 26
    private const val MIN_AGE_RECENT_CONFLICT = 24
    private const val MIN_AGE_UNIVERSITY = 26
    private const val YEAR_RED_GUARD = 1917
    private const val YEAR_RED_ARMY = 1918
    private const val YEAR_BORDER_TROOPS = 1918
}

/** Land classes a branch-specific school may be authored for, shared by every pack. */
internal val COMMON_BRANCHES: Set<Int> =
    setOf(
        UnitClass.INFANTRY.value,
        UnitClass.TANK.value,
        UnitClass.RECON.value,
        UnitClass.ANTI_TANK.value,
        UnitClass.ARTILLERY.value,
        UnitClass.FLAK.value,
        UnitClass.AIR_DEFENCE.value,
        UnitClass.GROUND_TRANSPORT.value,
    )

internal val ARMOUR_BRANCHES: Set<Int> = setOf(UnitClass.TANK.value, UnitClass.RECON.value)

internal val AIR_BRANCHES: Set<Int> =
    setOf(
        UnitClass.FIGHTER.value,
        UnitClass.TACTICAL_BOMBER.value,
        UnitClass.LEVEL_BOMBER.value,
        UnitClass.AIR_TRANSPORT.value,
    )
