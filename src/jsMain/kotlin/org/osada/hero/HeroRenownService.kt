package org.osada.hero

import org.osada.hero.HeroRenownService.advance

/**
 * Derives earned public standing (§4.4, §8.1) from a hero's accumulated leader XP.
 *
 * Experience determines the earned floor, while [advance] preserves higher authored or migrated
 * standing. That explicit ratchet matters because authored legendary heroes and the procedural
 * early-legend fallback can start with more renown than their initial XP alone would grant.
 */
internal object HeroRenownService {
    /** Order matches [HeroBalance.renownThresholds]: experienced, distinguished, hero, legend. */
    private val TIERS = listOf(HeroRenown.EXPERIENCED, HeroRenown.DISTINGUISHED, HeroRenown.HERO, HeroRenown.LEGEND)

    fun forExperience(
        experience: Int,
        balance: HeroBalance = HeroBalance.DEFAULT,
    ): HeroRenown =
        TIERS
            .zip(balance.renownThresholds)
            .lastOrNull { (_, threshold) -> experience >= threshold }
            ?.first
            ?: HeroRenown.UNKNOWN

    /** Raises renown when XP warrants it, but never lowers standing already stored on the hero. */
    fun advance(
        current: HeroRenown,
        experience: Int,
        balance: HeroBalance = HeroBalance.DEFAULT,
    ): HeroRenown {
        val earned = forExperience(experience, balance)
        return if (earned.ordinal > current.ordinal) earned else current
    }
}
