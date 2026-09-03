package org.osada.model

/**
 * OG's **Make Core** enrollment forwarders for [GameMap] -- the sweep every loader path calls and
 * its single-unit form for a formation that arrives mid-battle.
 *
 * A file of their own rather than a pair of lines in `GameMapDeployDelegation.kt`, which is at
 * detekt's per-file function budget. `CoreUnitListOperations` carries what they actually do and why
 * enrollment is separate from wearing the marker.
 */
fun GameMap.enrollAuthoredCoreUnits(player: Player) = coreUnitListOperations.enrollAuthoredCoreUnits(player)

internal fun GameMap.enrollIfAuthoredCore(
    player: Player,
    unit: GameUnit,
) = coreUnitListOperations.enrollIfAuthoredCore(player, unit)
