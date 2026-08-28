package org.osada.rules

import org.osada.model.ATTR_EX_MASK_SABOTEUR
import org.osada.model.EfileConfig
import org.osada.model.GameUnit
import org.osada.model.hit

/**
 * Open General's **`Saboteur`** (`SpecialEx` 62.2, `attrEx` bit 18) — a pre-combat attempt to
 * disable the defender outright. Wired 2026-08-27 from the author's combat procedures.
 *
 * Ten shipped records carry it (Infantry 6, Fighter 3, Recon 1), which is the whole population and
 * makes this the rarest ability OSADA executes. It is built anyway because it is cheap once the
 * numbers are known and because a badge that states a rule the engine does not run is the defect
 * `docs/og-fidelity-plan.md` §L.10 exists to prevent.
 *
 * ### The sequence
 *
 * Sabotage is step 3 of OG's combat order — after fighter interception and support fire, before
 * evasion and the main exchange. The attempt spends **one ammunition point** whatever happens.
 *
 *  - **Success**: normal combat does not happen at all, and the defender becomes *sabotaged*.
 *  - **Failure**: the attacker takes **two suppression points** and the combat proceeds normally.
 *
 * ### The chance
 *
 * ```
 * base = sabotage_min + initiative + 2 x experience_bars + 3 x (attack value vs this defender)
 * adjusted for the two sides' actual strengths, then capped at sabotage_max
 * ```
 *
 * `sabotage_min` and `sabotage_max` are per-efile (`eqp-lxf` ships 40 and 85). The strength
 * adjustment is the one term the author's page describes rather than states as a formula, so
 * [strengthAdjusted] applies the shape every other strength comparison in this engine uses — the
 * ratio of the two — and says so. **If OG turns out to weight it differently, that function is the
 * one sentence to correct**; the terms around it are quoted.
 *
 * ### What being sabotaged does
 *
 * −2 attack and −2 defence; the next move and the next attack are lost; it cannot reinforce,
 * resupply or evade; and it cannot act as a Depot or a Healer. OSADA has no Depot or Healer, so
 * those two clauses are inert here rather than approximated — see [GameUnit.sabotaged].
 */
internal object Sabotage {
    /** OG's own figures where an efile names none; `eqp-lxf` ships 40 and 85. */
    private const val DEFAULT_MIN = 40
    private const val DEFAULT_MAX = 85

    private const val FULL_ROLL = 100
    private const val PER_EXPERIENCE_BAR = 2
    private const val PER_ATTACK_POINT = 3

    /** OG's cost, whatever the outcome. */
    const val AMMO_COST = 1

    /** OG's penalty for a failed attempt. */
    const val FAILURE_SUPPRESSION = 2

    /** Whether [unit]'s equipment carries the ability. Read on the REAL record. */
    fun isSaboteur(unit: GameUnit): Boolean = unit.unitData(true).attrEx and ATTR_EX_MASK_SABOTEUR != 0

    /**
     * Whether [attacker] may attempt sabotage on [defender] at all: it has the ability, the
     * ammunition, and the defender is not already sabotaged.
     *
     * A defender that is already sabotaged is excluded because the effect does not stack — OG's
     * penalties are stated once, not per attempt — and because spending ammunition to re-apply a
     * state the unit is already in would be a cost with no effect.
     */
    fun canAttempt(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean = isSaboteur(attacker) && attacker.getAmmo() >= AMMO_COST && !defender.sabotaged

    /**
     * The chance of success as a percentage, clamped to the efile's own `sabotage_max`.
     *
     * Every term but [strengthAdjusted] is quoted from the author's combat page.
     */
    fun percentFor(
        attacker: GameUnit,
        defender: GameUnit,
    ): Int {
        if (!canAttempt(attacker, defender)) return 0
        val min = EfileConfig.intKey("sabotage_min", DEFAULT_MIN)
        val max = EfileConfig.intKey("sabotage_max", DEFAULT_MAX)
        val data = attacker.unitData()
        val bars = UnitExperience.bars(attacker)
        val attackValue = attackValueAgainst(data, defender.unitData())
        val raw =
            min + data.initiative + PER_EXPERIENCE_BAR * bars + PER_ATTACK_POINT * attackValue
        return strengthAdjusted(raw, attacker, defender).coerceIn(0, max)
    }

    /**
     * The *"adjusted for actual strengths"* term.
     *
     * **This is the one INFERENCE in this file.** The author's page names the adjustment without
     * giving its arithmetic, so this applies the shape every other strength comparison in the
     * engine uses: the attacker's share of the two strengths, against an even split. A full-strength
     * saboteur against a full-strength defender is therefore unmodified, and a battered one is
     * proportionally less likely to get in.
     */
    private fun strengthAdjusted(
        raw: Int,
        attacker: GameUnit,
        defender: GameUnit,
    ): Int {
        val total = attacker.strength + defender.strength
        if (total <= 0) return raw
        return raw * 2 * attacker.strength / total
    }

    /**
     * Rolls the attempt for a COMMITTED attack, spending the ammunition either way.
     *
     * **Only ever called from the committed path** — `GameRandomSource`'s first contract rule. A
     * forecast that rolled here would advance the shared stream on one peer's screen.
     */
    fun attempt(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean {
        val percent = percentFor(attacker, defender)
        if (percent <= 0) return false
        attacker.ammo -= AMMO_COST
        val succeeded = GameRandomSource.nextInt(FULL_ROLL) < percent
        if (succeeded) {
            defender.sabotaged = true
        } else {
            attacker.hit(FAILURE_SUPPRESSION, false)
        }
        return succeeded
    }
}
