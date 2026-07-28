package org.osada.hero

import org.osada.i18n.I18n
import kotlin.math.roundToInt

/**
 * Turns one unit's part in a resolved combat into recognition — design brief §7.1.
 *
 * Recognition is what a leaderless formation accumulates until it produces an officer (§5, §9.3).
 * The design is emphatic that it comes from **notable actions, not every routine attack**: a unit
 * that trades fire without result gains nothing, so a formation earns its commander by doing
 * something, not by being in enough combats.
 *
 * ## Phase 2 scope
 *
 * The §7.1 source list is long (encirclement, forced surrender, objective capture, air
 * interception, rescue…). Those richer signals are not all reachable from where combat is resolved
 * ([org.osada.model.CombatApplication] runs before retreat/surrender are decided) and belong with
 * Phase 3's structured [CombatAchievementEvent] pipeline (§19). Phase 2 scores the subset that IS
 * available at the point of combat resolution — kills, stronger-enemy kills, critical survival, and
 * veteran level-ups — which is enough for a formation to reliably grow a leader (§29.3).
 *
 * The same assessment also names the [EmergenceEvent] that best characterises the action, so if the
 * emergence roll succeeds the officer's first trait and biography are justified by what just
 * happened rather than rolled blind (§4.1, §8.3).
 */
internal object RecognitionService {
    data class Progress(
        val recognition: Int,
        val target: Int,
        val filledStages: Int,
        val status: String,
        val chancePercent: Int,
        val drought: Int,
        val guaranteedAfterFailures: Int,
    )

    /**
     * One unit's contribution to a single resolved combat, as [org.osada.model.CombatApplication] sees it.
     *
     * [terrain] and [enemyUnitClass] are Phase 3 additions consumed by [HeroAchievements] — both
     * default to null so every existing caller (and `HeroAcquisitionTest`, which builds these with
     * named arguments) is unaffected.
     *
     * [attackedGroundFromAir] and [closedDistanceBeforeAttack] are §7.43's two additions, and are
     * plain booleans rather than the unit classes they derive from on purpose: the air/ground test
     * that matters is `UnitPredicates.isAir`/`isGround` (movement method, not class number), which is
     * exactly what `AttackCalculation` gates the Skilled Ground Attack bonus on. Resolving them at
     * the combat site keeps this package free of a dependency on `rules` and keeps the achievement
     * and the bonus it justifies from drifting apart.
     */
    data class Contribution(
        val role: Role,
        val destroyedEnemy: Boolean,
        val enemyStronger: Boolean,
        val kills: Int,
        val gainedLevel: Boolean,
        val survivedCriticalDamage: Boolean,
        val terrain: Int? = null,
        val enemyUnitClass: Int? = null,
        val attackedGroundFromAir: Boolean = false,
        val closedDistanceBeforeAttack: Boolean = false,
    ) {
        enum class Role { ATTACKER, DEFENDER }
    }

    /** Recognition earned and the action that characterises it; [event] is null when nothing was notable. */
    data class Assessment(
        val points: Int,
        val event: EmergenceEvent?,
    ) {
        val isNotable: Boolean get() = points > 0
    }

    /** Critical strength (out of 10) at or below which a survivor counts as having held on. */
    private const val CRITICAL_STRENGTH = 3

    fun assess(
        contribution: Contribution,
        balance: HeroBalance = HeroBalance.DEFAULT,
    ): Assessment {
        var points = 0
        if (contribution.gainedLevel) points += balance.recognitionPerLevel
        if (contribution.destroyedEnemy) {
            points += balance.recognitionPerKill
            if (contribution.enemyStronger) points += balance.recognitionStrongerKillBonus
        }
        if (contribution.survivedCriticalDamage) points += balance.recognitionPerCriticalSurvival
        return Assessment(points, characterise(contribution))
    }

    /**
     * The most telling event for the contribution, in priority order. A defender that both survived
     * a heavy blow and destroyed its attacker is remembered for the stand, not the kill — the story
     * the player is shown should match the situation they saw.
     */
    private fun characterise(contribution: Contribution): EmergenceEvent? =
        when {
            contribution.destroyedEnemy && contribution.enemyStronger -> EmergenceEvent.DESTROYED_STRONGER_ENEMY
            contribution.role == Contribution.Role.DEFENDER && contribution.survivedCriticalDamage ->
                EmergenceEvent.HELD_UNDER_ATTACK
            contribution.survivedCriticalDamage -> EmergenceEvent.SURVIVED_CRITICAL_DAMAGE
            contribution.destroyedEnemy -> EmergenceEvent.DESTROYED_ENEMY
            contribution.gainedLevel -> EmergenceEvent.DISTINGUISHED_SERVICE
            else -> null
        }

    /**
     * Whether a strength survivor (out of 10) counts as having held on at critical strength.
     * Extracted so the combat site and tests agree on the threshold.
     */
    fun isCriticalSurvival(
        strengthAfter: Int,
        tookLosses: Boolean,
    ): Boolean = tookLosses && strengthAfter in 1..CRITICAL_STRENGTH

    /**
     * The coarse recognition status a leaderless formation shows (§7.1) — a broad phrase, not the
     * exact number, so the mechanic reads as "an officer is emerging" rather than a progress bar.
     * A formation that already has a commander returns null (there is nothing to recognise toward).
     *
     * An advanced tooltip may still show [CoreFormation.recognition] verbatim for players who want
     * the raw figure.
     */
    fun coarseStatus(
        formation: CoreFormation,
        balance: HeroBalance = HeroBalance.DEFAULT,
    ): String? {
        if (formation.assignedHeroId != null) return null
        val floor = balance.recognitionEmergenceFloor
        val key =
            when {
                formation.recognition <= 0 -> "hero.recognition.status.none"
                formation.recognition < floor / 2 -> "hero.recognition.status.promising"
                formation.recognition < floor -> "hero.recognition.status.pending"
                else -> "hero.recognition.status.likely"
            }
        return I18n.t(key)
    }

    /** Three visible stages plus the exact raw progress used by the Unit Info tooltip. */
    fun progress(
        recognition: Int,
        drought: Int = 0,
        balance: HeroBalance = HeroBalance.DEFAULT,
    ): Progress {
        val target = balance.recognitionEmergenceFloor
        val filled =
            when {
                recognition <= 0 -> 0
                recognition < target / 2 -> 1
                recognition < target -> 2
                else -> 3
            }
        val formation =
            CoreFormation(
                id = FormationId("recognition-preview"),
                ownerId = -1,
                country = -1,
                displayName = "",
                currentEquipmentId = 0,
                unitClass = 0,
                recognition = recognition,
            )
        val chancePercent =
            if (recognition >= target) {
                (LeaderAcquisitionService.chance(recognition, drought, balance) * 100.0).roundToInt()
            } else {
                0
            }
        return Progress(
            recognition = recognition,
            target = target,
            filledStages = filled,
            status = coarseStatus(formation, balance).orEmpty(),
            chancePercent = chancePercent,
            drought = drought,
            guaranteedAfterFailures = balance.guaranteedAfterEligibleFailures,
        )
    }
}
