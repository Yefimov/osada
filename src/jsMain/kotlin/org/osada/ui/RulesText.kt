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

    /**
     * **Audited against Open General's manual section 9 on 2026-08-25, and it was short by five.**
     *
     * OG lists ten OPTIONAL RULES. This list named four of them plus the standing AI note, so the
     * profile shipped without the "partial" qualifier while its own disclaimer under-stated what
     * was missing — the exact failure the disclaimer exists to prevent. The audit found
     * `docs/og-fidelity-plan.md` §C had never tracked Barrage (9.2), Build and Repair (9.3),
     * Counterbattery (9.4), Extended LOS (9.5) or Triggers (9.10) at all.
     *
     * **Four of those five are now BUILT** and are therefore not on this list: Build and Repair,
     * Counterbattery and Extended LOS (schema 6), and **Barrage (schema 7, 2026-08-26)** — which
     * left this list once its gate turned out to be the record's Bomber Size rather than the
     * undiscovered special bit §L.6 was waiting for (§Q.2, §R). What remains is Triggers, which is
     * genuinely absent, plus one narrowing inside a rule that otherwise ships
     * whole — OSADA builds four of OG's five facilities and no railroad station, because it has no
     * rail transport for a station to serve (`rules/Engineering`).
     *
     * **`authored_options` was added on review, 2026-08-25**, and it is the subtlest entry here.
     * The three rules schema 6 added are real Open General mechanics, but OG lets each scenario and
     * each efile decide what may be built, blown and seen (`build_mask`, `blow_mask`,
     * `blow_any_terrain`, `TrueDLOF`, `UnitsBlockDLOF`, and the per-scenario Can Build / Can Blow /
     * Can Repair switches). None of those was imported, so this profile applied ONE set of
     * engineering and sight rules to content that authored many. Saying so out loud is the
     * difference between a rule that is Open General's and one that is merely OG-shaped.
     *
     * **Narrowed twice on 2026-08-26.** First (§N) `blow_any_terrain` became readable, so a sapper
     * flattens woods only in the efiles that authorise it. Then (§O) the scenario option bitfield
     * was cracked and imported, so **each scenario's own Can Build / Can Blow / Can Repair and
     * Extended LOS switches are now honoured** — 397 of the 502 deployed scenarios carry them, and
     * the ~10% that forbid a mechanic finally get their way.
     *
     * What is left of this entry, and all the string now claims: `TrueDLOF` and `UnitsBlockDLOF`
     * are imported and unread. `build_mask` and `blow_mask` are not in it at all any more — no
     * shipped efile sets either one.
     *
     * **`naval_mines` and `air_zoc` were added on audit, 2026-08-26** — both were already counted
     * as absent by `docs/og-fidelity-plan.md` §M and both were missing from this list, which is the
     * worse of the two ways to be wrong: §M is read by whoever builds, this is read by whoever
     * plays. Neither is covered by an entry already here. `naval_mines` is NOT the shipped mine
     * system: OSADA's minefields are a GROUND mechanic with their own option, and OG's naval mines
     * have their own laying, sweeping and damage model that nothing here implements.
     * `air_zoc` is the sharper omission of the two, because the option IS imported — 79 shipped
     * scenarios ask for it and no rule reads it, exactly the shape `authored_options` exists to
     * confess, but a different mechanic and so a different line.
     *
     * In the order a player meets them rather than the order they would be built.
     */
    private val OG_FIDELITY_GAP_KEYS =
        listOf(
            "rail",
            "stations",
            "air_missions",
            "carriers",
            "extended_naval",
            "naval_mines",
            "air_zoc",
            "authored_options",
            "triggers",
            "ai",
        )

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
