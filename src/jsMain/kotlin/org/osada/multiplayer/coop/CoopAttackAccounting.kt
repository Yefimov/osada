package org.osada.multiplayer.coop

/** One defender-side support shot, reduced to the facts the ledger needs. */
data class CoopSupportFact(
    val supporterFriendly: Boolean,
    val supporterCommanderId: String?,
    /** Actual strength the shot took off the attacker. */
    val damage: Int,
)

/**
 * A resolved attack order as plain numbers, so the scoring rules can be tested without a game map.
 * "Friendly" means the human side -- the commanders' own units AND allied AI; a commander id is
 * present only for a unit actually assigned to one. Nothing here is a forecast: every figure is what
 * the authoritative exchange actually did (§8.2 -- "never forecast damage or overkill").
 */
data class CoopAttackFacts(
    val attackerId: Int,
    val defenderId: Int,
    val attackerFriendly: Boolean,
    val defenderFriendly: Boolean,
    val attackerCommanderId: String?,
    val defenderCommanderId: String?,
    /** Strength the attacker lost across support fire, return fire and counter-battery. */
    val attackerLost: Int,
    val attackerDestroyed: Boolean,
    /** Strength the defender lost to combat itself -- a surrender's remaining strength is NOT damage. */
    val defenderLost: Int,
    val defenderDestroyed: Boolean,
    val defenderCaptured: Boolean,
    val defenderRetreated: Boolean,
    val attackStopped: Boolean,
    val supportShots: List<CoopSupportFact> = emptyList(),
    /** Remaining strength taken prisoner, separate from combat casualties. */
    val defenderCapturedStrength: Int = 0,
)

/**
 * Turns one resolved attack into ledger entries (`docs/multiplayer/cooperative-commanders.md`
 * §8.2/§8.3). Called once per attack by the authority, from `AttackObservers`, which is what makes
 * an ENEMY-turn exchange -- the defender's endurance, an automatic supporter's shot, a counter-fire
 * kill -- credit the right commander using the assignment AT EVENT TIME.
 *
 * "Stunned" is deliberately not scored (§8.2): attacks stopped and forced retreats are recorded as
 * their own statistics with no points until the owner picks the meaning (§13.5).
 */
object CoopAttackAccounting {
    fun record(
        ledger: CoopLedger,
        facts: CoopAttackFacts,
    ) {
        when {
            facts.attackerFriendly && !facts.defenderFriendly -> friendlyAttack(ledger, facts)
            !facts.attackerFriendly && facts.defenderFriendly -> enemyAttack(ledger, facts)
        }
    }

    private fun friendlyAttack(
        ledger: CoopLedger,
        f: CoopAttackFacts,
    ) {
        val who = f.attackerCommanderId
        // The terminal outcome is settled BEFORE this attack's own damage joins the window tally, so
        // "earlier damage in the same turn" really means earlier -- the finisher keeps a flat half.
        when {
            f.defenderCaptured -> ledger.recordTerminal(f.defenderId, who, captured = true)
            f.defenderDestroyed -> ledger.recordTerminal(f.defenderId, who, captured = false)
        }
        val terminal = f.defenderCaptured || f.defenderDestroyed
        ledger.recordDamage(f.defenderId, who, f.defenderLost, tally = !terminal)
        if (f.defenderRetreated) ledger.recordForcedRetreat(who)
        ledger.recordLoss(who, f.attackerLost, f.attackerDestroyed)
    }

    private fun enemyAttack(
        ledger: CoopLedger,
        f: CoopAttackFacts,
    ) {
        val defender = f.defenderCommanderId
        ledger.recordLoss(
            defender,
            f.defenderLost,
            f.defenderDestroyed || f.defenderCaptured,
            capturedStrength = f.defenderCapturedStrength,
        )
        val heldItsHex = !f.defenderDestroyed && !f.defenderRetreated && !f.defenderCaptured
        if (defender != null && heldItsHex) ledger.recordEndurance(f.defenderId, defender)
        f.supportShots.filter { it.supporterFriendly }.forEach { ledger.recordSupport(it.supporterCommanderId) }

        // The damage the enemy attacker took, in the order it happened: each friendly support shot,
        // then the defender's return fire. Every event joins the window tally EXCEPT the one that
        // finished the attacker, so the finisher keeps a flat half and earlier contributors share the rest.
        val returnFire = (f.attackerLost - f.supportShots.sumOf { it.damage }).coerceAtLeast(0)
        val events =
            f.supportShots
                .filter {
                    it.supporterFriendly && it.damage > 0
                }.map { it.supporterCommanderId to it.damage } +
                listOfNotNull(if (returnFire > 0) defender to returnFire else null)
        val finisher = events.lastOrNull()?.first
        events.forEachIndexed { index, (commander, amount) ->
            val finishing = f.attackerDestroyed && index == events.lastIndex
            ledger.recordDamage(f.attackerId, commander, amount, tally = !finishing)
        }
        if (f.attackerDestroyed) {
            // Support fire from an UNASSIGNED unit that made the kill has no player actor to credit.
            ledger.recordTerminal(f.attackerId, if (events.isEmpty()) null else finisher, captured = false)
        }
        if (f.attackStopped) ledger.recordAttackStopped(f.supportShots.lastOrNull()?.supporterCommanderId)
    }
}
