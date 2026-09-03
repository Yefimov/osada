package org.osada.rules

import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.getUnits

/**
 * The scenario author's **AI plan for the battle** — OpenSuite's "Unit settings" panel, imported
 * 2026-09-01 from `.xscn` unit records `@45`, `@50`, `@56`, `@58`, `@59`, `@62`, `@64`.
 *
 * ## The decision this object embodies
 *
 * `docs/og-import-rules-backlog.md` §6 asked one question before any of these fields could be
 * deployed: **does the scenario author or `org/osada/ai/` command the enemy?** The answer taken, and
 * the reason:
 *
 * > The author supplies CONSTRAINTS and OBJECTIVES. The planner keeps command.
 *
 * An authored order narrows what the AI may choose (an anchored formation has no legal move, a held
 * one has none before its turn) or weights what it prefers (an objective hex, a fearless valuation).
 * It never replaces the planner's own scoring. That is the only reading under which the two systems
 * compose: OSADA's AI is a general position evaluator with no notion of a battle plan, so an
 * authored order that tried to drive it directly would have to reimplement everything the evaluator
 * does about terrain, supply, adjacency and risk.
 *
 * ## Three things this is not
 *
 * * **Not a movement prohibition on the player.** Every reader here is inside `org/osada/ai`. A
 *   human commanding a side whose formations carry these fields keeps the Move button; hot-seat and
 *   multiplayer are unaffected.
 * * **Not a combat rule.** An anchored artillery battery still fires, refits and entrenches, and is
 *   still pushed off its hex by a forced retreat. Nothing here reaches `CombatResolver`.
 * * **Not immunity to anything.** [ignoresOwnLosses] is a SCORING input, per the author's own
 *   description of Fearless: the AI discards this formation's expected own casualties when valuing
 *   an attack or a move. The combat resolver is untouched, and the formation dies exactly as fast.
 *
 * ## What is deliberately not here
 *
 * * **AI stance** (`@57`, 29,294 records) is the largest authored field of the nine and is blocked
 *   by an older decision: `docs/og-fidelity-plan.md` §0 rules that stances stay unbuilt until the P3
 *   benchmark exists and must never ship labelled "OG AI". Deploying the byte without that would be
 *   the labelled-but-unmeasured AI that section forbids.
 * * **Avoid auto hold** (`@50` bit 4, 17,537) suppresses OG's automatic hold behaviour. OSADA's
 *   planner has no automatic hold to suppress, so the field would be imported with nothing to
 *   switch off — the "a bit imported is not a rule" failure `og-fidelity-plan.md` §M names.
 */
object AiOrders {
    /**
     * Whether the AI planner may move [unit] on [turn].
     *
     * Both clauses are the author's: *"unit is fixed in place"* and *"unit does not move before turn
     * N"*. A held formation becomes movable ON the authored turn, not after it — `@56` is the turn
     * the hold expires, and the corpus histogram of small turn numbers reads as a deadline.
     */
    fun mayMove(
        unit: GameUnit,
        turn: Int,
    ): Boolean = !unit.aiAnchored && turn >= unit.aiHoldUntilTurn

    /**
     * OG's **Fearless**: the AI discards this formation's expected own casualties when valuing an
     * attack.
     *
     * Read by `ai/AIAttackEvaluation` in exactly two places — the loss term of the score, and the
     * "too costly" veto — because those are the two places the valuation consults its own expected
     * losses. Nothing else changes: the attack still happens, the losses still happen.
     */
    fun ignoresOwnLosses(unit: GameUnit?): Boolean = unit?.aiFearless == true

    /**
     * The hex [unit] is ordered to take, resolved through OG's inheritance, or null when it has
     * none.
     *
     * `@62` names another formation of the same player by its `@46` ordinal and means *"take that
     * one's objective"*; `@50` bit 5 (*"follow unit's position"*) is its companion and points at
     * where that formation currently IS rather than where it was told to go. Resolution is one hop
     * deep on purpose — a chain would have to defend itself against a cycle the format permits and
     * no shipped scenario builds.
     *
     * **Released within [GameUnit.aiFreeObjectiveDistance] hexes.** OG's own wording is *"free OH
     * when closer than N"*: once the formation is that close the order has been carried out and the
     * planner's ordinary scoring takes over, which is what stops a unit standing on its objective
     * from being pinned there while the battle moves on.
     */
    fun objectiveOf(
        map: GameMap,
        unit: GameUnit,
    ): Cell? {
        val target = resolveObjective(map, unit) ?: return null
        val here = unit.getPos()
        val free = unit.aiFreeObjectiveDistance
        val released =
            here != null &&
                free > 0 &&
                GameRules.distance(here.row, here.col, target.row, target.col) < free
        return if (released) null else target
    }

    private fun resolveObjective(
        map: GameMap,
        unit: GameUnit,
    ): Cell? {
        val inherited = unit.aiObjectiveFromOrdinal
        if (inherited > 0) {
            val source =
                map.getUnits().firstOrNull {
                    it.owner == unit.owner && it.aiOrdinal == inherited && it !== unit
                }
            if (source != null) {
                return if (unit.aiFollowsObjectiveUnit) source.getPos() else ownObjective(source)
            }
        }
        return ownObjective(unit)
    }

    private fun ownObjective(unit: GameUnit): Cell? =
        if (unit.aiObjectiveRow >= 0 && unit.aiObjectiveCol >= 0) {
            Cell(unit.aiObjectiveRow, unit.aiObjectiveCol)
        } else {
            null
        }
}
