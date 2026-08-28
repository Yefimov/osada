package org.osada.rules

import org.osada.TerrainType
import org.osada.model.ATTR_EX_MASK_AIR_DROP_MINES
import org.osada.model.ATTR_EX_MASK_CLEAR_MINES
import org.osada.model.ATTR_MASK_DROP_MINES
import org.osada.model.Cell
import org.osada.model.EfileConfig
import org.osada.model.EquipmentData
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
 * ### Where the three abilities come from
 *
 * `Drop mines` is `attr` bit 0 and has been in our shipped data since the importer existed — 478
 * `eqp-lxf` records carry it: engineers, commandos and partisans, destroyers and motor launches,
 * minesweepers, submarines, and the maritime-patrol bombers that historically laid mines. (The bit
 * was mislabelled `Lasting Sup.` until a controlled OpenSuite staircase settled it on 2026-08-18;
 * `OG_ABILITY_AUDIT.md` §7.1.1.)
 *
 * `Clear mines` (`SpecialEx` 60.6, `attrEx` bit 6) and `AirDropMines` (`SpecialEx` 62.1, `attrEx`
 * bit 17) were **imported 2026-08-19** once `attrEx` widened the import past `attr`'s 24 bits
 * (`model/EquipmentCombatEligibility.kt`'s header). Until then [canClearMines] read `Drop mines` as
 * a stand-in, on the strength of the two populations nearly coinciding; that approximation is gone
 * — [canClearMines] now reads its own bit. `AirDropMines` carries the prerequisite OG's own UI
 * enforces (greyed out unless the unit already has `Drop mines`): [canDropMines] honours it by
 * requiring both bits on an air unit rather than either alone.
 */
internal object Minefields {
    /** The most an undetected field can take off a formation. **Quoted, not inferred**: OG's own
     *  minefield rules say entering an undetected field can cost *"up to three strength points,
     *  depending on experience and Engineer status"*. Was a flat inferred 2 until 2026-08-28, when
     *  the author's Features page turned out to name both the ceiling and the two modifiers
     *  (`docs/og-fidelity-plan.md` §Z.3). */
    const val MAX_UNDETECTED_MINE_DAMAGE = 3

    /** The floor. A mine that goes off always costs something -- OG's sentence is "up to three",
     *  not "up to three or nothing", and a strike that cost nothing would make the reveal read as
     *  a bug rather than an ambush. */
    const val MIN_UNDETECTED_MINE_DAMAGE = 1

    /** Bars at which a formation has seen enough minefields to lose one point less. `INFERENCE`:
     *  OG names experience as a modifier and gives no threshold. */
    private const val VETERAN_BARS = 3

    /**
     * Strength points an undetected field takes off [unit], between [MIN_UNDETECTED_MINE_DAMAGE]
     * and [MAX_UNDETECTED_MINE_DAMAGE].
     *
     * OG's ceiling and its two modifiers are quoted; **the arithmetic between them is an
     * `INFERENCE`** and is the open question `docs/og-open-questions.md` Q3.1 asks. One point off
     * for a veteran, one off for a formation that knows how to clear mines, floored at one: a green
     * line unit takes the documented maximum, a veteran engineer takes the minimum.
     *
     * **Deliberately DETERMINISTIC**, which is the constraint the flat 2 was built under and it has
     * not changed: multiplayer is host-authoritative but the same move is previewed on both
     * clients, and a random mine would be the one movement outcome the two could disagree about.
     * Grading by experience and Engineer status is how OG's "up to" becomes a spread without a die.
     *
     * Class-agnostic, so a ship striking a naval mine is charged by the same rule -- which is the
     * whole of the "naval mine damage" gap `docs/og-fidelity-plan.md` §Y.3 recorded.
     */
    fun strikeDamage(unit: GameUnit): Int {
        var damage = MAX_UNDETECTED_MINE_DAMAGE
        if (UnitExperience.bars(unit) >= VETERAN_BARS) damage--
        if (MineAbilities.canClearMines(unit)) damage--
        return damage.coerceAtLeast(MIN_UNDETECTED_MINE_DAMAGE)
    }

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

    /** OG's `Drop mines` (`attr` bit 0). Ground and naval units need only the one bit. An aircraft
     *  needs `AirDropMines` (`attrEx` bit 17) as well — OG's own UI greys that checkbox out unless
     *  the unit already carries `Drop mines`, so an air unit is eligible only with BOTH bits, never
     *  `AirDropMines` alone. */
    fun canDropMines(unit: GameUnit): Boolean {
        val data = unit.unitData(true)
        val laysMines = Minefields.enabled() && data.attr and ATTR_MASK_DROP_MINES != 0
        return laysMines && (!UnitPredicates.isAir(unit) || airMayMineHere(data, unit.getHex()))
    }

    /**
     * Which mines an AIRCRAFT may lay, corrected 2026-08-27.
     *
     * OSADA required `AirDropMines` for any air mine-laying at all. The efile key that governs it
     * says otherwise, in its own comment:
     *
     * > `air_landmines=0`
     * > *"Set to 1 to allow air units having "DropMines" but not "AirDropMines" specials, to drop
     * > mines in land too (not only in sea)"*
     *
     * So `AirDropMines` is the permission for **land** mines from the air. An aircraft carrying only
     * `Drop mines` may already mine the SEA — which is how OG's naval minefields are laid from the
     * air — and gains the land as well only where the efile sets `air_landmines`.
     *
     * A ground or naval unit is unaffected: it needs `Drop mines` and nothing else, wherever it
     * stands.
     */
    private fun airMayMineHere(
        data: EquipmentData,
        hex: Hex?,
    ): Boolean {
        if (data.attrEx and ATTR_EX_MASK_AIR_DROP_MINES != 0) return true
        val overSea = hex?.terrain == TerrainType.OCEAN.value
        return overSea || EfileConfig.flag("air_landmines", false)
    }

    /** OG's `Clear mines` (`SpecialEx` 60.6, `attrEx` bit 6) — its own bit, independent of
     *  `Drop mines`: OG's own data has units that clear without laying and vice versa
     *  (`OG_ABILITY_AUDIT.md` §7.1.1's `Clear mines` cross-check). Ground/naval only, matching
     *  [canDropMines]'s reasoning and the manual's *"the unit must be standing in the mined
     *  hex"* — a handful of aircraft carry the bit in the merged data but the minefield-entry
     *  model (1 movement point, reduced defence while standing in the hex) is not one an
     *  overflying aircraft ever enters. */
    fun canClearMines(unit: GameUnit): Boolean =
        Minefields.enabled() &&
            !UnitPredicates.isAir(unit) &&
            unit.unitData(true).attrEx and ATTR_EX_MASK_CLEAR_MINES != 0

    /** The success chance as a percentage, for the action tooltip. Derived from the same constant
     *  the roll uses, so the number the player is shown is the number that is rolled. */
    fun clearSuccessPercent(): Int = CLEAR_SUCCESS_IN_TEN * PERCENT_PER_ROLL_STEP

    /** True when this clearing attempt succeeds. A failure costs the attempt and suppresses the
     *  engineer, which is OG's stated penalty. */
    fun clearAttemptSucceeds(): Boolean = GameRandomSource.nextInt(FULL_ROLL) < CLEAR_SUCCESS_IN_TEN
}
