package org.osada.model

import org.osada.rules.ExtendedLos
import org.osada.rules.isSightBlocker

/**
 * Re-derives the whole fog when a unit that BLOCKS SIGHT changes the map's geometry.
 *
 * OG's `Cut LOS` is read on the line of sight (`rules/ExtendedLos.isSightBlocker`), and a blocker
 * that walks breaks the add/remove symmetry [recomputeSpotting] exists to repair: the spot ranges
 * other units had while it stood there cannot be cancelled once it has left. Call this after any
 * arrival, move, undo or death — it is a no-op unless Extended LOS is on AND [unit] is a blocker,
 * which is every shipped scenario by default.
 *
 * The same escape `rules/Engineering` takes when building or razing changes terrain mid-scenario.
 */
fun GameMap.rebuildSpottingForSightBlocker(unit: GameUnit) {
    if (ExtendedLos.isSightBlocker(unit)) recomputeSpotting()
}
