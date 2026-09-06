package org.osada.hero

import org.osada.LeaderType
import org.osada.hero.ProceduralHeroGenerator.UNIVERSAL_FALLBACKS
import org.osada.model.Leaders

/**
 * Builds a procedural heroic commander — design brief §8.2, §8.3, §16.
 *
 * This is the Phase 2 realisation of §29.7: "a procedural leader receives a deterministic name,
 * portrait, biography, background, and personal trait". Everything it produces is a pure function
 * of the [Request] (the request carries the seed), so the same emergence reconstructs the same
 * hero across save and reload — the §7.4 / §29.17 determinism contract.
 *
 * ## The two traits a hero starts with
 *
 * A new hero mirrors what the old system granted, but now both halves are justified:
 *
 * - a **professional background** ([HeroBackgrounds]) — the hero's training, granting the class's
 *   signature effect with a stated reason;
 * - a **personal trait** — a class-compatible [LeaderType] chosen from the [EmergenceEvent] that
 *   produced them (§8.3), so the trait argues from what just happened rather than a blind roll.
 *
 * Both are stored as [LegacyTraitMapping] ids and reach combat through [HeroTraitResolver] exactly
 * as a migrated hero's do — no new combat wiring, and the two are guaranteed distinct so the
 * hero really has two effects rather than one counted twice.
 */
internal object ProceduralHeroGenerator {
    /** Everything the generator needs, resolved by [LeaderAcquisitionService] at emergence time. */
    data class Request(
        val heroId: HeroId,
        val seed: Int,
        val country: Int,
        val unitClass: Int,
        val unitExperience: Int,
        val event: EmergenceEvent,
        val formationId: FormationId,
        /** Campaign year, for a plausible birth year; null when unknown (biography omits the clause). */
        val serviceYear: Int?,
    )

    /** Experience at which a newly emerged hero starts as [HeroPotential.PROMISING] rather than line. */
    private const val PROMISING_EXPERIENCE = 300

    /** Universal traits with an effect on any unit, used to break a personal/background collision. */
    private val UNIVERSAL_FALLBACKS =
        listOf(
            LeaderType.AGGRESSIVE_ATTACK,
            LeaderType.DETERMINED_DEFENSE,
            LeaderType.AGGRESSIVE_MANEUVER,
            LeaderType.FIRST_STRIKE,
        )

    fun generate(request: Request): Pair<HeroDefinition, HeroState> {
        val background = HeroBackgrounds.forUnitClass(request.unitClass)
        val personalTrait = resolvePersonalTrait(request, background?.grantedTrait)
        val rankId = HeroNaming.rankForExperience(request.unitExperience)
        return definition(request, background, rankId) to state(request, personalTrait, rankId)
    }

    private fun definition(
        request: Request,
        background: HeroBackgrounds.Background?,
        rankId: String,
    ): HeroDefinition =
        HeroDefinition(
            id = request.heroId,
            origin = HeroOrigin.PROCEDURAL,
            displayName = HeroNaming.nameFor(request.seed, request.country),
            backgroundId = background?.id.orEmpty(),
            // The life path REPLACES the four independent draws this generator used to make, and
            // with them the defect that `prewarProfessionId` was the military `backgroundId`
            // copied across -- a hero whose "pre-war occupation" was their officer training
            // (design 3.2's first listed gap). Occupation now comes from a civilian pool that the
            // hero's civilian schooling has already narrowed.
            biographyFacts = HeroLifePath.generate(lifePathContext(request, rankId)),
            // §15.3: store the composed layer ids and seed, not a bitmap — the portrait re-renders
            // deterministically wherever it is shown. Uses the v2 head-centric composer so
            // the stored layers match the approved redesign the game renders.
            portrait =
                PortraitComposerV2.composeFor(
                    seed = request.seed,
                    unitClass = request.unitClass,
                    rankId = rankId,
                    birthYear = HeroLifePath.birthYear(lifePathContext(request, rankId)),
                    serviceYear = request.serviceYear,
                    country = request.country,
                ),
        )

    private fun state(
        request: Request,
        personalTrait: LeaderType,
        rankId: String,
    ): HeroState {
        val potential =
            if (request.unitExperience >= PROMISING_EXPERIENCE) HeroPotential.PROMISING else HeroPotential.LINE_OFFICER
        return HeroState(
            heroId = request.heroId,
            rankId = rankId,
            potential = potential,
            renown = HeroRenown.UNKNOWN,
            assignedFormationId = request.formationId,
            learnedTraitIds = setOf(LegacyTraitMapping.toTraitId(personalTrait)),
        )
    }

    /**
     * A class-appropriate personal trait for the emergence event, never equal to the background's
     * granted trait (which would leave the hero only one effective trait).
     *
     * Resolution order: the event's [EmergenceEvent.preferredTrait] if the class's own leader list
     * allows it; otherwise a seeded pick from that list (excluding index 0, the signature the
     * background already covers); otherwise — for the classes that have no list — the preferred
     * trait directly, which [HeroTraitResolver] honours regardless of class. A collision with the
     * background is then broken against [UNIVERSAL_FALLBACKS].
     */
    private fun resolvePersonalTrait(
        request: Request,
        backgroundTrait: LeaderType?,
    ): LeaderType {
        val classList = Leaders.unitClassLeaders[request.unitClass].orEmpty()
        val rollable = classList.drop(1) // index 0 is the signature carried by the background
        val preferred = request.event.preferredTrait
        val chosen =
            when {
                preferred in rollable -> preferred
                rollable.isNotEmpty() -> SeededRandom(request.seed).pick(rollable) ?: preferred
                else -> preferred
            }
        if (chosen != backgroundTrait) return chosen
        return UNIVERSAL_FALLBACKS.firstOrNull { it != backgroundTrait } ?: chosen
    }

    /** The generation context [HeroLifePath] walks; a pure projection of the request plus the rank. */
    private fun lifePathContext(
        request: Request,
        rankId: String,
    ): HeroLifePath.Context =
        HeroLifePath.Context(
            seed = request.seed,
            country = request.country,
            serviceYear = request.serviceYear,
            unitClass = request.unitClass,
            rankId = rankId,
            emergenceEventId = request.event.eventId,
        )
}
