package org.osada.rules

import org.osada.UNIT_MAX_EXPERIENCE
import org.osada.model.GameUnit

/**
 * Experience: how much a unit may hold, and how many bars that is worth.
 *
 * **One home for `experience / 100`.** Three call sites each owned a private copy ([Evade],
 * [Sabotage], [UnitCapabilities.combatSupportBars]) until 2026-08-28, which is how a bar count
 * silently meant three things at once.
 *
 * ### The two `equip.cfg` keys this object briefly read, and why it does not
 *
 * `exp_unit_cap` (`eqp-gce` 5000) and `exp_bar_factor` (`eqp-lxf` 5) were built on 2026-08-28 and
 * **reverted the same day** (`docs/og-fidelity-plan.md` §AB). They are per-efile divergences, and
 * OSADA merged every efile into one `eqp-united` database: a unit has one record, so it must earn
 * and hold experience at one rate. Reading them would have meant the same merged unit levelling at
 * two different speeds depending which campaign it appeared in.
 *
 * The keys are not lost — they are recorded in §AB with their values and their arithmetic, so a
 * future per-campaign ruleset key can pick them up if that is ever wanted. What is gone is the
 * silent per-efile divergence.
 */
object UnitExperience {
    /** OG's own bar granularity, and OSADA's. */
    const val EXPERIENCE_PER_BAR = 100

    /** Five bars is the top of OG's display and of every formula that pays per bar. */
    const val MAX_BARS = 5

    /** The most experience a unit may hold. One number for every unit, whatever its efile. */
    fun cap(): Int = UNIT_MAX_EXPERIENCE

    /** [unit]'s experience bars, clamped to [MAX_BARS]. Every per-bar formula reads this. */
    fun bars(unit: GameUnit): Int = (unit.experience / EXPERIENCE_PER_BAR).coerceIn(0, MAX_BARS)
}
