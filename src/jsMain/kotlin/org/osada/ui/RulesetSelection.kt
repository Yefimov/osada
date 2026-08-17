package org.osada.ui

import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.ResolvedRuleset
import org.osada.rules.ruleset.RulesetProfile
import org.osada.rules.ruleset.RulesetProfileStore
import org.osada.rules.ruleset.RulesetResolver

/**
 * Which profile the next launch will use, per launch surface
 * (`docs/design/ruleset-profiles.md` §1).
 *
 * Convenience state only: it is remembered for the page, never attached to a campaign or scenario
 * catalogue record. What a save or a room records is the RESOLVED result, taken at launch.
 */
internal object RulesetSelection {
    /** The launch surfaces that own a selection of their own. */
    enum class Surface {
        CAMPAIGN,
        SCENARIO,
        MULTIPLAYER,
    }

    private val selected = mutableMapOf<Surface, String>()

    fun selectedId(surface: Surface): String = selected[surface] ?: RulesetProfile.AUTHORS_VISION_ID

    /** Selecting an unsupported profile is refused rather than silently downgraded (§3). */
    fun select(
        surface: Surface,
        id: String,
    ): Boolean {
        val profile = RulesetProfileStore.byId(id)
        val usable = profile != null && profile.supported
        if (usable) selected[surface] = id
        return usable
    }

    fun selectedProfile(surface: Surface): RulesetProfile =
        RulesetProfileStore.byId(selectedId(surface)) ?: RulesetProfileStore.builtIns().first()

    /**
     * Resolves the current selection against the content configuration loaded right now.
     *
     * Called at launch, never earlier: resolving before the efile is loaded would record OSADA's
     * defaults as though the author had chosen them (§4).
     */
    fun resolve(surface: Surface): ResolvedRuleset = RulesetResolver.resolve(selectedProfile(surface))

    /** Locks the resolution in for the operation about to start. */
    fun apply(surface: Surface) {
        ActiveRuleset.set(resolve(surface))
    }

    /** A deleted profile must not leave a surface pointing at nothing. */
    fun forget(id: String) {
        selected.entries.filter { it.value == id }.forEach { selected.remove(it.key) }
    }

    internal fun resetForTest() {
        selected.clear()
    }
}
