package org.osada.rules

import org.osada.GameHolder
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Open General's **Air ZOC** scenario option — the one word in manual §6.30 that OSADA never read.
 *
 * > **6.30. Zone of Control.** *"The six hexes around a unit are its zone of control. When any unit
 * > moves into the zone of control of a unit from other player, the unit's movement is finished,
 * > unless the moving unit has the Recon ability. **Air units usually don't have a zone of
 * > control.**"*
 *
 * *Usually* is the whole rule. An aircraft projects no zone of control by default, and this
 * scenario option is what gives it one — a fighter over a road stops the column beneath it exactly
 * as a ground unit beside it would.
 *
 * ### Why this needed a rule at all, when it is one word
 *
 * The option has been imported since §O (`Scenario.airZoc`, authored by **79 of the 457** scenarios
 * whose source is readable) and read by nothing, which `docs/og-fidelity-plan.md` §M called *"the
 * sharper omission of the two"* among the systems named to the player: the switch was in the data,
 * in the save and in the profile's own gap list, and no line of engine code consulted it.
 *
 * It is a one-line rule because §L.4 already did the hard part. `No ZOC` (`attr2` bit 6) turned
 * `MovementRules.setZOCRange`'s blanket "every non-air unit" into a question asked per unit,
 * [UnitCapabilities.projectsZoneOfControl] — so the air exemption became a clause in a predicate
 * instead of a hard-coded branch, and this option is that clause becoming conditional.
 *
 * ### The reference count, and why this cannot make it drift
 *
 * `setZOCRange` adds and removes one reference count per hex, and the fog of ZOC is only correct
 * while every remove cancels an add made under the same answer — the constraint
 * [UnitCapabilities.projectsZoneOfControl] states for `useReal = true`. This rule satisfies it for
 * the same reason terrain satisfies `ExtendedLos`'s spotting counters: **both of its inputs are
 * fixed for the whole scenario.** The resolved ruleset is locked at launch
 * (`docs/design/ruleset-profiles.md` §3) and the scenario's own switch is parsed once at load, so
 * no unit can add a ZOC under one answer and remove it under another.
 *
 * ### An absent switch follows the key, as it does for the other rule-level options
 *
 * `null` reads as permission here, which is `ExtendedLos.enabled`'s and `ExtendedNaval.enabled`'s
 * rule for a RULE-level switch: 105 of the 502 deployed scenarios name a source this project could
 * not read, and reading their silence as a prohibition would drop the whole optional rule for them
 * alone. **It is deliberately not `TrueDLOF`'s rule**, even though this option — like that one —
 * only ever ADDS an obstruction. §T drew that line between a rule's own gate and the sub-options
 * that tune it, not between additive and restrictive switches, and Air ZOC is a gate: OG gives it
 * its own bit in the option bitfield rather than nesting it under another rule. The cost of the
 * choice is bounded by [RuleKey.AIR_ZOC] itself, which is off in every profile except Open General
 * Fidelity, so an unreadable scenario only ever sees this because a player asked for OG's rules.
 */
internal object AirZoneOfControl {
    /**
     * Whether aircraft project a zone of control: the ruleset key AND the scenario's own `airzoc`
     * switch, authored by 79 of the 457 scenarios that carry the option bitfield.
     */
    fun enabled(): Boolean =
        ActiveRuleset.flag(RuleKey.AIR_ZOC, false) &&
            (GameHolder.instance?.scenario?.airZoc ?: true)
}
