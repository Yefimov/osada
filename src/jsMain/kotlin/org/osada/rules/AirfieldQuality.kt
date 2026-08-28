package org.osada.rules

import org.osada.model.ATTR2_MASK_NO_DIRT_AIRFIELDS
import org.osada.model.EquipmentData
import org.osada.model.Hex

/**
 * Open General's **`Cannot use dirt airfields`** (`Special4` bit 2, `attr2` bit 2) — wired
 * 2026-08-27 (`docs/og-fidelity-plan.md` §U).
 *
 * > *"Cannot use dirt airfields: unit can't refuel nor deploy in airfields defined as dirt or built
 * > by sappers during the scenario."* — manual §7.2
 *
 * A jet or a heavy bomber needs a real runway. 102 `eqp-lxf` records carry it and the population is
 * exactly what the name predicts — `Me 262A-1a`, `P-80A`, `He 162A-2`, `B-17E`, `Liberator`
 * (`OG_ABILITY_AUDIT.md` §7.1.1).
 *
 * ### Half of OG's sentence is built and half is not, and the difference is data
 *
 * OG names two kinds of unusable field: **dirt** (a property of the map) and **built by sappers
 * during the scenario** (a property of the game). Only the second is representable here.
 *
 *  - **Sapper-built: BUILT.** `Engineering` now stamps [Hex.sapperBuilt] when it raises an
 *    airfield, because nothing else recorded the origin — the construction fields are cleared as
 *    the work completes, and `terrain == AIRFIELD` cannot tell a scraped strip from a permanent
 *    one.
 *  - **Authored dirt: NOT BUILT, and not imported.** No per-hex "this field is dirt" marking has
 *    been located in OG's `.xscn` or `.map` binaries, so a map's own dirt strips are
 *    indistinguishable from its concrete ones in this project's data. Recorded as an open question
 *    rather than approximated: guessing which airfields are dirt would ground aircraft on fields OG
 *    lets them use, which is the failure direction that costs the player something.
 *
 * ### Refuelling, and why "nor deploy" is not a second call site here
 *
 * [MovementRules.hasAirfield] is the one predicate that answers *"is this aircraft properly
 * based?"*, and three rules read it: automatic and manual resupply (`SupplyRules`), the Resupply
 * action's own explanation (`UnitActionAvailability`), and OG's out-of-fuel sweep
 * (`AirOperations.strandedAircraft`). Putting the ability there is what keeps those three from
 * disagreeing — an aircraft is never destroyed on a hex that would have refuelled it, which is the
 * guarantee `AirOperations` states about itself.
 *
 * OSADA's deployment zone is built from supply hexes and ownership rather than from a per-class
 * airfield test (`model/GameMapDeployZone`), so there is no distinct deploy check for this to
 * refuse. The refuel half is where the sentence bites in this engine.
 *
 * ### Gated on `build_and_repair`, because without it there are no sapper strips
 *
 * Nothing but `Engineering` can set [Hex.sapperBuilt], and `Engineering` does nothing at all with
 * the key off — so with the key off this ability has nothing to refuse and its badge would
 * advertise a restriction the player can never meet. That is `Minefields`' own gate argument
 * (§K.4), applied to the one ability whose subject the other rule creates.
 */
internal object AirfieldQuality {
    /** Whether [data] carries OG's `Cannot use dirt airfields`. */
    fun refusesDirtAirfields(data: EquipmentData): Boolean =
        Engineering.enabled() && data.attr2 and ATTR2_MASK_NO_DIRT_AIRFIELDS != 0

    /**
     * Whether [hex] is an airfield that [data]'s aircraft may not use.
     *
     * A carrier is not an airfield and is not asked about here; `Carrier Deploy` is the ability
     * that governs those, and it is still descriptive-only.
     */
    fun unusableBy(
        hex: Hex?,
        data: EquipmentData,
    ): Boolean = hex != null && hex.sapperBuilt && refusesDirtAirfields(data)
}
