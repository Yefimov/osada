package org.osada.hero

import org.osada.hero.HeroNaming.nameFor


/**
 * Deterministic name and rank selection.
 *
 * As of Phase 2 the naming body lives in [HeroNamePools], which is nation-aware per §16. This
 * object remains the stable entry point and keeps the two things a name generator on its own does
 * not: rank derivation, and a country-less overload for the save migration, which reconstructs an
 * officer from a bare integer and so has no nation to key on (it takes the [HeroNamePools] generic
 * pool). New emergences call [nameFor] with a country.
 *
 * Determinism is the part that must not change: the same seed must always yield the same officer,
 * or reloading a save rerolls heroes and §29.17 fails.
 */
internal object HeroNaming {
    /** Rank ids, lowest first. Display text belongs to the UI layer, not here. */
    private val RANKS = listOf("lieutenant", "captain", "major", "colonel")

    private const val EXPERIENCE_PER_LEVEL = 100

    /**
     * A stable full-name designation for [seed], using [country]'s pool (§16). Gender is derived
     * from the same [seed] via [PortraitComposerV2.genderFor] (§4.11), so the name always agrees
     * with the portrait the same seed composes.
     */
    fun nameFor(
        seed: Int,
        country: Int,
    ): String = HeroNamePools.nameFor(seed, country, female = PortraitComposerV2.genderFor(seed) == "female")

    /** Country-less overload for the migration, which has no nation for a reconstructed officer. */
    fun nameFor(seed: Int): String =
        HeroNamePools.nameFor(seed, country = -1, female = PortraitComposerV2.genderFor(seed) == "female")

    /**
     * Rank implied by the formation's veteran experience.
     *
     * Using unit experience is a migration-time approximation, not the model: §8.5 makes rank a
     * function of the leader's own career, which a reconstructed hero has none of. It gives a
     * long-serving formation a plausibly senior commander instead of starting everyone as a
     * lieutenant.
     */
    fun rankForExperience(experience: Int): String {
        val level = experience / EXPERIENCE_PER_LEVEL
        return RANKS[level.coerceIn(0, RANKS.size - 1)]
    }

    /**
     * The rank one rung above [currentRankId] (§8.5) — capped at the ladder's top rather than
     * throwing, so a hero already at the senior-most rank simply stays there. An unrecognised
     * [currentRankId] (a save from a build with a different ladder) is treated as the bottom rung.
     */
    fun nextRank(currentRankId: String): String {
        val index = RANKS.indexOf(currentRankId).coerceAtLeast(0)
        return RANKS[(index + 1).coerceAtMost(RANKS.size - 1)]
    }
}
