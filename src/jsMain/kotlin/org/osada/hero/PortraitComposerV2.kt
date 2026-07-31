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
@Suppress("TooManyFunctions")
object PortraitComposerV2 {
    enum class Pool(
        val id: String,
    ) {
        USSR_1942("ussr_1942"),
        REVOLUTION_1919("revolution_1919"),
        SPANISH_REPUBLIC_1936("spanish_republic_1936"),
        YUGOSLAV_PARTISAN_1941("yugoslav_partisan_1941"),
        EAST_ASIAN_REVOLUTIONARY("east_asian_revolutionary"),
        NONE("none"),
    }

    val ORDER =
        listOf(
            "background",
            "uniform_back",
            "hair_back",
            "face",
            "age_face",
            "scar",
            "facial_hair",
            "hair_front",
            "under_headgear_hair",
            "uniform_front_collar",
            "rank",
            "branch",
            "headgear",
            "wound",
        )

    private val CATEGORY_DIR =
        mapOf(
            "background" to "background",
            "uniform_back" to "uniform_back",
            "hair_back" to "hair_back",
            "face" to "face",
            "age_face" to "age_face",
            "scar" to "scar",
            "facial_hair" to "facial_hair",
            "hair_front" to "hair_front",
            "under_headgear_hair" to "under_headgear_hair",
            "uniform_front_collar" to "uniform_front_collar",
            "rank" to "rank",
            "branch" to "branch",
            "headgear" to "headgear",
            "wound" to "wound",
        )

    private val BACKGROUNDS = listOf("bg_dossier_slate", "bg_dossier_stone")
    private val FEMALE_FACES = listOf("round_young", "broad_calm", "narrow_stern", "long_mature")
    private val MALE_FRONT = listOf("hair_front_crop", "hair_front_side", "hair_front_receding", "hair_front_swept")
    private val FEMALE_FRONT = listOf("hair_front_female_a", "hair_front_female_b")
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
                    "round_young" to 0.4,
                    "narrow_stern" to 0.2,
                    "broad_calm" to 0.2,
                    "long_mature" to 0.1,
                    "angular_tired" to 0.05,
                    "square_veteran" to 0.05,
                ),
            "middle" to
                mapOf(
                    "broad_calm" to 0.25,
                    "narrow_stern" to 0.2,
                    "long_mature" to 0.2,
                    "square_veteran" to 0.15,
                    "angular_tired" to 0.1,
                    "round_young" to 0.1,
                ),
            "old" to
                mapOf(
                    "square_veteran" to 0.3,
                    "long_mature" to 0.25,
                    "angular_tired" to 0.25,
                    "narrow_stern" to 0.1,
                    "broad_calm" to 0.1,
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
                    "summer" to mapOf("headgear_officer_cap" to 0.4, "headgear_pilotka" to 0.3, "none" to 0.3),
                    "winter" to mapOf("headgear_ushanka" to 0.7, "headgear_officer_cap" to 0.2, "none" to 0.1),
                ),
            "artillery" to
                mapOf(
                    "summer" to mapOf("headgear_officer_cap" to 0.4, "headgear_pilotka" to 0.3, "none" to 0.3),
                    "winter" to mapOf("headgear_ushanka" to 0.7, "headgear_officer_cap" to 0.2, "none" to 0.1),
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
    private val SPANISH_HEADGEAR =
        fieldHeadgear(
            groundPrimary = "headgear_spanish_side_cap",
            groundSecondary = "headgear_spanish_beret",
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
    private val HAIR_MODE =
        mapOf(
            "headgear_officer_cap" to "UNDER_CAP",
            "headgear_pilotka" to "UNDER_CAP",
            "headgear_ushanka" to "UNDER_FUR_HAT",
            "headgear_flight_helmet" to "UNDER_FLIGHT_HELMET",
            "headgear_rev1919_field_cap" to "UNDER_CAP",
            "headgear_rev1919_service_cap" to "UNDER_CAP",
            "headgear_spanish_side_cap" to "UNDER_CAP",
            "headgear_spanish_beret" to "UNDER_CAP",
            "headgear_yugoslav_titovka" to "UNDER_CAP",
            "headgear_yugoslav_partisan_cap" to "UNDER_CAP",
            "headgear_east_asian_field_cap" to "UNDER_CAP",
            "headgear_east_asian_boonie" to "UNDER_CAP",
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
        chosen["background"] = pick(BACKGROUNDS, seed, "bg")
        chosen["uniform_back"] = "back_${facts.branch}"
        putHair(chosen, facts, seed, hairMode)
        chosen["face"] = "face_${facts.archetype(seed)}"
        ageLayer(facts.age)?.let { chosen["age_face"] = it }
        if (facts.scar) chosen["scar"] = pick(SCARS, seed, "scar")
        chosen["facial_hair"] = facialHair(facts, seed)
        chosen["uniform_front_collar"] = collarFor(facts)
        chosen["rank"] = rankFor(facts)
        chosen["branch"] = "branch_${facts.branch}"
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
        val base =
            if (stored.any { it.startsWith("face_") }) {
                stored
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
        val withCondition = applyCondition(base, state, definition.portrait.seed)
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
        return out.distinct().sortedBy { ORDER.indexOf(categoryOf(it)) }
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
                base
            }
        return weightedPick(weights, seed, "arch")
    }

    private fun putHair(
        chosen: LinkedHashMap<String, String>,
        facts: Facts,
        seed: Int,
        hairMode: String,
    ) {
        if (facts.gender == "female") {
            chosen["hair_back"] = "hair_back_female"
            when (hairMode) {
                "FULL_HAIR" -> chosen["hair_front"] = pick(FEMALE_FRONT, seed, "hairfront")
                "UNDER_CAP" -> chosen["under_headgear_hair"] = "under_hair_female"
            }
        } else {
            when (hairMode) {
                "FULL_HAIR" -> {
                    chosen["hair_back"] =
                        if (chance(seed, "hairvol", HAIR_VOLUME_CHANCE)) "hair_back_full" else "hair_back_short"
                    chosen["hair_front"] = pick(MALE_FRONT, seed, "hairfront")
                }

                "UNDER_CAP" -> {
                    chosen["hair_back"] = "hair_back_short"
                    chosen["under_headgear_hair"] = "under_hair_temples"
                }
            }
        }
    }

    private fun facialHair(
        facts: Facts,
        seed: Int,
    ): String =
        if (facts.gender ==
            "female"
        ) {
            "facial_clean"
        } else {
            weightedPick(FACIAL_BY_AGE.getValue(facts.age), seed, "facial")
        }

    private fun pickHeadgear(
        facts: Facts,
        seed: Int,
    ): String? {
        val catalog =
            when (facts.pool) {
                Pool.USSR_1942 -> HEADGEAR_BY_BRANCH_SEASON
                Pool.REVOLUTION_1919 -> REVOLUTION_HEADGEAR
                Pool.SPANISH_REPUBLIC_1936 -> SPANISH_HEADGEAR
                Pool.YUGOSLAV_PARTISAN_1941 -> YUGOSLAV_HEADGEAR
                Pool.EAST_ASIAN_REVOLUTIONARY -> EAST_ASIAN_HEADGEAR
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

            Pool.REVOLUTION_1919 -> "collar_rev1919_field"
            Pool.SPANISH_REPUBLIC_1936 -> "collar_spanish_republic"
            Pool.YUGOSLAV_PARTISAN_1941 -> "collar_yugoslav_partisan"
            Pool.EAST_ASIAN_REVOLUTIONARY -> "collar_east_asian_field"
            Pool.NONE -> error("A no-portrait pool has no collar")
        }

    private fun rankFor(facts: Facts): String =
        when (facts.pool) {
            Pool.USSR_1942 -> "rank_${facts.rank}"
            Pool.REVOLUTION_1919 -> "rank_rev1919_${facts.rank}"
            Pool.SPANISH_REPUBLIC_1936 -> "rank_spanish_${facts.rank}"
            Pool.YUGOSLAV_PARTISAN_1941 -> "rank_yugoslav_${facts.rank}"
            Pool.EAST_ASIAN_REVOLUTIONARY -> "rank_east_asian_${facts.rank}"
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
            pool = poolOverride ?: poolFor(country),
        )
    }

    /**
     * Country ids are the merged equipment-country ids used by the actual scenario player. Unknown
     * countries deliberately receive the monogram fallback instead of silently wearing Soviet
     * insignia; `null` keeps the historical default for old/internal callers that predate country
     * threading.
     */
    @Suppress("MagicNumber") // Stable equipment-country ids, not arithmetic constants.
    fun poolFor(country: Int?): Pool =
        when (country) {
            null, 19, 61, 89 -> Pool.USSR_1942
            103, 144, 187, 188, 196 -> Pool.REVOLUTION_1919
            226 -> Pool.SPANISH_REPUBLIC_1936
            43 -> Pool.YUGOSLAV_PARTISAN_1941
            21, 25, 276 -> Pool.EAST_ASIAN_REVOLUTIONARY
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

    private val PREFIX_CATEGORY =
        listOf(
            "bg_" to "background",
            "back_" to "uniform_back",
            "collar_" to "uniform_front_collar",
            "face_" to "face",
            "age_" to "age_face",
            "scar_" to "scar",
            "facial_" to "facial_hair",
            "hair_back_" to "hair_back",
            "hair_front_" to "hair_front",
            "under_hair_" to "under_headgear_hair",
            "rank_" to "rank",
            "branch_" to "branch",
            "headgear_" to "headgear",
            "wound_" to "wound",
        )

    private const val FEMALE_CHANCE = 0.12
    private const val SEASON_WINTER_CHANCE = 0.6
    private const val HAIR_VOLUME_CHANCE = 0.35
    private const val MIDDLE_AGE = 34
    private const val OLD_AGE = 48
}
