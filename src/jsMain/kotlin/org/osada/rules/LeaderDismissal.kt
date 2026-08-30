package org.osada.rules

import org.osada.model.EfileConfig
import org.osada.model.GameUnit

/**
 * OG's **`rem_leader`** — letting the player dismiss a formation's commander.
 *
 * ### The sourcing, and it is thinner than most rules here
 *
 * **`rem_leader` carries no comment in any shipped `equip.cfg`.** Unlike `remove_leader` (the green
 * -replacement clause, documented in full in `OPENTXT_SAMPLE`) this key is set — by `eqp-atomic`
 * and `eqp-lxf` — and explained nowhere in the installed files. What names it is the author's own
 * changelog:
 *
 * > *"New leader management options including **removal via Ctrl+X**"* — 0.93.5.RC1, 21-Feb-2024
 *
 * So the reading is: **`rem_leader = 1` lets the player send a commander away on demand.** That is
 * an inference from a changelog line plus a key name, which is weaker evidence than this project
 * usually builds on, and it is stated here so the next reader can overturn it cheaply. What makes
 * it safe to build anyway is the shape of the action: it takes something away only when the player
 * asks for it, so a wrong reading costs nobody anything they did not choose.
 *
 * ### Why a player would ever want to
 *
 * A commander is not always an improvement. OSADA's leaders carry two effective traits and some are
 * situational — an anti-air specialist on a formation that never sees aircraft is a slot spent —
 * and a dismissed commander frees the formation to acquire a different one later. OG's own
 * `upgrade_ldr = 2` works the same way: *"remove leader, reducing unit's exp and bars as to be able
 * to get a new leader"*.
 *
 * ### What it deliberately does NOT do
 *
 * `upgrade_ldr = 2`'s experience penalty is **not** applied. That clause belongs to the UPGRADE
 * path (`rules/LeaderOnUpgrade`) and is documented for it; charging it here would be borrowing a
 * cost from a different key. Dismissal is free, which is the reading that takes least from the
 * player — and if OG turns out to charge for it, this sentence is the one to come back to.
 */
object LeaderDismissal {
    /** Whether the efile allows a commander to be sent away at all. */
    fun enabled(): Boolean = EfileConfig.intKey("rem_leader", 0) == 1

    /** Whether [unit] has a commander this player could dismiss. */
    fun canDismiss(unit: GameUnit): Boolean = enabled() && unit.leader >= 0 && !unit.destroyed

    /**
     * Send [unit]'s commander away, returning true when one actually left.
     *
     * Deliberately costs no action: the formation has not done anything on the battlefield, and
     * spending its turn would make the choice punitive rather than free.
     */
    fun dismiss(unit: GameUnit): Boolean {
        if (!canDismiss(unit)) return false
        unit.leader = -1
        return true
    }
}
