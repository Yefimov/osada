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

    /** The effective value in plain language. OG's interception bitmask is never shown as a bare
     *  number: each mode gets a sentence naming what actually fires. */
    fun value(
        rule: RuleKey,
        value: Int,
    ): String =
        when (rule) {
            RuleKey.AA_INTERCEPT_MODE -> interceptMode(value)
            RuleKey.FLAK_RANGE -> I18n.plural("rules.flak_range.value", value)
            RuleKey.GROUND_FOLLOWS_WEATHER -> I18n.t("rules.ground_follows_weather.value.$value")
            RuleKey.GROUND_CHANGE_TURNS -> I18n.plural("rules.ground_change_turns.value", value)
            // Named states rather than On/Off: "off" reads as "no replacements" instead of "keeps
            // its experience", which is the opposite of what this rule does.
            RuleKey.REPLACEMENT_EXPERIENCE -> I18n.t("rules.replacement_experience.value.$value")
            else -> I18n.t(if (value != 0) "rules.value.on" else "rules.value.off")
        }

    /** A mode outside the documented 0..3 set is reported as its raw number rather than guessed at:
     *  content is allowed to carry a value this build has no sentence for. */
    private fun interceptMode(value: Int): String =
        I18n.tOrNull("rules.aa_intercept_mode.value.$value")
            ?: I18n.t("rules.value.unknown", mapOf("value" to value))

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
            RulesetSource.CUSTOM -> I18n.t("rules.source.custom")
        }

    /** `RuleProvenance.EFILE_EXPLICIT` is the one case that means "the author chose this". */
    fun authoredByContent(resolved: ResolvedRule): Boolean = resolved.provenance == RuleProvenance.EFILE_EXPLICIT
}
