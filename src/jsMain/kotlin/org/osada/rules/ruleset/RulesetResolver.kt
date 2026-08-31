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
    fun authorsVisionRule(rule: RuleKey): ResolvedRule =
        when {
            rule == RuleKey.ATTACHMENTS -> authorsVisionAttachments()
            rule.efileKey == null -> ownedRule(rule)
            else -> efileBackedRule(rule, rule.efileKey)
        }

    /** The `equip.cfg`-backed half of [authorsVisionRule], split out to keep that one a plain
     *  three-way choice rather than a ladder of early returns. */
    private fun efileBackedRule(
        rule: RuleKey,
        efileKey: String,
    ): ResolvedRule {
        val value = EfileConfig.intKey(efileKey, RulesetDefaults.OSADA.getValue(rule))
        val provenance =
            if (EfileConfig.hasIntKey(efileKey)) RuleProvenance.EFILE_EXPLICIT else RuleProvenance.EFILE_DEFAULT
        return ResolvedRule(value, value, provenance)
    }

    /**
     * `attach_on` never appears as a plain `equip.cfg` int key: `equip_cfg_to_json.py` always emits
     * it as the nested `attachments.on` block together with the slot table, never into `keys`/`raw`.
     * Reading it through [EfileConfig.intKey] (as every other efile-backed rule does) therefore
     * always misses, and Author's Vision resolved every attachment-using efile (LXF, ATOMIC, GCE,
     * BASEKORP) to "off" -- silently turning off attachments for campaigns whose OG source has them.
     * [EfileConfig.attachments] is the one accessor that actually parses that block, so it is the
     * only correct source of truth here.
     */
    private fun authorsVisionAttachments(): ResolvedRule {
        val on = EfileConfig.attachments() != null
        val value = if (on) 1 else 0
        val provenance = if (on) RuleProvenance.EFILE_EXPLICIT else RuleProvenance.EFILE_DEFAULT
        return ResolvedRule(value, value, provenance)
    }

    /**
     * OG rules whose MASTER switch Author's Vision turns on so that each scenario's own authored
     * bit decides — `Scenario.extendedLos`, `.airZoc`, `.extendedNaval`, `.barrageAllowed` and the
     * `.canBuild`/`.canBlow`/`.canRepair` trio, all imported by §O into 397 scenario XMLs.
     *
     * **The key is not the author here; the scenario is.** Every rule in this set already ANDs the
     * key with its own scenario flag at read time — `ExtendedLos.enabled()`, `AirZoneOfControl`,
     * `ExtendedNaval`, `Barrage.enabled()` and `Engineering`'s per-work check — and each of those
     * reads `?: true`, so a scenario whose source could not be read is unaffected either way.
     *
     * Resolving these to 1 is therefore not "switch the rule on": it is *stop overriding the
     * author*. Until 2026-08-28 Author's Vision resolved them to OSADA's off, which silently
     * discarded every one of those imported bits and made the profile's own name untrue
     * (`docs/og-fidelity-plan.md` §AC).
     *
     * The rules with no per-scenario content gate — `counterbattery`, `minefields`,
     * `naval_critical_hits`, `depot_supply` — are deliberately NOT here: nothing in the content
     * asks for them, so Author's Vision has nobody to defer to and OSADA's default stands.
     * (`depot_supply`'s gate is `supply_ex`, which **no shipped efile sets**.)
     *
     * ### `RAIL_TRANSPORT` joined on 2026-08-29, and why it qualifies
     *
     * Its gate is not a bit but a POOL — `railtrans`, the per-player rail transport count. That
     * gate did not exist when this set was written: the importer could not find the field, so every
     * deployed scenario carried no pool and the entry would have been meaningless. `+21` was
     * confirmed and the pools imported on 2026-08-28/29, and **120 deployed scenarios now grant
     * one** (`docs/og-fidelity-plan.md` §AE.3).
     *
     * It meets this set's condition exactly: `RailTransport.canEntrain` already ANDs the key with
     * the player's pool, so resolving it to 1 hands the decision to the scenario rather than
     * switching a rule on. A scenario with no pool is unaffected, which is 382 of the 502.
     */
    private val SCENARIO_AUTHORED =
        setOf(
            RuleKey.EXTENDED_LOS,
            RuleKey.AIR_ZOC,
            RuleKey.EXTENDED_NAVAL,
            RuleKey.BARRAGE,
            RuleKey.BUILD_AND_REPAIR,
            RuleKey.RAIL_TRANSPORT,
        )

    /**
     * A rule OSADA owns outright.
     *
     * `stalin_regime` is seeded from the existing legacy preference at resolution time and locked
     * from then on: changing that old checkbox later must not mutate a campaign already under way
     * (§2). Everything in [SCENARIO_AUTHORED] defers to the scenario. The rest gets OSADA's
     * documented baseline.
     */
    private fun ownedRule(rule: RuleKey): ResolvedRule =
        when {
            rule == RuleKey.STALIN_REGIME ->
                (if (uiSettings.stalinRegime) 1 else 0).let { ResolvedRule(it, it, RuleProvenance.OSADA_DEFAULT) }

            rule in SCENARIO_AUTHORED -> ResolvedRule(1, 1, RuleProvenance.SCENARIO_AUTHORED)

            else ->
                RulesetDefaults.OSADA.getValue(rule).let { ResolvedRule(it, it, RuleProvenance.OSADA_DEFAULT) }
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
