package org.osada.rules

import org.osada.model.ATTR_EX_MASK_SUPPLY_UNIT
import org.osada.model.EfileConfig
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * OG's **Depot supply** — `supply_ex`, and the `Supply Unit` equipment special that marks a mobile
 * one.
 *
 * `OPENTXT_SAMPLE/equip.cfg` documents the whole mechanic, and it is the richer of the two
 * commented copies (`docs/og-sources.md` §3):
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
 * That matches the changelog's own Depot bug fixes (`DEFERRED.md` §2.10): *"units failing to
 * resupply from an adjacent Depot after moving or firing"* was a BUG, so moving and firing must not
 * disqualify.
 *
 * ### What is inert, and why the rule exists anyway
 *
 * **No shipped efile sets `supply_ex`, and 0 of 56,970 shipped records carry `Supply Unit`.** Both
 * measurements are in `docs/og-fidelity-plan.md` §AA. The mechanic therefore cannot alter a single
 * shipped scenario — [enabled] is false everywhere today — and it needs no ruleset key, because the
 * efile key and the equipment bit are already its two gates.
 *
 * It is built because it stopped being blocked on evidence. `og-fidelity-plan.md` §Y filed the
 * Depot as a mechanic nobody understood; §Z found it documented in a file this project had already
 * read, in `DEFERRED.md` §2.10, and on the author's own Features page.
 *
 * ### The scenario-designated Depot is still missing, and this is not it
 *
 * OG has TWO ways to make a Depot: the **scenario designation** on a placed Ground Transport /
 * Fortification / Naval Transport, and the later (May 2024) **`Supply Unit` equipment special**.
 * Only the second is expressible from data OSADA imports — the scenario flag's byte offset in the
 * 120-byte `.xscn` unit record is still unlocated, and its correlation search is exhausted
 * (`DEFERRED.md` §2.10). [isDepot] therefore reads the equipment bit alone. When the flag is found,
 * this is the object that gains a second source, not a second mechanic.
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

    /** The efile's `supply_ex`, or 0 when the player's ruleset has the mechanic switched off.
     *  Two gates, and the key is the outer one: it says "run OG's supply modes", the efile says
     *  which. */
    private fun mode(): Int =
        if (!ActiveRuleset.flag(RuleKey.DEPOT_SUPPLY, false)) 0 else EfileConfig.intKey("supply_ex", 0)

    /** Whether the supply rules are changed at all. False for all ten shipped efiles, and false
     *  for every ruleset that leaves [RuleKey.DEPOT_SUPPLY] off. */
    fun enabled(): Boolean = mode() != 0

    /** Whether Depots participate. *"Adding 4 or 8 implies Depots are enabled."* */
    private fun depotsEnabled(): Boolean = mode() and (MODE_DEPOTS or MODE_DEPOT_AMMO or MODE_DEPOT_FROM_DEPOT) != 0

    /** Whether cities and ports participate (`2`). */
    fun citiesSupply(): Boolean = mode() and MODE_CITIES != 0

    /** Whether a Depot must spend ammunition this turn to supply anyone (`4`). */
    fun depotSpendsAmmo(): Boolean = mode() and MODE_DEPOT_AMMO != 0

    /** Whether a Depot may be supplied by another Depot (`8`). */
    private fun depotMaySupplyDepot(): Boolean = mode() and MODE_DEPOT_FROM_DEPOT != 0

    /**
     * Whether [unit] is a Depot — a mobile one, from `Supply Unit`.
     *
     * Read on the unit's own record **or on its organic transport's**, because OG's own wording for
     * the special is that *an organic transport carrying it makes its main unit behave as a Supply
     * Unit* (`DEFERRED.md` §2.10). A lorry-borne depot is the shape the ability was added for.
     */
    fun isDepot(unit: GameUnit): Boolean {
        if (unit.destroyed) return false
        val own = unit.unitData(true).attrEx and ATTR_EX_MASK_SUPPLY_UNIT != 0
        val carried =
            unit.transport
                ?.unitData()
                ?.attrEx
                ?.and(ATTR_EX_MASK_SUPPLY_UNIT) ?: 0
        return own || carried != 0
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
        val spent = depot.hasMoved || depot.hasFired || depot.hasResupplied
        val idleEnough = !recipientIsDepot || !spent
        // "So if no ammo, cannot resupply units in that turn."
        val canPay = !depotSpendsAmmo() || depot.getAmmo() >= DEPOT_AMMO_COST
        return isFriendlyDepot && idleEnough && canPay
    }

    /**
     * Whether [unit] may resupply at all under a non-default `supply_ex`.
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
     * turn."* [GameUnit.hasResupplied] on the DEPOT is the once-per-turn latch — the depot is not
     * itself resupplying, so the flag is free for this, and it is already cleared at the turn
     * boundary by `unitEndTurn`.
     */
    fun chargeSupplier(
        map: GameMap,
        unit: GameUnit,
    ) {
        val depot = if (depotSpendsAmmo()) supplierFor(map, unit) else null
        if (depot != null && !depot.hasResupplied) {
            depot.ammo = (depot.ammo - DEPOT_AMMO_COST).coerceAtLeast(0)
            depot.hasResupplied = true
        }
    }
}
