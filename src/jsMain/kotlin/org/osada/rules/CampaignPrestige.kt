package org.osada.rules

import org.osada.model.Player
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Open General's per-scenario starting budgets in a campaign ([RuleKey.CAMPAIGN_START_PRESTIGE]):
 * OpenSuite's *"Start AI Prestige"* (`.xcam` record `@4`, `aiprestige`) and *"Start Player
 * Prestige"* (`@6`, `playerprestige`), both placed by the owner's controlled diff of 2026-09-25.
 *
 * > *"the cap is 2000 and the AI starts with 150 prestige points"* — `Manual_OSuite-Basic.pdf`
 * > pp.15-16, and every installed `Camp6.xcam` record for `ciechan` carries `@4 = 150`.
 *
 * Both are ADDED. The AI already starts with its turn-1 income (`ScenarioPlayerParser` seeds
 * `prestige` with `turnprestige[0]`); the human with whatever the core carried in from the last
 * battle, or the campaign's own starting budget on its first.
 */
internal object CampaignStartPrestige {
    fun enabled(): Boolean = ActiveRuleset.flag(RuleKey.CAMPAIGN_START_PRESTIGE, false)

    /** Credits the authored budgets: `aiprestige` to every player opposing [human], and
     *  `playerprestige` to [human]. Nothing when the rule is off or the record authors neither. */
    fun apply(
        scenarioRecord: dynamic,
        human: Player,
        players: List<Player>,
    ) {
        if (!enabled()) return
        val ai = (scenarioRecord?.aiprestige as? Int)?.takeIf { it > 0 } ?: 0
        val own = (scenarioRecord?.playerprestige as? Int)?.takeIf { it > 0 } ?: 0
        players.filter { it.side != human.side }.forEach { it.prestige += ai }
        human.prestige += own
    }
}

/**
 * Open General's campaign prestige CAP ([RuleKey.CAMPAIGN_PRESTIGE_CAP]), from the author's own
 * campaign page (`luis-tools.open-general.com/OpenGen_Campaigns.html`):
 *
 * > *"the award is determined when the computer compares the CAP to the value of your army plus
 * > your prestige ... If the value of your army plus your prestige is less than the CAP, you win
 * > the difference up to the maximum prestige award for the type of victory you won. Otherwise,
 * > you get nothing."*
 * > *"the original units in a campaign are not counted against the CAP, nor are prototypes ...
 * > Overstrength is not counted against the CAP either."*
 * > *"only the prestige cost of the current equipment is counted"* (so an upgrade counts at the
 * > new equipment's price, not at what was paid for it).
 *
 * The cap compared is the scenario JUST PLAYED — the page's example applies Madrid's 2,000 to the
 * award at the end of Madrid. Imported as the scenario record's `prestigecap`; 0 or absent is no cap.
 *
 * **Army value is the purchased formations only** ([org.osada.model.GameUnit.isPurchased]), each
 * at its current unit + transport price, whatever its strength. That covers the page's three
 * exclusions and also leaves out trigger and story grants. One OG nuance is NOT modelled: a core
 * unit a LATER scenario grants counts against the cap in OG unless that scenario sets *"Added Core
 * behave as prototypes"*; here every grant is free. The error runs in the player's favour.
 */
internal object CampaignPrestigeCap {
    fun enabled(): Boolean = ActiveRuleset.flag(RuleKey.CAMPAIGN_PRESTIGE_CAP, false)

    fun armyValue(player: Player): Int =
        player
            .getCoreUnitList()
            .filter { it.isPurchased && !it.destroyed }
            .sumOf { CostCalculator.calculateUnitCosts(it.eqid, it.transport?.eqid ?: 0) }

    /** [award] cut to the room left under [cap]; unchanged when the rule is off, there is no
     *  cap, or the award is not a gain. */
    fun cappedAward(
        award: Int,
        cap: Int?,
        player: Player,
    ): Int {
        val authoredCap = cap?.takeIf { it > 0 && enabled() }
        if (authoredCap == null || award <= 0) return award
        val room = authoredCap - armyValue(player) - player.prestige
        return award.coerceAtMost(room.coerceAtLeast(0))
    }
}
