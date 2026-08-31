package org.osada.hero

import org.osada.LeaderType
import org.osada.UnitClass

/**
 * Authored legendary heroes and the early-campaign reservation logic — design brief §6, §23.
 *
 * A "legendary" here is the **authored-origin** meaning of §4.4: a handcrafted officer with a name,
 * a background, and one **rule-changing signature ability** (§6.5) — not merely bigger numbers. The
 * signature is an existing combat-honoured [LeaderType] (the same seam Phase 3 used to make the
 * "defined but unobtainable" traits reachable), so it takes effect through [HeroTraitResolver] with
 * no new combat wiring, while reading as a distinct, characterful power in the dossier.
 *
 * The heroes are **authored-fictional composites** ([HeroOrigin.AUTHORED_FICTIONAL]) rather than
 * named real people, so nothing risks being date- or fact-incorrect (§26). Reservation filters
 * campaign, nation, date and the player's available unit classes before selecting; campaigns
 * without an authored match receive a deterministic procedural reservation rather than an
 * incompatible Soviet officer (§23).
 *
 * Every shipped player side has at least one candidate. The original Soviet and Russian Civil War
 * entries use painted portraits; the broader campaign set uses the matching deterministic layered
 * national/era pool. That is why [yearRange] and [campaignIds] are per hero rather than per pool —
 * a 1919 commander must be unable to reach a 1942 campaign even when both sides call themselves the
 * Red Army. Campaign ids and [nationIds] were read from the deployed scenarios' own `player id="0"`
 * country, not from the campaign list's prose label.
 *
 * **[female] is authored, not rolled.** The painting already decided; the biography narrator and
 * name must follow it rather than the portrait seed (§4.11), so it is stored on the composition.
 */
internal object LegendaryHeroPool {
    const val PROCEDURAL_FALLBACK_ID = "procedural_early_legend"

    data class LegendaryHero(
        val id: String,
        val name: String,
        val campaignIds: Set<String>,
        val nationIds: Set<Int>,
        val yearRange: IntRange,
        val compatibleUnitClasses: Set<Int>,
        val backgroundId: String,
        val signatureTrait: LeaderType,
        val signatureTitle: String,
        val signatureDescription: String,
        val startingRankId: String,
        /** Painted portrait when one exists; null deliberately uses the matching procedural pool. */
        val portraitArtId: String? = null,
        val female: Boolean = false,
    )

    // Campaign files, grouped so a hero's reach is legible at the call site rather than a bag of
    // filenames. Soviet WW2 splits by the nation id its scenarios actually author.
    private val URANUS = setOf("062d.json", "camp6.json")
    private val BLACK_SEA = setOf("rcampdfr.json")
    private val RED_ARMY_19 = setOf("camp6bn9.json", "camp6bn5.json", "reddestiny.json")
    private val RED_ARMY_89 = setOf("forward.json", "ga4.json")
    private val CIVIL_WAR = setOf("ccampdfc.json", "volarm.json", "simpob.json", "polsov.json")

    private const val NATION_USSR_61 = 61
    private const val NATION_SOVIET_UNION_19 = 19
    private const val NATION_USSR_89 = 89
    private const val NATION_RED_RUSSIA_103 = 103

    private val COMMON_LAND_CLASSES =
        setOf(
            UnitClass.INFANTRY.value,
            UnitClass.TANK.value,
            UnitClass.RECON.value,
            UnitClass.ANTI_TANK.value,
            UnitClass.ARTILLERY.value,
        )

    /** Great Patriotic War, 1936–1954 — pilotka, ushanka, peaked cap, later-war pogony. */
    private val SOVIET_WW2: List<LegendaryHero> =
        listOf(
            LegendaryHero(
                id = "ussr_breakthrough",
                name = "Major Dmitri Voroshin",
                campaignIds = URANUS,
                nationIds = setOf(NATION_USSR_61),
                yearRange = 1941..1943,
                compatibleUnitClasses = setOf(UnitClass.TANK.value),
                backgroundId = "armored_academy_graduate",
                signatureTrait = LeaderType.OVERWHELMING_ATTACK,
                signatureTitle = "Breakthrough Assault",
                signatureDescription = "Presses a shattered enemy without pause, opening the line.",
                startingRankId = "major",
                portraitArtId = "ussr_ww2_voroshin",
            ),
            LegendaryHero(
                id = "ussr_stalingrad",
                name = "Sergeant Yakov Belov",
                campaignIds = URANUS,
                nationIds = setOf(NATION_USSR_61),
                yearRange = 1942..1943,
                compatibleUnitClasses = setOf(UnitClass.INFANTRY.value, UnitClass.RECON.value),
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.STREET_FIGHTER,
                signatureTitle = "Stalingrad Veteran",
                signatureDescription = "Owns the rubble — deadly in the close, room-to-room fight of the city.",
                startingRankId = "captain",
                portraitArtId = "ussr_ww2_belov",
            ),
            LegendaryHero(
                id = "ussr_ace",
                name = "Lieutenant Nadya Sokolova",
                campaignIds = URANUS,
                nationIds = setOf(NATION_USSR_61),
                yearRange = 1942..1944,
                compatibleUnitClasses = setOf(UnitClass.FIGHTER.value, UnitClass.TACTICAL_BOMBER.value),
                backgroundId = "fighter_squadron_leader",
                signatureTrait = LeaderType.FIRST_STRIKE,
                signatureTitle = "Ace's Advantage",
                signatureDescription = "Sees the merge first — strikes before the enemy can bring guns to bear.",
                startingRankId = "captain",
                portraitArtId = "ussr_ww2_sokolova",
                female = true,
            ),
            LegendaryHero(
                id = "ussr_coastal_gunner",
                name = "Lieutenant Zinaida Grebnyova",
                campaignIds = URANUS + BLACK_SEA,
                nationIds = setOf(NATION_USSR_61),
                yearRange = 1941..1945,
                compatibleUnitClasses = setOf(UnitClass.ARTILLERY.value, UnitClass.ANTI_TANK.value),
                backgroundId = "regimental_artillery_officer",
                signatureTrait = LeaderType.DEVASTATING_FIRE,
                signatureTitle = "Coastal Battery",
                signatureDescription = "Lays the guns as if the range card were written years ago.",
                startingRankId = "lieutenant",
                portraitArtId = "ussr_ww2_pool_greatcoat_f",
                female = true,
            ),
            LegendaryHero(
                id = "ussr_tank_star",
                name = "Captain Arkady Rudenko",
                campaignIds = RED_ARMY_19 + RED_ARMY_89,
                nationIds = setOf(NATION_SOVIET_UNION_19, NATION_USSR_89),
                yearRange = 1941..1945,
                compatibleUnitClasses = setOf(UnitClass.TANK.value),
                backgroundId = "armored_academy_graduate",
                signatureTrait = LeaderType.AGGRESSIVE_TANK_MANEUVER,
                signatureTitle = "Deep Battle",
                signatureDescription = "Turns a torn seam into an operation — exploits before the enemy can seal it.",
                startingRankId = "captain",
                portraitArtId = "ussr_ww2_tanker_star",
            ),
            LegendaryHero(
                id = "ussr_flyer",
                name = "Major Semyon Rogachyov",
                campaignIds = RED_ARMY_19 + RED_ARMY_89,
                nationIds = setOf(NATION_SOVIET_UNION_19, NATION_USSR_89),
                yearRange = 1939..1945,
                compatibleUnitClasses =
                    setOf(
                        UnitClass.FIGHTER.value,
                        UnitClass.TACTICAL_BOMBER.value,
                        UnitClass.LEVEL_BOMBER.value,
                    ),
                backgroundId = "ground_attack_group_leader",
                signatureTrait = LeaderType.SKILLED_GROUND_ATTACK,
                signatureTitle = "Low Pass",
                signatureDescription = "Comes in under the flak and puts the ordnance where the map said it went.",
                startingRankId = "major",
                portraitArtId = "ussr_ww2_flyer_veteran",
            ),
            LegendaryHero(
                id = "ussr_winter_armor",
                name = "Captain Pavel Zimin",
                campaignIds = RED_ARMY_19 + RED_ARMY_89,
                nationIds = setOf(NATION_SOVIET_UNION_19, NATION_USSR_89),
                yearRange = 1941..1954,
                compatibleUnitClasses = setOf(UnitClass.TANK.value, UnitClass.RECON.value),
                backgroundId = "armored_academy_graduate",
                // Was ALL_WEATHER_COMBAT until 2026-08-17, which could never do anything for him:
                // that rule only fires for an air unit and he commands TANK/RECON. Resilience keeps
                // the winter-attrition reading of "Winter Crews" and is a live rule for his classes
                // (`CombatResolver.attackValue` lowers incoming effectiveness by 2 when he defends).
                signatureTrait = LeaderType.RESILIENCE,
                signatureTitle = "Winter Crews",
                signatureDescription = "Keeps engines turning and crews fighting when the cold does the killing.",
                startingRankId = "captain",
                portraitArtId = "ussr_ww2_tanker_winter",
            ),
            LegendaryHero(
                id = "ussr_young_lion",
                name = "Lieutenant Kostya Nechayev",
                campaignIds = RED_ARMY_19 + RED_ARMY_89,
                nationIds = setOf(NATION_SOVIET_UNION_19, NATION_USSR_89),
                yearRange = 1939..1945,
                compatibleUnitClasses = setOf(UnitClass.INFANTRY.value),
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.SKILLED_ASSAULT,
                signatureTitle = "First Over",
                signatureDescription = "Leads from the front of the assault group and the company follows him there.",
                startingRankId = "lieutenant",
                portraitArtId = "ussr_ww2_pool_young_m",
            ),
            LegendaryHero(
                id = "ussr_partisan_scout",
                name = "Lieutenant Vera Trushina",
                campaignIds = RED_ARMY_19,
                nationIds = setOf(NATION_SOVIET_UNION_19),
                yearRange = 1941..1945,
                compatibleUnitClasses = setOf(UnitClass.RECON.value, UnitClass.INFANTRY.value),
                backgroundId = "veteran_reconnaissance_officer",
                signatureTrait = LeaderType.FOREST_CAMOUFLAGE,
                signatureTitle = "Behind the Line",
                signatureDescription = "Moves a detachment through country the enemy believes it holds.",
                startingRankId = "lieutenant",
                portraitArtId = "ussr_ww2_ushanka_woman",
                female = true,
            ),
            LegendaryHero(
                id = "ussr_sniper",
                name = "Sergeant Praskovya Lisitsyna",
                campaignIds = RED_ARMY_19,
                nationIds = setOf(NATION_SOVIET_UNION_19),
                yearRange = 1941..1945,
                compatibleUnitClasses = setOf(UnitClass.INFANTRY.value, UnitClass.RECON.value),
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.MARKSMAN,
                signatureTitle = "Sniper's Patience",
                signatureDescription = "Waits out a whole morning for the one shot that costs the enemy a commander.",
                startingRankId = "lieutenant",
                portraitArtId = "ussr_ww2_pool_braid_f",
                female = true,
            ),
            LegendaryHero(
                id = "ussr_gunner",
                name = "Lieutenant Klavdiya Yermolina",
                campaignIds = RED_ARMY_19 + RED_ARMY_89,
                nationIds = setOf(NATION_SOVIET_UNION_19, NATION_USSR_89),
                yearRange = 1941..1945,
                compatibleUnitClasses = setOf(UnitClass.ARTILLERY.value),
                backgroundId = "regimental_artillery_officer",
                signatureTrait = LeaderType.FIRE_DISCIPLINE,
                signatureTitle = "Fire Discipline",
                signatureDescription = "Holds the battery's fire until it will break something, then breaks it.",
                startingRankId = "lieutenant",
                portraitArtId = "ussr_ww2_pool_pilotka_f1",
                female = true,
            ),
            LegendaryHero(
                id = "ussr_flak",
                name = "Lieutenant Rimma Zhurbina",
                campaignIds = RED_ARMY_89,
                nationIds = setOf(NATION_USSR_89),
                yearRange = 1941..1945,
                compatibleUnitClasses = setOf(UnitClass.AIR_DEFENCE.value, UnitClass.FLAK.value),
                backgroundId = "mechanized_air_defence_officer",
                signatureTrait = LeaderType.SKILLED_INTERCEPTOR,
                signatureTitle = "Layered Sky",
                signatureDescription = "Reads the approach and has the guns already tracking when it arrives.",
                startingRankId = "lieutenant",
                portraitArtId = "ussr_ww2_pool_pilotka_f2",
                female = true,
            ),
            LegendaryHero(
                id = "ussr_postwar_rifles",
                name = "Captain Lidiya Panfyorova",
                campaignIds = RED_ARMY_19,
                nationIds = setOf(NATION_SOVIET_UNION_19),
                yearRange = 1943..1954,
                compatibleUnitClasses = setOf(UnitClass.INFANTRY.value, UnitClass.RECON.value),
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.INFILTRATION_TACTICS,
                signatureTitle = "Assault Groups",
                signatureDescription = "Splits a rifle company into fists and sends them at the gaps, not the wall.",
                startingRankId = "captain",
                portraitArtId = "ussr_ww2_pool_pilotka_f3",
                female = true,
            ),
        )

    /** Russian Civil War, 1917–1922 — budenovka and papakha, and emphatically no shoulder boards. */
    private val RUSSIAN_CIVIL_WAR: List<LegendaryHero> =
        listOf(
            LegendaryHero(
                id = "rcw_cavalry",
                name = "Komdiv Semyon Karetnik",
                campaignIds = CIVIL_WAR,
                nationIds = setOf(NATION_RED_RUSSIA_103),
                yearRange = 1918..1922,
                compatibleUnitClasses = setOf(UnitClass.RECON.value, UnitClass.INFANTRY.value),
                backgroundId = "veteran_reconnaissance_officer",
                signatureTrait = LeaderType.SUPERIOR_MANEUVER,
                signatureTitle = "Cavalry Raid",
                signatureDescription = "Appears where the enemy's map says there is only open steppe.",
                startingRankId = "major",
                portraitArtId = "rcw_papakha_cavalry",
            ),
            LegendaryHero(
                id = "rcw_sailor",
                name = "Commissar Fyodor Zhelnin",
                campaignIds = CIVIL_WAR,
                nationIds = setOf(NATION_RED_RUSSIA_103),
                yearRange = 1918..1922,
                compatibleUnitClasses = setOf(UnitClass.INFANTRY.value, UnitClass.GROUND_TRANSPORT.value),
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.RESILIENCE,
                signatureTitle = "Baltic Detachment",
                signatureDescription = "Sailors ashore do not give ground; the line holds where he stands on it.",
                startingRankId = "captain",
                portraitArtId = "rcw_sailor",
            ),
            LegendaryHero(
                id = "rcw_kombrig",
                name = "Kombrig Ilya Nesterov",
                campaignIds = CIVIL_WAR,
                nationIds = setOf(NATION_RED_RUSSIA_103),
                yearRange = 1918..1922,
                compatibleUnitClasses = setOf(UnitClass.INFANTRY.value),
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.SHOCK_TACTICS,
                signatureTitle = "Storm Brigade",
                signatureDescription = "Throws the whole brigade at one point of the line and does not thin it.",
                startingRankId = "major",
                portraitArtId = "rcw_budenovka_officer",
            ),
            LegendaryHero(
                id = "rcw_partisan",
                name = "Commander Grigory Voloshin",
                campaignIds = CIVIL_WAR,
                nationIds = setOf(NATION_RED_RUSSIA_103),
                yearRange = 1918..1922,
                compatibleUnitClasses = setOf(UnitClass.RECON.value, UnitClass.INFANTRY.value),
                backgroundId = "veteran_reconnaissance_officer",
                signatureTrait = LeaderType.FOREST_CAMOUFLAGE,
                signatureTitle = "Taiga Band",
                signatureDescription = "Fights out of the forest and is gone before the column can deploy.",
                startingRankId = "captain",
                portraitArtId = "rcw_partisan_bearded",
            ),
            LegendaryHero(
                id = "rcw_voenspets",
                name = "Voenspets Nikolai Arkhangelsky",
                campaignIds = CIVIL_WAR,
                nationIds = setOf(NATION_RED_RUSSIA_103),
                yearRange = 1918..1922,
                compatibleUnitClasses = setOf(UnitClass.ARTILLERY.value),
                backgroundId = "regimental_artillery_officer",
                signatureTrait = LeaderType.BATTLEFIELD_INTELLIGENCE,
                signatureTitle = "Imperial Schooling",
                signatureDescription = "An old army's staff training, kept on and pointed the other way.",
                startingRankId = "major",
                portraitArtId = "rcw_staff_spectacles",
            ),
            LegendaryHero(
                id = "rcw_armoured_car",
                name = "Commander Anton Reshetov",
                campaignIds = CIVIL_WAR,
                nationIds = setOf(NATION_RED_RUSSIA_103),
                yearRange = 1918..1922,
                compatibleUnitClasses = setOf(UnitClass.TANK.value, UnitClass.GROUND_TRANSPORT.value),
                backgroundId = "transport_column_officer",
                signatureTrait = LeaderType.MECHANIZED_VETERAN,
                signatureTitle = "Armoured Detachment",
                signatureDescription = "Nurses worn-out armoured cars further than their engines should carry them.",
                startingRankId = "captain",
                portraitArtId = "rcw_pool_leather_m",
            ),
            LegendaryHero(
                id = "rcw_line_officer",
                name = "Commander Timofei Lagunov",
                campaignIds = CIVIL_WAR,
                nationIds = setOf(NATION_RED_RUSSIA_103),
                yearRange = 1918..1922,
                compatibleUnitClasses = setOf(UnitClass.INFANTRY.value),
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.FEROCIOUS_DEFENSE,
                signatureTitle = "Dug In",
                signatureDescription = "Chooses ground badly worth holding and then holds it anyway.",
                startingRankId = "lieutenant",
                portraitArtId = "rcw_pool_shaven_m",
            ),
            LegendaryHero(
                id = "rcw_medic",
                name = "Commander Zoya Rakhmanova",
                campaignIds = CIVIL_WAR,
                nationIds = setOf(NATION_RED_RUSSIA_103),
                yearRange = 1918..1922,
                compatibleUnitClasses = setOf(UnitClass.INFANTRY.value),
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.DETERMINED_DEFENSE,
                signatureTitle = "Field Dressing Station",
                signatureDescription = "Took the company when its commander fell, and gave back fewer of the wounded.",
                startingRankId = "lieutenant",
                portraitArtId = "rcw_medic_f",
                female = true,
            ),
            LegendaryHero(
                id = "rcw_scout",
                name = "Commander Marfa Belozerova",
                campaignIds = CIVIL_WAR,
                nationIds = setOf(NATION_RED_RUSSIA_103),
                yearRange = 1918..1922,
                compatibleUnitClasses = setOf(UnitClass.RECON.value, UnitClass.INFANTRY.value),
                backgroundId = "veteran_reconnaissance_officer",
                signatureTrait = LeaderType.RECON_MOVEMENT,
                signatureTitle = "Forward Screen",
                signatureDescription = "Rides ahead of the column and comes back knowing what is in front of it.",
                startingRankId = "lieutenant",
                portraitArtId = "rcw_pool_budenovka_f",
                female = true,
            ),
            LegendaryHero(
                id = "rcw_battery",
                name = "Commander Yevgenia Sotnikova",
                campaignIds = CIVIL_WAR,
                nationIds = setOf(NATION_RED_RUSSIA_103),
                yearRange = 1918..1922,
                compatibleUnitClasses = setOf(UnitClass.ARTILLERY.value),
                backgroundId = "regimental_artillery_officer",
                signatureTrait = LeaderType.COMBAT_SUPPORT,
                signatureTitle = "Two Guns and a Cart",
                signatureDescription = "Gets a battery into action off the march faster than it has any right to.",
                startingRankId = "lieutenant",
                portraitArtId = "rcw_pool_pilotka_f",
                female = true,
            ),
        )

    /** One authored-fictional composite for every remaining player side in the shipped campaigns. */
    private val OTHER_CAMPAIGN_SIDES: List<LegendaryHero> =
        listOf(
            LegendaryHero(
                id = "red_army_internationalist",
                name = "Captain Alexei Serebryakov",
                campaignIds = setOf("camp6bn9.json"),
                nationIds = setOf(19),
                yearRange = 1936..1940,
                compatibleUnitClasses = COMMON_LAND_CLASSES,
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.COMBAT_SUPPORT,
                signatureTitle = "International Detachment",
                signatureDescription = "Turns unfamiliar weapons and mixed detachments into a coordinated formation.",
                startingRankId = "captain",
            ),
            LegendaryHero(
                id = "yugoslav_woodland",
                name = "Captain Milan Vukovic",
                campaignIds = setOf("camp6bn8.json"),
                nationIds = setOf(43),
                yearRange = 1941..1945,
                compatibleUnitClasses = COMMON_LAND_CLASSES,
                backgroundId = "veteran_reconnaissance_officer",
                signatureTrait = LeaderType.FOREST_CAMOUFLAGE,
                signatureTitle = "Mountain Detachment",
                signatureDescription =
                    "Moves a brigade through wooded high ground without offering the enemy a target.",
                startingRankId = "captain",
            ),
            LegendaryHero(
                id = "north_korean_vanguard",
                name = "Captain Kang Chol",
                campaignIds = setOf("ncampdfn.json"),
                nationIds = setOf(25),
                yearRange = 1950..1953,
                compatibleUnitClasses = COMMON_LAND_CLASSES,
                backgroundId = "armored_academy_graduate",
                signatureTrait = LeaderType.OVERWHELMING_ATTACK,
                signatureTitle = "Vanguard Column",
                signatureDescription = "Turns a breach into a pursuit before the defending line can reform.",
                startingRankId = "captain",
            ),
            LegendaryHero(
                id = "german_fortyeighter",
                name = "Captain Friedrich Adler",
                campaignIds = setOf("aljf.json"),
                nationIds = setOf(196),
                yearRange = 1848..1865,
                compatibleUnitClasses = COMMON_LAND_CLASSES,
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.INFLUENCE,
                signatureTitle = "Citizen Officer",
                signatureDescription = "Finds arms, horses and willing hands wherever the cause still has friends.",
                startingRankId = "captain",
            ),
            LegendaryHero(
                id = "red_hungarian_mobile",
                name = "Captain Laszlo Farkas",
                campaignIds = setOf("rhu.json"),
                nationIds = setOf(187),
                yearRange = 1919..1919,
                compatibleUnitClasses = COMMON_LAND_CLASSES,
                backgroundId = "veteran_reconnaissance_officer",
                signatureTrait = LeaderType.SUPERIOR_MANEUVER,
                signatureTitle = "Northern Column",
                signatureDescription = "Keeps the advance moving through gaps the enemy believes are closed.",
                startingRankId = "captain",
            ),
            LegendaryHero(
                id = "red_german_council_guard",
                name = "Captain Otto Reimers",
                campaignIds = setOf("novemberrevolution.json"),
                nationIds = setOf(188),
                yearRange = 1918..1919,
                compatibleUnitClasses = COMMON_LAND_CLASSES,
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.COMBAT_SUPPORT,
                signatureTitle = "Council Network",
                signatureDescription = "Links scattered detachments into a line that shares what each has learned.",
                startingRankId = "captain",
            ),
            LegendaryHero(
                id = "czechoslovak_rail_guard",
                name = "Captain Jan Novak",
                campaignIds = setOf("acampdf2.json"),
                nationIds = setOf(144),
                yearRange = 1917..1920,
                compatibleUnitClasses = COMMON_LAND_CLASSES,
                backgroundId = "transport_column_officer",
                signatureTrait = LeaderType.RESILIENCE,
                signatureTitle = "Railway Legion",
                signatureDescription =
                    "Keeps men and machines together through another thousand kilometres of retreat.",
                startingRankId = "captain",
            ),
            LegendaryHero(
                id = "spanish_popular_army",
                name = "Captain Isabel Navarro",
                campaignIds = setOf("gce.json"),
                nationIds = setOf(226),
                yearRange = 1936..1939,
                compatibleUnitClasses = COMMON_LAND_CLASSES,
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.STREET_FIGHTER,
                signatureTitle = "Barricade Command",
                signatureDescription = "Makes every courtyard and stairwell part of the defensive plan.",
                startingRankId = "captain",
                female = true,
            ),
            LegendaryHero(
                id = "ancient_rebel_liberator",
                name = "Castus",
                campaignIds = setOf("spa.json"),
                nationIds = setOf(310),
                yearRange = -73..-71,
                compatibleUnitClasses = COMMON_LAND_CLASSES,
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.LIBERATOR,
                signatureTitle = "Broken Chains",
                signatureDescription = "Every captured settlement brings more people and supplies to the uprising.",
                startingRankId = "captain",
            ),
            LegendaryHero(
                id = "viet_cong_trailmaster",
                name = "Tran Minh",
                campaignIds = setOf("nvc.json"),
                nationIds = setOf(276),
                yearRange = 1964..1975,
                compatibleUnitClasses = COMMON_LAND_CLASSES,
                backgroundId = "veteran_reconnaissance_officer",
                signatureTrait = LeaderType.INFILTRATION_TACTICS,
                signatureTitle = "Hidden Approach",
                signatureDescription = "Finds the covered route that puts the formation inside the enemy position.",
                startingRankId = "captain",
            ),
            LegendaryHero(
                id = "chinese_long_march_scout",
                name = "Wang Ming",
                campaignIds = setOf("rsoc.json"),
                nationIds = setOf(21),
                yearRange = 1927..1949,
                compatibleUnitClasses = COMMON_LAND_CLASSES,
                backgroundId = "veteran_reconnaissance_officer",
                signatureTrait = LeaderType.RECON_MOVEMENT,
                signatureTitle = "Long March Screen",
                signatureDescription = "Keeps contact ahead of the column without surrendering the road behind it.",
                startingRankId = "captain",
            ),
            LegendaryHero(
                id = "greek_mountain_officer",
                name = "Captain Dimitrios Karalis",
                campaignIds = setOf("camp6bn4.json"),
                nationIds = setOf(39),
                yearRange = 1940..1949,
                compatibleUnitClasses = COMMON_LAND_CLASSES,
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.ALPINE_TRAINING,
                signatureTitle = "Pindus Paths",
                signatureDescription = "Treats steep tracks and forested ridges as roads known since childhood.",
                startingRankId = "captain",
            ),
            LegendaryHero(
                id = "white_russian_rearguard",
                name = "Captain Nikolai Orlov",
                campaignIds = setOf("simpob.json"),
                nationIds = setOf(100),
                yearRange = 1918..1920,
                compatibleUnitClasses = COMMON_LAND_CLASSES,
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.FIRST_STRIKE,
                signatureTitle = "Rearguard Reflex",
                signatureDescription = "Has the line firing before the advancing enemy finishes deploying.",
                startingRankId = "captain",
            ),
        )

    val ALL: List<LegendaryHero> = SOVIET_WW2 + RUSSIAN_CIVIL_WAR + OTHER_CAMPAIGN_SIDES

    private val byId: Map<String, LegendaryHero> = ALL.associateBy { it.id }

    fun byId(id: String): LegendaryHero? = byId[id]

    /** Legacy test/tool query retained; runtime reservation uses the full-context overload below. */
    fun compatible(
        hero: LegendaryHero,
        unitClass: Int,
        year: Int?,
    ): Boolean = unitClass in hero.compatibleUnitClasses && (year == null || year in hero.yearRange)

    /** Legacy test/tool reservation retained for callers that do not have campaign context. */
    fun reserve(
        campaignId: String,
        year: Int?,
    ): String? {
        val candidates = ALL.filter { year == null || year in it.yearRange }.sortedBy { it.id }
        return SeededRandom(SeededRandom.seedFrom(campaignId, "legendary_reservation")).pick(candidates)?.id
    }

    /** Whether [hero] can attach in this exact campaign context. */
    fun compatible(
        hero: LegendaryHero,
        campaignId: String,
        nationId: Int?,
        unitClass: Int,
        year: Int?,
    ): Boolean =
        normalizeCampaignId(campaignId) in hero.campaignIds &&
            nationId != null &&
            nationId in hero.nationIds &&
            unitClass in hero.compatibleUnitClasses &&
            year != null &&
            year in hero.yearRange

    /**
     * Deterministically reserves a compatible authored hero, or the procedural fallback sentinel
     * when the authored pool has no match. The returned plain string preserves the v1 save shape.
     */
    fun reserve(
        campaignId: String,
        nationId: Int?,
        year: Int?,
        availableUnitClasses: Set<Int>,
    ): String {
        val candidates =
            ALL
                .filter { hero ->
                    availableUnitClasses.any { unitClass -> compatible(hero, campaignId, nationId, unitClass, year) }
                }.sortedBy { it.id }
        return SeededRandom(SeededRandom.seedFrom(campaignId, "legendary_reservation"))
            .pick(candidates)
            ?.id
            ?: PROCEDURAL_FALLBACK_ID
    }

    fun reservationCompatible(
        reservationId: String,
        campaignId: String,
        nationId: Int?,
        year: Int?,
        availableUnitClasses: Set<Int>,
    ): Boolean =
        reservationId == PROCEDURAL_FALLBACK_ID ||
            byId(reservationId)?.let { hero ->
                availableUnitClasses.any { compatible(hero, campaignId, nationId, it, year) }
            } == true

    private fun normalizeCampaignId(campaignId: String): String =
        campaignId
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .lowercase()

    /** Builds the authored hero's identity and opening career for an emergence at [request]. */
    fun build(
        hero: LegendaryHero,
        request: ProceduralHeroGenerator.Request,
    ): Pair<HeroDefinition, HeroState> {
        val signatureId = LegacyTraitMapping.toTraitId(hero.signatureTrait)
        val definition =
            HeroDefinition(
                id = request.heroId,
                origin = HeroOrigin.AUTHORED_FICTIONAL,
                displayName = hero.name,
                backgroundId = hero.backgroundId,
                biographyFacts =
                    HeroBiographyFacts(
                        birthYear = request.serviceYear?.let { it - LEGENDARY_AGE },
                        prewarProfessionId = hero.backgroundId,
                        emergenceEventId = request.event.eventId,
                    ),
                // The composed layer stack is kept even though a painting exists: it is the fallback
                // if the asset is ever missing (see HeroPortraitArt), so the dossier degrades to a
                // procedural face rather than to an empty frame.
                portrait =
                    PortraitComposerV2
                        .composeFor(
                            seed = request.seed,
                            unitClass = request.unitClass,
                            rankId = hero.startingRankId,
                            birthYear = request.serviceYear?.let { it - LEGENDARY_AGE },
                            serviceYear = request.serviceYear,
                            country = request.country,
                        ).copy(artId = hero.portraitArtId, female = hero.female),
                signatureTraitId = signatureId,
            )
        val state =
            HeroState(
                heroId = request.heroId,
                rankId = hero.startingRankId,
                potential = HeroPotential.AUTHORED_LEGENDARY,
                renown = HeroRenown.DISTINGUISHED,
                assignedFormationId = request.formationId,
                learnedTraitIds = setOf(signatureId),
            )
        return definition to state
    }

    private const val LEGENDARY_AGE = 30
}
