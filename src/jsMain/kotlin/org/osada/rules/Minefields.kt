package org.osada.rules

import org.osada.model.ATTR_MASK_DROP_MINES
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.getUnits
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Land minefields (OG manual 9.9, `docs/og-fidelity-plan.md` C.1).
 *
 * A minefield is a **characteristic of a hex, not a unit**: the scenario author places some, units
 * with OG's `Drop mines` ability lay more during play, and units able to clear them take them away
 * again. State lives on [Hex.mines] / [Hex.minesDetected]; this object owns every rule that reads or
 * writes it.
 *
 * **Entirely gated on the `minefields` ruleset key, which is OFF by default.** That is a product
 * decision, not caution: undetected mines damage a formation mid-move with no visible cause, which
 * is exactly the failure `DEFERRED.md` §1.1 forbids (*"Movement damage with no visible cause reads
 * as a bug"*). A player who has not asked for the mechanic never meets it, and one who has gets the
 * two halves that make it fair — a **detected** field is drawn, costs the rest of the move and does
 * no damage; only an **undetected** field ambushes, and it reveals itself in the same instant.
 *
 * ### Where the two abilities come from
 *
 * `Drop mines` is `attr` bit 0 and is **already in our shipped data** — 478 `eqp-lxf` records carry
 * it: engineers, commandos and partisans, destroyers and motor launches, minesweepers, submarines,
 * and the maritime-patrol bombers that historically laid mines. (The bit was mislabelled
 * `Lasting Sup.` until a controlled OpenSuite staircase settled it on 2026-08-18;
 * `OG_ABILITY_AUDIT.md` §7.1.1.)
 *
 * `Clear mines` is `SpecialEx` 60.6 and is **not imported at all** — it lives outside the three
 * special bytes the importer packs into `attr`, so no mask can express it
 * (`model/EquipmentCombatEligibility.kt`'s "WHERE `attr` STOPS" note). Rather than invent a mask
 * above bit 23, [canClearMines] reads `Drop mines` as the stand-in and says so out loud: the two
 * populations are very nearly the same set in OG's own data — every engineer and every minesweeper
 * that clears also lays — so the approximation errs by granting clearing to some minelaying bombers
 * and submarines, which is a bounded, visible error rather than a silent one. When the importer is
 * widened (the same change `All weather` needs), this is the one function to correct.
 */
internal object Minefields {
    /** Strength points an undetected field takes off the formation that walks into it. OG says only
     *  *"suffers some damage and ends its movement"* and names no number.
     *
     *  `INFERENCE`, and deliberately DETERMINISTIC: multiplayer is host-authoritative but the same
     *  move is previewed on both clients, and a random mine would be the one movement outcome the
     *  two could disagree about. Two points is a real bite on a ten-point formation without being
     *  the sort of loss a player would call a bug. */
    const val UNDETECTED_MINE_DAMAGE = 2

    /** Ammunition an OG mine-laying action costs: *"It costs two ammo points."* Quoted, not inferred. */
    const val LAY_MINES_AMMO_COST = 2

    /** Movement points a formation has while it stands in an enemy minefield. OG: *"While in a
     *  minefield a unit has 1 movement point and decreased defense."* Quoted. */
    const val MOVEMENT_IN_MINEFIELD = 1

    /** Defence a formation loses while it stands in an enemy minefield. OG says "decreased" and
     *  names no number; `INFERENCE`, sized to match the rain/snow defence bonus it works against so
     *  a minefield is worth about as much as the weather. */
    const val MINEFIELD_DEFENSE_PENALTY = 3

    /** Whether the mechanic runs at all. Every other function here returns the "no minefields"
     *  answer when this is false, so no call site needs its own guard. */
    fun enabled(): Boolean = ActiveRuleset.flag(RuleKey.MINEFIELDS, false)

    /** Highest side index the bitmask can hold. 31 would set the sign bit, and no scenario defines
     *  more than three players anyway. */
    private const val MAX_SIDE_BIT = 30

    private fun sideBit(side: Int): Int = if (side in 0..MAX_SIDE_BIT) 1 shl side else 0

    /** Whether [hex] holds a field laid by anyone other than [side] — the only fields that can hurt
     *  it. A side walks through its own minefields freely, which is the point of laying them. */
    fun threatens(
        hex: Hex?,
        side: Int,
    ): Boolean {
        if (!enabled() || hex == null) return false
        return hex.mines and sideBit(side).inv() != 0
    }

    /** Whether [side] has detected the field(s) on [hex]. A side always sees its own. */
    fun isDetectedBy(
        hex: Hex?,
        side: Int,
    ): Boolean {
        if (hex == null) return false
        return hex.minesDetected and sideBit(side) != 0
    }

    /** Whether [hex] holds a field [side] must be warned about before committing a route: one that
     *  threatens it AND that it has already detected. */
    fun isKnownThreat(
        hex: Hex?,
        side: Int,
    ): Boolean = threatens(hex, side) && isDetectedBy(hex, side)

    fun markDetected(
        hex: Hex,
        side: Int,
    ) {
        hex.minesDetected = hex.minesDetected or sideBit(side)
    }

    /** Lays a field for [side], detected by [side] itself from the moment it exists. */
    fun lay(
        hex: Hex,
        side: Int,
    ) {
        hex.mines = hex.mines or sideBit(side)
        markDetected(hex, side)
    }

    /** Removes every field on [hex], whoever laid it — an engineer clears the ground, not one
     *  army's paperwork. Detection is cleared with it so a re-laid field is a new ambush. */
    fun clearAll(hex: Hex) {
        hex.mines = 0
        hex.minesDetected = 0
    }

    /**
     * Reveals to [side] every field it stands on or beside, at the start of that side's turn.
     *
     * Adjacency rather than spot range on purpose: OG's own text for the trait-driven case reads
     * *"unless enemy moves adjacent"*, sappers find mines by being next to them rather than by
     * seeing far, and tying detection to spotting would let one recon aircraft strip every field on
     * the map. Called once per turn from [org.osada.model.GameMap.endTurn].
     */
    fun revealAdjacent(
        map: GameMap,
        side: Int,
    ) {
        if (!enabled()) return
        val grid = map.map ?: return
        map.getUnits().forEach { unit ->
            if (unit.player?.side != side) return@forEach
            val pos = unit.getPos() ?: return@forEach
            if (UnitPredicates.isAir(unit)) return@forEach
            val cells = HexGeometry.getAdjacent(pos.row, pos.col) + Cell(pos.row, pos.col)
            cells.forEach { cell ->
                grid.getOrNull(cell.row)?.getOrNull(cell.col)?.let { hex ->
                    if (hex.mines != 0) markDetected(hex, side)
                }
            }
        }
    }
}

/**
 * Which formations can lay or clear a minefield, and how a clearing attempt resolves.
 *
 * A second object rather than four more functions on [Minefields] so each stays within the project's
 * function-per-object budget, and because the split is a real one: [Minefields] owns HEX state,
 * this owns EQUIPMENT capability and the one die roll in the mechanic.
 */
internal object MineAbilities {
    /** How many clearing attempts in ten succeed. OG: *"The attempt can fail, and a failed attempt
     *  suppresses the unit."* No probability is given; `INFERENCE`. Seven in ten keeps failure
     *  meaningful without making an engineer's turn a coin toss. */
    private const val CLEAR_SUCCESS_IN_TEN = 7

    private const val FULL_ROLL = 10
    private const val PERCENT_PER_ROLL_STEP = 10

    /** OG's `Drop mines` (`attr` bit 0). Ground and naval units only — an aircraft needs
     *  `AirDropMines` on top, which is `SpecialEx` 62.1 and is not imported, so aircraft are
     *  refused here rather than silently granted a second ability they do not have. */
    fun canDropMines(unit: GameUnit): Boolean =
        Minefields.enabled() &&
            !UnitPredicates.isAir(unit) &&
            unit.unitData(true).attr and ATTR_MASK_DROP_MINES != 0

    /** See [Minefields]' header: `Clear mines` is not in our data, so `Drop mines` stands in for it
     *  until the importer is widened. */
    fun canClearMines(unit: GameUnit): Boolean = canDropMines(unit)

    /** The success chance as a percentage, for the action tooltip. Derived from the same constant
     *  the roll uses, so the number the player is shown is the number that is rolled. */
    fun clearSuccessPercent(): Int = CLEAR_SUCCESS_IN_TEN * PERCENT_PER_ROLL_STEP

    /** True when this clearing attempt succeeds. A failure costs the attempt and suppresses the
     *  engineer, which is OG's stated penalty. */
    fun clearAttemptSucceeds(): Boolean = GameRandomSource.nextInt(FULL_ROLL) < CLEAR_SUCCESS_IN_TEN
}
