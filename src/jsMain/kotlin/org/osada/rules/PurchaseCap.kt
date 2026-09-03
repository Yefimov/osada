package org.osada.rules

import org.osada.GameHolder
import org.osada.model.GameUnit
import org.osada.model.Player

/**
 * Open General's **purchase cap** — how many NET-NEW formations the author lets a player add.
 *
 * > *"You can repurchase only the lost units."* — `EFILE_KAISER/rhu190613`'s own briefing, on a
 * > scenario whose `purchasecap` byte is **0**
 *
 * The switch is `opt_purchase_cap` (`.xscn` `@1016` bit 4) and the number is player record `+35`;
 * **12 deployed scenarios and 24 player records carry one**. Seven shipped scenarios state their
 * number in prose and all seven match the byte.
 *
 * ## Why this is two counters and not one expression
 *
 * The cap counts GROWTH, not purchases: replacing a formation the player lost does not consume a
 * slot, which is what makes `purchasecap="0"` mean *"no growth"* rather than *"no purchases"*. So
 * the rule needs to know how many losses are still un-replaced, and that is history rather than a
 * property of the current army.
 *
 * A live `currentUnits < initialUnits + cap` expression would look equivalent and is not: it
 * silently decides that SELLING a unit, disbanding one, withdrawing through an escape hex or having
 * one removed by a script all grant replacement capacity. It also cannot express
 * [org.osada.scenario.Scenario.coresExemptFromPurchaseCap] at all. Hence
 * [Player.purchaseGrowthSpent] and [Player.replacementCredits], both serialized — a counter that
 * did not survive a save would make reloading a way to restore spent slots.
 *
 * ## Where each half is driven from
 *
 * * **Credits are minted in `GameMap.updateUnitList()`** ([creditReplacement]). That is the one
 *   sweep every death passes through, whatever killed it — combat, surrender, a minefield, running
 *   out of fuel, a kamikaze run — which is exactly the set OG means by *"lost"*. The deliberate
 *   removals are excluded by the signals they already set: sale and disband, scripted removal and
 *   the between-scenario purge all mark the unit `nodossier`, while escape-hex withdrawal,
 *   undeployment and boarding a container never set `destroyed` at all and so never reach the sweep.
 * * **Slots are spent in `Player.buyUnit`** ([recordPurchase]), and the same [allows] test is asked
 *   FIRST by the equipment window (so a disabled Buy button can name the rule) and by
 *   `ai/AIPurchasing` (so the AI does not keep selecting a unit the mutation layer will reject).
 *   `buyUnit` keeps the final check regardless: it is the one function a replayed multiplayer order
 *   also passes through.
 *
 * ## `opt_cores_off_cap`
 *
 * OG's *"core units added by design do not count against the CAP"* (`@1015` bit 5) presupposes that
 * by default they DO — a formation the scenario author enrols into the campaign core is a net-new
 * formation like any other ([recordDesignAddedCore]). **Inert on shipped content**: 12 deployed
 * scenarios author a cap and 26 author Make Core units, and the two sets do not intersect. It is
 * built because the option is meaningless without it and because the alternative is a rule that
 * cannot represent what the author asked for; it is measured so nobody mistakes it for live
 * behaviour.
 *
 * ## Selling does not refund a slot
 *
 * A formation sold from the reserve tray or disbanded on the map neither mints a credit nor gives a
 * growth slot back. That is deliberate and is the safe direction: OG's own sentence is about units
 * LOST, and a refund would let a player cycle buy-and-sell to exceed the author's number. If a
 * source is ever found saying otherwise, this is the paragraph that has to change.
 *
 * ## Not the tutorial's prestige ceiling
 *
 * OSADA's Khalkhin Gol tutorial has an army-value/Jensen prestige limit. That is a different
 * mechanic living nowhere near this byte, and nothing here touches it.
 */
object PurchaseCap {
    /**
     * Whether [player] may add one more formation right now.
     *
     * True whenever no cap was authored, which is 490 of the 502 deployed scenarios. A player
     * holding a replacement credit may always buy, however many growth slots they have spent.
     */
    fun allows(player: Player?): Boolean = allowsAfter(player, 0)

    /**
     * Whether [player] could still add a formation once [pending] acquisitions already chosen but
     * not yet booked have been paid for.
     *
     * The AI needs this and the UI does not: `AIPurchasing.selectUnits` builds a whole shopping
     * LIST and only then hands each entry to `Player.buyUnit`, so asking [allows] alone would let it
     * fill a list the mutation layer then throws most of the way away. [pending] is charged the same
     * way a real purchase is -- credits first, then growth slots.
     */
    fun allowsAfter(
        player: Player?,
        pending: Int,
    ): Boolean {
        val cap = player?.purchaseCap ?: return true
        val creditsLeft = player.replacementCredits - pending
        // A credit still in hand always allows. Otherwise every acquisition past the credits has
        // spent a growth slot, which is what `-creditsLeft` counts once it has gone negative.
        return creditsLeft > 0 || player.purchaseGrowthSpent - creditsLeft < cap
    }

    /**
     * Growth slots still available to [player], or null when they are uncapped.
     *
     * For UI copy only. Replacement credits are reported separately by [replacementCreditsFor]
     * because the two spend differently and collapsing them into one number would tell the player
     * they may grow when they may only replace.
     */
    fun remainingGrowth(player: Player?): Int? =
        player?.purchaseCap?.let { (it - player.purchaseGrowthSpent).coerceAtLeast(0) }

    /** Un-replaced losses [player] may buy back outside the cap; 0 when uncapped. */
    fun replacementCreditsFor(player: Player?): Int = player?.replacementCredits ?: 0

    /**
     * Books one acquisition against [player]'s cap: a replacement credit if they hold one, a growth
     * slot otherwise.
     *
     * A no-op for an uncapped player, so callers need no condition. Credits are spent first because
     * OG's rule is that replacing a loss is free — spending a slot while a credit was available
     * would charge for the one purchase the author said was always allowed.
     */
    fun recordPurchase(player: Player) {
        if (player.purchaseCap == null) return
        if (player.replacementCredits > 0) {
            player.replacementCredits--
        } else {
            player.purchaseGrowthSpent++
        }
        invalidateUndo()
    }

    /**
     * Books a formation the SCENARIO AUTHOR added to the core roster (OG's Make Core), unless this
     * scenario sets *"core units added by design do not count against the CAP"*.
     *
     * Charged the same way a purchase is, credits first: the author handing the player a formation
     * is exactly as much growth as buying one, and OG's own option exists to say when it is not.
     */
    fun recordDesignAddedCore(
        player: Player,
        unit: GameUnit,
    ) {
        if (player.purchaseCap == null || unit.isTemporaryBorrowed) return
        if (GameHolder.instance?.scenario?.coresExemptFromPurchaseCap == true) return
        recordPurchase(player)
    }

    /**
     * Mints a replacement credit for a formation [unit]'s owner LOST.
     *
     * Called from the death sweep, so every cause of loss is covered by construction. Two
     * exclusions, both reading a signal the engine already sets rather than inventing one:
     *
     * * `nodossier` — the marker every deliberate removal already carries (sale/disband, a scenario
     *   event converting a unit away, the between-scenario purge) and the one scripted
     *   non-combatants are authored with. It is the same signal the campaign dossier uses to decide
     *   whether a removal was a casualty, so the two can never disagree.
     * * `isTemporaryBorrowed` — a formation lent for one battle was never the player's to replace.
     */
    fun creditReplacement(unit: GameUnit) {
        if (unit.nodossier || unit.isTemporaryBorrowed) return
        val player = unit.player?.takeIf { it.purchaseCap != null } ?: return
        player.replacementCredits++
        invalidateUndo()
    }

    /**
     * A cap counter changed, so the pending single-move undo record is dropped.
     *
     * Undo restores a unit's position, not the economy, and OG's cap is spent history: leaving the
     * record in place would let a player undo a move taken after a purchase and keep both.
     */
    private fun invalidateUndo() {
        GameHolder.instance
            ?.scenario
            ?.map
            ?.undoState
            ?.invalidate(null, org.osada.model.UndoInvalidation.IRREVERSIBLE_ACTION)
    }
}
