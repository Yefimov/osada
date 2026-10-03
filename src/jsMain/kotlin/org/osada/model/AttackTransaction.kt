package org.osada.model

import org.osada.CombatLog
import org.osada.addSurrender
import org.osada.hero.HeroCampaign
import org.osada.rules.CombatPositioning
import org.osada.rules.CombatResolver
import org.osada.rules.supportFiresAtHalfStrength

/** One defender-side support shot fired before the main exchange, in firing order. */
class SupportShot(
    val supporter: GameUnit,
    val supporterPos: Cell,
    /** The support exchange itself, with the supporter as ITS attacker -- kept so the presenter can
     *  announce what that exchange produced (a leader gained on either side). */
    val result: CombatResults,
)

/** A defender that could not pay an owed retreat and surrendered (`CombatResolver.shouldDefenderSurrender`). */
class Surrender(
    val unit: GameUnit,
    val pos: Cell,
    val name: String,
    val prestige: Int,
)

/**
 * Everything an attack ORDER did, in the order it happened, for whoever presents it. The model has
 * already applied all of it; nothing here is a request.
 */
class AttackOutcome(
    val result: CombatResults,
    val supportShots: List<SupportShot>,
    val attackStopped: Boolean,
    val retreat: ForcedMove?,
    val surrender: Surrender?,
)

/**
 * A resolved attack order as an observer sees it: both units' strength BEFORE the order began
 * (support fire included), and everything it did. Raised once per [executeAttack], whoever called
 * it -- a player's order, a surprise engagement or an AI attack -- so a listener (the cooperative
 * contribution ledger) cannot miss an enemy-turn exchange or count one twice.
 */
class AttackReport(
    val attacker: GameUnit,
    val defender: GameUnit,
    val attackerStrengthBefore: Int,
    val defenderStrengthBefore: Int,
    val outcome: AttackOutcome,
)

/** The single seam through which model-level attack resolution is observed. Null outside a room. */
object AttackObservers {
    var onResolved: ((GameMap, AttackReport) -> Unit)? = null
}

/**
 * A complete attack order, resolved in the model: the defender's support fire, Panzer Marshal's
 * "Attack Stopped", the overrun decision, the main exchange, and the defender's retreat or
 * surrender (`docs/design/combat-movement-heroes-weather-audit.md` §4.1).
 *
 * Until 2026-09-27 only the desktop/touch/AI path had all of this: it lived in the UI's
 * `AnimationOrchestrator.uiUnitAttack`, while a multiplayer `AttackUnit` command applied the bare
 * exchange -- no support fire, no stopped attack, no overrun, no retreat, no surrender. Every caller
 * now goes through here; animation completion decides nothing.
 *
 * Random draws happen only in committed exchanges, in a fixed order (support shots in `map.units`
 * order, then the main exchange, then any evade/retreat), so every peer draws the same numbers.
 *
 * A surprise is ENCOUNTER-scoped: [attacker]'s surprised state is cleared once the encounter that
 * caused it has been fought, whoever called this -- it can no longer leak into a later attack
 * because a UI cleanup step did not run.
 */
internal fun GameMap.executeAttack(
    attacker: GameUnit,
    defender: GameUnit,
): AttackOutcome {
    val units = getUnits().toList()
    val preview = CombatResolver.calculateAttackResults(attacker, defender, true, units)
    val attackerOldStrength = attacker.strength
    val supportShots =
        if (attacker.isSurprised || preview.isOverrun) {
            emptyList()
        } else {
            fireSupport(attacker, defender, units)
        }
    val supportFireLosses = attackerOldStrength - attacker.strength
    val attackStopped =
        attacker.destroyed || CombatResolver.isLossOverRetreatThreshold(attacker.strength, attackerOldStrength)
    val defenderOldStrength = defender.strength
    val result =
        if (attackStopped) {
            attacker.spendAttack()
            // An empty result, NOT the forecast: no exchange happened, so nothing may be reported
            // as dealt. The loss the attacker did take is [CombatResults.supportFireLosses].
            CombatResults().apply {
                isAttackStopped = true
                defcanfire = false
            }
        } else {
            attackUnit(attacker, defender, false, preview.isOverrun)
        }
    result.supportFireLosses = supportFireLosses
    // OG's retreat procedure opens with *"Combat is not Rugged"*: a defender that ambushed its
    // attacker, or stood in a rugged defence, holds its hex whatever it lost. Close combat is left
    // out of this test deliberately -- see `CombatResolver.shouldDefenderRetreat`'s history.
    val rugged = attacker.isSurprised || result.isRugged
    val owesRetreat =
        !attackStopped &&
            !defender.destroyed &&
            !rugged &&
            CombatResolver.shouldDefenderRetreat(attacker, defender, defenderOldStrength)
    var retreat: ForcedMove? = null
    var surrender: Surrender? = null
    if (owesRetreat) {
        val destination = CombatPositioning.getRetreatPosition(map, defender, rows)
        if (destination != null) {
            retreat = retreatUnit(defender, destination)
        } else {
            surrender = surrenderIfCutOff(attacker, defender)
        }
    }
    attacker.isSurprised = false
    val outcome = AttackOutcome(result, supportShots, attackStopped, retreat, surrender)
    AttackObservers.onResolved?.invoke(
        this,
        AttackReport(attacker, defender, attackerOldStrength, defenderOldStrength, outcome),
    )
    return outcome
}

/**
 * The defender's supporting units fire on [attacker], each at the strength the forecast promised
 * ([supportFiresAtHalfStrength] measured to the protected defender), until the attacker is dead.
 */
private fun GameMap.fireSupport(
    attacker: GameUnit,
    defender: GameUnit,
    units: List<GameUnit>,
): List<SupportShot> {
    val defenderPos = defender.getPos()
    val shots = mutableListOf<SupportShot>()
    for (support in CombatResolver.getSupportFireUnits(units, attacker, defender)) {
        if (attacker.destroyed) break
        support.getPos()?.let { supportPos ->
            val shot =
                attackUnit(
                    support,
                    attacker,
                    true,
                    supportHalfStrength = supportFiresAtHalfStrength(support, defenderPos),
                )
            shots += SupportShot(support, supportPos, shot)
        }
    }
    return shots
}

/**
 * Surrender is reachable ONLY after a retreat has genuinely been owed and has no legal hex: a unit
 * that merely stands in a corner is never forced to surrender (`CombatResolver.shouldDefenderSurrender`).
 * The commander's fate is resolved here, since the defender was alive when combat processed heroes.
 */
private fun GameMap.surrenderIfCutOff(
    attacker: GameUnit,
    defender: GameUnit,
): Surrender? {
    val blockedByOwn = CombatPositioning.isRetreatBlockedByOwnUnitsOnly(map, defender, rows)
    val pos = defender.getPos()
    if (!CombatResolver.shouldDefenderSurrender(defender, blockedByOwn) || pos == null) return null
    val name = defender.unitData(true).name
    val prestige = surrenderUnit(defender, attacker)
    HeroCampaign.recordCasualty(defender, turn)
    attacker.player?.side?.let { captorSide -> CombatLog.addSurrender(defender, pos, captorSide, prestige) }
    return Surrender(defender, pos, name, prestige)
}
