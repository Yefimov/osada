package org.osada.rules

import org.osada.RoadType
import org.osada.model.Hex
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Cutting a railway line — **an OSADA rule, not an Open General one**, behind
 * [RuleKey.RAIL_DEMOLITION].
 *
 * OG has nine engineering jobs and none of them touches track, and its barrage wrecks facilities,
 * roads and bridges and nothing else. A rail hex there is authored map data that nothing in play
 * can change, which is why [Hex.rail] was written by the importer and by nothing else until this
 * rule existed. So this is an OSADA invention in the sense `OG_ABILITY_AUDIT.md` §1 means, and it
 * is admitted on two grounds.
 *
 * The first is that OG already gives `Can Blow` its meaning: the ability *"applies to bridges,
 * ports, airfields && cities"* — the works that carry an army — and a charge under a rail line is
 * the same act against the same kind of target. The second is the history the player expects. The
 * rail war of 1943 on the Eastern Front, the SOE and maquis lines before Normandy, Operation
 * Strangle in Italy: cutting track was one of the war's main tasks for both partisans and bombers.
 * A game that fields armoured trains and rail transport but cannot cut a line is missing the
 * counter to both.
 *
 * ### One rule, two ways to use it
 *
 * A sapper cuts a line by hand (`EngineeringWork.BLOW_RAIL`) and a barrage cuts one from a
 * distance (`Barrage`). That is why this is its own switch rather than a grade of either rule: a
 * player who wants partisans tearing up track has no reason to also be shelling unseen hexes, and
 * vice versa. It is also why it has no [org.osada.rules.ruleset.RULE_REQUIRES] entry — that table
 * maps one rule to ONE prerequisite, and this one is live if either of its two paths is.
 *
 * ### The line is cut by clearing the mask, not by a flag beside it
 *
 * Every rule that asks whether a train may be here already reads [Hex.rail]: the rail-only movement
 * cost in `MoveRangeCalculation`, `RailTransport`'s connectivity walk, `ReinforcementDeployment`'s
 * entrainment hexes. So [cut] clears the mask and all of them are cut at once, with nothing new to
 * learn. [Hex.blownRail] keeps what was there, which is what makes [relay] a repair rather than a
 * free railway.
 */
internal object RailDemolition {
    /** Whether the rule is in force. Off in every built-in profile, including — deliberately, for
     *  the reason `Craters` gives about its own switch — Open General Fidelity. */
    fun enabled(): Boolean = ActiveRuleset.flag(RuleKey.RAIL_DEMOLITION, false)

    /**
     * Cuts the line on [hex], returning whether there was one to cut.
     *
     * The station goes with the rails: a depot without track is not a place a train can be met.
     * It does NOT come back with them — [relay] puts rails back, and Build Station is what raises
     * buildings — so shelling a junction costs the defender 18 prestige to restore.
     */
    fun cut(hex: Hex): Boolean {
        if (!enabled() || hex.rail <= RoadType.NONE.value) return false
        hex.blownRail = hex.rail
        hex.rail = RoadType.NONE.value
        hex.station = false
        return true
    }

    /**
     * Relays whatever track was cut here, returning whether there was any.
     *
     * **Deliberately NOT gated on [enabled].** A line cut while the rule was on must stay repairable
     * if the rule is later switched off, or a profile change would leave permanently broken track on
     * a map nothing could ever fix. Switching the rule off stops new lines being cut, which is what
     * the switch is for.
     */
    fun relay(hex: Hex): Boolean {
        if (hex.blownRail == 0) return false
        hex.rail = hex.blownRail
        hex.blownRail = 0
        return true
    }
}
