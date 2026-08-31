package org.osada.hero

/*
 * Hero data model — design brief §17.
 *
 * Split in two along a lifetime boundary, which is the point of the design:
 *
 * - [HeroDefinition] is the hero's IDENTITY (name, portrait, biography facts, background). It is
 *   generated or authored once and never mutates, so it can be regenerated deterministically from
 *   a seed instead of being trusted from the save.
 * - [HeroState] is the hero's CAREER (rank, status, evidence, traits, assignment). It mutates
 *   constantly and is the authoritative save payload.
 *
 * Phase 1 populates identity and the trait/background parts of career. The evidence, medal,
 * injury and service-event fields exist now and are serialized now — deliberately, because the
 * save format is the expensive thing to change later (§30 asks the data model to support future
 * authored expansion even where the feature is not built yet). Nothing in Phase 1 writes to them.
 */

/** Four command competencies (§8.1). Deliberately not RPG stats — meaning is class-aware. */
data class CommandAttributes(
    val offense: Int,
    val defense: Int,
    val maneuver: Int,
    val coordination: Int,
) {
    /**
     * One point in [attribute] (§8.1: attributes rise rarely, and always with a stated reason —
     * the caller is expected to be a promotion, which is the reason).
     */
    fun increment(attribute: CommandAttribute): CommandAttributes =
        when (attribute) {
            CommandAttribute.OFFENSE -> copy(offense = offense + 1)
            CommandAttribute.DEFENSE -> copy(defense = defense + 1)
            CommandAttribute.MANEUVER -> copy(maneuver = maneuver + 1)
            CommandAttribute.COORDINATION -> copy(coordination = coordination + 1)
        }

    companion object {
        /** Neutral baseline: a competent officer with no distinguishing strength yet. */
        val BASELINE = CommandAttributes(offense = 0, defense = 0, maneuver = 0, coordination = 0)
    }
}

/**
 * Structured biography facts (§16). Rendered into prose at display time rather than stored as
 * text, so the biography stays localizable and re-renderable.
 *
 * All fields are nullable because Phase 1's migration path knows almost nothing about a hero it
 * is reconstructing from a bare integer — it has the unit's class and experience and nothing else.
 * A null field renders as an omitted clause, not as "unknown".
 */
data class HeroBiographyFacts(
    val birthYear: Int? = null,
    val birthplaceId: String? = null,
    val socialBackgroundId: String? = null,
    val prewarProfessionId: String? = null,
    val militaryEducationId: String? = null,
    val priorServiceId: String? = null,
    val emergenceEventId: String,
)

/**
 * Portrait as a LAYER RECIPE plus its seed (§15.3), never as a rendered bitmap — so a portrait can
 * be re-rendered at a new resolution or art style, and so a save stays small.
 *
 * Phase 1 stores an empty [layerIds] with a live [seed]: the layered portrait art described in
 * §15.2 does not exist in the repository yet, so rendering falls back to a branch/rank placeholder.
 * When the art lands, the same seed reproduces a stable portrait for heroes already in saves.
 *
 * [artId] is the escape hatch for an **authored** hero whose face is a painted asset rather than a
 * layer recipe (see [HeroPortraitArt]): the composer still fills [layerIds], so a missing or renamed
 * asset degrades to the procedural face instead of to an empty frame. It is an id, not a path — the
 * directory layout stays a rendering detail and old saves keep resolving after a move.
 *
 * [female] is likewise an authored override. Procedural heroes leave it null and the gender is
 * rolled from [seed] ([PortraitComposerV2.genderFor]); an authored hero states it, because the
 * painting already decided and the biography's inflections must agree with the face (§4.11).
 *
 * [poolId] remembers the national/era recipe even when [layerIds] is intentionally empty. That is
 * important for unsupported settings such as Spartacus: after the hero leaves a formation there
 * is no country to derive from, but the saved `none` verdict must still select the monogram fallback
 * instead of silently turning the hero into a Soviet officer.
 */
data class PortraitComposition(
    val seed: Int,
    val layerIds: List<String> = emptyList(),
    val artId: String? = null,
    val female: Boolean? = null,
    val poolId: String? = null,
)

/**
 * Immutable hero identity (§17).
 *
 * [backgroundId] is the explicit professional background that REPLACES the old hidden class
 * signature trait — see [HeroBackgrounds] for why that mattered.
 */
data class HeroDefinition(
    val id: HeroId,
    val origin: HeroOrigin,
    val displayName: String,
    val backgroundId: String,
    val biographyFacts: HeroBiographyFacts,
    val portrait: PortraitComposition,
    val signatureTraitId: String? = null,
)

/** A one-time career decoration. Medal ids are additive so old saves remain valid as the catalogue grows. */
data class HeroMedal(
    val medalId: String,
    val scenarioId: String,
)

/** A wound and its consequence. Reserved for a later phase (§11). */
data class HeroInjury(
    val injuryId: String,
    val scenarioId: String,
    val permanent: Boolean,
)

/** A structured service-record entry (§10, §19). Reserved for a later phase. */
data class HeroEvent(
    val eventId: String,
    val scenarioId: String,
    val turn: Int,
    val date: String? = null,
    val location: String? = null,
)

/**
 * Mutable hero career state (§17).
 *
 * [learnedTraitIds] holds trait ids in [LegacyTraitMapping]'s string form during the compatibility
 * period, so a migrated hero's rolled trait survives without inventing a Phase 3 trait catalog
 * ahead of time. As of Phase 3 it also holds ids from [HeroTraitCatalog] chosen at promotion.
 *
 * [promotionsAwarded] counts milestones already consumed (§8.5), so [HeroPromotionService] knows
 * which [HeroBalance.promotionThresholds] entry is next and never re-offers a milestone the hero
 * already passed.
 *
 * [settlingScenarioId] / [settlingUntilTurn] are the cost of a commander transfer (§1.10): an
 * officer handed a formation they have never commanded needs time to learn it, and until
 * [settlingUntilTurn] none of their traits apply to it ([HeroTraitResolver]). Stored as a
 * (scenario, turn) pair rather than a countdown so it cannot drift: turn numbers restart at 1 each
 * battle, so a bare "turns remaining" would have to be ticked down by someone, and a scenario
 * transition or a reload would be one more place to get that wrong. A stale scenario id simply
 * reads as settled.
 */
data class HeroState(
    val heroId: HeroId,
    val rankId: String,
    val status: HeroStatus = HeroStatus.ACTIVE,
    val potential: HeroPotential = HeroPotential.LINE_OFFICER,
    val renown: HeroRenown = HeroRenown.UNKNOWN,
    val attributes: CommandAttributes = CommandAttributes.BASELINE,
    val experience: Int = 0,
    val assignedFormationId: FormationId? = null,
    val learnedTraitIds: Set<String> = emptySet(),
    val specializationEvidence: Map<String, Int> = emptyMap(),
    val medals: List<HeroMedal> = emptyList(),
    val injuries: List<HeroInjury> = emptyList(),
    val nicknameId: String? = null,
    val serviceEvents: List<HeroEvent> = emptyList(),
    val promotionsAwarded: Int = 0,
    val settlingScenarioId: String? = null,
    val settlingUntilTurn: Int = 0,
)
