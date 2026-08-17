package org.osada.rules.ruleset

/**
 * Whether two peers will execute the same game (`docs/design/ruleset-profiles.md` §8).
 *
 * Compares semantics, never identity: a client does not need the host's local profile, and a host
 * who renamed "My rules" to "My rules v2" has not changed a single rule. Blocking that join would
 * be a lie about the state of the match.
 */
object RulesetCompatibility {
    /** Why a join was refused. */
    enum class Refusal {
        /** The room declares a schema this build does not implement. */
        UNSUPPORTED_SCHEMA,

        /** The room names rules this build cannot execute. */
        UNKNOWN_RULES,

        /** Effective gameplay values differ from the ones this client resolved. */
        DIFFERENT_RULES,
    }

    data class Verdict(
        val allowed: Boolean,
        val refusal: Refusal? = null,
        val differingRules: List<RuleKey> = emptyList(),
        val unknownKeys: Set<String> = emptySet(),
        val localHash: String = "",
        val remoteHash: String = "",
    )

    /** The rules whose effective values differ. Empty means the two peers execute the same game. */
    fun difference(
        local: ResolvedRuleset?,
        remote: ResolvedRuleset?,
    ): List<RuleKey> {
        if (local == null || remote == null) return emptyList()
        return RuleKey.entries.filter { rule -> local.effective(rule) != remote.effective(rule) }
    }

    /**
     * The join decision for a client holding [local] against a room block [remoteData].
     *
     * A client that has expressed no opinion adopts the host's rules, which is the normal case and
     * is not a conflict; only a client that deliberately resolved its own can genuinely disagree.
     * An unsupported schema or an unknown key refuses regardless, because this build cannot promise
     * to execute what it cannot read (§8).
     */
    fun verdict(
        local: ResolvedRuleset?,
        remoteData: dynamic,
    ): Verdict {
        val schema = readRulesetSchemaVersion(remoteData)
        val unknown = unknownRulesetKeys(remoteData)
        val remote = deserializeRuleset(remoteData)
        return when {
            schema != null && schema > RULESET_SCHEMA_VERSION ->
                Verdict(false, Refusal.UNSUPPORTED_SCHEMA, remoteHash = remote?.deterministicHash.orEmpty())

            unknown.isNotEmpty() ->
                Verdict(
                    allowed = false,
                    refusal = Refusal.UNKNOWN_RULES,
                    unknownKeys = unknown,
                    remoteHash = remote?.deterministicHash.orEmpty(),
                )

            else -> valueVerdict(local, remote)
        }
    }

    private fun valueVerdict(
        local: ResolvedRuleset?,
        remote: ResolvedRuleset?,
    ): Verdict {
        val differing = difference(local, remote)
        return Verdict(
            allowed = differing.isEmpty(),
            refusal = if (differing.isEmpty()) null else Refusal.DIFFERENT_RULES,
            differingRules = differing,
            localHash = local?.deterministicHash.orEmpty(),
            remoteHash = remote?.deterministicHash.orEmpty(),
        )
    }
}
