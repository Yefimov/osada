package org.osada.rules

import org.osada.CombatLog
import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.InterceptionEvent
import org.osada.model.Leaders
import org.osada.model.fire
import org.osada.model.getUnits
import org.osada.model.hit
import org.osada.rules.AAInterception.fires
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * AA interception of moving aircraft (DEFERRED.md §1.1, `docs/design/aa-interception.md`).
 *
 * `g2a_intercept_mode` is a bitmask, and mode 0 is NOT "off": hidden AD/Flak already intercept a
 * plane moving through or finishing in range at mode 0. Bit 1 (`+1`) stops the interceptor from
 * also air-defending this turn; bit 2 (`+2`) additionally lets SPOTTED AD/Flak intercept a plane
 * that finishes its move in range (spotted AA never intercepts a plane merely flying through).
 * Eligibility mirrors [CombatResolver]'s own support-fire predicate exactly (ammo, class, weather,
 * target-type via [AttackEligibility.canInitiateAttack]) so the rule and the SUP/AA badges can
 * never drift apart (the §4.6 mistake).
 */
internal object AAInterception {
    // equip.cfg's own comment: "Default all flak-type actions are limited to range 1."
    private const val DEFAULT_FLAK_RANGE = 1
    private const val MODE_NO_AIR_DEFENSE_AFTER_INTERCEPT = 1
    private const val MODE_SPOTTED_INTERCEPTS_AT_DESTINATION = 2

    /** Enemy AA units that would fire on [plane] entering [cell]. Hidden AA fires whether the
     *  plane is flying through or finishing here; spotted AA fires only when [isDestination] and
     *  `g2a_intercept_mode` bit 2 is set -- spotted AA never intercepts a plane merely passing by. */
    fun interceptorsFor(
        map: GameMap,
        plane: GameUnit,
        cell: Cell,
        isDestination: Boolean,
    ): List<GameUnit> {
        // OG's own per-scenario switch, authored by 346 of the 397 scenarios that carry one and
        // read by nothing until 2026-08-28 (`docs/og-fidelity-plan.md` §AD). A scenario whose
        // source could not be read is `null` and stays permitted, as every authored switch is.
        val authorAllows = GameHolder.instance?.scenario?.airIntercept != false
        val planeSide = if (authorAllows) plane.player?.side else null
        if (planeSide == null) return emptyList()
        val mode = ActiveRuleset.intKey(RuleKey.AA_INTERCEPT_MODE, 0)
        val range = ActiveRuleset.intKey(RuleKey.FLAK_RANGE, DEFAULT_FLAK_RANGE)
        return map.getUnits().filter { aa ->
            isEligibleInterceptor(aa, plane, planeSide, cell, range) && fires(aa, planeSide, mode, isDestination)
        }
    }

    /** Hexes covered by SPOTTED enemy AA that [side] can legitimately see -- the only threat
     *  worth drawing. Hidden AA must never leak through this (that is the whole point of the
     *  ambush); deriving it from unfiltered unit positions would destroy the mechanic.
     *
     *  Spotted AA only ever fires when `g2a_intercept_mode` bit 2 is set (see [fires]), and even
     *  then only against a plane finishing its move in range -- never one merely passing through.
     *  Without bit 2 (every efile except LXF), spotted AA can never intercept, so there is no
     *  threat to draw at all.
     *
     *  Returns `(row, col)` pairs rather than [Cell] -- [Cell] has no value `equals`/`hashCode`, so
     *  a caller comparing against its own freshly-built `Cell`s (e.g. a move-range list) would
     *  never find a match by reference. */
    fun visibleThreatHexes(
        map: GameMap,
        side: Int,
        forPlane: GameUnit,
    ): Set<Pair<Int, Int>> {
        val mode = ActiveRuleset.intKey(RuleKey.AA_INTERCEPT_MODE, 0)
        if ((mode and MODE_SPOTTED_INTERCEPTS_AT_DESTINATION) == 0) return emptySet()
        val range = ActiveRuleset.intKey(RuleKey.FLAK_RANGE, DEFAULT_FLAK_RANGE)
        val threatened = mutableSetOf<Pair<Int, Int>>()
        val rows = map.rows
        val cols = map.cols
        map.getUnits().forEach { aa ->
            val aaHex = aa.getHex()
            val aaPos = aa.getPos()
            if (aa.player?.side == side || aaHex == null || aaPos == null) return@forEach
            if (!aaHex.isSpotted(side)) return@forEach
            if (!UnitCapabilities.hasAirDefenceFire(aa.unitData())) return@forEach
            // OG's `No Intercept Air`: disables the INTERCEPTION path specifically for AD/FlaK/
            // Fighter, while leaving ordinary defensive AA fire untouched -- see
            // UnitCapabilities.hasNoInterceptAir's header. Checked here rather than folded into
            // hasAirDefenceFire so the AA badge and any non-interception defensive fire are unaffected.
            if (UnitCapabilities.hasNoInterceptAir(aa)) return@forEach
            if (!AttackEligibility.canInitiateAttack(aa, forPlane, asActiveAttack = false)) return@forEach
            val cells = HexGeometry.getRing(aaPos.row, aaPos.col, range, rows, cols, false)
            cells.add(Cell(aaPos.row, aaPos.col))
            cells.forEach { c -> if (c.row in 0 until rows && c.col in 0 until cols) threatened.add(c.row to c.col) }
        }
        return threatened
    }

    /** Applies one-sided interception damage from [interceptors] to [plane], which must already be
     *  positioned at the cell it was intercepted in (so range/terrain resolve correctly). The AA
     *  is never fired back at -- interception is not an attack the plane can answer, matching how
     *  [CombatResolver]'s support-fire loop runs one attack per supporter. Each firing AA spends
     *  ammo and reveals itself ([org.osada.model.fire] sets `tempSpotted`); mode bit 1 additionally
     *  marks it unable to support-fire again this turn ([GameUnit.hasInterceptedThisTurn]). */
    fun applyInterception(
        map: GameMap,
        plane: GameUnit,
        interceptors: List<GameUnit>,
    ): List<InterceptionEvent> {
        val mode = ActiveRuleset.intKey(RuleKey.AA_INTERCEPT_MODE, 0)
        val units = map.getUnits().toList()
        val turn = map.turn
        val events = mutableListOf<InterceptionEvent>()
        for (aa in interceptors) {
            if (plane.destroyed) break
            val logId = CombatLog.addCombatStart(aa, plane, turn)
            val result = GameRules.calculateAttackResults(aa, plane, true, units, committed = true)
            plane.hit(result.kills)
            aa.fire(false)
            // OG's `Skilled Interceptor` ("can intercept multiple enemy fighters in the defensive
            // phase"): the gun keeps its defensive fire after intercepting, so the one flag that
            // spends it is never set. `hasInterceptedThisTurn` is read in exactly one place
            // (`CombatResolver.isSupportFireEligible`), which is why the exemption belongs here
            // rather than at the read -- the trait was advertised and inert until 2026-08-18
            // (`docs/og-fidelity-plan.md` A.4), and it is the guaranteed class trait of every
            // Fighter commander.
            val keepsDefensiveFire = Leaders.unitHasLeader(aa, LeaderType.SKILLED_INTERCEPTOR)
            if ((mode and MODE_NO_AIR_DEFENSE_AFTER_INTERCEPT) != 0 && !keepsDefensiveFire) {
                aa.hasInterceptedThisTurn = true
            }
            CombatLog.addCombatEnd(aa, plane, logId, true)
            // Reported to the HUD only now that the gun has fired -- see [InterceptionEvent].
            events += InterceptionEvent(aa, plane, result.kills, plane.destroyed)
        }
        return events
    }

    private fun isEligibleInterceptor(
        aa: GameUnit,
        plane: GameUnit,
        planeSide: Int,
        cell: Cell,
        range: Int,
    ): Boolean {
        val aaPos = aa.getPos() ?: return false
        val inRange = HexGeometry.distance(aaPos.row, aaPos.col, cell.row, cell.col) <= range
        // OG's `Jet (Stealth)`: a jet is interceptable from the ground only by an interceptor that
        // is itself jet-capable. It does NOT stop a fighter -- this is the ground-to-air path only,
        // which is the whole of what the author's specials reference claims for it.
        val jetMatched =
            !UnitCapabilities.hasJetStealth(plane.unitData(true)) ||
                UnitCapabilities.hasJetStealth(aa.unitData(true))
        return aa.player?.side != planeSide &&
            UnitCapabilities.hasAirDefenceFire(aa.unitData()) &&
            !UnitCapabilities.hasNoInterceptAir(aa) &&
            jetMatched &&
            inRange &&
            AttackEligibility.canInitiateAttack(aa, plane, asActiveAttack = false)
    }

    private fun fires(
        aa: GameUnit,
        planeSide: Int,
        mode: Int,
        isDestination: Boolean,
    ): Boolean {
        val hidden = aa.getHex()?.isSpotted(planeSide) != true && !aa.tempSpotted
        return hidden || (isDestination && (mode and MODE_SPOTTED_INTERCEPTS_AT_DESTINATION) != 0)
    }
}
