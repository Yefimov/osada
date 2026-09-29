package org.osada.rules

import org.osada.LeaderType
import org.osada.model.Cell
import org.osada.model.GameUnit
import org.osada.model.Leaders

/**
 * Open General's two support-fire leaders, which Panzer Marshal never had and OSADA had no
 * `LeaderType` for until 2026-09-28 (OG `* Leaders` entries 29 and 35).
 *
 * - **Extended Front** — *"Gives support fire to adjacent friend units"*
 *   (`OPENTXT_SAMPLE/strings-en-template.txt`). A formation that does not support by class (a tank,
 *   an anti-tank gun) answers for a friend it stands BESIDE. The gun still has to reach the
 *   attacker with its own range; that test stays in `CombatResolver.isSupportFireEligible`.
 * - **Flak Support** — *"Support Fire and Multiple intercept"*; the manual's class line reads
 *   *"FlaK: 'FlaK Support'. Multiple interception and support fire."* It is the Flak class
 *   attribute ([org.osada.model.OgLeaderPools]). Its support fire is against a GROUND attacker —
 *   against an aircraft a Flak battery already answers through `AirDefenceSupport` — and its
 *   interception half lifts `AAInterception`'s one-interception-per-turn budget.
 *
 * Both are the leader-layer sources of a capability OG also grants by equipment (`Support Fire`
 * toggle) and by class (artillery). `OG_ABILITY_AUDIT.md` §1 names Extended Front as exactly that
 * fourth layer.
 */
object LeaderSupportFire {
    private const val ADJACENT = 1

    /** Whether a leader lets [support], standing at [supportPos], fire in support of [defender]. */
    fun grants(
        support: GameUnit,
        defender: GameUnit,
        supportPos: Cell,
    ): Boolean {
        val dPos = defender.getPos()
        val besideDefender =
            dPos != null && HexGeometry.distance(supportPos.row, supportPos.col, dPos.row, dPos.col) <= ADJACENT
        return Leaders.unitHasLeader(support, LeaderType.FLAK_SUPPORT) ||
            (besideDefender && Leaders.unitHasLeader(support, LeaderType.EXTENDED_FRONT))
    }

    /** Whether [aa] may intercept more than one moving aircraft a turn (Flak Support). */
    fun interceptsRepeatedly(aa: GameUnit): Boolean = Leaders.unitHasLeader(aa, LeaderType.FLAK_SUPPORT)
}
