package org.osada.rules

import org.osada.CombatLog
import org.osada.LeaderType
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.InterceptionEvent
import org.osada.model.Leaders
import org.osada.model.MoveReactionKind
import org.osada.model.fire
import org.osada.model.getUnits
import org.osada.model.hit

/**
 * Opportunity fire by a commander with OG's `Overwatch` trait: *"The unit will fire at any enemy
 * unit that moves within range."*
 *
 * The trait has been offered to the player, with that exact sentence, since the port began, and
 * nothing anywhere read it (`docs/og-fidelity-plan.md` A.4 — the same defect class as
 * `SUPERIOR_MANEUVER` and `LIBERATOR` before it). This is that rule.
 *
 * **Deliberately modelled on [AAInterception] rather than on support fire**, because it is the same
 * shape of event: a combat the moving player did not initiate and does not watch resolve. So it
 * follows every constraint that mechanic already answers to (`DEFERRED.md` §1.1):
 *
 *  - it is **one-sided** — the mover does not fire back, exactly as an intercepted aircraft cannot
 *    shoot at the gun;
 *  - it publishes **nothing before it fires**, and reports itself afterwards through the same
 *    [InterceptionEvent] channel the banner and HUD log already read, so movement damage never
 *    appears with no visible cause;
 *  - it **invalidates undo**, or a player could sweep for overwatching guns and take the probe back.
 *
 * Three differences from interception, each a decision rather than an omission:
 *
 *  1. **It does not stop the move.** Interception halts the aircraft because OG's rule says the
 *     sortie is broken; overwatch is fire *at* something passing, and the sentence describes no
 *     interruption.
 *  2. **It fires once per moving formation**, at the first hex of that formation's path where it
 *     comes into range — not once per hex walked, which would let one gun destroy a formation for
 *     crossing an open field.
 *  3. **It spends the watcher's own attack for the turn** ([GameUnit.hasFired]). Without that a
 *     single overwatch gun answers every enemy formation that moves all turn, for free, and then
 *     still attacks in its own. The trait buys a shot taken at a better moment, not extra shots.
 */
internal object OverwatchFire {
    /**
     * Enemy units that would fire on [mover] entering [cell]: an `Overwatch` commander, in its own
     * gun range, with a shot left and able to engage this target at all.
     *
     * [alreadyFired] holds the ids of watchers that have already answered this move, which is what
     * makes the rule once-per-formation rather than once-per-hex.
     */
    fun watchersFor(
        map: GameMap,
        mover: GameUnit,
        cell: Cell,
        alreadyFired: Set<Int>,
    ): List<GameUnit> {
        val moverSide = mover.player?.side ?: return emptyList()
        return map.getUnits().filter { watcher ->
            watcher.id !in alreadyFired && isEligibleWatcher(watcher, mover, moverSide, cell)
        }
    }

    private fun isEligibleWatcher(
        watcher: GameUnit,
        mover: GameUnit,
        moverSide: Int,
        cell: Cell,
    ): Boolean {
        val wPos = watcher.getPos() ?: return false
        val range = AttackEligibility.getUnitAttackRange(watcher)
        val inRange = HexGeometry.distance(wPos.row, wPos.col, cell.row, cell.col) <= range
        return watcher.player?.side != moverSide &&
            !watcher.hasFired &&
            !watcher.destroyed &&
            Leaders.unitHasLeader(watcher, LeaderType.OVERWATCH) &&
            inRange &&
            AttackEligibility.canInitiateAttack(watcher, mover, asActiveAttack = false)
    }

    /**
     * Applies one-sided fire from [watchers] to [mover], which must already stand on the cell that
     * triggered them so range and terrain resolve against where it actually is.
     *
     * Returns the events for the HUD. Damage stops as soon as the mover is destroyed — the
     * remaining watchers hold their fire rather than shooting a wreck.
     */
    fun applyOverwatch(
        map: GameMap,
        mover: GameUnit,
        watchers: List<GameUnit>,
    ): List<InterceptionEvent> {
        val units = map.getUnits().toList()
        val turn = map.turn
        val events = mutableListOf<InterceptionEvent>()
        for (watcher in watchers) {
            if (mover.destroyed) break
            val logId = CombatLog.addCombatStart(watcher, mover, turn)
            val result = GameRules.calculateAttackResults(watcher, mover, true, units, committed = true)
            mover.hit(result.kills, UnitCapabilities.hasLastingSuppression(watcher))
            // `fire(true)` rather than `fire(false)`: the shot is spent, which is the cost that
            // keeps one commander from answering every enemy move of the turn. Devastating Fire
            // still applies on top, through `fire` itself -- a commander with both really does get
            // two opportunity shots, which is what the two sentences together say.
            watcher.fire(true)
            CombatLog.addCombatEnd(watcher, mover, logId, true)
            events += InterceptionEvent(watcher, mover, result.kills, mover.destroyed, MoveReactionKind.OVERWATCH)
        }
        return events
    }
}
