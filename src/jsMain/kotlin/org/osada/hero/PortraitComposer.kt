package org.osada.hero

import org.osada.UnitClass

/**
 * Deterministic layered-portrait composer — the Kotlin half of the portrait system whose assets,
 * manifest and demo live under `src/jsMain/resources/portraits/`.
 *
 * It selects one SVG layer per category from a seed and a small set of facts (branch, gender, rank,
 * age band, scars, wounds), returning the ordered [PortraitComposition.layerIds] that reproduce the
 * portrait. Rendering is pure stacking (see [pathsFor]); nothing here rasterises.
 *
 * ## Why this mirrors the JS core
 *
 * The selection algorithm is a bit-for-bit twin of `resources/portraits/portrait-core.mjs`: both use
 * [SeededRandom] (mulberry32 + FNV-1a), both key each category's pick on `seedFrom(seed, category)`,
 * and both iterate the same [ORDER]. So a portrait generated here at emergence time, a portrait
 * generated in the browser studio, and a portrait re-derived on load all agree — the §7.4 / §29.17
 * determinism contract, now extended to the portrait layers §15.3 asked to be stored as ids + seed.
 *
 * The catalogue below is the source of truth for the app; `scripts/portraits/validate.mjs` and
 * [org.osada.PortraitCompositionTest] both assert it stays in lockstep with the on-disk manifest and
 * files, so the two representations cannot silently drift.
 */
@Suppress("TooManyFunctions")
internal object PortraitComposer {
    /** Stacking / selection order — must equal `manifest.json` `order`. */
    val ORDER =
        listOf(
            "background",
            "uniform",
            "head",
            "eyes",
            "nose",
            "mouth",
            "scar",
            "facialHair",
            "hair",
            "headgear",
            "rankInsignia",
            "branchBadge",
            "ageOverlay",
            "woundOverlay",
        )

    private val CATEGORY_DIR =
        mapOf(
            "background" to "background",
            "uniform" to "uniform",
            "head" to "head",
            "eyes" to "eyes",
            "nose" to "nose",
            "mouth" to "mouth",
            "scar" to "scar",
            "facialHair" to "facial_hair",
            "hair" to "hair",
            "headgear" to "headgear",
            "rankInsignia" to "rank",
            "branchBadge" to "branch",
            "ageOverlay" to "age",
            "woundOverlay" to "wound",
        )

    // id prefix -> category. Ordered longest-safe: "head_" never matches "headgear_" and vice versa.
    private val PREFIX_CATEGORY =
        listOf(
            "bg_" to "background",
            "uniform_" to "uniform",
            "headgear_" to "headgear",
            "head_" to "head",
            "eyes_" to "eyes",
            "nose_" to "nose",
            "mouth_" to "mouth",
            "scar_" to "scar",
            "facial_" to "facialHair",
            "hair_" to "hair",
            "rank_" to "rankInsignia",
            "branch_" to "branchBadge",
            "age_" to "ageOverlay",
            "wound_" to "woundOverlay",
        )

    // Candidate lists, ordered exactly as the manifest lists them so a seeded index matches JS.
    private val BACKGROUNDS = listOf("bg_field_gray", "bg_dossier_red", "bg_winter")
    private val UNIFORM_BY_BRANCH =
        mapOf(
            "infantry" to "uniform_infantry_ussr_1942",
            "armor" to "uniform_tank_ussr_1942",
            "artillery" to "uniform_artillery_ussr_1942",
            "aviation" to "uniform_pilot_ussr_1942",
        )
    private val MALE_HEADS = (1..6).map { "head_male_$it" }
    private val FEMALE_HEADS = (1..2).map { "head_female_$it" }
    private val EYES = (1..4).map { "eyes_$it" }
    private val NOSES = (1..4).map { "nose_$it" }
    private val MOUTHS = (1..4).map { "mouth_$it" }
    private val HAIR = (1..6).map { "hair_$it" }
    private val FACIAL = listOf("facial_clean", "facial_mustache", "facial_beard", "facial_stubble")
    private val SCARS = (1..3).map { "scar_$it" }

    // Headgear candidates per branch, preserving manifest order (ushanka, pilotka, officer_cap, helmet).
    private val HEADGEAR_BY_BRANCH =
        mapOf(
            "infantry" to listOf("headgear_ushanka", "headgear_pilotka", "headgear_officer_cap"),
            "armor" to listOf("headgear_ushanka", "headgear_officer_cap"),
            "artillery" to listOf("headgear_ushanka", "headgear_pilotka", "headgear_officer_cap"),
            "aviation" to listOf("headgear_flight_helmet"),
        )
    private val WOUND_BY_ID =
        mapOf("head" to "wound_bandage_head", "arm" to "wound_arm_sling", "eye" to "wound_eye_patch")

    // Categories whose only rule is "seeded pick from the whole list".
    private val SEEDED_SIMPLE =
        mapOf(
            "background" to BACKGROUNDS,
            "eyes" to EYES,
            "nose" to NOSES,
            "mouth" to MOUTHS,
            "hair" to HAIR,
        )

    /** The inputs a portrait needs; strings match the manifest tag vocabulary verbatim. */
    data class Facts(
        val branch: String,
        val gender: String,
        val rank: String,
        val ageBand: String,
        val scar: Boolean = false,
        val wound: String? = null,
    )

    /** Choose one layer per category; returns the ids ordered by [ORDER] (omitted categories dropped). */
    fun compose(
        facts: Facts,
        seed: Int,
    ): List<String> = ORDER.mapNotNull { category -> selectId(category, facts, seed) }

    private fun selectId(
        category: String,
        facts: Facts,
        seed: Int,
    ): String? {
        SEEDED_SIMPLE[category]?.let { return pick(it, seed, category) }
        return when (category) {
            "uniform" -> UNIFORM_BY_BRANCH[facts.branch]
            "head" -> pick(headsFor(facts.gender), seed, category)
            "scar" -> if (facts.scar) pick(SCARS, seed, category) else null
            "facialHair" -> facialFor(facts.gender, seed)
            "headgear" -> pick(HEADGEAR_BY_BRANCH[facts.branch].orEmpty(), seed, category)
            "rankInsignia" -> "rank_${facts.rank}"
            "branchBadge" -> "branch_${facts.branch}"
            "ageOverlay" -> "age_${facts.ageBand}"
            "woundOverlay" -> facts.wound?.let { WOUND_BY_ID[it] }
            else -> null
        }
    }

    private fun headsFor(gender: String): List<String> = if (gender == "female") FEMALE_HEADS else MALE_HEADS

    private fun facialFor(
        gender: String,
        seed: Int,
    ): String? = if (gender == "female") "facial_clean" else pick(FACIAL, seed, "facialHair")

    /** A finished [PortraitComposition] for a newly emerged officer (§15.3: store layer ids + seed). */
    fun composeFor(
        seed: Int,
        unitClass: Int,
        rankId: String,
        birthYear: Int?,
        serviceYear: Int?,
    ): PortraitComposition {
        val facts = deriveFacts(seed, unitClass, rankId, birthYear, serviceYear)
        return PortraitComposition(seed = seed, layerIds = compose(facts, seed))
    }

    /** Facts for an emerging officer: branch from class, gender/age seeded, rank as given. */
    fun deriveFacts(
        seed: Int,
        unitClass: Int,
        rankId: String,
        birthYear: Int?,
        serviceYear: Int?,
    ): Facts =
        Facts(
            branch = branchFor(unitClass),
            gender = seededGender(seed),
            rank = if (rankId in RANKS) rankId else "lieutenant",
            ageBand = ageBand(seed, birthYear, serviceYear),
        )

    /** Resource-relative paths for an ordered id list, ready to fetch and stack in the UI. */
    fun pathsFor(layerIds: List<String>): List<String> = layerIds.mapNotNull(::layerPath)

    /** `portraits/layers/<dir>/<id>.svg`, or null for an id whose prefix maps to no known category. */
    fun layerPath(id: String): String? =
        categoryOf(id)?.let(CATEGORY_DIR::get)?.let { dir -> "portraits/layers/$dir/$id.svg" }

    private fun categoryOf(id: String): String? = PREFIX_CATEGORY.firstOrNull { id.startsWith(it.first) }?.second

    private fun seededGender(seed: Int): String {
        val rng = SeededRandom(SeededRandom.seedFrom(seed.toString(), "gender"))
        return if (rng.roll(FEMALE_CHANCE)) "female" else "male"
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

    private fun ageBand(
        seed: Int,
        birthYear: Int?,
        serviceYear: Int?,
    ): String {
        val age =
            if (birthYear != null && serviceYear != null) {
                serviceYear - birthYear
            } else {
                YOUNG_BASE + SeededRandom(SeededRandom.seedFrom(seed.toString(), "age")).nextInt(AGE_SPREAD)
            }
        return when {
            age < MIDDLE_AGE -> "young"
            age < OLD_AGE -> "middle"
            else -> "old"
        }
    }

    /** Seeded index into [list], keyed on the portrait seed and category — twin of the JS `pick`. */
    private fun pick(
        list: List<String>,
        seed: Int,
        category: String,
    ): String? =
        if (list.isEmpty()) {
            null
        } else {
            list[SeededRandom(SeededRandom.seedFrom(seed.toString(), category)).nextInt(list.size)]
        }

    private val RANKS = setOf("lieutenant", "captain", "major", "colonel")
    private const val FEMALE_CHANCE = 0.12
    private const val YOUNG_BASE = 26
    private const val AGE_SPREAD = 22
    private const val MIDDLE_AGE = 34
    private const val OLD_AGE = 48
}
