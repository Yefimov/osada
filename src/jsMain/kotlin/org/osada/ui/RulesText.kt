package org.osada.ui

import org.osada.i18n.I18n
import org.osada.rules.ruleset.ResolvedRule
import org.osada.rules.ruleset.ResolvedRuleset
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RuleProvenance
import org.osada.rules.ruleset.RulesetProfile
import org.osada.rules.ruleset.RulesetSource

/**
 * Player-facing wording for the ruleset windows
 * (`docs/design/ruleset-profiles.md` §7).
 *
 * One owner, so the pre-launch summary, the editor and the multiplayer refusal message cannot drift
 * into describing the same rule three different ways. Every sentence describes a branch the engine
 * takes; none of them previews a hidden AA position.
 */
internal object RulesText {
    fun ruleLabel(rule: RuleKey): String = I18n.t("rules.${rule.key}.label")

    fun ruleHelp(rule: RuleKey): String = I18n.t("rules.${rule.key}.help")

    /**
     * Rules whose values are NAMED rather than shown as On/Off.
     *
     * Every one of them is a rule where "off" would describe the wrong thing: "off" for
     * `replacement_experience` reads as "no replacements" instead of "keeps its experience"; "off"
     * for an ORDERING rule reads as "guns cannot fire"; "off" for a fuel rate or an initiative model
     * says nothing at all about what the rule then is. A rule genuinely shaped as a switch --
     * `installation_spotting`, the four weather branches -- is deliberately NOT in this set, because
     * On/Off is the honest wording there and inventing two nouns for it would be worse.
     *
     * Kept as a set rather than as one `when` branch per rule so adding the next OG rule is a
     * one-line change and this function's shape stops growing.
     */
    private val NAMED_VALUE_RULES =
        setOf(
            RuleKey.GROUND_FOLLOWS_WEATHER,
            RuleKey.REPLACEMENT_EXPERIENCE,
            RuleKey.HEAVY_MOVE_FIRE,
            RuleKey.SNOW_FUEL,
            RuleKey.SUPPORT_FIRE_FALLOFF,
            RuleKey.AIR_FUEL,
            RuleKey.INITIATIVE_MODEL,
            RuleKey.SPOTTING_MEMORY,
            RuleKey.GROUND_AUTO_SUPPLY,
        )

    /** The effective value in plain language. OG's interception bitmask is never shown as a bare
     *  number: each mode gets a sentence naming what actually fires. */
    fun value(
        rule: RuleKey,
        value: Int,
    ): String =
        when (rule) {
            RuleKey.AA_INTERCEPT_MODE -> interceptMode(value)
            RuleKey.FLAK_RANGE -> I18n.plural("rules.flak_range.value", value)
            RuleKey.GROUND_CHANGE_TURNS -> I18n.plural("rules.ground_change_turns.value", value)
            in NAMED_VALUE_RULES -> I18n.t("rules.${rule.key}.value.$value")
            else -> I18n.t(if (value != 0) "rules.value.on" else "rules.value.off")
        }

    /** A mode outside the documented 0..3 set is reported as its raw number rather than guessed at:
     *  content is allowed to carry a value this build has no sentence for. */
    private fun interceptMode(value: Int): String =
        I18n.tOrNull("rules.aa_intercept_mode.value.$value")
            ?: I18n.t("rules.value.unknown", mapOf("value" to value))

    /**
     * The list of Open General systems the fidelity profile does NOT reproduce, in the order a
     * player meets them rather than the order they would be built (`docs/og-fidelity-plan.md` D.2).
     *
     * Shown with the profile, not buried in a help topic: the label says "partial", and a partial
     * claim that never says what is missing is the unverifiable marketing claim §0.2 forbids.
     */
    fun ogFidelityGaps(): List<String> = OG_FIDELITY_GAP_KEYS.map { I18n.t("rules.og_fidelity.gap.$it") }

    private val OG_FIDELITY_GAP_KEYS =
        listOf("rail", "air_missions", "carriers", "extended_naval", "ai")

    /** The full summary line, including the "unavailable for this equipment file" case, which must
     *  never read as though the player had simply switched it off (§2). */
    fun summary(
        rule: RuleKey,
        resolved: ResolvedRule,
    ): String =
        if (resolved.unavailable) {
            I18n.t("rules.value.unavailable")
        } else {
            value(rule, resolved.effective)
        }

    /** Where the value came from, so the window can be truthful without reading `equip.cfg` in the
     *  UI layer (§3). */
    fun provenance(resolved: ResolvedRule): String = I18n.t("rules.provenance.${resolved.provenance.name.lowercase()}")

    fun profileName(profile: RulesetProfile): String =
        when (profile.source) {
            RulesetSource.AUTHORS_VISION -> I18n.t("rules.profile.authors_vision")
            RulesetSource.OSADA_DEFAULT -> I18n.t("rules.profile.osada_default")
            RulesetSource.OG_FIDELITY -> I18n.t("rules.profile.og_fidelity")
            RulesetSource.CUSTOM -> profile.name.ifBlank { profile.id }
        }

    /** Why a profile cannot be selected, or `null` when it can. */
    fun unsupportedReason(profile: RulesetProfile): String? =
        when {
            profile.supported -> null
            profile.unknownKeys.isNotEmpty() ->
                I18n.t("rules.profile.unknown_rules", mapOf("rules" to profile.unknownKeys.sorted().joinToString(", ")))

            else -> I18n.t("rules.profile.newer_schema", mapOf("version" to profile.schemaVersion))
        }

    /** The source note under the picker: what this selection actually follows. */
    fun sourceNote(resolved: ResolvedRuleset): String =
        when (resolved.source) {
            RulesetSource.AUTHORS_VISION -> I18n.t("rules.source.authors_vision")
            RulesetSource.OSADA_DEFAULT -> I18n.t("rules.source.osada_default")
            RulesetSource.OG_FIDELITY -> I18n.t("rules.source.og_fidelity")
            RulesetSource.CUSTOM -> I18n.t("rules.source.custom")
        }

    /** `RuleProvenance.EFILE_EXPLICIT` is the one case that means "the author chose this". */
    fun authoredByContent(resolved: ResolvedRule): Boolean = resolved.provenance == RuleProvenance.EFILE_EXPLICIT
}
