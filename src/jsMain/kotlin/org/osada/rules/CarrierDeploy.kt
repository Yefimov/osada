package org.osada.rules

import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.ATTR_MASK_CARRIER_DEPLOY
import org.osada.model.EfileConfig
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.isWorking
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * OG's **`Carrier Deploy`** (`attr` bit 19) — *"permits deployment on carriers and dirt
 * airfields"*.
 *
 * **It is a DEPLOYMENT permission, not a hangar**, and that is why it took so long to place.
 * `docs/og-fidelity-plan.md` §Y filed this ability as blocked on *"aircraft stored INSIDE the ship
 * ... a hangar list, a launch order, spotting suppressed while contained"* — a containment model
 * OSADA does not have. The author's specials page says something much narrower: the aircraft may be
 * **placed on a carrier during deployment**, which it otherwise may not be, because a ship at sea
 * is never in a deploy zone. Read at its word, per `docs/og-sources.md`'s standing rule that a
 * stated condition is not refused for reading oddly.
 *
 * The hangar is a real and separate mechanic and is still unbuilt (§AA.5). This ability does not
 * need it: OSADA already stacks one aircraft over a ship in [Hex.airunit], which is the state a
 * carrier-based aircraft has been in since the port began — `MovementRules.hasAirfield` has always
 * resupplied it there.
 *
 * ### What is checked
 *
 * - the aircraft carries the bit, read on its REAL record — the permission belongs to the airframe;
 * - the hex holds a **friendly carrier** with a hangar, so `EquipmentData.hangarCap` finally gates
 *   something: a ship whose record gives it no hangar cannot receive aircraft. The 56 carrier
 *   records with no capacity are `eqp-olgcw`/`eqp-olgww2`, the two efiles §J.2 showed to manufacture
 *   their ability bytes, and neither ships a scenario that deploys onto a carrier;
 * - `air2container_deploy`, when the efile sets it: *"restrict the deployment when container is
 *   located at a port airfield"* — the carrier must be in port. Only `eqp-basekorp` sets it.
 *
 * **The dirt-airfield half is not built**, because the per-hex dirt flag is still unlocated and its
 * correlation search has been run (`SCENARIO_FORMAT_NOTES.md`, `@13` bit 0 ruled out). When the
 * flag is found this ability gains a second permitted hex type, not a second mechanic.
 */
object CarrierDeploy {
    /** Whether [unit]'s own record carries the permission. */
    fun isCarrierCapable(unit: GameUnit): Boolean = unit.unitData(true).attr and ATTR_MASK_CARRIER_DEPLOY != 0

    /**
     * Whether [unit] may be deployed onto ([row], [col]) BECAUSE of a carrier there, ignoring the
     * ordinary deploy zone.
     *
     * Additive: a caller ORs this with its existing zone test, so no hex a player could already
     * deploy onto is taken away, and the answer is false for every record that lacks the bit —
     * which is all but 322 of the 56,970 shipped ones.
     */
    fun permits(
        map: GameMap,
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Boolean {
        val allowed = ActiveRuleset.flag(RuleKey.CARRIER_DEPLOY, false)
        val hex =
            if (allowed && UnitPredicates.isAir(unit) && isCarrierCapable(unit)) {
                map.map?.getOrNull(row)?.getOrNull(col)
            } else {
                null
            }
        return hex != null && hex.airunit == null && receivingCarrier(hex, unit) != null
    }

    /** The friendly, hangar-bearing carrier on [hex] that could receive [unit], or null. */
    private fun receivingCarrier(
        hex: Hex,
        unit: GameUnit,
    ): GameUnit? =
        hex.unit?.takeIf { ship ->
            val data = ship.unitData(true)
            !ship.destroyed &&
                ship.player?.side == unit.player?.side &&
                data.uclass == UnitClass.CARRIER.value &&
                data.hangarCap > 0 &&
                (!portRequired() || hex.isWorking(TerrainType.PORT.value))
        }

    /**
     * `air2container_deploy` — *"1 restrict the deployment when container is located at a port
     * airfield for ground/air container"*.
     *
     * Absent is 0, *"allows to enter the carrier without limitation (default)"*, quoted from
     * `EFILE_NOKORP/equip.cfg`. Only `eqp-basekorp` sets it.
     */
    private fun portRequired(): Boolean = EfileConfig.flag("air2container_deploy", false)
}
