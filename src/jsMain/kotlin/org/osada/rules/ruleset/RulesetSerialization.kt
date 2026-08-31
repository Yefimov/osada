package org.osada.rules.ruleset

/*
 * The one ruleset codec (`docs/design/ruleset-profiles.md` §6).
 *
 * Saves, campaign autosave/recovery generations, campaign export, restart checkpoints and
 * multiplayer snapshots all use this. The block carries the complete EFFECTIVE values, so restoring
 * reproduces the battle that was recorded even after the named profile has been renamed, edited or
 * deleted.
 */

/** `{ id, name, source, schemaVersion, hash, effective: { key: n } }`, or `null` when unresolved. */
fun serializeRuleset(ruleset: ResolvedRuleset?): dynamic {
    if (ruleset == null) return null
    val effective = js("{}")
    RuleKey.entries.forEach { rule -> effective[rule.key] = ruleset.effective(rule) }
    val out = js("{}")
    out.id = ruleset.id
    out.name = ruleset.name
    out.source = ruleset.source.name
    out.schemaVersion = ruleset.schemaVersion
    // Diagnostic only: the reader recomputes it (§5).
    out.hash = ruleset.deterministicHash
    out.effective = effective
    return out
}

/**
 * Rebuilds a resolved ruleset from stored effective values.
 *
 * `null` for a save written before rulesets shipped: that battle ran under its own content's rules
 * with no overlay, which is exactly what an absent active ruleset reproduces. Substituting OSADA
 * Default here would silently rewrite the rules of every existing save (§4).
 */
@Suppress("TooGenericExceptionCaught")
fun deserializeRuleset(data: dynamic): ResolvedRuleset? =
    try {
        if (data == null || data == undefined) null else readRuleset(data)
    } catch (e: Throwable) {
        console.warn("[OSADA] saved ruleset unreadable; restoring without an overlay", e)
        null
    }

/**
 * The stored block's schema and effective values, or `null` when this build cannot execute them.
 * Used by the multiplayer join gate, which must refuse rather than approximate (§8).
 */
fun readRulesetSchemaVersion(data: dynamic): Int? =
    if (data == null || data == undefined) null else data.schemaVersion as? Int

/** Keys in a stored block this build does not understand. Non-empty means "do not execute this". */
fun unknownRulesetKeys(data: dynamic): Set<String> {
    val effective = if (data == null || data == undefined) null else data.effective
    if (effective == null || effective == undefined) return emptySet()
    return js("Object.keys")(effective)
        .unsafeCast<Array<String>>()
        .filter { RuleKey.byKey(it) == null && it !in RETIRED_RULE_KEYS }
        .toSet()
}

private fun readRuleset(data: dynamic): ResolvedRuleset? {
    val id = (data.id as? String)?.trim()
    if (id.isNullOrBlank()) return null
    return RulesetResolver.fromEffective(
        id = id,
        name = (data.name as? String).orEmpty(),
        source = readSource(data.source as? String),
        schemaVersion = (data.schemaVersion as? Int) ?: RULESET_SCHEMA_VERSION,
        effective = readEffective(data.effective),
    )
}

private fun readSource(name: String?): RulesetSource =
    RulesetSource.entries.firstOrNull { it.name == name } ?: RulesetSource.CUSTOM

/** Deliberately unclamped: a stored effective value is what the battle ran under, including a
 *  content value outside the editor's first-release range such as LXF's `flak_range = 4` (§2). */
private fun readEffective(effective: dynamic): Map<RuleKey, Int> {
    if (effective == null || effective == undefined) return emptyMap()
    val result = mutableMapOf<RuleKey, Int>()
    js("Object.keys")(effective).unsafeCast<Array<String>>().forEach { key ->
        val rule = RuleKey.byKey(key)
        val value = effective[key] as? Int
        if (rule != null && value != null) result[rule] = value
    }
    return result
}
