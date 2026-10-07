package org.osada.rules

import org.osada.model.GameUnit

/**
 * How many turns [this] job takes when [builder] does it: the active efile's duration
 * ([EngineeringWork.turnsFor]) shortened by Fast Builder (slot 10, `docs/design/attachments.md`
 * Tier 3). OG's commented configs: *"Reduces 1 turn building/repairing time"* (NOKORP, bonus 1) and
 * *"Reduces turns needed for building/repairing"* (OPENTXT_SAMPLE) -- so the slot's bonus is the
 * number of turns taken off.
 *
 * **Never below one turn** -- `INFERENCE`: OG does not say whether the attachment can make a
 * construction instant, and an instant job would skip the end-of-turn completion every other job
 * goes through (`Engineering.advanceTurn`). Demolitions, which are already instant (0 turns), stay 0.
 *
 * Every place that states or starts a job's duration reads this, so the chip, the started-message
 * and the countdown cannot disagree.
 */
internal fun EngineeringWork.turnsForBuilder(builder: GameUnit): Int {
    val base = turnsFor(builder.getHex())
    return if (base == 0) 0 else maxOf(1, base - Attachments.bonus(builder, Attachments.SLOT_FAST_BUILDER))
}
