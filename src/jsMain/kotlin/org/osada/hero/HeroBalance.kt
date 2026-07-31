package org.osada.hero

import org.osada.hero.HeroBalance.Companion.DEFAULT


/**
 * Externalised tuning for acquisition — design brief §27.
 *
 * §22 is explicit that "the exact formula belongs in balance data and should not be hard-coded in
 * the UI layer". This is that data. It is a plain object rather than JSON-loaded config because no
 * campaign varies it yet; when one needs to (§27 wants per-campaign/difficulty tuning) the values
 * move to a loaded file and [DEFAULT] becomes the fallback. Nothing else in the system reads a
 * literal — every number an emergence roll depends on lives here so a balance pass is one file.
 *
 * Phase 2 populated recognition, the emergence chance curve, and drought protection. Phase 3 adds
 * the leader-XP, promotion and renown thresholds §27 also lists.
 */
data class HeroBalance(
    /** Recognition granted when a formation's unit gains a veteran experience level (§7.1). */
    val recognitionPerLevel: Int = 25,
    /** Recognition for destroying an enemy of comparable or lesser value (§7.1). */
    val recognitionPerKill: Int = 10,
    /** Extra recognition when the destroyed enemy was the more valuable unit (§7.1). */
    val recognitionStrongerKillBonus: Int = 15,
    /** Recognition for a defender that survived the attack at critical strength (§7.1). */
    val recognitionPerCriticalSurvival: Int = 8,
    /** Recognition floor below which a formation cannot yet produce a hero. */
    val recognitionEmergenceFloor: Int = 30,
    /** Baseline per-eligible-combat emergence chance once past the floor (§22). */
    val baseEmergenceChance: Double = 0.05,
    /** Added chance per recognition point above the floor (§22). */
    val recognitionChanceScale: Double = 0.0015,
    /** Added chance per accumulated campaign-wide drought point (§7.2, §22). */
    val droughtChanceScale: Double = 0.03,
    /** Ceiling so a run of luck cannot make emergence a certainty ahead of the guarantee. */
    val maxEmergenceChance: Double = 0.6,
    /**
     * Eligible failures after which the next eligible check is guaranteed (§7.2). The drought
     * counter rises on every failed eligible roll and is what makes long stretches without a hero
     * self-correcting.
     */
    val guaranteedAfterEligibleFailures: Int = 12,
    /** Leader XP awarded per notable combat action, once a formation has a commander (§8, §27). */
    val leaderXpPerCombat: Int = 20,
    /**
     * Cumulative leader XP at which the next promotion milestone fires (§8.5). Exactly three
     * entries: [HeroNaming]'s rank ladder has four rungs, so a hero can be promoted three times.
     */
    val promotionThresholds: List<Int> = listOf(FIRST_PROMOTION_XP, SECOND_PROMOTION_XP, THIRD_PROMOTION_XP),
    /** A promising hero reaches only the first promotion this many XP sooner (§7.3). */
    val promisingFirstPromotionXpReduction: Int = 20,
    /** A distinguished hero reaches only the first promotion this many XP sooner (§7.3). */
    val distinguishedFirstPromotionXpReduction: Int = 40,
    /** An authored legendary uses the distinguished first-promotion pace; later milestones are shared. */
    val authoredLegendaryFirstPromotionXpReduction: Int = 40,
    /**
     * Cumulative leader XP at which renown rises a tier (§4.4, §8.1) — [HeroRenown.EXPERIENCED],
     * [HeroRenown.DISTINGUISHED], [HeroRenown.HERO], [HeroRenown.LEGEND] in order.
     */
    val renownThresholds: List<Int> = listOf(EXPERIENCED_XP, DISTINGUISHED_XP, HERO_XP, LEGEND_XP),
    /** Scenario index by which the reserved legendary is force-assigned if not yet organic (§6.2). */
    val legendaryGuaranteedByScenarioIndex: Int = 1,
    /** Campaign-wide notable combats by which the opening legendary hook is guaranteed. */
    val legendaryGuaranteedByQualifyingCombat: Int = 3,
    /** Added replacement chance after each opening notable combat before the guarantee. */
    val legendaryReplacementCombatScale: Double = 0.25,
    /** When a hero emerges early, the base chance it is the reserved legendary rather than procedural (§6.4). */
    val legendaryReplacementBaseChance: Double = 0.45,
    /** Added legendary-replacement chance per scenario, so the hook escalates over the first battles. */
    val legendaryReplacementScenarioScale: Double = 0.25,
) {
    /**
     * Potential changes the hero's starting pace, never their ceiling (§7.3, §26). Only the first
     * milestone is brought forward; all heroes use the same later thresholds and can reach the
     * same final rank.
     */
    fun promotionThresholdsFor(potential: HeroPotential): List<Int> {
        if (promotionThresholds.isEmpty()) return emptyList()
        val firstReduction =
            when (potential) {
                HeroPotential.LINE_OFFICER -> 0
                HeroPotential.PROMISING -> promisingFirstPromotionXpReduction
                HeroPotential.DISTINGUISHED -> distinguishedFirstPromotionXpReduction
                HeroPotential.AUTHORED_LEGENDARY -> authoredLegendaryFirstPromotionXpReduction
            }
        return promotionThresholds.mapIndexed { index, threshold ->
            if (index == 0) (threshold - firstReduction).coerceAtLeast(0) else threshold
        }
    }

    companion object {
        val DEFAULT = HeroBalance()

        private const val FIRST_PROMOTION_XP = 80
        private const val SECOND_PROMOTION_XP = 220
        private const val THIRD_PROMOTION_XP = 420

        private const val EXPERIENCED_XP = 60
        private const val DISTINGUISHED_XP = 180
        private const val HERO_XP = 380
        private const val LEGEND_XP = 600
    }
}
