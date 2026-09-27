package org.osada.rules

import org.osada.LeaderType
import org.osada.UnitClass
import org.osada.model.ATTR_MASK_CAN_AIR_ATK
import org.osada.model.Cell
import org.osada.model.EfileConfig
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Who answers an aircraft's attack, and from how far -- OG's Air-Interception and Air Defense
 * procedures, quoted from the author's combat page (`luis-guzman.com/OpenGen_Combat.html`):
 *
 * > *"When Attacker is Air unit, engine checks if any Defenders' adjacent friend (same side) fighter
 * > can fire first to Attacker. ... Fighters can do Air-Interception only once during enemy turn,
 * > except if they have "Skilled Interceptor" leader."*
 *
 * > *"When Attacker is Air unit attacking a Ground unit ... AirDefense (AD) class units ... having
 * > the plane in its range of fire; FlaK class units ... having the plane in adjacent hex, or if
 * > plane is adjacent to Defender, FlaK is also adjacent to Defender and $flak_ex2 is active; Units
 * > having "AD Support" special attribute; Units having "CAn Air Attack" special attribute"*.
 *
 * **Replaced 2026-09-26** a single flat `flak_range` for every kind of air defence, fighters
 * included. That reading came from `equip.cfg`'s comment (*"all flak-type actions are limited to
 * range 1 ... a different air-defense range for flaks"*), which the author's page shows is about the
 * FlaK class: an AD battery answers at its own range of fire, and a fighter covers the unit it
 * stands beside, not a disc around the attacking plane. In LXF (`flak_range=4`) the old rule gave AD
 * and fighters four hexes; in ATOMIC/BASEKORP (`flak_range=1`) it cut 3-6 hex AD batteries to one.
 *
 * [groundReach] is shared with the Air-mode overlay (`ui/ArtilleryRangeOverlay.computeAirDefence`),
 * so what the map hatches is exactly what this rule fires from.
 *
 * Not changed here: movement interception (`AAInterception`) keeps its own `flak_range` ring, and
 * the capital-ships-as-flak option keeps OpenSuite's *"defend from air attacks with a range of 1"*.
 */
internal object AirDefenceSupport {
    // equip.cfg: "Default all flak-type actions are limited to range 1."
    private const val DEFAULT_FLAK_RANGE = 1
    private const val ADJACENT = 1

    /** Whether [support] may fire on the attacking plane standing on [aPos], in defence of
     *  [defender]. Whether it CAN hurt that plane (ammo, air attack, weather) is left to
     *  `AttackEligibility.canInitiateAttack`, which the caller runs on every supporter. */
    fun answers(
        support: GameUnit,
        defender: GameUnit,
        sPos: Cell,
        aPos: Cell,
    ): Boolean {
        val dPos = defender.getPos() ?: return false
        return if (UnitPredicates.isAir(support)) {
            interceptorCovers(support, sPos, dPos)
        } else {
            // Ground air defence answers an attack on a GROUND unit only; an air-to-air attack
            // draws fighters and nothing else.
            !UnitPredicates.isAir(defender) &&
                (
                    HexGeometry.distance(sPos.row, sPos.col, aPos.row, aPos.col) <= groundReach(support) ||
                        flakGuardsNeighbour(support, sPos, aPos, dPos)
                )
        }
    }

    /**
     * How far from [unit] an attacking plane draws its air-defence fire, or 0 when it gives none:
     * an AD battery's own range of fire, a FlaK's `flak_range`, and one hex for every other kind of
     * air defence (`AD Support`, `CAn Air Attack`, capital ships under `opt_capitals_as_flak`).
     * Aircraft give none here -- a fighter's cover is [interceptorCovers], which depends on the
     * target rather than on where the plane is.
     */
    fun groundReach(unit: GameUnit): Int {
        if (UnitPredicates.isAir(unit)) return 0
        val data = unit.unitData()
        val shootsAir = data.airatk > 0
        return when {
            data.uclass == UnitClass.AIR_DEFENCE.value && shootsAir -> AttackEligibility.getUnitAttackRange(unit)
            data.uclass == UnitClass.FLAK.value && shootsAir -> flakRange()
            UnitCapabilities.hasAirDefenceFire(data) -> ADJACENT
            data.attr and ATTR_MASK_CAN_AIR_ATK != 0 && shootsAir -> ADJACENT
            else -> 0
        }
    }

    /** A fighter standing beside the unit under attack -- once a turn unless it carries the
     *  `Skilled Interceptor` leader. `hasSupportedThisTurn` is set by every committed support shot. */
    private fun interceptorCovers(
        fighter: GameUnit,
        sPos: Cell,
        dPos: Cell,
    ): Boolean {
        val data = fighter.unitData()
        val isInterceptor = data.uclass == UnitClass.FIGHTER.value && data.airatk > 0
        val fresh =
            !fighter.hasSupportedThisTurn || Leaders.unitHasLeader(fighter, LeaderType.SKILLED_INTERCEPTOR)
        return isInterceptor && fresh && HexGeometry.distance(sPos.row, sPos.col, dPos.row, dPos.col) <= ADJACENT
    }

    /** `flak_ex2`: a FlaK beside the defender also answers a plane beside the defender, from any
     *  side. Read from the efile's `equip.cfg` (ATOMIC and BASEKORP set it). */
    private fun flakGuardsNeighbour(
        support: GameUnit,
        sPos: Cell,
        aPos: Cell,
        dPos: Cell,
    ): Boolean {
        val data = support.unitData()
        return EfileConfig.intKey("flak_ex2", 0) == 1 &&
            data.uclass == UnitClass.FLAK.value &&
            data.airatk > 0 &&
            HexGeometry.distance(sPos.row, sPos.col, dPos.row, dPos.col) <= ADJACENT &&
            HexGeometry.distance(aPos.row, aPos.col, dPos.row, dPos.col) <= ADJACENT
    }

    private fun flakRange(): Int = ActiveRuleset.intKey(RuleKey.FLAK_RANGE, DEFAULT_FLAK_RANGE)
}
