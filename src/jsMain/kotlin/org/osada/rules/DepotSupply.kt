package org.osada.rules

import org.osada.model.ATTR_EX_MASK_SUPPLY_UNIT
import org.osada.model.EfileConfig
import org.osada.model.GameMap
import org.osada.model.GameUnit

/**
 * OG's **Depot supply** — `supply_ex`, and the `Supply Unit` equipment special that marks a mobile
 * one.
 *
 * `OPENTXT_SAMPLE/equip.cfg` documents the whole mechanic, and it is the richer of the two
 * commented copies (`docs/og/og-sources.md` §3):
 *
 * > *"Change supply rules. Air units are not affected by this config var. 0 = disabled (default
 * > supply rules). Any other value restricts units to resupply only from Depots and/or
 * > Cities/Ports. Options are made summing up these values:*
 * > *1 = Units can resupply if adjacent to Depots (supply units), except if unit is also a Depot.*
 * > *2 = Units can resupply if in/adjacent to cities or ports.*
 * > *4 = Depots must spend 1 ammo/turn to resupply other units. So if no ammo, cannot resupply
 * >   units in that turn.*
 * > *8 = Depots can resupply from other Depots, as far as they have not supplied other units, nor
 * >   moved nor fired.*
 * > *Adding 4 or 8 implies Depots are enabled (no need to add 1 as well).*
 * > *Naval units can always supply in Ports, and optionally also adjacent to Depots."*
 *
 * and NOKORP's copy supplies the sentence that says what a Depot RELAXES:
 *
 * > *"Normal supply restrictions are applied when `$supply_ex = 2`: Units cannot resupply if moved
 * > or fired, amount of fuel/ammo resupplied is affected by terrain supply-factor percentage and/or
 * > enemies on ZOC"*
 *
 * — named for mode 2 alone, so a formation supplied by a **Depot** is subject to none of the three.
 * That matches the changelog's own Depot bug fixes (`tools/og-import/OG_ABILITY_AUDIT.md` §7.5): *"units failing to
 * resupply from an adjacent Depot after moving or firing"* was a BUG, so moving and firing must not
 * disqualify.
 *
 * ### When it applies
 *
 * **Always, since ruleset schema 22 (owner, 2026-10-08).** OG's normal supply includes Depots, so
 * a formation beside a friendly Depot gets its full refill whatever the profile. No shipped efile
 * sets `supply_ex` and 0 of 56,970 shipped records carry `Supply Unit`, so in shipped content this
 * means exactly the eight authored KAISER depots (mobile `Zárt Kocsi` wagons) and nothing else;
 * the restricted modes ([enabled]) still need an efile that asks for them.
 *
 * ### Both of OG's two ways to BE a Depot are now read
 *
 * OG has TWO: the **scenario designation** on a placed Ground Transport / Fortification / Naval
 * Transport, and the later (May 2024) **`Supply Unit` equipment special**. This rule was built
 * against the second alone, because the first was a byte nobody had located.
 *
 * **The byte was located 2026-08-29** — `.xscn` unit record `@50` bit 1, by the controlled
 * OpenSuite diff the Depot had been waiting on since the beginning — so [isDepot] now
 * reads both, and the prediction that entry made held exactly: *"this is the object that gains a
 * second source, not a second mechanic"*. Nothing below this line changed.
 *
 * Eight deployed scenarios designate a Depot this way; `Supply Unit` is carried by no shipped record.
 */
object DepotSupply {
    /** `1` — units may resupply from an adjacent Depot. */
    private const val MODE_DEPOTS = 1

    /** `2` — units may resupply in or adjacent to a city or port. */
    private const val MODE_CITIES = 2

    /** `4` — a Depot spends one ammo per TURN (not per recipient) to supply anybody at all. */
    private const val MODE_DEPOT_AMMO = 4

    /** `8` — a Depot may itself be supplied by another Depot. */
    private const val MODE_DEPOT_FROM_DEPOT = 8

    /** OG's cost under [MODE_DEPOT_AMMO]: one ammo for the whole turn's work. */
    const val DEPOT_AMMO_COST = 1

    /** The efile's `supply_ex`; absent is 0, OG's normal supply. No ruleset switch since schema 22
     *  (owner, 2026-10-08): Depots are part of normal supply and always work. */
    private fun mode(): Int = EfileConfig.intKey("supply_ex", 0)

    /**
     * Whether standard supply is REPLACED by the restricted sources: bits 1 and 2 *"Disable
     * standard supply rules"* on the author's current Features page. Modes 4 and 8 alone keep the
     * standard rules (*"4 standard rules plus Depots spending 1 ammo"*). Until 2026-10-08 any
     * nonzero mode restricted supply.
     */
    fun enabled(): Boolean = mode() and (MODE_DEPOTS or MODE_CITIES) != 0

    /**
     * Whether Depots participate.
     *
     * **Absent or zero `supply_ex` is OG's normal supply, and normal supply includes Depots** --
     * the Features page lists *"Land and naval units can resupply at the end of the turn if
     * adjacent to a Depot"* among the normal rules (`tools/og-import/OG_ABILITY_AUDIT.md` §7.6).
     * Before 2026-10-08 a zero mode disabled them, which left all eight authored KAISER depots
     * inert even with the old `depot_supply` key on.
     *
     * The one way to lose them is bit 2 without bit 1: *"When $supply_ex is set to 2 ... Depots
     * cannot supply adjacent units"*, and *"6 (2+4) would be taken as 2 by engine, because Depots
     * would be disabled"*. 10 and 14 follow the same rule. The page's "12 (1+2+4+8)" row is a typo
     * (that sum is 15); the bits are read independently and no 12-specific meaning is invented.
     */
    private fun depotsEnabled(): Boolean {
        val m = mode()
        return m and MODE_DEPOTS != 0 || m and MODE_CITIES == 0
    }

    /** Whether cities and ports participate (`2`). */
    fun citiesSupply(): Boolean = mode() and MODE_CITIES != 0

    /** Whether a Depot must spend ammunition this turn to supply anyone (`4`). Meaningless, and
     *  so false, when Depots are disabled: *"adding 4 (to require ammo) is meaningless"*. */
    fun depotSpendsAmmo(): Boolean = depotsEnabled() && mode() and MODE_DEPOT_AMMO != 0

    /** Whether a Depot may be supplied by another Depot (`8`). */
    private fun depotMaySupplyDepot(): Boolean = depotsEnabled() && mode() and MODE_DEPOT_FROM_DEPOT != 0

    /**
     * Whether [unit] is a Depot — a mobile one, from `Supply Unit`.
     *
     * Read on the unit's own record **or on its organic transport's**, because OG's own wording for
     * the special is that *an organic transport carrying it makes its main unit behave as a Supply
     * Unit* (`tools/og-import/OG_ABILITY_AUDIT.md` §7.5). A lorry-borne depot is the shape the ability was added for.
     */
    fun isDepot(unit: GameUnit): Boolean {
        // The SCENARIO designation, recovered 2026-08-29 (`GameUnit.isScenarioDepot`): the designer
        // saying "this placed formation is a depot", which is how OG made depots before the 2024
        // special existed. Eight shipped scenarios use it; `Supply Unit` is used by none.
        val own =
            unit.isScenarioDepot ||
                unit.unitData(true).attrEx and ATTR_EX_MASK_SUPPLY_UNIT != 0
        val carried =
            unit.transport
                ?.unitData()
                ?.attrEx
                ?.and(ATTR_EX_MASK_SUPPLY_UNIT) ?: 0
        return !unit.destroyed && (own || carried != 0)
    }

    /**
     * The friendly Depot that would supply [unit] where it stands, or null.
     *
     * *"Units can resupply if adjacent to Depots ... except if unit is also a Depot"* — a Depot is
     * excluded unless the efile adds `8`, and then only from a Depot that has *"not supplied other
     * units, nor moved nor fired"*.
     *
     * **Air units are never served**: *"Air units are not affected by this config var."*
     */
    fun supplierFor(
        map: GameMap,
        unit: GameUnit,
    ): GameUnit? {
        val recipientIsDepot = isDepot(unit)
        val eligible =
            depotsEnabled() &&
                !UnitPredicates.isAir(unit) &&
                (!recipientIsDepot || depotMaySupplyDepot())
        val pos = if (eligible) unit.getPos() else null
        val side = unit.player?.side
        if (pos == null || side == null) return null
        return HexGeometry
            .getAdjacent(pos.row, pos.col)
            .firstNotNullOfOrNull { cell ->
                map.map
                    ?.getOrNull(cell.row)
                    ?.getOrNull(cell.col)
                    ?.unit
                    ?.takeIf { serves(it, side, recipientIsDepot) }
            }
    }

    private fun serves(
        depot: GameUnit,
        side: Int,
        recipientIsDepot: Boolean,
    ): Boolean {
        val isFriendlyDepot = !depot.destroyed && depot.player?.side == side && isDepot(depot)
        // A Depot supplying another Depot must be idle and unspent -- OG names all three
        // conditions, and they apply to the SUPPLIER, not to the recipient.
        val spent = depot.hasMoved || depot.hasFired || depot.hasResupplied || depot.depotPaidThisTurn
        val idleEnough = !recipientIsDepot || !spent
        // "So if no ammo, cannot resupply units in that turn." A depot that already PAID this turn
        // keeps serving at zero: the cost is one ammo for the turn, not a balance it must hold.
        val canPay = !depotSpendsAmmo() || depot.depotPaidThisTurn || depot.getAmmo() >= DEPOT_AMMO_COST
        return isFriendlyDepot && idleEnough && canPay
    }

    /**
     * Whether [unit] may resupply at all under a restricting `supply_ex` (bit 1 or 2).
     *
     * *"Any other value restricts units to resupply only from Depots and/or Cities/Ports."* This is
     * the RESTRICTING half, and it is the reason the whole object is gated on [enabled]: with the
     * key absent, OSADA's own supply rules run untouched.
     *
     * Air units pass straight through — the key does not reach them — and so does a naval unit in a
     * port, which *"can always supply in Ports"*.
     */
    fun permitsSupply(
        map: GameMap,
        unit: GameUnit,
    ): Boolean =
        !enabled() ||
            UnitPredicates.isAir(unit) ||
            supplierFor(map, unit) != null ||
            (citiesSupply() && SupplyFacilities.inOrBesideCityOrPort(map, unit))

    /**
     * Charges the Depot serving [unit] its ammunition, once per turn, under mode `4`.
     *
     * *"Notice it is not 1 ammo per unit, just 1 ammo to resupply any number of units in that
     * turn."* [GameUnit.depotPaidThisTurn] is the once-per-turn latch. It used to be the depot's
     * own `hasResupplied`, which also barred the depot from its own refill, engineering and
     * mine-clearing; the dedicated flag is saved and cleared by `unitEndTurn`.
     */
    fun chargeSupplier(
        map: GameMap,
        unit: GameUnit,
    ) {
        val depot = if (depotSpendsAmmo()) supplierFor(map, unit) else null
        if (depot != null && !depot.depotPaidThisTurn) {
            depot.ammo = (depot.ammo - DEPOT_AMMO_COST).coerceAtLeast(0)
            depot.depotPaidThisTurn = true
        }
    }
}
