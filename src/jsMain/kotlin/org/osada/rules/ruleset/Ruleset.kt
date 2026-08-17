package org.osada.rules.ruleset

/*
 * The typed ruleset model (`docs/design/ruleset-profiles.md` §§2-3).
 *
 * Three different objects, deliberately not collapsed into one "ruleset": a reusable browser-local
 * overlay ([RulesetProfile]), one rule after resolution ([ResolvedRule]), and the complete immutable
 * result the engine actually executes ([ResolvedRuleset]).
 *
 * Every key here steers a branch OSADA already executes. A key for a mechanic the engine does not
 * run is an explicit non-goal (§10) -- it would make this window a list of promises.
 */

/**
 * Bumped when the MEANING of a key changes, never for display copy. Part of the hash (§5).
 *
 * 2 (2026-08-17) added the four weather switches. Additive: a schema-1 profile stays selectable and
 * simply names none of them, so those four keep following the content, which is exactly the
 * behaviour that profile already had.
 */
const val RULESET_SCHEMA_VERSION = 2

/**
 * One configurable rule.
 *
 * [key] is the stable serialized name: it goes into saves, into the multiplayer hash and into the
 * profile store, and is never renamed for display reasons.
 *
 * [efileKey] is the `equip.cfg` name this rule reads, or `null` for a rule OSADA owns outright.
 *
 * [editorMin]/[editorMax] bound the EDITOR only. Resolution never narrows a content value with them
 * (§2): LXF ships `flak_range = 4`, and clamping Author's Vision would silently rewrite that
 * campaign's air war.
 */
enum class RuleKey(
    val key: String,
    val efileKey: String?,
    val editorMin: Int,
    val editorMax: Int,
) {
    /** OG's `g2a_intercept_mode` bitmask. 0 is NOT "off": concealed AA already intercepts at 0. */
    AA_INTERCEPT_MODE("aa_intercept_mode", "g2a_intercept_mode", 0, 3),

    /** Hexes an AA gun reaches, for interception and for flak support fire. */
    FLAK_RANGE("flak_range", "flak_range", 1, 4),

    /** Whether content attachments are allowed at all (`attach_on` plus the efile's slot table). */
    ATTACHMENTS("attachments", "attach_on", 0, 1),

    /** 1 = each efile's own per-terrain supply factors, 0 = the flat off-city rule OSADA already
     *  runs for the five efiles that ship no terrain data. */
    SUPPLY_MODEL("supply_model", null, 0, 1),

    /** OSADA's own Stalin-regime rewrite of the player's formations. */
    STALIN_REGIME("stalin_regime", null, 0, 1),

    // ---- weather (schema 2) ---------------------------------------------------------------
    // Four switches rather than one, because they are four separate branches with four separate
    // call sites -- collapsing them would hide from the player which of the four they turned off.
    // All default ON, so the shipped game is unchanged unless somebody deliberately changes them.

    /** Whether bad weather stops aircraft initiating attacks at all
     *  (`AttackEligibility.airGroundedByWeather`). */
    WEATHER_GROUNDS_AIRCRAFT("weather_grounds_aircraft", null, 0, 1),

    /** Whether bad weather halves the strength points an air<->ground exchange brings to bear
     *  (`WeatherCombatRules.firingStrength`). */
    WEATHER_HALVES_AIR_GROUND("weather_halves_air_ground", null, 0, 1),

    /** Whether rain and snow add +3 to defence (`WeatherCombatRules.defenseBonus`). */
    WEATHER_DEFENSE_BONUS("weather_defense_bonus", null, 0, 1),

    /** Whether bad weather halves spotting range (`WeatherCombatRules.spotRange`). */
    WEATHER_HALVES_SPOTTING("weather_halves_spotting", null, 0, 1),

    /**
     * Whether rain and snow turn the ground to mud and frozen at all
     * (`GroundConditionModel`). Three states rather than a switch, because the scenario author has
     * an opinion of their own here (`weatherchg`) and the honest default is to keep it:
     * 0 = never, 1 = as each scenario authorises, 2 = always.
     */
    GROUND_FOLLOWS_WEATHER("ground_follows_weather", null, 0, 2),

    /** How many continuous turns of one sky it takes to move the ground one step
     *  (`GroundConditionModel.TURNS_TO_CHANGE`). */
    GROUND_CHANGE_TURNS("ground_change_turns", null, 1, 6),
    ;

    /** Editor-only bounds. Never applied to a value that came from content (§2). */
    fun clampForEditor(value: Int): Int = value.coerceIn(editorMin, editorMax)

    companion object {
        fun byKey(key: String): RuleKey? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Where an effective value came from. Lets the window explain a rule truthfully without teaching
 * the UI to read `equip.cfg` itself (§3).
 */
enum class RuleProvenance {
    /** The content's own `equip.cfg` names this key explicitly. */
    EFILE_EXPLICIT,

    /** The content has no opinion, so the documented call-site default stands. */
    EFILE_DEFAULT,

    /** OSADA's own baseline, including the legacy preference `stalin_regime` is seeded from. */
    OSADA_DEFAULT,

    /** A custom profile asked for this value. */
    CUSTOM_OVERRIDE,

    /** The mechanic does not exist for this content, so the request could not be honoured. */
    CONTENT_UNAVAILABLE,
}

enum class RuleAvailability {
    AVAILABLE,

    /** "On" cannot invent what the content never defined -- KAISER has no attachment slots at all. */
    CONTENT_UNAVAILABLE,
}

/**
 * One rule after resolution. [requested] is what the profile asked for and [effective] is what the
 * engine will run; they differ only when the content cannot honour the request.
 */
data class ResolvedRule(
    val requested: Int,
    val effective: Int,
    val provenance: RuleProvenance,
    val availability: RuleAvailability = RuleAvailability.AVAILABLE,
) {
    val unavailable: Boolean get() = availability == RuleAvailability.CONTENT_UNAVAILABLE
}

/**
 * A reusable browser-local overlay. Omitted keys keep following the selected content, which is what
 * makes one profile meaningful across campaigns built on different efiles.
 *
 * [unknownKeys] preserves keys this build does not understand instead of dropping them (§3):
 * discarding them and then hashing as though both peers agreed would let two clients execute
 * different games. A profile carrying any is unsupported here and is shown disabled.
 */
data class RulesetProfile(
    val id: String,
    val name: String,
    val schemaVersion: Int = RULESET_SCHEMA_VERSION,
    val overrides: Map<RuleKey, Int> = emptyMap(),
    val source: RulesetSource = RulesetSource.CUSTOM,
    val unknownKeys: Set<String> = emptySet(),
) {
    /** A profile from a newer schema, or carrying keys this build cannot execute, is visible but
     *  cannot be selected (§3). */
    val supported: Boolean get() = schemaVersion <= RULESET_SCHEMA_VERSION && unknownKeys.isEmpty()

    companion object {
        const val AUTHORS_VISION_ID = "authors-vision"
        const val OSADA_DEFAULT_ID = "osada-default"
    }
}

enum class RulesetSource {
    /** The effective configuration loaded for the selected content. */
    AUTHORS_VISION,

    /** OSADA's single documented baseline, identical for every content. */
    OSADA_DEFAULT,

    /** A named profile the player saved. */
    CUSTOM,
}

/**
 * The complete, immutable resolution the engine executes. Rule code reads only [effective]; nothing
 * downstream ever re-derives a value from a profile.
 */
data class ResolvedRuleset(
    val id: String,
    val name: String,
    val source: RulesetSource,
    val schemaVersion: Int,
    val rules: Map<RuleKey, ResolvedRule>,
    val deterministicHash: String,
) {
    fun effective(rule: RuleKey): Int = rules[rule]?.effective ?: RulesetDefaults.OSADA.getValue(rule)

    fun flag(rule: RuleKey): Boolean = effective(rule) != 0

    fun rule(rule: RuleKey): ResolvedRule =
        rules[rule] ?: ResolvedRule(
            requested = RulesetDefaults.OSADA.getValue(rule),
            effective = RulesetDefaults.OSADA.getValue(rule),
            provenance = RuleProvenance.OSADA_DEFAULT,
        )

    /** Rules the content cannot honour, for the window's warnings (§7). */
    fun unavailable(): List<RuleKey> = RuleKey.entries.filter { rules[it]?.unavailable == true }
}

/**
 * OSADA's documented baseline, and simultaneously each rule's call-site default -- the value the
 * engine uses when content says nothing. Keeping one table means the window cannot claim a default
 * the engine does not actually apply.
 */
object RulesetDefaults {
    val OSADA: Map<RuleKey, Int> =
        mapOf(
            // Mode 0: concealed AA intercepts a plane flying through or finishing in range; spotted
            // AA never intercepts. `AAInterception`'s own documented default.
            RuleKey.AA_INTERCEPT_MODE to 0,
            // equip.cfg's own comment: "Default all flak-type actions are limited to range 1."
            RuleKey.FLAK_RANGE to 1,
            // `attach_on` absent really does mean off (`EfileConfig`'s trap-4 note).
            RuleKey.ATTACHMENTS to 0,
            // TerrainEx already prefers the efile's own factors and falls back per terrain id to
            // PM's flat formula, so "efile factors" is what OSADA runs today.
            RuleKey.SUPPLY_MODEL to 1,
            RuleKey.STALIN_REGIME to 0,
            // Every weather rule ships on: these are Open General's own rules
            // (`tools/og-import/DEFERRED.md` §7.45), and each is already a no-op in Fair weather.
            RuleKey.WEATHER_GROUNDS_AIRCRAFT to 1,
            RuleKey.WEATHER_HALVES_AIR_GROUND to 1,
            RuleKey.WEATHER_DEFENSE_BONUS to 1,
            RuleKey.WEATHER_HALVES_SPOTTING to 1,
            // Ground: follow whatever each scenario authorises, and OG's own "several turns".
            RuleKey.GROUND_FOLLOWS_WEATHER to 1,
            RuleKey.GROUND_CHANGE_TURNS to 3,
        )
}
