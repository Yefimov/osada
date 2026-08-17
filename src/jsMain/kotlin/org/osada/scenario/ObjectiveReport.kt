package org.osada.scenario

import org.osada.model.Hex
import org.osada.model.getPlayer

/*
 * The objectives rail's progress model
 * (`docs/design/action-affordances-and-objectives.md` §§8, 9).
 *
 * It lives beside [Scenario.checkVictory] / [Scenario.checkTimedOutcome] on purpose: the rail must
 * report the rule the engine is actually going to run, and the fastest way for a checklist to start
 * lying is for it to grow its own copy of the win condition.
 */

/** The three different things the scenario model calls an "objective" (§8). */
enum class ObjectiveKind {
    /** `victorySide != -1 && flag != -1`: required for the capture-all victory. */
    VICTORY,

    /** `flag != -1 && victorySide == -1`: prestige/score only, never required. */
    OPTIONAL_CAPTURE,

    /** `victorySide != -1 && flag == -1`: authored as a true win objective with no visible flag.
     *  Never listed unless Observer Mode is on -- it is authored concealment, not fog. */
    HIDDEN_VICTORY,
}

data class ObjectiveRow(
    val kind: ObjectiveKind,
    val name: String,
    val row: Int,
    val col: Int,
    val held: Boolean,
)

/** Ordered best-first, matching `checkVictory`'s own `victoryTurns` order. */
enum class VictoryTier {
    BRILLIANT,
    VICTORY,
    TACTICAL,
}

/** `capture every victory objective on or before [byTurn]` earns [tier]. From `map.victoryTurns`. */
data class VictoryDeadline(
    val tier: VictoryTier,
    val byTurn: Int,
)

/** `hold at least [count] victory hexes when the turn limit runs out` earns [tier]. Authored, and
 *  present in only a handful of scenarios -- absent means the legacy all-or-defeat rule applies. */
data class HoldThreshold(
    val tier: VictoryTier,
    val count: Int,
)

data class ObjectiveReport(
    val rows: List<ObjectiveRow>,
    val turn: Int,
    val maxTurns: Int,
    val deadlines: List<VictoryDeadline>,
    val holdThresholds: List<HoldThreshold>,
) {
    val victory: List<ObjectiveRow> get() = rows.filter { it.kind == ObjectiveKind.VICTORY }
    val optional: List<ObjectiveRow> get() = rows.filter { it.kind == ObjectiveKind.OPTIONAL_CAPTURE }
    val hidden: List<ObjectiveRow> get() = rows.filter { it.kind == ObjectiveKind.HIDDEN_VICTORY }

    val victoryHeld: Int get() = victory.count { it.held }
    val victoryTotal: Int get() = victory.size

    /** True when the turn limit, not a capture, is what will decide this scenario's grade. */
    val gradedByHoldCount: Boolean get() = holdThresholds.isNotEmpty()

    /** Deadlines the player can still reach. A missed deadline is shown struck through rather than
     *  removed, so the rail explains why the best grade is gone. */
    fun missed(deadline: VictoryDeadline): Boolean = turn > deadline.byTurn
}

/**
 * Builds the rail's model for [side] (the OBSERVING side -- during an AI turn `currentPlayer` is the
 * opponent, which is what used to invert every Held/Enemy label).
 *
 * [revealHidden] must be Observer Mode's own setting and nothing else. In normal play a flag-less
 * victory hex contributes no row, no name, no coordinate and no count.
 */
fun Scenario.objectiveReport(
    side: Int,
    revealHidden: Boolean,
): ObjectiveReport {
    val rows = mutableListOf<ObjectiveRow>()
    for (r in 0 until map.rows) {
        for (c in 0 until map.cols) {
            val hex = map.map?.getOrNull(r)?.getOrNull(c)
            val kind = hex?.let(::kindOf)
            val listed = kind != null && (kind != ObjectiveKind.HIDDEN_VICTORY || revealHidden)
            if (listed) {
                rows +=
                    ObjectiveRow(
                        kind = kind,
                        name = hex.name,
                        row = r,
                        col = c,
                        // hex.owner is a PLAYER id, not a side: a side with a support country owns
                        // hexes under more than one player id.
                        held = hex.owner != -1 && map.getPlayer(hex.owner).side == side,
                    )
            }
        }
    }
    return ObjectiveReport(
        rows = rows,
        turn = map.turn,
        maxTurns = map.maxTurns,
        deadlines =
            VictoryTier.entries.mapNotNull { tier ->
                map.victoryTurns.getOrNull(tier.ordinal)?.let { VictoryDeadline(tier, it) }
            },
        holdThresholds =
            if (victoryHoldCounts.size < VictoryTier.entries.size) {
                emptyList()
            } else {
                VictoryTier.entries.map { tier -> HoldThreshold(tier, victoryHoldCounts[tier.ordinal]) }
            },
    )
}

/**
 * A hex with no owner is invisible to the rail even when it carries a flag: the existing map
 * tooltips use the same `flag != -1 && owner != -1` gate, and the objective panel must not become
 * the one surface that leaks an ownerless authored hex.
 */
private fun kindOf(hex: Hex): ObjectiveKind? =
    when {
        hex.victorySide != -1 && hex.flag != -1 && hex.owner != -1 -> ObjectiveKind.VICTORY
        hex.victorySide == -1 && hex.flag != -1 && hex.owner != -1 -> ObjectiveKind.OPTIONAL_CAPTURE
        hex.victorySide != -1 && hex.flag == -1 -> ObjectiveKind.HIDDEN_VICTORY
        else -> null
    }
