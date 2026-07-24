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
 */
data class PortraitComposition(
    val seed: Int,
    val layerIds: List<String> = emptyList(),
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

/** A decoration. Reserved for a later phase; nothing in Phase 1 awards one. */
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
)
