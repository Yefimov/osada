package org.osada.multiplayer.coop

/**
 * When a campaign grants a cooperative commander's third and fourth ability slot
 * (`docs/multiplayer/cooperative-commanders.md` §5.4, §7).
 *
 * Two SEPARATE unlocks, each +1 for every commander, granted at common campaign milestones and never
 * for individual score. The default tuning is "the third slot after two completed missions and the
 * fourth after five"; a short campaign compresses that so the slot arrives while there is still a
 * mission left to use it in -- a fourth slot awarded after the final battle would be no slot at all.
 *
 * "Completed" counts DISTINCT missions the campaign advanced out of (the caller keeps the set), so
 * retrying a lost mission, restoring a snapshot or replaying a transition cannot grant a slot twice.
 * These counts are tuning proposals, not owner decisions, and are the one place to change them.
 */
object CoopProgress {
    private const val DEFAULT_FIRST_AFTER = 2
    private const val DEFAULT_SECOND_AFTER = 5

    /** Missions that must be completed for unlock one and unlock two; null when the unlock cannot be useful. */
    data class Milestones(
        val first: Int?,
        val second: Int?,
    )

    fun milestones(totalMissions: Int): Milestones {
        if (totalMissions <= 1) return Milestones(null, null)
        // A long campaign uses the default tuning. A short one is compressed so that BOTH slots arrive
        // while at least one mission is left to play with them.
        val first =
            if (totalMissions >
                DEFAULT_SECOND_AFTER
            ) {
                DEFAULT_FIRST_AFTER
            } else {
                ((totalMissions - 1) / 2).coerceAtLeast(1)
            }
        val second =
            (
                if (totalMissions >
                    DEFAULT_SECOND_AFTER
                ) {
                    DEFAULT_SECOND_AFTER
                } else {
                    totalMissions - 1
                }
            ).takeIf { it > first }
        return Milestones(first, second)
    }

    /** Slots earned beyond the base two after [completed] distinct missions of a [totalMissions]-mission campaign. */
    fun unlocksAfter(
        completed: Int,
        totalMissions: Int,
    ): Int {
        val m = milestones(totalMissions)
        var unlocks = 0
        if (m.first != null && completed >= m.first) unlocks++
        if (m.second != null && completed >= m.second) unlocks++
        return unlocks
    }
}
