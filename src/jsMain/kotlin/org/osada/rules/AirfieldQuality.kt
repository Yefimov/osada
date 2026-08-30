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
 *  - **Authored dirt: BUILT 2026-08-29**, once the flag stopped being missing. `.xscn` grid byte
 *    `@19` bit 6, found by the controlled OpenSuite diff `og-fidelity-plan.md` §Y.1 had been
 *    waiting on — 29 hexes across 15 shipped scenarios, [Hex.dirt]. The refusal to approximate it
 *    was the right call and cost nothing: when the real flag arrived, the rule gained one clause
 *    rather than having to unpick a guess.
 *
 * ### The two halves have DIFFERENT gates, and collapsing them was a real bug
 *
 * A sapper strip cannot exist with `build_and_repair` off, because nothing else sets
 * [Hex.sapperBuilt] — so gating the whole ability on `Engineering.enabled()` was correct while
 * that was the only half. **An authored dirt field is on the map whatever the ruleset says**, so
 * that gate now has to sit on the sapper clause alone; leaving it where it was would let a jet
 * refuel on an authored dirt strip in every default game.
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
 * The badge follows the same correction: `equipment.ability.no_dirt_airfields` was hidden behind
 * `AbilityGates::engineering` on the old argument and is now ungated, because with 29 authored dirt
 * hexes in shipped content the ability refuses something in a default game.
 */
internal object AirfieldQuality {
    /** Whether [data] carries OG's `Cannot use dirt airfields`. */
    fun refusesDirtAirfields(data: EquipmentData): Boolean = data.attr2 and ATTR2_MASK_NO_DIRT_AIRFIELDS != 0

    /**
     * Whether [hex] is a dirt field at all — authored by the scenario, or scraped by sappers under
     * a ruleset that lets them scrape.
     */
    fun isDirt(hex: Hex): Boolean = hex.dirt || (hex.sapperBuilt && Engineering.enabled())

    /**
     * Whether [hex] is an airfield that [data]'s aircraft may not use.
     *
     * A carrier is not an airfield and is not asked about here; `Carrier Deploy` is the ability
     * that governs those.
     */
    fun unusableBy(
        hex: Hex?,
        data: EquipmentData,
    ): Boolean = hex != null && isDirt(hex) && refusesDirtAirfields(data)
}
