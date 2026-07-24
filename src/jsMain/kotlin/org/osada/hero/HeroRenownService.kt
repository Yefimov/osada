package org.osada.hero

/**
 * Derives earned public standing (§4.4, §8.1) from a hero's accumulated leader XP.
 *
 * Deliberately a pure function of cumulative experience rather than a stored transition: XP only
 * grows, so renown is naturally monotonic without needing its own ratchet logic, and a hero
 * reconstructed by migration or restored from an older save gets the right tier for free instead
 * of starting back at [HeroRenown.UNKNOWN].
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
}
