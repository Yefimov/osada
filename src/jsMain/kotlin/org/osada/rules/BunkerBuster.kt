package org.osada.rules

import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.GameUnit

/**
 * The **Bunker Buster** attachment (slot 9), narrowed to OG's own wording on 2026-10-09 (owner's
 * decision). Both commented `equip.cfg` copies define it identically: *"Ignores effective defense
 * of bunkers and bonus of fortification terrain"*.
 *
 * Until then OSADA modelled it on the equipment `Ignore entrench` bit and bypassed entrenchment
 * against EVERY target. Now it acts only against a **bunker**: a Fortification-class formation, or
 * any formation standing on fortification terrain (authored, or raised by sappers). Against such a
 * target the target's entrenchment does not count and it cannot raise a rugged defence -- the two
 * places a fortification's effective defence comes from in OSADA, which has no separate
 * fortification-terrain bonus of its own to cancel. Against anything else it does nothing.
 */
internal object BunkerBuster {
    /** Whether [attacker]'s Bunker Buster applies to [defender] standing on [terrain]. */
    fun busts(
        attacker: GameUnit,
        defender: GameUnit,
        terrain: Int,
    ): Boolean = Attachments.has(attacker, Attachments.SLOT_BUNKER_BUSTER) && isBunker(defender, terrain)

    fun isBunker(
        defender: GameUnit,
        terrain: Int,
    ): Boolean =
        defender.unitData().uclass == UnitClass.FORTIFICATION.value || terrain == TerrainType.FORTIFICATION.value
}
