package org.osada.rules.ruleset

import org.osada.model.EfileConfig
import org.osada.multiplayer.sync.Sha256
import org.osada.uiSettings

/**
 * Turns a chosen profile into the complete, immutable set of values the engine executes
 * (`docs/design/ruleset-profiles.md` §4).
 *
 * The order is fixed: content baseline, then the profile's overlay, then availability reduction,
 * then the hash. Nothing downstream re-derives a value, which is what lets a save reproduce a
 * battle after its profile has been renamed, edited or deleted.
 */
object RulesetResolver {
    /**
     * Resolves [profile] against the content configuration currently loaded.
     *
     * Must be called only once that configuration is known: resolving before the efile is loaded
     * would record OSADA's defaults as though the author had chosen them.
     */
    fun resolve(profile: RulesetProfile): ResolvedRuleset {
        val rules =
            RuleKey.entries.associateWith { rule ->
                reduceForAvailability(rule, requestedRule(profile, rule))
            }
        return ResolvedRuleset(
            id = profile.id,
            name = profile.name,
            source = profile.source,
            schemaVersion = RULESET_SCHEMA_VERSION,
            rules = rules,
            deterministicHash = hash(RULESET_SCHEMA_VERSION, rules.mapValues { (_, r) -> r.effective }),
        )
    }

    private fun requestedRule(
        profile: RulesetProfile,
        rule: RuleKey,
    ): ResolvedRule {
        val override = profile.overrides[rule]
        return when {
            profile.source == RulesetSource.OSADA_DEFAULT ->
                RulesetDefaults.OSADA.getValue(rule).let { ResolvedRule(it, it, RuleProvenance.OSADA_DEFAULT) }

            // The editor's bounds are the only place a value is narrowed; a stored override has
            // already been through them.
            override != null ->
                rule.clampForEditor(override).let { ResolvedRule(it, it, RuleProvenance.CUSTOM_OVERRIDE) }

            else -> authorsVisionRule(rule)
        }
    }

    /**
     * What the selected content actually runs for [rule].
     *
     * Deliberately unclamped: `flak_range = 4` in LXF is the author's air war, not an out-of-range
     * value to be corrected (§2).
     */
    fun authorsVisionRule(rule: RuleKey): ResolvedRule {
        val efileKey = rule.efileKey
        if (efileKey == null) return ownedRule(rule)
        val value = EfileConfig.intKey(efileKey, RulesetDefaults.OSADA.getValue(rule))
        val provenance =
            if (EfileConfig.hasIntKey(efileKey)) RuleProvenance.EFILE_EXPLICIT else RuleProvenance.EFILE_DEFAULT
        return ResolvedRule(value, value, provenance)
    }

    /**
     * A rule OSADA owns outright. `stalin_regime` is seeded from the existing legacy preference at
     * resolution time, and locked from then on: changing that old checkbox later must not mutate a
     * campaign already under way (§2).
     */
    private fun ownedRule(rule: RuleKey): ResolvedRule {
        val value =
            when (rule) {
                RuleKey.STALIN_REGIME -> if (uiSettings.stalinRegime) 1 else 0
                else -> RulesetDefaults.OSADA.getValue(rule)
            }
        return ResolvedRule(value, value, RuleProvenance.OSADA_DEFAULT)
    }

    /**
     * "On" means "allow the slots this efile defines" and can never invent definitions the content
     * does not have (§2). The request is kept so the window can say *unavailable* rather than
     * silently reporting Off, which would read as the player's own choice.
     */
    private fun reduceForAvailability(
        rule: RuleKey,
        requested: ResolvedRule,
    ): ResolvedRule {
        val honourable =
            rule != RuleKey.ATTACHMENTS || requested.effective == 0 || EfileConfig.attachments() != null
        return if (honourable) {
            requested
        } else {
            requested.copy(
                effective = 0,
                provenance = RuleProvenance.CONTENT_UNAVAILABLE,
                availability = RuleAvailability.CONTENT_UNAVAILABLE,
            )
        }
    }

    /**
     * Deterministic fingerprint over semantics only (§5): the schema plus every effective key in
     * lexical order, newline-separated, SHA-256.
     *
     * Excludes profile id, name and provenance, so a rename can never block a join; includes the
     * schema, so an older client cannot claim compatibility with a ruleset it does not understand.
     */
    fun hash(
        schemaVersion: Int,
        effective: Map<RuleKey, Int>,
    ): String =
        Sha256.digest(
            (
                listOf("ruleset-schema=$schemaVersion") +
                    RuleKey.entries
                        .sortedBy { it.key }
                        .map { rule -> "${rule.key}=${effective[rule] ?: RulesetDefaults.OSADA.getValue(rule)}" }
            ).joinToString("\n"),
        )

    /**
     * Rebuilds a resolved ruleset from stored effective values -- a save, a restart checkpoint, a
     * campaign export or a multiplayer room block.
     *
     * The hash is RECOMPUTED rather than trusted (§5); a stored one is only ever diagnostic. This
     * deliberately does not consult the profile store: the named profile may have been renamed,
     * edited or deleted, and a save has to reproduce the battle it recorded.
     */
    fun fromEffective(
        id: String,
        name: String,
        source: RulesetSource,
        schemaVersion: Int,
        effective: Map<RuleKey, Int>,
    ): ResolvedRuleset {
        val rules =
            RuleKey.entries.associateWith { rule ->
                val value = effective[rule] ?: RulesetDefaults.OSADA.getValue(rule)
                ResolvedRule(value, value, RuleProvenance.CUSTOM_OVERRIDE)
            }
        return ResolvedRuleset(
            id = id,
            name = name,
            source = source,
            schemaVersion = schemaVersion,
            rules = rules,
            deterministicHash = hash(schemaVersion, rules.mapValues { (_, r) -> r.effective }),
        )
    }
}
