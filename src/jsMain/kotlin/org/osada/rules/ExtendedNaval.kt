package org.osada.rules

import org.osada.GameHolder
import org.osada.UnitClass
import org.osada.model.ATTR_EX_MASK_NO_INTERCEPT_AIR
import org.osada.model.GameUnit
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Open General's **Extended Naval Rules** optional rule (manual §9.6) — the largest single gap this
 * project had, by shipped content: **238 of the 457 scenarios whose source is readable ask for it**
 * (`docs/og-fidelity-plan.md` §O.2), and until 2026-08-27 the switch was imported and no rule read
 * it.
 *
 * The manual states it as four bullets and nothing else:
 *
 * > *"When Extended naval rules are in effect, the following changes occur:*
 * > * *Ships return fire to artillery and forts.*
 * > * *Ships can only attack submarines at range 1.*
 * > * *Destroyers can escort naval transports against submarine attacks, just like fighters escort
 * >   bombers.*
 * > * *Submarines need direct LOF to attack."*
 *
 * All four are built. Each lives here and is CALLED from the rule it modifies rather than
 * reimplementing it, so there is exactly one return-fire rule, one support-fire rule and one
 * line-of-fire rule in the engine:
 *
 * | # | Built as | Called from |
 * |---|---|---|
 * | 1 | [shipReturnsFireToShoreBattery] | `AttackCalculation.resolveRuggedSurpriseAndFireEligibility` |
 * | 2 | [blockedByRangeToSubmarine] | `AttackEligibility.canInitiateAttack` |
 * | 3 | [escortsNavalTransport] | `CombatResolver.isSupportFireEligible` |
 * | 4 | [submarineLacksLineOfFire] | `AttackEligibility.canInitiateAttack` |
 *
 * ### One key for four bullets, because OG has one switch for four bullets
 *
 * `docs/og-fidelity-plan.md` §C said this from the start — *"may ship as ONE key
 * `extended_naval_rules`, because OG itself treats them as one coherent optional set"* — and the
 * scenario bitfield agrees: there is a single `extnaval` bit, not four. Splitting them would invent
 * a granularity no author can express and no OG source describes.
 *
 * **Counterbattery is NOT one of these four.** §C listed it here and was wrong; it is §9.4, a rule
 * about land artillery, and it has shipped under its own key since §L. Naval mines are §9.9. Do not
 * refile either one into this set.
 *
 * ### Two of the four take shots away, which is why the key exists
 *
 * Bullets 2 and 4 are restrictions: 4,129 of the 4,990 shipped ship records have a gun range above
 * one and would lose every long shot at a submarine, and 627 of the 710 submarine records would
 * have their own fire cut by terrain. That is the §5.10 hazard the whole fidelity plan is written
 * around — a rule that re-tunes 502 shipped scenarios belongs behind a key the player chooses — and
 * it is why [RuleKey.EXTENDED_NAVAL] defaults OFF everywhere except Open General Fidelity.
 *
 * ### The scenario's own switch, and what an ABSENT one means
 *
 * This reads `null` as **permission**, so a scenario with no readable source follows the key alone.
 * That is `ExtendedLos.enabled`'s rule and for the same reason: 105 of the 502 deployed scenarios
 * name a source this project could not read, and a RULE-level switch read as prohibition would
 * silently drop the whole optional rule for them. It differs from `TrueDLOF`/`UnitsBlockDLOF`,
 * which read `null` as false, because those are sub-options that only ever ADD an obstruction — see
 * `ExtendedLos`'s header for the distinction.
 */
internal object ExtendedNaval {
    /** OG's *"only at range 1"*, as a number rather than a literal at three call sites. */
    private const val SUBMARINE_ATTACK_RANGE = 1

    /**
     * Whether the rule is in force: the ruleset key AND the scenario's own `extnaval` switch.
     *
     * Authored by 238 of the 457 scenarios that carry the option bitfield. Off in every profile
     * except Open General Fidelity either way.
     */
    fun enabled(): Boolean =
        ActiveRuleset.flag(RuleKey.EXTENDED_NAVAL, false) &&
            (GameHolder.instance?.scenario?.extendedNaval ?: true)

    private fun isSubmarine(unit: GameUnit): Boolean = unit.unitData().uclass == UnitClass.SUBMARINE.value

    private fun isShoreBattery(unit: GameUnit): Boolean {
        val uclass = unit.unitData().uclass
        return uclass == UnitClass.ARTILLERY.value || uclass == UnitClass.FORTIFICATION.value
    }

    /**
     * The two RESTRICTIONS of §9.6 as one question, for `AttackEligibility.canInitiateAttack`.
     *
     * It is asked there rather than in `isInAttackRange` so that the attack overlay, the AI and the
     * click path all see it: every one of those funnels through `canInitiateAttack` via
     * `Hex.getAttackableUnit`, and only some of them reach `isInAttackRange`.
     *
     * The other two bullets are not refusals and are not here: bullet 1 grants return fire in
     * `AttackCalculation` and bullet 3 grants support fire in `CombatResolver`.
     *
     * Both halves apply to a reaction as well as to an ordered attack, unlike
     * `AttackEligibility.blockedByMovedAirGrant`. Nothing in §9.6 scopes either sentence to one of
     * OG's three actions, and a submarine that may not see its target may not see it when
     * answering either.
     */
    fun blocksAttack(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean {
        if (!enabled()) return false
        val aPos = attacker.getPos()
        val dPos = defender.getPos()
        // An unresolvable position is treated as adjacent, which refuses nothing: a rule must not
        // take an order away because the geometry could not be read.
        val distance =
            if (aPos == null || dPos == null) {
                SUBMARINE_ATTACK_RANGE
            } else {
                HexGeometry.distance(aPos.row, aPos.col, dPos.row, dPos.col)
            }
        return blockedByRangeToSubmarine(attacker, defender, distance) ||
            submarineLacksLineOfFire(attacker, defender)
    }

    /**
     * **Bullet 1** — *"Ships return fire to artillery and forts."*
     *
     * Ordinarily a ranged attack draws no answer at all: `AttackCalculation` clears `defcanfire`
     * beyond range 1 unless BOTH sides are naval, so a battery ashore or a coastal fort shells a
     * fleet with complete impunity. This is the rule that ends that, and it is the only one of the
     * four that gives something rather than taking it away.
     *
     * **The ship must be able to reach back**, which is the one condition OG's sentence does not
     * state and this build adds. It is not an invention from nothing: it is the identical condition
     * OSADA already imposes on the naval-versus-naval return fire immediately above this call
     * (`defenderData.gunrange >= distance`), and the alternative reading — a range-1 motor launch
     * answering a battery four hexes inland — describes no gunnery either game models. Recorded
     * here rather than buried: **if OG turns out to let a ship answer out of its own range, this is
     * the line to correct.**
     *
     * 6,409 artillery and 1,154 fortification records can be the attacker; 317 of the forts have a
     * gun range above one, so this changes the shore-bombardment duel rather than close combat,
     * which already answered.
     */
    fun shipReturnsFireToShoreBattery(
        attacker: GameUnit,
        defender: GameUnit,
        distance: Int,
    ): Boolean =
        enabled() &&
            UnitPredicates.isSea(defender) &&
            isShoreBattery(attacker) &&
            defender.unitData().gunrange >= distance

    /**
     * **Bullet 2** — *"Ships can only attack submarines at range 1."*
     *
     * A restriction on the ATTACKER being a ship, not on anything that can hurt a submarine: OG
     * §8.2.4 has tactical bombers attack submarines from the air and says nothing about their range
     * here, so aircraft are untouched. It stacks with, and does not replace,
     * `EquipmentCombatEligibility.canAttackSubmarineTarget` — a cruiser that could never engage a
     * submarine at all is refused there, and a destroyer that can is refused here until it closes.
     *
     * 4,129 of the 4,990 shipped ship records carry a gun range above one, so this is a real
     * narrowing rather than a formality, and it is the sharper half of why the key exists.
     */
    fun blockedByRangeToSubmarine(
        attacker: GameUnit,
        defender: GameUnit,
        distance: Int,
    ): Boolean =
        enabled() &&
            UnitPredicates.isSea(attacker) &&
            isSubmarine(defender) &&
            distance > SUBMARINE_ATTACK_RANGE

    /**
     * **Bullet 3** — *"Destroyers can escort naval transports against submarine attacks, just like
     * fighters escort bombers."*
     *
     * **The mechanic is support fire, and that much the first build got right.** OG's own procedure
     * (`luis-guzman.com/OpenGen_Combat.html`) is: the escort fires at the submarine FIRST, and if
     * the submarine survives its attack continues against the original target. It does not become
     * the target itself — there is no damage redirection, which is what the manual's *"just like
     * fighters escort bombers"* had left open. Fighter escort works the same way.
     *
     * **Four narrowings were missing, and they are added here (2026-08-27):**
     *
     * | OG's condition | Where |
     * |---|---|
     * | the destroyer is **adjacent to the unit being attacked** | [escortsNavalTransport] |
     * | **only one** destroyer escorts | `CombatResolver.getSupportFireUnits` |
     * | it has not already given support this turn | `GameUnit.hasSupportedThisTurn` |
     * | it does not carry `No Intercept Air` | [escortsNavalTransport] |
     * | it can fire on the submarine | `AttackEligibility.canInitiateAttack`, already in the path |
     *
     * The first build required only the class triangle and let every destroyer in range pile on.
     *
     * **`No Intercept Air` is OG's condition and not an obvious one** — the special is named for
     * aircraft and this is a submarine attack. It is applied because the procedure lists it, not
     * because it can be explained: `OG_ABILITY_AUDIT.md` §1's rule against inventing a mechanic
     * from a name cuts both ways, and declining to apply a stated condition because it reads oddly
     * would be the same error in reverse.
     *
     * The class triangle is exactly OG's: attacker Submarine, defender Naval Transport, supporter
     * Destroyer. 1,717 destroyer and 678 naval-transport records are shipped.
     */
    fun escortsNavalTransport(
        support: GameUnit,
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean =
        enabled() &&
            isSubmarine(attacker) &&
            defender.unitData().uclass == UnitClass.NAVAL_TRANSPORT.value &&
            support.unitData(true).uclass == UnitClass.DESTROYER.value &&
            support.unitData(true).attrEx and ATTR_EX_MASK_NO_INTERCEPT_AIR == 0 &&
            !support.hasSupportedThisTurn &&
            adjacentTo(support, defender)

    /** OG requires the escort beside the unit it is protecting, not merely within reach of the
     *  submarine — see [escortsNavalTransport]. */
    private fun adjacentTo(
        support: GameUnit,
        defender: GameUnit,
    ): Boolean {
        val a = support.getPos()
        val b = defender.getPos()
        return a != null && b != null && HexGeometry.distance(a.row, a.col, b.row, b.col) <= 1
    }

    /**
     * **Bullet 4** — *"Submarines need direct LOF to attack."*
     *
     * This bullet is the reason `ExtendedLos` can be sure §6.18's terrain check does NOT apply to
     * every attack in the game: a rule that has to be ADDED here would be redundant if it did (see
     * that file's header, point 1). So what it grants is the §6.18 check itself, applied to
     * submarines whether or not the Extended LOS key is on — which is why it calls
     * [ExtendedLos.lineOfFireClear], the ungated computation, rather than
     * `ExtendedLos.hasLineOfFire`, which answers "true" whenever §9.5 is switched off.
     *
     * The scenario's `TrueDLOF` and `UnitsBlockDLOF` still shape it, because those two tune §6.18
     * itself rather than §9.5, and a submarine firing under §9.6 is firing under §6.18.
     *
     * 627 of the 710 shipped submarine records have a gun range above one, so this bites on the
     * great majority of them; at range 1 there is nothing in between and it never applies.
     *
     * **A scenario may switch it back off.** OG's own `Subs no need DLOF` (byte 1017 bit 1, three
     * scenarios corpus-wide) exempts submarines from this bullet, and `Scenario.subsNeedLineOfFire`
     * carries it. Null -- the source could not be read -- leaves the bullet in force, which is the
     * direction §AD prescribes for a SUB-OPTION that only ever removes an obstruction.
     */
    fun submarineLacksLineOfFire(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean =
        enabled() &&
            GameHolder.instance?.scenario?.subsNeedLineOfFire != false &&
            isSubmarine(attacker) &&
            !ExtendedLos.lineOfFireClear(attacker, defender)
}
