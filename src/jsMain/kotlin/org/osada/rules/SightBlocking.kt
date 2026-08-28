package org.osada.rules

import org.osada.model.ATTR2_MASK_ALLOW_LOF
import org.osada.model.ATTR2_MASK_CUT_LOS
import org.osada.model.GameUnit

/**
 * Whether [unit] standing in an intervening hex blocks SIGHT through it — OG's `Cut LOS`, read
 * on the line of sight its name actually names.
 *
 * **Built 2026-08-28 (`docs/og-fidelity-plan.md` §Z.4), reversing a narrowing
 * [ExtendedLos]'s own header defended.** The narrowing was never about the evidence: the author's specials page says
 * `Cut LOS` blocks spotting, and for BOTH sides. It was about the fog being a per-side reference
 * count that a *moving* blocker would strand — a spot range added while the blocker stood there
 * cannot be cancelled once it has walked away.
 *
 * That objection had a solution the whole time, and `rules/Engineering` was already using it:
 * **anything that changes the map's sight geometry en masse calls
 * `GameMap.recomputeSpotting()`** and re-derives every counter from scratch. A `Cut LOS` unit
 * arriving, moving, being undone or dying now does the same
 * (`GameMap.rebuildSpottingForSightBlocker`), so the add/remove symmetry is never relied on
 * across a blocker's move.
 *
 * **Blocks for both sides**, which is OG's stated behaviour and not the convenient reading: a
 * unit can mask its own side's observers. `Allow LOF` exempts a record here as it does on the
 * line of fire — `INFERENCE`, since that ability is named for fire, but a record carrying both
 * bits is plainly saying "I do not obstruct", and reading it any other way would let one
 * ability contradict the other on the same unit.
 */
internal fun ExtendedLos.isSightBlocker(unit: GameUnit?): Boolean {
    val attr2 = if (enabled()) unit?.unitData(true)?.attr2 else null
    return attr2 != null && attr2 and ATTR2_MASK_CUT_LOS != 0 && attr2 and ATTR2_MASK_ALLOW_LOF == 0
}
