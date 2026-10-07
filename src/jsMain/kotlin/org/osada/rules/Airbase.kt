package org.osada.rules

import org.osada.GameHolder
import org.osada.model.Cell
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit

/**
 * The **Airbase**: the purchasable ground unit that services aircraft (OSADA decision 2026-10-07).
 *
 * Two different things give aircraft somewhere to land, and their old shared name hid the
 * difference:
 *
 *  - an **airfield hex** -- terrain. Authored by the scenario, or a **field airfield** that sappers
 *    scrape during the battle (`EngineeringWork.AIRFIELD`). Its flag says who holds it; it can be
 *    captured; aircraft deploy onto it. A dirt or sapper-built one refuses aircraft with OG's
 *    `Cannot use dirt airfields` ([AirfieldQuality]).
 *  - an **Airbase** -- a unit: ground crews, workshops and fuel trucks. Through OG's `Air support`
 *    ability it services aircraft on its own hex and the six around it on any terrain, needs no
 *    construction, and cannot be captured, only destroyed. Aircraft do not deploy onto it.
 *
 * **An Airbase services aircraft beside it AND inside its hangar** (owner's decisions 2026-10-07).
 * Beside it, OG's `Air support` (*"can supply air units, the same than an airfield"*): its own hex
 * and the six around it. Inside, every round the aircraft is refuelled, rearmed and repaired with no
 * order given (`rules/CarrierHangars`). Either way repair costs [REPAIR_COST_PERCENT] of the usual
 * prestige. Destroying the Airbase takes its hangar with it and leaves any airfield hex underneath
 * untouched. It needs no airfield underneath -- most authored airbase units stand on open ground
 * (12 of the 18 placed in shipped scenarios).
 *
 * **The role is an explicit list of merged `eqp-united` record ids, not the `Air support` bit.**
 * That bit is carried by more than 600 warships with floatplanes, and by records such as `Spy-Cave`
 * and `Gas Discharger`, none of which is an airbase.
 */
internal object Airbase {
    /** Prestige charged for a repaired strength point at an Airbase, as a percent of the usual price. */
    const val REPAIR_COST_PERCENT = 50

    private const val PERCENT = 100

    /** OG's `JKJ61` hangars, as the nine-facing strip every unit sprite uses. */
    private const val HANGARS_ICON = "resources/units/images/jkj61.png"

    /**
     * Ground air-service units across the shipped efiles: `Aeródromo` (gce), `Airbase` (cc76),
     * `Airfield` and `Airfield 2`-`5` (pzliga, lxf and others), `Airfield [#]` (pacific), `Airport`,
     * `Dirty Airfield`, `Hangar`, `Seaplane Base`, the carrier-class `Airfield` (comww2) and the
     * basekorp `Airfield`.
     */
    private val RECORDS: Set<Int> = setOf(460, 464, 465, 466, 467, 468, 469, 470, 471, 600, 663, 842, 1473, 9575)

    /**
     * Display names for records whose efile calls the UNIT an airfield. Applied when equipment is
     * parsed, so every surface shows the same name; saves and scenarios address units by id, so
     * nothing reads the old name back.
     */
    private val DISPLAY_NAMES: Map<Int, String> =
        mapOf(
            465 to "Airbase",
            466 to "Airbase 2",
            467 to "Airbase 3",
            468 to "Airbase 4",
            469 to "Airbase 5",
            470 to "Airbase [#]",
            600 to "Forward Airbase",
            1473 to "Airbase",
            9575 to "Airbase",
        )

    /**
     * Records whose efile icon is a runway with its tower (`jkv72`, `lxf`/`basekorp`/`olgww2`
     * copies) -- the same picture a completed field airfield now carries. They show OG's hangars
     * (`JKJ61`) instead, so the servicing unit and the runway never look alike. Per record, on
     * purpose: `jkv72.png` itself is left alone for anything else that uses it.
     */
    private val ICONS: Map<Int, String> =
        mapOf(
            465 to HANGARS_ICON,
            600 to HANGARS_ICON,
            9575 to HANGARS_ICON,
        )

    /** Whether [data] is an Airbase unit. */
    fun isAirbase(data: EquipmentData): Boolean = data.eqid in RECORDS

    /** Renames [data] in place when its efile name calls the unit an airfield, and gives it OG's
     *  hangars where its efile icon is a runway ([ICONS]). */
    fun applyDisplayName(
        eqid: Int,
        data: EquipmentData,
    ) {
        DISPLAY_NAMES[eqid]?.let { data.name = it }
        ICONS[eqid]?.let { data.icon = it }
    }

    /**
     * The friendly Airbase servicing aircraft [unit]: the one whose hangar it is inside, or one on its
     * hex or beside it, owned by the same player -- as [MovementRules.hasAirfield] requires of every
     * `Air support` unit -- or null.
     */
    fun servicing(
        unit: GameUnit,
        map: GameMap? = GameHolder.instance?.scenario?.map,
    ): GameUnit? =
        if (!UnitPredicates.isAir(unit)) {
            null
        } else {
            unit.containedIn?.takeIf { standing(it) } ?: alongside(map, unit)
        }

    private fun standing(container: GameUnit): Boolean = !container.destroyed && isAirbase(container.unitData(true))

    private fun alongside(
        map: GameMap?,
        unit: GameUnit,
    ): GameUnit? {
        val grid = map?.map
        val pos = unit.getPos()
        if (grid == null || pos == null) return null
        return (listOf(pos) + HexGeometry.getAdjacent(pos.row, pos.col)).firstNotNullOfOrNull { cell: Cell ->
            grid
                .getOrNull(cell.row)
                ?.getOrNull(cell.col)
                ?.unit
                ?.takeIf { holder -> holder.owner == unit.player?.id && standing(holder) }
        }
    }

    /**
     * [cost] per strength point, discounted when an Airbase services [unit] -- beside it or inside its
     * hangar. Rounded up and never below 1, the same as the `Influence` leader's halving it may stack
     * with.
     */
    fun repairCost(
        unit: GameUnit,
        cost: Int,
        map: GameMap? = GameHolder.instance?.scenario?.map,
    ): Int =
        if (cost > 0 && servicing(unit, map) != null) {
            ((cost * REPAIR_COST_PERCENT + PERCENT - 1) / PERCENT).coerceAtLeast(1)
        } else {
            cost
        }
}
