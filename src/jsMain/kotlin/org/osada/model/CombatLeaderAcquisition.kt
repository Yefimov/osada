package org.osada.model

import org.osada.CombatLog
import org.osada.hero.FormationIdentity
import org.osada.hero.HeroCampaign
import org.osada.hero.RecognitionService
import org.osada.rules.UnitPredicates

/**
 * Turns a resolved combat into leader acquisition for one combatant — extracted from
 * [CombatApplication] to keep that class's responsibilities (and function count) in bounds.
 *
 * Two worlds meet here. Campaign **core units** (those with a formation id) go through the Phase 2
 * hero system: recognition accumulates on the formation and a seeded emergence check may produce an
 * officer whose traits live in the hero roster, so `unit.leader` is deliberately left untouched.
 * **Scenario-only units**, which have no formation, keep the legacy integer path
 * (`docs/leaders.md` §5) unchanged — the open question of when to stop writing `unit.leader`
 * entirely is deferred until nothing reads it (it needs a save-format bump).
 */
internal object CombatLeaderAcquisition {
    private const val EXPERIENCE_PER_LEVEL = 100

    /** Resolves acquisition for [unit] and reports whether it gained a leader (for the bounce text). */
    fun acquire(
        unit: GameUnit,
        enemy: GameUnit,
        combatResult: CombatResults,
        isAttacker: Boolean,
        turn: Int = 0,
    ): Boolean {
        val expGained = if (isAttacker) combatResult.atkExpGained else combatResult.defExpGained
        return if (FormationIdentity.of(unit) != null) {
            val contribution = contributionFor(unit, enemy, combatResult, isAttacker, expGained)
            HeroCampaign.recordCombat(unit, contribution, turn)
        } else {
            acquireLegacyLeader(unit, expGained)
        }
    }

    private fun contributionFor(
        unit: GameUnit,
        enemy: GameUnit,
        combatResult: CombatResults,
        isAttacker: Boolean,
        expGained: Int,
    ): RecognitionService.Contribution {
        // The attacker's blow is the defender's casualties (kills) and vice versa; the enemy is
        // destroyed when this unit's fire reduced it to nothing.
        val casualtiesInflicted = if (isAttacker) combatResult.kills else combatResult.losses
        val casualtiesTaken = if (isAttacker) combatResult.losses else combatResult.kills
        return RecognitionService.Contribution(
            role =
                if (isAttacker) {
                    RecognitionService.Contribution.Role.ATTACKER
                } else {
                    RecognitionService.Contribution.Role.DEFENDER
                },
            destroyedEnemy = enemy.destroyed && casualtiesInflicted > 0,
            enemyStronger = enemy.unitData().cost > unit.unitData().cost,
            kills = casualtiesInflicted,
            gainedLevel = crossedExperienceLevel(unit.experience, expGained),
            survivedCriticalDamage =
                !unit.destroyed && RecognitionService.isCriticalSurvival(unit.strength, casualtiesTaken > 0),
            // The defender's hex, for river/urban/forest evidence (§8.4) — the contested ground
            // both combatants are judged against, regardless of which one "unit" is here.
            terrain = (if (isAttacker) enemy else unit).getHex()?.terrain,
            enemyUnitClass = enemy.unitData().uclass,
            // §7.43 evidence for the two categories the promotion catalogue gates on. The air/ground
            // test mirrors `AttackCalculation`'s Skilled Ground Attack gate exactly, so the trait can
            // only ever be earned by the kind of sortie it then improves.
            attackedGroundFromAir =
                isAttacker && UnitPredicates.isAir(unit) && UnitPredicates.isGround(enemy),
            closedDistanceBeforeAttack = isAttacker && spentMovementThisTurn(unit),
        )
    }

    /**
     * Whether [unit] paid movement before this attack — the "closed the distance" half of a
     * [org.osada.hero.AchievementType.MANEUVER_KILL].
     *
     * Compares against the equipment's own movement allowance rather than tracking a per-turn
     * odometer, because that allowance is what `unitEndTurn` restores `moveLeft` to. Artillery firing
     * from where it started the turn therefore scores nothing, which is the point: this is evidence
     * for mobile warfare, not for having a gun.
     *
     * `unitData(true)` — the *unit's* equipment, not its transport's — for exactly that reason:
     * `unitEndTurn` refills `moveLeft` from `Equipment.equipment[eqid]`, so a mounted formation
     * compared against `unitData()` (which resolves to the transport, or the carrier for an aircraft)
     * would read as having spent movement while sitting still.
     */
    internal fun spentMovementThisTurn(unit: GameUnit): Boolean = unit.moveLeft < unit.unitData(true).movpoints

    /** True when this combat's XP push carried the unit across a veteran level boundary. */
    private fun crossedExperienceLevel(
        experienceAfter: Int,
        expGained: Int,
    ): Boolean =
        expGained > 0 &&
            experienceAfter / EXPERIENCE_PER_LEVEL > (experienceAfter - expGained) / EXPERIENCE_PER_LEVEL

    private fun acquireLegacyLeader(
        unit: GameUnit,
        expGained: Int,
    ): Boolean {
        val leader = Leaders.generateLeaderWithChance(unit, expGained)
        if (leader == -1) return false
        unit.leader = leader
        CombatLog.addLeader(unit)
        return true
    }
}
