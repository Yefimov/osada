package org.osada.rules

import org.osada.model.EfileConfig
import org.osada.model.GameUnit
import org.osada.model.Leaders

/**
 * OG's `upgrade_ldr` — what becomes of a commander when the formation under them re-equips.
 *
 * > *"When upgrading leadered unit which makes random leader attribute useless... Default = 0.
 * > Set to 1 to assign new random (according new equipment) but losing 1 bar. Set to 2 to remove
 * > leader, reducing unit's exp and bars as to be able to get a new leader."* —
 * > `EFILE_NOKORP/equip.cfg`
 *
 * The problem it solves is real and OSADA has it too: leaders are drawn from a per-CLASS list
 * (`Leaders.unitClassLeaders`), so a trait that made sense on the old equipment can be useless on
 * the new — a naval trait on a formation that has just become something else entirely.
 *
 * **Inert on every shipped efile.** `eqp-lxf` is the only one that authors the key and it writes
 * **0**, which is the documented default and OSADA's existing behaviour: the commander stays. The
 * rule is built so content that asks for 1 or 2 is honoured rather than silently overruled — the
 * standard `blow_any_terrain` has been held to since §N.4.
 *
 * The bar arithmetic for mode 2 is the one `INFERENCE` here. OG says *"reducing unit's exp and bars
 * as to be able to get a new leader"* without giving a number; the reading taken is the smallest
 * that satisfies the sentence — drop to just below the full-experience threshold, which is exactly
 * the state a formation is in when it is next eligible for a leader at all.
 */
object LeaderOnUpgrade {
    private const val KEEP = 0
    private const val REROLL_LOSING_A_BAR = 1
    private const val REMOVE = 2

    /** One experience bar, the cost mode 1 charges. */
    private const val BAR = UnitExperience.EXPERIENCE_PER_BAR

    /**
     * Applies the efile's policy to [unit], which has just re-equipped.
     *
     * A no-op for a formation with no leader, and a no-op under mode 0 — so the caller may invoke
     * it unconditionally after every upgrade.
     */
    fun afterUpgrade(unit: GameUnit) {
        if (unit.leader == -1) return
        when (EfileConfig.intKey("upgrade_ldr", KEEP)) {
            REROLL_LOSING_A_BAR -> {
                unit.experience = (unit.experience - BAR).coerceAtLeast(0)
                unit.leader = Leaders.generateLeader(unit)
            }

            REMOVE -> {
                unit.leader = -1
                unit.experience = unit.experience.coerceAtMost(UnitExperience.cap() - 1)
            }

            else -> Unit
        }
    }
}
