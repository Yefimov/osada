package org.osada.hero

import org.osada.UnitClass
import org.osada.hero.PortraitComposerV2.deriveFacts

/**
 * Kotlin port of the v2 head-centric dossier portrait generator — the twin of
 * `resources/portraits/portrait-core-v2.mjs`. It selects the ordered v2 layer ids for a hero so the
 * game renders the approved redesign (not the v1 slice), selects the historical national/era
 * uniform pool, and derives scar/wound overlays from the hero's own condition so a wounded or
 * scarred commander looks the part (§11.1).
 *
 * Determinism is the contract (§7.4 / §29.17): every choice is a seeded, weighted function of the
 * portrait seed plus stable facts, so the same hero reproduces the same portrait on reload. The
 * weight tables mirror the manifest's `weights` block; keeping them here (rather than parsing JSON)
 * matches how [HeroBalance] keeps acquisition tuning in code.
 */
@Suppress("TooManyFunctions", "LargeClass")
object PortraitComposerV2 {
    enum class Pool(
        val id: String,
    ) {
        USSR_1942("ussr_1942"),
        USSR_1943("ussr_1943"),
        SOVIET_INTERWAR("soviet_interwar"),
        REVOLUTION_1919("revolution_1919"),
        SPANISH_REPUBLIC_1936("spanish_republic_1936"),
        YUGOSLAV_PARTISAN_1941("yugoslav_partisan_1941"),
        EAST_ASIAN_REVOLUTIONARY("east_asian_revolutionary"),
        GREEK_1940("greek_1940"),
        WHITE_ARMY_1919("white_army_1919"),
        ANCIENT_REBEL("ancient_rebel"),
        NONE("none"),
    }

    val ORDER =
        listOf(
            "background",
            "uniform_back",
            "hair_back",
            "face",
            "expression",
            "age_face",
            "scar",
            "facial_hair",
            "hair_front",
            "under_headgear_hair",
            "uniform_front_collar",
            "rank",
            "branch",
            "accessory",
            "headgear",
            "wound",
        )

    private val CATEGORY_DIR =
        mapOf(
            "background" to "background",
            "uniform_back" to "uniform_back",
            "hair_back" to "hair_back",
            "face" to "face",
            "expression" to "expression",
            "age_face" to "age_face",
            "scar" to "scar",
            "facial_hair" to "facial_hair",
            "hair_front" to "hair_front",
            "under_headgear_hair" to "under_headgear_hair",
            "uniform_front_collar" to "uniform_front_collar",
            "rank" to "rank",
            "branch" to "branch",
            "accessory" to "accessory",
            "headgear" to "headgear",
            "wound" to "wound",
        )

    private val BACKGROUNDS = listOf("bg_dossier_slate", "bg_dossier_stone")
    private val FEMALE_FACES = listOf("female_oval", "female_heart", "female_broad", "female_square", "female_long")
    private val MALE_FRONT = listOf("hair_front_crop", "hair_front_side", "hair_front_receding", "hair_front_swept")
    private val FEMALE_HAIR_STYLES = listOf("bob", "braid", "bun", "waves")
    private val MODERN_ACCESSORIES = listOf("accessory_round_spectacles", "accessory_wire_spectacles")
    private val EARLY_ACCESSORIES = listOf("accessory_round_spectacles", "accessory_pince_nez")
    private val WOUNDS = listOf("wound_brow_bandage", "wound_cheek_dressing", "wound_eye_patch")
    private val SCARS = listOf("scar_cheek", "scar_brow", "scar_lip")

    private val RANK_AGE =
        mapOf(
            "lieutenant" to mapOf("young" to 0.7, "middle" to 0.3),
            "captain" to mapOf("young" to 0.4, "middle" to 0.5, "old" to 0.1),
            "major" to mapOf("young" to 0.1, "middle" to 0.55, "old" to 0.35),
            "colonel" to mapOf("young" to 0.05, "middle" to 0.35, "old" to 0.6),
        )
    private val AGE_ARCHETYPE =
        mapOf(
            "young" to
                mapOf(
                    "round_young" to 0.16,
                    "narrow_stern" to 0.12,
                    "broad_calm" to 0.08,
                    "long_mature" to 0.04,
                    "angular_tired" to 0.02,
                    "square_veteran" to 0.02,
                    "oval_reserved" to 0.18,
                    "compact_alert" to 0.2,
                    "high_cheekbones" to 0.16,
                    "heavy_jaw" to 0.02,
                    "lean_focused" to 0.16,
                    "rectangular_calm" to 0.04,
                    "female_oval" to 0.24,
                    "female_heart" to 0.3,
                    "female_broad" to 0.12,
                    "female_square" to 0.16,
                    "female_long" to 0.18,
                ),
            "middle" to
                mapOf(
                    "broad_calm" to 0.11,
                    "narrow_stern" to 0.1,
                    "long_mature" to 0.11,
                    "square_veteran" to 0.08,
                    "angular_tired" to 0.06,
                    "round_young" to 0.04,
                    "oval_reserved" to 0.14,
                    "compact_alert" to 0.11,
                    "high_cheekbones" to 0.13,
                    "heavy_jaw" to 0.12,
                    "lean_focused" to 0.13,
                    "rectangular_calm" to 0.11,
                    "female_oval" to 0.25,
                    "female_heart" to 0.15,
                    "female_broad" to 0.2,
                    "female_square" to 0.22,
                    "female_long" to 0.18,
                ),
            "old" to
                mapOf(
                    "square_veteran" to 0.16,
                    "long_mature" to 0.14,
                    "angular_tired" to 0.12,
                    "narrow_stern" to 0.05,
                    "broad_calm" to 0.06,
                    "oval_reserved" to 0.08,
                    "compact_alert" to 0.06,
                    "high_cheekbones" to 0.14,
                    "heavy_jaw" to 0.19,
                    "lean_focused" to 0.09,
                    "rectangular_calm" to 0.17,
                    "female_oval" to 0.18,
                    "female_heart" to 0.1,
                    "female_broad" to 0.27,
                    "female_square" to 0.27,
                    "female_long" to 0.18,
                ),
        )
    private val FACIAL_BY_AGE =
        mapOf(
            "young" to
                mapOf(
                    "facial_clean" to 0.55,
                    "facial_stubble" to 0.3,
                    "facial_mustache" to 0.12,
                    "facial_beard" to 0.03,
                ),
            "middle" to
                mapOf("facial_clean" to 0.4, "facial_stubble" to 0.3, "facial_mustache" to 0.2, "facial_beard" to 0.1),
            "old" to
                mapOf(
                    "facial_clean" to 0.35,
                    "facial_stubble" to 0.25,
                    "facial_mustache" to 0.25,
                    "facial_beard" to 0.15,
                ),
        )
    private val HEADGEAR_BY_BRANCH_SEASON =
        mapOf(
            "infantry" to
                mapOf(
                    "summer" to
                        mapOf(
                            "headgear_officer_cap" to 0.3,
                            "headgear_pilotka" to 0.25,
                            "headgear_ssh40" to 0.25,
                            "none" to 0.2,
                        ),
                    "winter" to
                        mapOf(
                            "headgear_ushanka" to 0.55,
                            "headgear_ssh40" to 0.2,
                            "headgear_officer_cap" to 0.15,
                            "none" to 0.1,
                        ),
                ),
            "artillery" to
                mapOf(
                    "summer" to
                        mapOf(
                            "headgear_officer_cap" to 0.3,
                            "headgear_pilotka" to 0.25,
                            "headgear_ssh40" to 0.25,
                            "none" to 0.2,
                        ),
                    "winter" to
                        mapOf(
                            "headgear_ushanka" to 0.55,
                            "headgear_ssh40" to 0.2,
                            "headgear_officer_cap" to 0.15,
                            "none" to 0.1,
                        ),
                ),
            "armor" to
                mapOf(
                    "summer" to mapOf("headgear_officer_cap" to 0.5, "none" to 0.3, "headgear_pilotka" to 0.2),
                    "winter" to mapOf("headgear_ushanka" to 0.6, "headgear_officer_cap" to 0.3, "none" to 0.1),
                ),
            "aviation" to
                mapOf(
                    "summer" to mapOf("headgear_flight_helmet" to 0.7, "none" to 0.2, "headgear_officer_cap" to 0.1),
                    "winter" to mapOf("headgear_flight_helmet" to 0.7, "none" to 0.2, "headgear_officer_cap" to 0.1),
                ),
        )
    private val REVOLUTION_HEADGEAR =
        fieldHeadgear(
            groundPrimary = "headgear_rev1919_field_cap",
            groundSecondary = "headgear_rev1919_service_cap",
        )
    private val SOVIET_INTERWAR_HEADGEAR =
        threeWayFieldHeadgear(
            groundPrimary = "headgear_rev1919_field_cap",
            groundSecondary = "headgear_rev1919_service_cap",
            groundTertiary = "headgear_budenovka",
        )
    private val SPANISH_HEADGEAR =
        threeWayFieldHeadgear(
            groundPrimary = "headgear_spanish_side_cap",
            groundSecondary = "headgear_spanish_beret",
            groundTertiary = "headgear_spanish_adrian",
        )
    private val YUGOSLAV_HEADGEAR =
        fieldHeadgear(
            groundPrimary = "headgear_yugoslav_titovka",
            groundSecondary = "headgear_yugoslav_partisan_cap",
        )
    private val EAST_ASIAN_HEADGEAR =
        fieldHeadgear(
            groundPrimary = "headgear_east_asian_field_cap",
            groundSecondary = "headgear_east_asian_boonie",
        )
    private val GREEK_HEADGEAR =
        seasonalThreeWayHeadgear(
            groundPrimary = "headgear_greek_side_cap",
            groundSecondary = "headgear_greek_field_cap",
            groundTertiary = "headgear_greek_m1936",
        )
    private val WHITE_ARMY_HEADGEAR =
        seasonalFieldHeadgear(
            groundPrimary = "headgear_white_army_cap",
            groundSecondary = "headgear_white_army_papakha",
        )
    private val ANCIENT_HEADGEAR =
        uniformHeadgear(
            groundPrimary = "headgear_ancient_pilos",
            groundSecondary = "headgear_ancient_phrygian",
            primaryWeight = 0.45,
            secondaryWeight = 0.35,
        )
    private val HAIR_MODE =
        mapOf(
            "headgear_officer_cap" to "UNDER_CAP",
            "headgear_pilotka" to "UNDER_CAP",
            "headgear_ssh40" to "UNDER_CAP",
            "headgear_ushanka" to "UNDER_FUR_HAT",
            "headgear_flight_helmet" to "UNDER_FLIGHT_HELMET",
            "headgear_rev1919_field_cap" to "UNDER_CAP",
            "headgear_rev1919_service_cap" to "UNDER_CAP",
            "headgear_budenovka" to "UNDER_CAP",
            "headgear_spanish_side_cap" to "UNDER_CAP",
            "headgear_spanish_beret" to "UNDER_CAP",
            "headgear_spanish_adrian" to "UNDER_CAP",
            "headgear_yugoslav_titovka" to "UNDER_CAP",
            "headgear_yugoslav_partisan_cap" to "UNDER_CAP",
            "headgear_east_asian_field_cap" to "UNDER_CAP",
            "headgear_east_asian_boonie" to "UNDER_CAP",
            "headgear_greek_side_cap" to "UNDER_CAP",
            "headgear_greek_field_cap" to "UNDER_CAP",
            "headgear_greek_m1936" to "UNDER_CAP",
            "headgear_white_army_cap" to "UNDER_CAP",
            "headgear_white_army_papakha" to "UNDER_CAP",
            "headgear_ancient_pilos" to "UNDER_CAP",
            "headgear_ancient_phrygian" to "UNDER_CAP",
        )
    private val UNDER_HAIR =
        mapOf(
            "headgear_officer_cap" to listOf("under_hair_temples", "under_hair_side_part"),
            "headgear_pilotka" to listOf("under_hair_short_fringe", "under_hair_side_part"),
            "headgear_ssh40" to listOf("under_hair_short_fringe", "under_hair_temples"),
            "headgear_rev1919_field_cap" to listOf("under_hair_short_fringe", "under_hair_side_part"),
            "headgear_rev1919_service_cap" to listOf("under_hair_temples", "under_hair_side_part"),
            "headgear_budenovka" to listOf("under_hair_short_fringe", "under_hair_temples"),
            "headgear_spanish_side_cap" to listOf("under_hair_short_fringe", "under_hair_side_part"),
            "headgear_spanish_beret" to listOf("under_hair_curls", "under_hair_side_part"),
            "headgear_spanish_adrian" to listOf("under_hair_short_fringe", "under_hair_temples"),
            "headgear_yugoslav_titovka" to listOf("under_hair_short_fringe", "under_hair_side_part"),
            "headgear_yugoslav_partisan_cap" to listOf("under_hair_short_fringe", "under_hair_temples"),
            "headgear_east_asian_field_cap" to listOf("under_hair_short_fringe", "under_hair_side_part"),
            "headgear_east_asian_boonie" to listOf("under_hair_temples", "under_hair_side_part"),
            "headgear_greek_side_cap" to listOf("under_hair_short_fringe", "under_hair_side_part"),
            "headgear_greek_field_cap" to listOf("under_hair_temples", "under_hair_side_part"),
            "headgear_greek_m1936" to listOf("under_hair_short_fringe", "under_hair_temples"),
            "headgear_white_army_cap" to listOf("under_hair_temples", "under_hair_side_part"),
            "headgear_white_army_papakha" to listOf("under_hair_curls"),
            "headgear_ancient_pilos" to listOf("under_hair_curls", "under_hair_short_fringe"),
            "headgear_ancient_phrygian" to listOf("under_hair_curls", "under_hair_side_part"),
        )
    private val UNDER_HAIR_FEMALE =
        mapOf(
            "headgear_white_army_papakha" to listOf("under_hair_female_fur"),
            "headgear_ancient_pilos" to listOf("under_hair_female_close"),
            "headgear_ancient_phrygian" to listOf("under_hair_female_close"),
        )
    private val SUPPRESS_HAIR_BACK = setOf("headgear_ancient_pilos", "headgear_ancient_phrygian")
    private val FACE_JAW_FIT =
        mapOf(
            "narrow_stern" to "narrow",
            "angular_tired" to "narrow",
            "long_mature" to "narrow",
            "high_cheekbones" to "narrow",
            "lean_focused" to "narrow",
            "round_young" to "medium",
            "oval_reserved" to "medium",
            "compact_alert" to "medium",
            "broad_calm" to "wide",
            "square_veteran" to "wide",
            "heavy_jaw" to "wide",
            "rectangular_calm" to "wide",
            "female_oval" to "narrow",
            "female_heart" to "narrow",
            "female_broad" to "medium",
            "female_square" to "medium",
            "female_long" to "narrow",
        )

    data class Facts(
        val branch: String,
        val gender: String,
        val rank: String,
        val age: String,
        val season: String,
        val scar: Boolean,
        val wound: String?,
        val pool: Pool = Pool.USSR_1942,
    )

    /** A finished v2 [PortraitComposition] for a hero, reflecting current rank and condition. */
    fun composeFor(
        seed: Int,
        unitClass: Int,
        rankId: String,
        birthYear: Int?,
        serviceYear: Int?,
        status: HeroStatus = HeroStatus.ACTIVE,
        permanentInjury: Boolean = false,
        country: Int? = null,
        poolOverride: Pool? = null,
    ): PortraitComposition {
        val facts =
            deriveFacts(
                seed,
                unitClass,
                rankId,
                birthYear,
                serviceYear,
                status,
                permanentInjury,
                country,
                poolOverride,
            )
        return PortraitComposition(seed = seed, layerIds = compose(facts, seed), poolId = facts.pool.id)
    }

    fun compose(
        facts: Facts,
        seed: Int,
    ): List<String> {
        if (facts.pool == Pool.NONE) return emptyList()
        val chosen = LinkedHashMap<String, String>()
        val headgear = pickHeadgear(facts, seed)
        val hairMode = if (headgear == null) "FULL_HAIR" else HAIR_MODE.getValue(headgear)
        val archetype = facts.archetype(seed)
        chosen["background"] = pick(BACKGROUNDS, seed, "bg")
        val uniformBack = if (facts.pool == Pool.ANCIENT_REBEL) "back_ancient_tunic" else "back_${facts.branch}"
        chosen["uniform_back"] = if (facts.gender == "female") "${uniformBack}_female" else uniformBack
        putHair(chosen, facts, seed, hairMode, headgear, archetype)
        chosen["face"] = "face_$archetype"
        ageLayer(facts.age)?.let { chosen["age_face"] = it }
        if (facts.scar) chosen["scar"] = pick(SCARS, seed, "scar")
        chosen["facial_hair"] = facialHair(facts, seed, archetype)
        chosen["uniform_front_collar"] = collarFor(facts)
        chosen["rank"] = rankFor(facts)
        chosen["branch"] = if (facts.pool == Pool.ANCIENT_REBEL) "branch_ancient_rebel" else "branch_${facts.branch}"
        val canWearAccessory = facts.pool != Pool.ANCIENT_REBEL && facts.wound != "wound_eye_patch"
        if (canWearAccessory && chance(seed, "accessory", ACCESSORY_CHANCE)) {
            chosen["accessory"] = pick(accessoriesFor(facts.pool), seed, "accessory-type")
        }
        headgear?.let { chosen["headgear"] = it }
        facts.wound?.let { chosen["wound"] = it }
        return ORDER.mapNotNull { chosen[it] }
    }

    /** Resource-relative paths (under `resources/`) for an ordered v2 id list, ready to fetch and stack. */
    fun pathsFor(layerIds: List<String>): List<String> = layerIds.mapNotNull(::layerPath)

    /**
     * The ordered v2 layer paths to render for a hero: a stable base identity (the stored v2 layers,
     * or re-derived from the seed for a v1/empty save) plus live wound/scar overlays from the hero's
     * current condition (§11.1). The base stays fixed so the face never shifts; only the injuries
     * evolve as the campaign wounds or scars the commander.
     */
    fun forHero(
        definition: HeroDefinition,
        state: HeroState,
        unitClass: Int,
        country: Int? = null,
    ): List<String> {
        val stored = definition.portrait.layerIds
        val female = definition.portrait.female ?: (genderFor(definition.portrait.seed) == "female")
        val base =
            if (stored.any { it.startsWith("face_") }) {
                repairLegacyLayerFits(stored, definition.portrait.seed, female, definition.portrait.poolId)
            } else {
                composeFor(
                    definition.portrait.seed,
                    unitClass,
                    state.rankId,
                    definition.biographyFacts.birthYear,
                    null,
                    country = country,
                    poolOverride = poolById(definition.portrait.poolId),
                ).layerIds
            }
        val withExpression = applyCharacterExpression(base, definition, state)
        val withCondition = applyCondition(withExpression, state, definition.portrait.seed)
        return pathsFor(withCondition)
    }

    private fun applyCondition(
        base: List<String>,
        state: HeroState,
        seed: Int,
    ): List<String> {
        val out = base.toMutableList()
        if (state.injuries.any { it.permanent } && out.none { it.startsWith("scar_") }) out += pick(SCARS, seed, "scar")
        val wounded = state.status == HeroStatus.WOUNDED || state.status == HeroStatus.SERIOUSLY_WOUNDED
        if (wounded && out.none { it.startsWith("wound_") }) out += pick(WOUNDS, seed, "wound")
        if (out.any { it == "wound_eye_patch" }) out.removeAll { it.startsWith("accessory_") }
        return out.distinct().sortedBy { ORDER.indexOf(categoryOf(it)) }
    }

    /** A hero's expression follows their strongest character-defining trait, never a fresh roll. */
    private fun applyCharacterExpression(
        base: List<String>,
        definition: HeroDefinition,
        state: HeroState,
    ): List<String> {
        // Pool.NONE is a persisted decision to render the monogram fallback. Adding even an
        // expression layer here would turn that saved no-portrait verdict back into a face fragment.
        if (base.isEmpty()) return emptyList()
        val traits = state.learnedTraitIds + listOfNotNull(definition.signatureTraitId)
        val expression =
            when {
                traits.any(::isAggressiveTrait) -> "expression_aggressive"
                traits.any(::isDeterminedTrait) -> "expression_determined"
                else -> "expression_calm"
            }
        return (base.filterNot { it.startsWith("expression_") } + expression)
            .distinct()
            .sortedBy { ORDER.indexOf(categoryOf(it)) }
    }

    private fun isAggressiveTrait(id: String): Boolean {
        val normalized = id.uppercase()
        return normalized.contains("AGGRESSIVE") ||
            normalized.contains("OVERWHELMING") ||
            normalized.contains("FLUID_MANEUVER") ||
            normalized.contains("OPEN_GROUND_INITIATIVE")
    }

    private fun isDeterminedTrait(id: String): Boolean {
        val normalized = id.uppercase()
        return normalized.contains("DETERMINED") ||
            normalized.contains("HARDENED") ||
            normalized.contains("RESILIENCE") ||
            normalized.contains("FEROCIOUS") ||
            normalized.contains("DUG_IN")
    }

    fun layerPath(id: String): String? =
        categoryOf(id)?.let(CATEGORY_DIR::get)?.let { dir -> "portraits/v2/layers/$dir/$id.svg" }

    // ------------------------------------------------------------------ internals

    private fun Facts.archetype(seed: Int): String {
        val base = AGE_ARCHETYPE.getValue(age)
        val weights =
            if (gender ==
                "female"
            ) {
                base.filterKeys { it in FEMALE_FACES }.ifEmpty { mapOf("round_young" to 1.0) }
            } else {
                base.filterKeys { it !in FEMALE_FACES }
            }
        return weightedPick(weights, seed, "arch")
    }

    private fun putHair(
        chosen: LinkedHashMap<String, String>,
        facts: Facts,
        seed: Int,
        hairMode: String,
        headgear: String?,
        archetype: String,
    ) {
        if (facts.gender == "female") {
            val style = femaleHairStyle(seed)
            if (headgear !in SUPPRESS_HAIR_BACK) chosen["hair_back"] = femaleHairBack(style)
            when (hairMode) {
                "FULL_HAIR" -> chosen["hair_front"] = femaleHairFront(style)
                "UNDER_CAP" -> {
                    val headgearHair = headgear?.let(UNDER_HAIR_FEMALE::get)
                    chosen["under_headgear_hair"] =
                        if (headgearHair != null) {
                            pick(headgearHair, seed, "underhairfemale")
                        } else {
                            femaleUnderHair(style)
                        }
                }
            }
        } else {
            when (hairMode) {
                "FULL_HAIR" -> {
                    chosen["hair_back"] =
                        if (chance(seed, "hairvol", HAIR_VOLUME_CHANCE)) {
                            "hair_back_full"
                        } else {
                            shortHairBack(archetype)
                        }
                    chosen["hair_front"] = pick(MALE_FRONT, seed, "hairfront")
                }

                "UNDER_CAP" -> {
                    if (headgear !in SUPPRESS_HAIR_BACK) chosen["hair_back"] = shortHairBack(archetype)
                    chosen["under_headgear_hair"] =
                        pick(headgear?.let(UNDER_HAIR::get) ?: listOf("under_hair_temples"), seed, "underhair")
                }
            }
        }
    }

    private fun femaleHairStyle(seed: Int): String = pick(FEMALE_HAIR_STYLES, seed, "hairstyle")

    private fun femaleHairBack(style: String): String =
        when (style) {
            "bob" -> "hair_back_female_bob"
            "braid" -> "hair_back_female_braid"
            "bun" -> "hair_back_female_bun"
            else -> "hair_back_female"
        }

    private fun femaleHairFront(style: String): String =
        when (style) {
            "bob" -> "hair_front_female_a"
            "braid" -> "hair_front_female_braided"
            "bun" -> "hair_front_female_bun"
            else -> "hair_front_female_b"
        }

    private fun femaleUnderHair(style: String): String =
        when (style) {
            "bob" -> "under_hair_female_bob"
            "braid" -> "under_hair_female_braid"
            "bun" -> "under_hair_female_bun"
            else -> "under_hair_female"
        }

    private fun shortHairBack(archetype: String): String = "hair_back_short_${FACE_JAW_FIT[archetype] ?: "medium"}"

    private fun facialHair(
        facts: Facts,
        seed: Int,
        archetype: String,
    ): String {
        if (facts.gender == "female") return "facial_clean"
        return fitFacialHair(weightedPick(FACIAL_BY_AGE.getValue(facts.age), seed, "facial"), archetype)
    }

    private fun fitFacialHair(
        facialHair: String,
        archetype: String,
    ): String =
        when (facialHair) {
            "facial_beard", "facial_stubble" -> "${facialHair}_${FACE_JAW_FIT[archetype] ?: "medium"}"
            else -> facialHair
        }

    private fun accessoriesFor(pool: Pool): List<String> =
        when (pool) {
            Pool.SOVIET_INTERWAR,
            Pool.REVOLUTION_1919,
            Pool.SPANISH_REPUBLIC_1936,
            Pool.WHITE_ARMY_1919,
            -> EARLY_ACCESSORIES

            else -> MODERN_ACCESSORIES
        }

    /** Correct geometry-only defects in old stored recipes without changing the hero's identity. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun repairLegacyLayerFits(
        stored: List<String>,
        seed: Int,
        female: Boolean,
        poolId: String?,
    ): List<String> {
        val legacyArchetype = stored.firstOrNull { it.startsWith("face_") }?.removePrefix("face_") ?: return stored
        val archetype =
            if (female && legacyArchetype !in FEMALE_FACES) {
                pick(FEMALE_FACES, seed, "arch-female-repair")
            } else {
                legacyArchetype
            }
        val headgear = stored.firstOrNull { it.startsWith("headgear_") }
        val compatibleUnderHair = headgear?.let(UNDER_HAIR::get)
        val repaired =
            stored.mapNotNull { id ->
                when {
                    id.startsWith("face_") -> "face_$archetype"
                    female && id.startsWith("back_") && !id.endsWith("_female") -> "${id}_female"
                    headgear in SUPPRESS_HAIR_BACK && id.startsWith("hair_back_") -> null
                    !female && id == "hair_back_short" -> shortHairBack(archetype)
                    female && id.startsWith("facial_") -> "facial_clean"
                    poolId == Pool.USSR_1942.id && id.matches(POST_1943_RANK_ID) ->
                        id.replace("rank_", "rank_pre1943_")
                    poolId == Pool.SOVIET_INTERWAR.id && id.startsWith("rank_rev1919_") ->
                        id.replace("rank_rev1919_", "rank_pre1943_")
                    id == "facial_beard" || id == "facial_stubble" -> fitFacialHair(id, archetype)
                    id.startsWith("under_hair_") && headgear in SUPPRESS_HAIR_BACK && female ->
                        pick(UNDER_HAIR_FEMALE.getValue(checkNotNull(headgear)), seed, "underhairfemale")
                    id == "under_hair_temples" && compatibleUnderHair != null && id !in compatibleUnderHair ->
                        pick(compatibleUnderHair, seed, "underhair")
                    else -> id
                }
            }
        if (headgear != "headgear_white_army_papakha") return repaired

        val withPapakhaHair = repaired.toMutableList()
        if (female) {
            if (withPapakhaHair.none { it == "hair_back_female" }) withPapakhaHair += "hair_back_female"
            withPapakhaHair.removeAll { it.startsWith("under_hair_") }
            withPapakhaHair += "under_hair_female_fur"
        } else {
            if (withPapakhaHair.none { it.startsWith("hair_back_") }) withPapakhaHair += shortHairBack(archetype)
            if (withPapakhaHair.none { it.startsWith("under_hair_") }) withPapakhaHair += "under_hair_curls"
        }
        return withPapakhaHair
    }

    private fun pickHeadgear(
        facts: Facts,
        seed: Int,
    ): String? {
        val catalog =
            when (facts.pool) {
                Pool.USSR_1942, Pool.USSR_1943 -> HEADGEAR_BY_BRANCH_SEASON
                Pool.SOVIET_INTERWAR -> SOVIET_INTERWAR_HEADGEAR
                Pool.REVOLUTION_1919 -> REVOLUTION_HEADGEAR
                Pool.SPANISH_REPUBLIC_1936 -> SPANISH_HEADGEAR
                Pool.YUGOSLAV_PARTISAN_1941 -> YUGOSLAV_HEADGEAR
                Pool.EAST_ASIAN_REVOLUTIONARY -> EAST_ASIAN_HEADGEAR
                Pool.GREEK_1940 -> GREEK_HEADGEAR
                Pool.WHITE_ARMY_1919 -> WHITE_ARMY_HEADGEAR
                Pool.ANCIENT_REBEL -> ANCIENT_HEADGEAR
                Pool.NONE -> return null
            }
        val key =
            weightedPick(catalog.getValue(facts.branch).getValue(facts.season), seed, "headgear")
        return if (key == "none") null else key
    }

    private fun collarFor(facts: Facts): String =
        when (facts.pool) {
            Pool.USSR_1942 ->
                if (facts.branch == "aviation") "collar_aviation" else "collar_${facts.branch}_${facts.season}"
            Pool.USSR_1943 -> "collar_ussr_1943_${facts.season}"

            Pool.SOVIET_INTERWAR -> "collar_rev1919_field"
            Pool.REVOLUTION_1919 -> "collar_rev1919_field"
            Pool.SPANISH_REPUBLIC_1936 -> "collar_spanish_republic"
            Pool.YUGOSLAV_PARTISAN_1941 -> "collar_yugoslav_partisan"
            Pool.EAST_ASIAN_REVOLUTIONARY -> "collar_east_asian_field"
            Pool.GREEK_1940 -> "collar_greek_1940"
            Pool.WHITE_ARMY_1919 -> "collar_white_army_1919"
            Pool.ANCIENT_REBEL -> "collar_ancient_rebel"
            Pool.NONE -> error("A no-portrait pool has no collar")
        }

    private fun rankFor(facts: Facts): String =
        when (facts.pool) {
            Pool.USSR_1942, Pool.SOVIET_INTERWAR -> "rank_pre1943_${facts.rank}"
            Pool.USSR_1943 -> "rank_${facts.rank}"
            Pool.REVOLUTION_1919 -> "rank_rev1919_${facts.rank}"
            Pool.SPANISH_REPUBLIC_1936 -> "rank_spanish_${facts.rank}"
            Pool.YUGOSLAV_PARTISAN_1941 -> "rank_yugoslav_${facts.rank}"
            Pool.EAST_ASIAN_REVOLUTIONARY -> "rank_east_asian_${facts.rank}"
            Pool.GREEK_1940 -> "rank_greek_${facts.rank}"
            Pool.WHITE_ARMY_1919 -> "rank_white_${facts.rank}"
            Pool.ANCIENT_REBEL -> "rank_ancient_${facts.rank}"
            Pool.NONE -> error("A no-portrait pool has no rank layer")
        }

    private fun ageLayer(age: String): String? =
        when (age) {
            "middle" -> "age_face_light"
            "old" -> "age_face_heavy"
            else -> null
        }

    /**
     * The hero's rolled gender, a pure function of [seed] alone (§4.11) — so any caller that needs
     * to agree with the portrait (biography narrator, name pools) can ask without pulling in the
     * unit-class/rank/birth-year context [deriveFacts] otherwise requires.
     */
    fun genderFor(seed: Int): String = if (chance(seed, "gender", FEMALE_CHANCE)) "female" else "male"

    private fun deriveFacts(
        seed: Int,
        unitClass: Int,
        rankId: String,
        birthYear: Int?,
        serviceYear: Int?,
        status: HeroStatus,
        permanentInjury: Boolean,
        country: Int?,
        poolOverride: Pool?,
    ): Facts {
        val rank = if (rankId in RANK_AGE) rankId else "lieutenant"
        val gender = genderFor(seed)
        val age = ageBand(seed, rank, birthYear, serviceYear)
        val season = if (chance(seed, "season", SEASON_WINTER_CHANCE)) "winter" else "summer"
        val wounded = status == HeroStatus.WOUNDED || status == HeroStatus.SERIOUSLY_WOUNDED
        return Facts(
            branch = branchFor(unitClass),
            gender = gender,
            rank = rank,
            age = age,
            season = season,
            scar = permanentInjury,
            wound = if (wounded) pick(WOUNDS, seed, "wound") else null,
            pool = poolOverride ?: poolFor(country, serviceYear),
        )
    }

    /**
     * Country ids are the merged equipment-country ids used by the actual scenario player. Unknown
     * countries deliberately receive the monogram fallback instead of silently wearing Soviet
     * insignia; `null` keeps the historical default for old/internal callers that predate country
     * threading.
     */
    @Suppress("CyclomaticComplexMethod", "MagicNumber") // Stable equipment-country ids form a lookup table.
    fun poolFor(
        country: Int?,
        serviceYear: Int? = null,
    ): Pool =
        when (country) {
            19 ->
                when {
                    serviceYear != null && serviceYear < 1941 -> Pool.SOVIET_INTERWAR
                    serviceYear != null && serviceYear >= 1943 -> Pool.USSR_1943
                    else -> Pool.USSR_1942
                }
            61, 89 -> if (serviceYear != null && serviceYear >= 1943) Pool.USSR_1943 else Pool.USSR_1942
            null -> Pool.USSR_1942
            103, 144, 187, 188, 196 -> Pool.REVOLUTION_1919
            226 -> Pool.SPANISH_REPUBLIC_1936
            43 -> Pool.YUGOSLAV_PARTISAN_1941
            21, 25, 276 -> Pool.EAST_ASIAN_REVOLUTIONARY
            39 -> Pool.GREEK_1940
            100 -> Pool.WHITE_ARMY_1919
            310 -> Pool.ANCIENT_REBEL
            else -> Pool.NONE
        }

    private fun poolById(id: String?): Pool? = Pool.entries.firstOrNull { it.id == id }

    private fun ageBand(
        seed: Int,
        rank: String,
        birthYear: Int?,
        serviceYear: Int?,
    ): String {
        if (birthYear != null && serviceYear != null) {
            val ageYears = serviceYear - birthYear
            return when {
                ageYears < MIDDLE_AGE -> "young"
                ageYears < OLD_AGE -> "middle"
                else -> "old"
            }
        }
        return weightedPick(RANK_AGE.getValue(rank), seed, "age")
    }

    private fun branchFor(unitClass: Int): String =
        when (unitClass) {
            UnitClass.TANK.value -> "armor"
            UnitClass.ARTILLERY.value,
            UnitClass.FLAK.value,
            UnitClass.AIR_DEFENCE.value,
            UnitClass.ANTI_TANK.value,
            -> "artillery"

            UnitClass.FIGHTER.value,
            UnitClass.TACTICAL_BOMBER.value,
            UnitClass.LEVEL_BOMBER.value,
            UnitClass.AIR_TRANSPORT.value,
            -> "aviation"

            else -> "infantry"
        }

    private fun categoryOf(id: String): String? = PREFIX_CATEGORY.firstOrNull { id.startsWith(it.first) }?.second

    private fun rng(
        seed: Int,
        salt: String,
    ) = SeededRandom(SeededRandom.seedFrom(seed.toString(), salt))

    private fun chance(
        seed: Int,
        salt: String,
        p: Double,
    ): Boolean = rng(seed, salt).roll(p)

    private fun pick(
        list: List<String>,
        seed: Int,
        salt: String,
    ): String = list[rng(seed, salt).nextInt(list.size)]

    /** Weighted key, keys walked in sorted order for a stable choice — twin of the JS `weightedPick`. */
    private fun weightedPick(
        weights: Map<String, Double>,
        seed: Int,
        salt: String,
    ): String {
        val entries = weights.entries.sortedBy { it.key }
        val total = entries.sumOf { it.value }
        var r = rng(seed, salt).nextDouble() * total
        for ((key, weight) in entries) {
            r -= weight
            if (r < 0) return key
        }
        return entries.last().key
    }

    private fun fieldHeadgear(
        groundPrimary: String,
        groundSecondary: String,
    ): Map<String, Map<String, Map<String, Double>>> {
        val ground =
            mapOf(
                "summer" to mapOf(groundPrimary to 0.55, groundSecondary to 0.25, "none" to 0.2),
                "winter" to mapOf(groundPrimary to 0.55, groundSecondary to 0.25, "none" to 0.2),
            )
        val aviation =
            mapOf(
                "summer" to mapOf("headgear_flight_helmet" to 0.55, groundPrimary to 0.25, "none" to 0.2),
                "winter" to mapOf("headgear_flight_helmet" to 0.55, groundPrimary to 0.25, "none" to 0.2),
            )
        return mapOf(
            "infantry" to ground,
            "armor" to ground,
            "artillery" to ground,
            "aviation" to aviation,
        )
    }

    /** Three historically compatible field choices plus a bareheaded minority. */
    private fun threeWayFieldHeadgear(
        groundPrimary: String,
        groundSecondary: String,
        groundTertiary: String,
    ): Map<String, Map<String, Map<String, Double>>> {
        val ground =
            mapOf(
                "summer" to
                    mapOf(groundPrimary to 0.45, groundSecondary to 0.2, groundTertiary to 0.15, "none" to 0.2),
                "winter" to
                    mapOf(groundPrimary to 0.45, groundSecondary to 0.2, groundTertiary to 0.15, "none" to 0.2),
            )
        val aviation =
            mapOf(
                "summer" to mapOf("headgear_flight_helmet" to 0.55, groundPrimary to 0.25, "none" to 0.2),
                "winter" to mapOf("headgear_flight_helmet" to 0.55, groundPrimary to 0.25, "none" to 0.2),
            )
        return mapOf(
            "infantry" to ground,
            "armor" to ground,
            "artillery" to ground,
            "aviation" to aviation,
        )
    }

    /** A field cap in summer, its cold-weather alternative in winter; aviation keeps its helmet. */
    private fun seasonalFieldHeadgear(
        groundPrimary: String,
        groundSecondary: String,
    ): Map<String, Map<String, Map<String, Double>>> {
        val ground =
            mapOf(
                "summer" to mapOf(groundPrimary to 0.55, groundSecondary to 0.25, "none" to 0.2),
                "winter" to mapOf(groundSecondary to 0.55, groundPrimary to 0.25, "none" to 0.2),
            )
        val aviation =
            mapOf(
                "summer" to mapOf("headgear_flight_helmet" to 0.55, groundPrimary to 0.25, "none" to 0.2),
                "winter" to mapOf("headgear_flight_helmet" to 0.55, groundPrimary to 0.25, "none" to 0.2),
            )
        return mapOf(
            "infantry" to ground,
            "armor" to ground,
            "artillery" to ground,
            "aviation" to aviation,
        )
    }

    /** Seasonal soft caps plus a steel helmet; aviation keeps its flight helmet. */
    private fun seasonalThreeWayHeadgear(
        groundPrimary: String,
        groundSecondary: String,
        groundTertiary: String,
    ): Map<String, Map<String, Map<String, Double>>> {
        val ground =
            mapOf(
                "summer" to
                    mapOf(groundPrimary to 0.4, groundSecondary to 0.15, groundTertiary to 0.25, "none" to 0.2),
                "winter" to
                    mapOf(groundSecondary to 0.4, groundPrimary to 0.15, groundTertiary to 0.25, "none" to 0.2),
            )
        val aviation =
            mapOf(
                "summer" to mapOf("headgear_flight_helmet" to 0.55, groundPrimary to 0.25, "none" to 0.2),
                "winter" to mapOf("headgear_flight_helmet" to 0.55, groundPrimary to 0.25, "none" to 0.2),
            )
        return mapOf(
            "infantry" to ground,
            "armor" to ground,
            "artillery" to ground,
            "aviation" to aviation,
        )
    }

    /** The ancient pool has no twentieth-century flight helmet; every branch shares its two caps. */
    private fun uniformHeadgear(
        groundPrimary: String,
        groundSecondary: String,
        primaryWeight: Double,
        secondaryWeight: Double,
    ): Map<String, Map<String, Map<String, Double>>> {
        val weights =
            mapOf(
                "summer" to
                    mapOf(groundPrimary to primaryWeight, groundSecondary to secondaryWeight, "none" to 0.2),
                "winter" to
                    mapOf(groundPrimary to primaryWeight, groundSecondary to secondaryWeight, "none" to 0.2),
            )
        return mapOf(
            "infantry" to weights,
            "armor" to weights,
            "artillery" to weights,
            "aviation" to weights,
        )
    }

    private val PREFIX_CATEGORY =
        listOf(
            "bg_" to "background",
            "back_" to "uniform_back",
            "collar_" to "uniform_front_collar",
            "face_" to "face",
            "expression_" to "expression",
            "age_" to "age_face",
            "scar_" to "scar",
            "facial_" to "facial_hair",
            "hair_back_" to "hair_back",
            "hair_front_" to "hair_front",
            "under_hair_" to "under_headgear_hair",
            "rank_" to "rank",
            "branch_" to "branch",
            "accessory_" to "accessory",
            "headgear_" to "headgear",
            "wound_" to "wound",
        )

    private const val FEMALE_CHANCE = 0.12
    private const val SEASON_WINTER_CHANCE = 0.6
    private const val HAIR_VOLUME_CHANCE = 0.35
    private const val ACCESSORY_CHANCE = 0.16
    private const val MIDDLE_AGE = 34
    private const val OLD_AGE = 48
    private val POST_1943_RANK_ID = Regex("rank_(lieutenant|captain|major|colonel)")
}
