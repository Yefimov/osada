package org.osada.model

import org.osada.GameHolder
import org.osada.PlayerType
import org.osada.hero.HeroCampaign
import org.osada.rules.ExtendedVictory
import org.osada.rules.GameRules
import org.osada.rules.TriggerHexes
import org.osada.rules.UnitCapabilities
import org.osada.rules.canCapture
import org.osada.rules.getDirection
import org.osada.rules.getShortestPath
import org.osada.rules.setSpotRange
import org.osada.rules.setZOCRange

/**
 * Executes unit movement along a path and manages the single-move undo stack.
 * Extracted from the former [GameMap] god-class (SRP).
 */
internal class MoveExecutor(
    // Internal (not private): MoveExecutorHelpers.kt's resolveUndoContext/resolveUndoPositions/
    // resolveUndoHexes extension functions (moved out to keep this class under detekt's
    // TooManyFunctions budget) read this from another file.
    internal val gameMap: GameMap,
) {
    fun canUndoMove(unit: GameUnit): Boolean = gameMap.undoState.unit?.id == unit.id

    fun moveUnit(
        unit: GameUnit,
        row: Int,
        col: Int,
    ): MovementResults {
        val result = MovementResults()
        // The undo snapshot is taken BEFORE the auto-mount, so rewinding a ride puts the formation
        // back on the ground where it started rather than sitting in a truck it never asked for.
        val snapshot = undoSnapshot(unit)
        val mounted = autoMountForMove(unit, row, col)
        val setup = setupMove(unit, row, col)
        if (setup == null) {
            if (mounted) revertAutoMount(unit)
            return result
        }
        commitUndoState(unit, snapshot)
        val totalCost = traversePath(unit, setup, result)
        // AA interception can destroy the plane mid-path (§3.4, docs/design/aa-interception.md):
        // it is already correctly positioned at the intercepting cell (applyAAInterception moved
        // it there before applying damage), so the normal destroyed-unit sweep finds and removes
        // it from exactly where it fell -- applyMove must NOT also run, or a dead unit would be
        // "placed" a second time.
        if (unit.destroyed) {
            gameMap.undoState.clear()
            gameMap.updateUnitList()
        } else {
            result.passedCells.lastOrNull()?.let { last ->
                applyMove(unit, setup, row, col, last, totalCost, result)
            }
        }
        return result
    }

    private fun setupMove(
        unit: GameUnit,
        row: Int,
        col: Int,
    ): MoveSetup? {
        val map = gameMap.map
        val from = unit.getPos()
        val side = unit.player?.side
        if (map == null || from == null || side == null) return null
        val path = GameRules.getShortestPath(from, Cell(row, col), gameMap.currentMoveRange)
        return if (path.isEmpty()) {
            null
        } else {
            MoveSetup(map, from, map[from.row][from.col], side, path)
        }
    }

    /** A detached copy of [unit] as it stands right now, or null for a player whose moves were
     *  never undoable. Split from [commitUndoState] so the snapshot can be taken before the move
     *  changes anything and kept only once the move is certain to happen. */
    private fun undoSnapshot(unit: GameUnit): GameUnit? =
        if (unit.player?.type != PlayerType.HUMAN_LOCAL) {
            null
        } else {
            GameUnit(unit.eqid).apply {
                copy(unit)
                setHex(unit.getHex())
            }
        }

    private fun commitUndoState(
        unit: GameUnit,
        snapshot: GameUnit?,
    ) {
        if (snapshot == null) return
        gameMap.undoState.clear()
        gameMap.undoState.unit = unit
        gameMap.undoState.savedUnit = snapshot
    }

    /**
     * Walks the path one hex at a time, accumulating cost, until it ends or something stops it.
     *
     * Every way a move can be interrupted after the first hex -- blocked terrain, AA interception,
     * `Overwatch` opportunity fire, a minefield, an unseen enemy ZOC -- lives in
     * [reactionStoppingMove] (`MoveReactions.kt`) rather than here, so this stays a walk and that
     * stays a list of interruptions.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun traversePath(
        unit: GameUnit,
        setup: MoveSetup,
        result: MovementResults,
    ): Int {
        val enemySide = 1 - setup.side
        var totalCost = 0
        // Ids of `Overwatch` commanders that have already answered THIS move, so each fires once
        // per moving formation rather than once per hex it walks.
        val overwatchSpent = mutableSetOf<Int>()
        for (i in setup.path.indices) {
            val cell = setup.path[i]
            if (isCellVisible(unit, setup.map, cell, enemySide)) {
                result.isVisible = true
                if (cell is ExtendedCell) cell.isVisible = true
            }
            result.passedCells.add(cell)
            totalCost += if (cell is ExtendedCell) cell.cost else 1
            if (i > 0 && reactionStoppingMove(unit, setup, cell, i, overwatchSpent, result)) break
        }
        return totalCost
    }

    /** DEFERRED.md §1.12: whether `AI_SCRIPTED` bypassing fog here (animating in full through
     *  hexes the human side has not spotted) is deliberate was previously undecided. **Decided:
     *  deliberate.** `AI_SCRIPTED` exists only for the Khalkhin Gol tutorial's scripted
     *  demonstration turns (`docs/tutorial.md`), and every other place that branches on player
     *  type treats it the same as `HUMAN_LOCAL` for exactly this reason -- `UICombatLog` logs its
     *  combats, `StatusBarController` narrates its turn -- because a guided demonstration that
     *  vanishes into "fogged, snap to destination" defeats the point of demonstrating it. Do not
     *  gate this on spotting; the off-screen gap this same entry also named is fixed instead, in
     *  `AnimationOrchestrator.isPathEntirelyOffScreen`. */
    private fun isCellVisible(
        unit: GameUnit,
        map: Array<Array<Hex>>,
        cell: Cell,
        enemySide: Int,
    ): Boolean {
        val type = unit.player?.type
        return type == PlayerType.HUMAN_LOCAL ||
            type == PlayerType.AI_SCRIPTED ||
            map[cell.row][cell.col].isSpotted(enemySide)
    }

    private fun applyMove(
        unit: GameUnit,
        setup: MoveSetup,
        row: Int,
        col: Int,
        last: Cell,
        totalCostIn: Int,
        result: MovementResults,
    ) {
        val from = setup.from
        val toHex = setup.map[last.row][last.col]
        if (last.row == row && last.col == col && GameRules.canCapture(unit)) {
            val capture = gameMap.captureHex(toHex, unit)
            result.isCapture = capture.isCapture
            result.capturePrestige = capture.prestigeGain as? Int ?: 0
            if (capture.isWin) result.isVictorySide = setup.side
        }

        val totalCost = if (totalCostIn < 0) 0 else totalCostIn
        unit.move(totalCost)
        // OG 9.9: entering a minefield "consumes all remaining movement", detected or not. The
        // overlay's ZOC sentinel already stops a route continuing through a KNOWN field, but it does
        // not zero the allowance -- and an undetected field has no overlay cost at all, so without
        // this a unit could stroll on after being mined.
        if (result.hitMinefield) {
            unit.moveLeft = 0
            unit.hasMoved = true
        }
        GameRules.setZOCRange(gameMap, unit, false)
        GameRules.setSpotRange(gameMap, unit, false)
        setup.fromHex.delUnit(unit)
        toHex.setUnit(unit)
        unit.facing = GameRules.getDirection(from.row, from.col, last.row, last.col) ?: unit.facing
        GameRules.setZOCRange(gameMap, unit, true)
        val newlySpotted = GameRules.setSpotRange(gameMap, unit, true)
        // A `Cut LOS` unit that moved changed what everybody else can see, and the reference counts
        // cannot express that on their own (`GameMap.rebuildSpottingForSightBlocker`).
        gameMap.rebuildSpottingForSightBlocker(unit)
        // §7.43 reconnaissance evidence. Safe against undo without any bookkeeping: `undoFinality`
        // below already refuses to offer an undo for a move that revealed something, so a credited
        // contact can never be rewound out from under the evidence it granted.
        HeroCampaign.recordReconnaissance(unit, newlySpotted)
        // OG 9.10: "a hex where if a unit ends there its move, something happens". Fired here, in
        // the model, rather than in the animation layer, so an AI arrival and a replayed
        // multiplayer order set off the same triggers a local player does.
        //
        // AFTER the arrival's spotting and ZOC are in place: `Extra spot` reveals from where the
        // unit now stands, and a reveal computed before `setSpotRange` would be immediately
        // overwritten by it.
        if (applyArrivalEffects(gameMap, unit, toHex, result)) return
        dismountAfterMove(unit)
        gameMap.setMoveRange(unit)
        gameMap.setAttackRange(unit)
        if (unit.player?.type != PlayerType.HUMAN_LOCAL) {
            // No record was ever saved for a non-local player, and its units never show the action
            // strip -- there is nothing to explain.
            gameMap.undoState.unit = null
            return
        }
        val finality =
            undoFinality(
                newlySpotted,
                unit,
                result.wasIntercepted,
                result.stoppedByUnseenEnemy,
                result.wasFiredOnWhileMoving,
                result.hitMinefield,
                result.firedTrigger,
            )
        if (finality == null) {
            gameMap.undoState.unit = unit
        } else {
            gameMap.undoState.invalidate(unit, finality)
        }
    }

    /**
     * OG's `Dismount after movement` (`attr2` bit 1): *"unit dismounts from its transport after
     * completing movement"* (manual §7.2).
     *
     * **Automatic, not an action, and free.** OG 8.3's base rule is that everyone else *"can only
     * mount or dismount before moving"* and a formation that rode stays aboard until its next turn;
     * this ability is the exception, and it fires by itself the moment the ride ends. It costs no
     * movement point and needs none left — a truck that spent its last point still puts its
     * passengers down, because completing the movement is the whole trigger.
     *
     * Runs after the arrival's spotting is in place, so `unmountUnitHandler`'s own remove/add pair
     * swaps the transport's spotting range for the passengers' with both counts balanced.
     *
     * A first pass on 2026-08-26 read this as a permission that spent the remaining movement. That
     * was a mechanic invented beyond the rules, and the manual says otherwise — corrected the same
     * day (§Q).
     */
    private fun dismountAfterMove(unit: GameUnit) {
        if (!unit.isMounted || unit.destroyed) return
        if (!UnitCapabilities.dismountsAfterMove(unit.unitData(true))) return
        gameMap.unmountUnitHandler(unit)
    }

    /** Null when the move stays undoable, otherwise the single reason it became final. Order is
     *  the reporting order too: the most immediate cause first. */
    private fun undoFinality(
        newlySpotted: Int,
        unit: GameUnit,
        wasIntercepted: Boolean,
        stoppedByUnseenEnemy: Boolean,
        wasFiredOnWhileMoving: Boolean,
        hitMinefield: Boolean,
        firedTrigger: Boolean,
    ): UndoInvalidation? =
        when {
            wasIntercepted -> UndoInvalidation.INTERCEPTED
            // A trigger hex paid out. Undo would hand back the move while the player keeps the
            // prestige, experience, leader or free formation -- and `Hex.triggerFired` means
            // stepping on it again yields nothing, so the reward could not even be re-earned
            // honestly. Final for the same reason interception is.
            firedTrigger -> UndoInvalidation.IRREVERSIBLE_ACTION
            // Overwatch fire is combat that followed the move, and it must be as final as
            // interception is -- see MovementResults.wasFiredOnWhileMoving.
            wasFiredOnWhileMoving -> UndoInvalidation.COMBAT
            // Walking into a minefield revealed it. Undo would hand that intelligence back for free.
            hitMinefield -> UndoInvalidation.NEW_INTELLIGENCE
            unit.isSurprised -> UndoInvalidation.SURPRISED
            newlySpotted != 0 -> UndoInvalidation.NEW_INTELLIGENCE
            // Same reasoning as an intercepted move: rewinding a move that a hidden enemy stopped
            // would make probing for hidden units free (DEFERRED.md §7.32 item 4). In practice a
            // stop usually reveals the enemy anyway, which `newlySpotted` already catches -- this
            // covers the case where it stopped without spotting it.
            stoppedByUnseenEnemy -> UndoInvalidation.STOPPED_BY_HIDDEN_ENEMY
            else -> null
        }

    fun undoLastMove() {
        val ctx = resolveUndoContext() ?: return
        val unit = ctx.unit
        val fromHex = ctx.fromHex
        // Both reference counts come off BEFORE the state is restored, because they were added with
        // the state the unit has NOW. A move that auto-mounted added the transport's spotting range
        // and would otherwise have the passengers' removed -- the add/remove asymmetry that strands
        // fog permanently, and the same one §L.12 fixed on in-place upgrades.
        GameRules.setZOCRange(gameMap, unit, false)
        GameRules.setSpotRange(gameMap, unit, false)
        unit.copy(ctx.saved)
        // copy() detaches unit.player onto a throwaway Player; re-point it at the shared
        // instance so refunds below land on the real player, not a copy of a copy.
        val player = gameMap.getPlayer(unit.player?.id ?: 0)
        unit.player = player
        fromHex.delUnit(unit)
        ctx.savedHex.setUnit(unit)
        GameRules.setZOCRange(gameMap, unit, true)
        GameRules.setSpotRange(gameMap, unit, true)
        gameMap.rebuildSpottingForSightBlocker(unit)
        gameMap.selectUnit(unit)
        gameMap.undoState.oldOwner?.let {
            fromHex.owner = it
            // Undoing a capture takes the hex (and any deploy zone it opened) back too.
            gameMap.invalidateDeployZones()
        }
        gameMap.undoState.oldFlag?.let { fromHex.flag = it }
        gameMap.undoState.oldVictorySide?.let { vs ->
            gameMap.updateVictorySides(1 - player.side, fromHex.getPos())
            fromHex.victorySide = vs
        }
        gameMap.undoState.prestigeGain?.let { player.prestige -= it }
        gameMap.undoState.scoreGain?.let { player.updateScore(-it) }
        gameMap.undoState.clear()
    }

    // Internal (not private): MoveReactions.kt's per-cell reaction extensions (moved out to keep
    // this class under detekt's TooManyFunctions budget) take one from another file.
    internal class MoveSetup(
        val map: Array<Array<Hex>>,
        val from: Cell,
        val fromHex: Hex,
        val side: Int,
        val path: List<Cell>,
    )

    // Internal (not private): MoveExecutorHelpers.kt's resolveUndoHexes extension function
    // (moved out to keep this class under detekt's TooManyFunctions budget) constructs this
    // from another file.
    internal class UndoContext(
        val unit: GameUnit,
        val saved: GameUnit,
        val fromHex: Hex,
        val savedHex: Hex,
    )
}

/**
 * What the destination hex does to a formation that has just stopped on it.
 *
 * Returns true when the unit LEFT THE MAP, in which case the caller must stop: a withdrawn
 * formation has no spotting to restore, no dismount to run and no undo record to write.
 *
 * Order matters. OG manual 3.7.4's escape hex is checked before 9.10's trigger because a unit that
 * has left cannot also collect a reward on the hex it left from.
 *
 * At FILE level rather than as a method because [MoveExecutor] is already at its function budget.
 */
private fun applyArrivalEffects(
    gameMap: GameMap,
    unit: GameUnit,
    toHex: Hex,
    result: MovementResults,
): Boolean {
    val withdrew =
        GameHolder.instance?.scenario?.let { ExtendedVictory.withdraw(it, unit, toHex) } == true
    result.withdrew = withdrew
    if (withdrew) return true
    TriggerHexes.fire(gameMap, unit, toHex)?.let { result.triggerMessage = it }
    result.firedTrigger = toHex.triggerFired && toHex.trigger != 0
    return false
}
