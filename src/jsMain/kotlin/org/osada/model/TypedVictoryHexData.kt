package org.osada.model

/** Returns this objective's authored victory-level mask for [side]. */
internal fun Hex.victoryTiersForSide(side: Int): Int = if (side == 1) victoryTiersSide1 else victoryTiersSide0

/** Assigns a legacy/imported mask to its normalized OSADA [side]. */
internal fun Hex.setVictoryTiersForSide(
    side: Int,
    mask: Int,
) {
    if (side == 1) victoryTiersSide1 = mask else victoryTiersSide0 = mask
}
