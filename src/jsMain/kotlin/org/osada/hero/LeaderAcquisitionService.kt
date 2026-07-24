package org.osada.hero

/**
 * Decides whether a leaderless formation produces an officer — design brief §7.2, §22.
 *
 * This is the deliberate replacement for `Leaders.generateLeaderWithChance`, whose three
 * divergences from Panzer Marshal (and its latent crash above level 5) are catalogued in
 * `docs/leaders.md` §5 and were left unrepaired precisely because this supersedes them. The old
 * per-combat lottery is gone; acquisition is now a function of accumulated recognition plus
 * campaign-wide drought protection, so veterans reliably grow commanders (§29.3, §29.4).
 *
 * ## Purity and determinism
 *
 * [tryGenerate] mutates nothing. It reads the formation and the campaign drought and returns a
 * verdict; [HeroCampaign] owns applying it. The one piece of state it depends on for its seed is
 * [CoreFormation.emergenceChecks], a per-formation counter persisted in the save. Seeding from a
 * saved counter is what satisfies §29.17: reloading an autosave taken before the combat replays the
 * counter at its old value, so the same check produces the same verdict and the same officer — a
 * reload cannot reroll a better hero.
 *
 * Legendary reservation (§6, §23) is Phase 5; the [EmergenceResult] shape leaves room for it but
 * every officer this phase produces is procedural.
 */
internal object LeaderAcquisitionService {
    /** Everything a check needs, assembled by the caller from combat + campaign state. */
    data class EmergenceContext(
        val campaignId: String,
        val scenarioIndex: Int,
        val formation: CoreFormation,
        val event: EmergenceEvent,
        val campaignDrought: Int,
        val country: Int,
        val unitExperience: Int,
        val serviceYear: Int?,
        /** The reserved legendary compatible with this formation, if any (§6, §23). */
        val reservedLegendary: LegendaryHeroPool.LegendaryHero? = null,
        /** True when no authored hero matched the campaign/nation/date/class reservation request. */
        val proceduralLegendaryFallback: Boolean = false,
        /** Notable leaderless-formation combats accumulated across the opening campaign. */
        val earlyLegendaryQualifyingCombats: Int = 0,
    )

    sealed interface EmergenceResult {
        /**
         * No officer this time. [eligible] distinguishes a failed roll (which counts toward drought)
         * from a formation that was not a candidate at all (already led, or below the floor).
         */
        data class NoLeader(
            val eligible: Boolean,
        ) : EmergenceResult

        data class Emerged(
            val definition: HeroDefinition,
            val state: HeroState,
            val event: EmergenceEvent,
            val guaranteed: Boolean,
            /** True when the officer is the campaign's reserved authored legendary (§6). */
            val legendary: Boolean = false,
            /** Authored or procedural, this emergence fulfilled the reserved early-hero hook. */
            val consumedReservation: Boolean = false,
        ) : EmergenceResult
    }

    fun tryGenerate(
        context: EmergenceContext,
        balance: HeroBalance = HeroBalance.DEFAULT,
    ): EmergenceResult {
        val formation = context.formation
        if (formation.assignedHeroId != null) return EmergenceResult.NoLeader(eligible = false)

        resolveEarlyLegendary(context, balance)?.let { return it }

        // Recognition 30 is the start of the ordinary officer lottery, not a completed progress bar.
        val eligible = formation.recognition >= balance.recognitionEmergenceFloor
        if (!eligible) return EmergenceResult.NoLeader(eligible = false)
        return resolveRegularEligible(context, balance)
    }

    /** The campaign's reserved opening character has its own onboarding roll: 45%, 70%, then 100%. */
    private fun resolveEarlyLegendary(
        context: EmergenceContext,
        balance: HeroBalance,
    ): EmergenceResult.Emerged? {
        val legendary = context.reservedLegendary
        val reservedPending = legendary != null || context.proceduralLegendaryFallback
        if (!reservedPending || context.earlyLegendaryQualifyingCombats <= 0) return null
        val forced =
            context.scenarioIndex >= balance.legendaryGuaranteedByScenarioIndex ||
                context.earlyLegendaryQualifyingCombats >= balance.legendaryGuaranteedByQualifyingCombat
        val rolled =
            forced ||
                SeededRandom(seed(context, "early-legendary")).roll(earlyLegendaryChance(context, balance))
        if (!rolled) return null
        return if (legendary != null) emergeLegendary(context, legendary) else emergeProceduralFallback(context)
    }

    /** Ordinary procedural-officer roll after the recognition floor has been reached. */
    private fun resolveRegularEligible(
        context: EmergenceContext,
        balance: HeroBalance,
    ): EmergenceResult {
        val guaranteed = context.campaignDrought >= balance.guaranteedAfterEligibleFailures
        val chance = chance(context.formation.recognition, context.campaignDrought, balance)
        val rolled = guaranteed || SeededRandom(rollSeed(context)).roll(chance)
        return if (!rolled) {
            EmergenceResult.NoLeader(eligible = true)
        } else {
            val (definition, state) = ProceduralHeroGenerator.generate(request(context))
            EmergenceResult.Emerged(definition, state, context.event, guaranteed)
        }
    }

    private fun emergeLegendary(
        context: EmergenceContext,
        hero: LegendaryHeroPool.LegendaryHero,
    ): EmergenceResult.Emerged {
        val (definition, state) = LegendaryHeroPool.build(hero, request(context))
        return EmergenceResult.Emerged(
            definition,
            state,
            context.event,
            guaranteed = false,
            legendary = true,
            consumedReservation = true,
        )
    }

    private fun emergeProceduralFallback(context: EmergenceContext): EmergenceResult.Emerged {
        val (definition, state) = ProceduralHeroGenerator.generate(request(context))
        return EmergenceResult.Emerged(
            definition = definition,
            state = state.copy(potential = HeroPotential.DISTINGUISHED, renown = HeroRenown.EXPERIENCED),
            event = context.event,
            guaranteed = false,
            consumedReservation = true,
        )
    }

    private fun earlyLegendaryChance(
        context: EmergenceContext,
        balance: HeroBalance,
    ): Double =
        (
            balance.legendaryReplacementBaseChance +
                (context.earlyLegendaryQualifyingCombats - 1).coerceAtLeast(0) *
                balance.legendaryReplacementCombatScale +
                context.scenarioIndex * balance.legendaryReplacementScenarioScale
        ).coerceIn(0.0, 1.0)

    /**
     * Emergence chance (§22). Rises with recognition above the floor and with accumulated drought,
     * capped so it never becomes a silent certainty ahead of the explicit guarantee.
     */
    fun chance(
        recognition: Int,
        drought: Int,
        balance: HeroBalance = HeroBalance.DEFAULT,
    ): Double {
        val overFloor = (recognition - balance.recognitionEmergenceFloor).coerceAtLeast(0)
        val raw =
            balance.baseEmergenceChance +
                overFloor * balance.recognitionChanceScale +
                drought * balance.droughtChanceScale
        return raw.coerceIn(0.0, balance.maxEmergenceChance)
    }

    private fun request(context: EmergenceContext): ProceduralHeroGenerator.Request =
        ProceduralHeroGenerator.Request(
            heroId = HeroId("H-${context.formation.id.value}-${context.formation.emergenceChecks}"),
            seed = heroSeed(context),
            country = context.country,
            unitClass = context.formation.unitClass,
            unitExperience = context.unitExperience,
            event = context.event,
            formationId = context.formation.id,
            serviceYear = context.serviceYear,
        )

    // Distinct salts so the roll and the officer's identity draw from unrelated streams, both keyed
    // on the persisted per-formation counter for reload-stable results.
    private fun rollSeed(context: EmergenceContext): Int = seed(context, "roll")

    private fun heroSeed(context: EmergenceContext): Int = seed(context, "hero")

    private fun seed(
        context: EmergenceContext,
        salt: String,
    ): Int =
        SeededRandom.seedFrom(
            context.campaignId,
            context.formation.id.value,
            salt,
            context.formation.emergenceChecks.toString(),
        )
}
