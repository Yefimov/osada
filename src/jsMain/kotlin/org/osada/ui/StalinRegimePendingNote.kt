package org.osada.ui

import org.osada.GameHolder
import org.osada.i18n.I18n
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey
import org.osada.uiSettings
import org.w3c.dom.HTMLElement

/**
 * Says out loud that the **Stalin Regime** checkbox has not taken effect yet.
 *
 * `RulesetResolver.ownedRule` seeds `stalin_regime` from this checkbox when an operation launches
 * and then FREEZES it for the whole operation, so that editing the checkbox cannot mutate a
 * campaign already under way (`docs/design/ruleset-profiles.md` §2). That is the intended rule --
 * but nothing on screen said so, so ticking the box mid-battle looked simply broken: reported as
 * *"I started the mission, picked Stalin Regime in settings, hit restart mission, and my units'
 * stats did not go up"*. Restarting does not help either, because a mission-restart replays the
 * checkpoint's own saved ruleset.
 *
 * So the row states the deferral instead of leaving the player to guess. Nothing is drawn while the
 * checkbox and the ruleset in force agree, which is every case outside a running battle.
 *
 * Its own file rather than a [StartMenuSettingsBuilder] member for the same reason
 * [ObserverModeLock] is: that object is at detekt's function ceiling and this is one rule.
 */
internal object StalinRegimePendingNote {
    private const val NOTE_CLASS = "osada-setting-pending"

    /**
     * True when the checkbox says one thing and the operation in force is running the other -- the
     * only state worth a note. `false` with no game, with no locked ruleset, or once the two agree.
     */
    fun isPending(): Boolean =
        GameHolder.instance?.gameStarted == true &&
            ActiveRuleset.currentOrNull() != null &&
            // Read with the checkbox as the fallback, exactly like `Player.usesStalinRegime`: the
            // answer wanted is "what would the engine do", not "what does the ruleset table hold".
            inForce() != uiSettings.stalinRegime

    /** Re-applies the note to the already-built settings DOM. Safe to call at any time. */
    fun refresh() {
        val value = byId("stalinRegime") ?: return
        val row = value.parentElement as? HTMLElement ?: return
        // A multiplayer match has its own, stronger explanation on this row; do not argue with it.
        val pending = isPending() && !ObserverModeLock.isLocked()
        val note =
            row.querySelector(".$NOTE_CLASS") as? HTMLElement
                ?: addTag(row, "div").also { it.className = NOTE_CLASS }
        note.textContent = if (pending) I18n.t(pendingKey()) else ""
        row.classList.toggle("settingContainer--pending", pending)
    }

    /**
     * Which deferral the player is actually looking at -- the note has to name the thing that WILL
     * work, not just the thing that will not.
     *
     * A campaign run freezes its rules for the WHOLE run, so restarting one mission of it replays
     * the same frozen set and changes nothing; only starting the campaign over picks the checkbox
     * up. A standalone battle freezes only itself, so starting that scenario again is enough.
     * Telling both cases "from the next operation" left a campaign player restarting the mission
     * over and over, which is exactly the loop this note exists to end.
     */
    private fun pendingKey(): String =
        if (GameHolder.instance?.campaign != null) {
            "settings.gameplay.stalin_regime.pending.campaign"
        } else {
            "settings.gameplay.stalin_regime.pending"
        }

    /** Whether the mode is actually running, as opposed to merely ticked. The OBSERVER badge reads
     *  this: a badge that claims an advantage the operation is not granting is worse than none. */
    fun inForce(): Boolean = ActiveRuleset.flag(RuleKey.STALIN_REGIME, uiSettings.stalinRegime)
}
