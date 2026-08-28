package org.osada.rules

import org.osada.CombatLog
import org.osada.UnitClass
import org.osada.model.ATTR_EX_MASK_COUNTER_BATTERY
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.InterceptionEvent
import org.osada.model.MoveReactionKind
import org.osada.model.fire
import org.osada.model.getUnits
import org.osada.model.hit
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Counterbattery fire — Open General manual §9.4: *"Some artillery units could have the
 * Counterbattery ability, so they will fire to enemy artillery units in range attacking friendly
 * units, once per turn."*
 *
 * **This is an optional rule in its own right, and `docs/og-fidelity-plan.md` §C had it filed in
 * the wrong place.** That table listed "counterbattery" as one item inside `extended_naval_rules`,
 * quoting OG's §9.6. §9.6 is four bullets — ships return fire to artillery and forts, ships attack
 * submarines only at range 1, destroyers escort naval transports, submarines need line of fire —
 * and counterbattery is not among them. It is §9.4, it is about land artillery, and it needs its own
 * key. [RuleKey.COUNTERBATTERY] is that key; the mis-filing is corrected in the plan in the same
 * change.
 *
 * ### Shape: a reaction, modelled on [OverwatchFire], not on support fire
 *
 * The two mechanics have the same player-facing problem — a formation loses strength in a combat
 * its owner did not order — so this follows every constraint `DEFERRED.md` §1.1 already imposed on
 * that one:
 *
 *  - **one-sided**: the artillery being counter-batteried does not shoot back, exactly as an
 *    intercepted aircraft cannot shoot at the gun;
 *  - **nothing is published before it fires**, and what fires is reported afterwards through the
 *    same [InterceptionEvent] channel the banner and the HUD log already read;
 *  - **it spends the answering gun's own shot** ([GameUnit.hasFired]), which is how OG's *"once per
 *    turn"* is expressed here. Without that one battery answers every enemy attack all turn for
 *    free and still fires in its own.
 *
 * ### Three deliberate narrowings, each recorded rather than discovered later
 *
 * 1. **It answers an ATTACK, never support fire and never an overrun.** OG's sentence is *"enemy
 *    artillery units... attacking friendly units"*; a gun that support-fires is answering someone
 *    else's attack, not making one. This also keeps the rule from nesting a reaction inside a
 *    reaction — and it keeps it off the one combat path multiplayer does not replay
 *    (`OsadaGameCommandHandlers` applies `AttackUnit` with `supportFire = false`, and resolves no
 *    support fire of its own), so counterbattery can never advance one peer's random cursor and not
 *    the other's.
 * 2. **Damage is applied directly rather than through `GameMap.attackUnit`.** A counterbattery shot
 *    that went through the full combat path could itself draw counterbattery, and two guns carrying
 *    the bit within range of each other would recurse. Resolving it the way [OverwatchFire] does —
 *    compute, apply, log — makes that structurally impossible instead of guarding against it.
 * 3. **The answering unit does not have to be Artillery class.** OG's own data does not respect the
 *    class boundary here: of the 818 shipped records carrying the bit, the population spreads past
 *    Artillery, and `AttackEligibility.canInitiateAttack` is a better gate than a class list anyway
 *    because it already rejects a unit with no ammunition or no attack value against the target.
 *    The TARGET must be Artillery, because that half is what OG's sentence actually says.
 */
internal object CounterBatteryFire {
    /** Whether the rule is in force at all. Off in every profile except Open General Fidelity. */
    fun enabled(): Boolean = ActiveRuleset.flag(RuleKey.COUNTERBATTERY, false)

    /** Whether [data] carries OG's `Counter Battery` ability, for the badge and the ability line. */
    fun hasAbility(unit: GameUnit): Boolean = unit.unitData(true).attrEx and ATTR_EX_MASK_COUNTER_BATTERY != 0

    /**
     * The friendly guns that answer [attacker] for firing on [defender], or an empty list when the
     * rule is off, the attacker is not artillery, or nobody is both in range and able to shoot.
     *
     * Range is the ANSWERING gun's own attack range measured to the attacker's hex: counterbattery
     * is that gun making an attack of its own, so it reaches exactly as far as it always does.
     */
    fun respondersTo(
        map: GameMap,
        attacker: GameUnit,
        defender: GameUnit,
    ): List<GameUnit> {
        val defenderSide = defender.player?.side
        val applies =
            enabled() &&
                defenderSide != null &&
                attacker.unitData(true).uclass == UnitClass.ARTILLERY.value
        val attackerPos = attacker.getPos()?.takeIf { applies } ?: return emptyList()
        return map.getUnits().filter { gun ->
            gun.player?.side == defenderSide &&
                gun.id != defender.id &&
                !gun.hasFired &&
                !gun.destroyed &&
                hasAbility(gun) &&
                withinRange(gun, attackerPos.row, attackerPos.col) &&
                AttackEligibility.canInitiateAttack(gun, attacker, asActiveAttack = false)
        }
    }

    private fun withinRange(
        gun: GameUnit,
        row: Int,
        col: Int,
    ): Boolean {
        val pos = gun.getPos() ?: return false
        return HexGeometry.distance(pos.row, pos.col, row, col) <= AttackEligibility.getUnitAttackRange(gun)
    }

    /**
     * Applies one-sided fire from [responders] onto [attacker] and returns the events for the HUD.
     *
     * Fire stops as soon as the target is destroyed — the remaining batteries hold rather than
     * shelling a wreck, the same courtesy [OverwatchFire] extends.
     */
    fun applyCounterBattery(
        map: GameMap,
        attacker: GameUnit,
        responders: List<GameUnit>,
    ): List<InterceptionEvent> {
        val units = map.getUnits().toList()
        val events = mutableListOf<InterceptionEvent>()
        for (gun in responders) {
            if (attacker.destroyed) break
            val logId = CombatLog.addCombatStart(gun, attacker, map.turn)
            // committed = true: this result is applied to the map, so it is one of the calls
            // allowed to draw from the shared random stream (`rules/GameRandomSource`).
            val result = GameRules.calculateAttackResults(gun, attacker, true, units, committed = true)
            attacker.hit(result.kills, UnitCapabilities.hasLastingSuppression(gun))
            gun.fire(true)
            CombatLog.addCombatEnd(gun, attacker, logId, true)
            events +=
                InterceptionEvent(
                    gun,
                    attacker,
                    result.kills,
                    attacker.destroyed,
                    MoveReactionKind.COUNTER_BATTERY,
                )
        }
        return events
    }
}
