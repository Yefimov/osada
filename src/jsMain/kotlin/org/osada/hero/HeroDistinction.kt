package org.osada.hero

/**
 * The highest degree of distinction a campaign can confer — §12's `Hero of the Soviet Union`,
 * modelled as a TITLE rather than as another entry in the generic medal list.
 *
 * ## Why not a medal
 *
 * [HeroMedals] hands out one-time career markers on evidence thresholds. That is the right shape
 * for "Armour Hunter Badge" and the wrong one for this: §12.1 requires a date the title could not
 * exist before, a period-correct presentation that changes in 1939, an exceptional recorded DEED
 * rather than an accumulated total, and a repeat conferral that must be a second independent deed.
 * None of those fit a threshold table, and forcing them in would have made the medal system carry
 * award regulations §12.6 explicitly rules out building.
 *
 * ## What it is not allowed to do
 *
 * §12.6: no attack, defense, movement or initiative modifier. "A hero worthy of the title may
 * already be mechanically exceptional through earned traits. The award records that career; it does
 * not make the medal itself operate as battlefield equipment." Nothing reads [HeroDistinction] in
 * any combat path, and that is the invariant `HeroDistinctionTest` pins.
 */
data class HeroDistinction(
    val distinctionId: String,
    /** 1 for the first conferral, 2 for the second, and so on — §12.2. Never reused. */
    val sequence: Int,
    val scenarioId: String,
    val turn: Int,
    val date: String? = null,
    val location: String? = null,
    /**
     * The exceptional deeds this conferral was granted for.
     *
     * Stored so §12.5's citation can be generated from facts the engine actually observed. It is
     * also the duplicate guard: a conferral cites specific event ids, so the same event being
     * processed twice cannot produce a second award (§12.3).
     */
    val deedEventIds: List<String> = emptyList(),
    val posthumous: Boolean = false,
)
