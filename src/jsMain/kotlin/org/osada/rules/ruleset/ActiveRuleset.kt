package org.osada.rules.ruleset

import org.osada.model.EfileConfig

/**
 * The resolved ruleset in force right now (`docs/design/ruleset-profiles.md` §4).
 *
 * Rule code reads through here rather than calling [EfileConfig] directly, which is what makes the
 * selection real. With nothing resolved the overlay is absent and every read falls straight through
 * to the content exactly as before, so a player who never opens the Rules window sees byte-identical
 * behaviour.
 *
 * Locked when a campaign run or a scenario launch begins, and restored verbatim from a save rather
 * than re-resolved -- the profile it came from may have been renamed, edited or deleted since.
 */
object ActiveRuleset {
    private var current: ResolvedRuleset? = null

    fun currentOrNull(): ResolvedRuleset? = current

    fun set(ruleset: ResolvedRuleset?) {
        current = ruleset
    }

    fun clear() {
        current = null
    }

    /** Fingerprint for room state and diagnosis; empty when nothing has been resolved. */
    fun hash(): String = current?.deterministicHash ?: ""

    /**
     * Effective value for [rule], honouring the locked ruleset and otherwise deferring to the
     * content exactly as the call site used to.
     *
     * [efileDefault] stays the CALL SITE's documented default -- `EfileConfig`'s own rule that
     * absence is meaningful and must never be baked into the loader.
     */
    fun intKey(
        rule: RuleKey,
        efileDefault: Int,
    ): Int {
        val resolved = current
        val efileKey = rule.efileKey
        return when {
            resolved != null -> resolved.effective(rule)
            // A rule OSADA owns has no content key to consult, so the call site's live value stands.
            efileKey == null -> efileDefault
            else -> EfileConfig.intKey(efileKey, efileDefault)
        }
    }

    fun flag(
        rule: RuleKey,
        efileDefault: Boolean,
    ): Boolean = intKey(rule, if (efileDefault) 1 else 0) != 0

    internal fun resetForTest() {
        current = null
    }
}
