package org.osada.hero

import org.osada.LeaderType

/**
 * Authored legendary heroes and the early-campaign reservation logic — design brief §6, §23.
 *
 * A "legendary" here is the **authored-origin** meaning of §4.4: a handcrafted officer with a name,
 * a background, and one **rule-changing signature ability** (§6.5) — not merely bigger numbers. The
 * signature is an existing combat-honoured [LeaderType] (the same seam Phase 3 used to make the
 * "defined but unobtainable" traits reachable), so it takes effect through [HeroTraitResolver] with
 * no new combat wiring, while reading as a distinct, characterful power in the dossier.
 *
 * The heroes are **authored-fictional composites** ([HeroOrigin.AUTHORED_FICTIONAL]) rather than
 * named real people, so nothing risks being date- or fact-incorrect (§26). Reservation filters
 * campaign, nation, date and the player's available unit classes before selecting; campaigns
 * without an authored match receive a deterministic procedural reservation rather than an
 * incompatible Soviet officer (§23).
 *
 * Every shipped player side has at least one candidate, and every authored legendary has a unique
 * painted portrait. [yearRange] and [campaignIds] remain per hero rather than per pool —
 * a 1919 commander must be unable to reach a 1942 campaign even when both sides call themselves the
 * Red Army. Campaign ids and [nationIds] were read from the deployed scenarios' own `player id="0"`
 * country, not from the campaign list's prose label.
 *
 * **[female] is authored, not rolled.** The painting already decided; the biography narrator and
 * name must follow it rather than the portrait seed (§4.11), so it is stored on the composition.
 */
internal object LegendaryHeroPool {
    const val PROCEDURAL_FALLBACK_ID = "procedural_early_legend"

    data class LegendaryHero(
        val id: String,
        val name: String,
        val campaignIds: Set<String>,
        val nationIds: Set<Int>,
        val yearRange: IntRange,
        val compatibleUnitClasses: Set<Int>,
        val backgroundId: String,
        val signatureTrait: LeaderType,
        val signatureTitle: String,
        val signatureDescription: String,
        val startingRankId: String,
        /** Painted portrait id; nullable only for compatibility with older callers and saves. */
        val portraitArtId: String? = null,
        val female: Boolean = false,
    )

    /** The authored roster, kept in `LegendaryHeroRoster.kt` so this object stays logic. */
    val ALL: List<LegendaryHero> = LEGENDARY_ROSTER

    private val byId: Map<String, LegendaryHero> = ALL.associateBy { it.id }

    fun byId(id: String): LegendaryHero? = byId[id]

    /** Legacy test/tool query retained; runtime reservation uses the full-context overload below. */
    fun compatible(
        hero: LegendaryHero,
        unitClass: Int,
        year: Int?,
    ): Boolean = unitClass in hero.compatibleUnitClasses && (year == null || year in hero.yearRange)

    /** Legacy test/tool reservation retained for callers that do not have campaign context. */
    fun reserve(
        campaignId: String,
        year: Int?,
    ): String? {
        val candidates = ALL.filter { year == null || year in it.yearRange }.sortedBy { it.id }
        return SeededRandom(SeededRandom.seedFrom(campaignId, "legendary_reservation")).pick(candidates)?.id
    }

    /** Whether [hero] can attach in this exact campaign context. */
    fun compatible(
        hero: LegendaryHero,
        campaignId: String,
        nationId: Int?,
        unitClass: Int,
        year: Int?,
    ): Boolean =
        normalizeCampaignId(campaignId) in hero.campaignIds &&
            nationId != null &&
            nationId in hero.nationIds &&
            unitClass in hero.compatibleUnitClasses &&
            year != null &&
            year in hero.yearRange

    /**
     * Deterministically reserves a compatible authored hero, or the procedural fallback sentinel
     * when the authored pool has no match. The returned plain string preserves the v1 save shape.
     */
    fun reserve(
        campaignId: String,
        nationId: Int?,
        year: Int?,
        availableUnitClasses: Set<Int>,
    ): String {
        val candidates =
            ALL
                .filter { hero ->
                    availableUnitClasses.any { unitClass -> compatible(hero, campaignId, nationId, unitClass, year) }
                }.sortedBy { it.id }
        return SeededRandom(SeededRandom.seedFrom(campaignId, "legendary_reservation"))
            .pick(candidates)
            ?.id
            ?: PROCEDURAL_FALLBACK_ID
    }

    fun reservationCompatible(
        reservationId: String,
        campaignId: String,
        nationId: Int?,
        year: Int?,
        availableUnitClasses: Set<Int>,
    ): Boolean =
        reservationId == PROCEDURAL_FALLBACK_ID ||
            byId(reservationId)?.let { hero ->
                availableUnitClasses.any { compatible(hero, campaignId, nationId, it, year) }
            } == true

    private fun normalizeCampaignId(campaignId: String): String =
        campaignId
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .lowercase()

    /** Builds the authored hero's identity and opening career for an emergence at [request]. */
    fun build(
        hero: LegendaryHero,
        request: ProceduralHeroGenerator.Request,
    ): Pair<HeroDefinition, HeroState> {
        val signatureId = LegacyTraitMapping.toTraitId(hero.signatureTrait)
        val definition =
            HeroDefinition(
                id = request.heroId,
                origin = HeroOrigin.AUTHORED_FICTIONAL,
                displayName = hero.name,
                backgroundId = hero.backgroundId,
                biographyFacts =
                    HeroBiographyFacts(
                        birthYear = request.serviceYear?.let { it - LEGENDARY_AGE },
                        prewarProfessionId = hero.backgroundId,
                        emergenceEventId = request.event.eventId,
                    ),
                // The composed layer stack is kept even though a painting exists: it is the fallback
                // if the asset is ever missing (see HeroPortraitArt), so the dossier degrades to a
                // procedural face rather than to an empty frame.
                portrait =
                    PortraitComposerV2
                        .composeFor(
                            seed = request.seed,
                            unitClass = request.unitClass,
                            rankId = hero.startingRankId,
                            birthYear = request.serviceYear?.let { it - LEGENDARY_AGE },
                            serviceYear = request.serviceYear,
                            country = request.country,
                        ).copy(artId = hero.portraitArtId, female = hero.female),
                signatureTraitId = signatureId,
            )
        val state =
            HeroState(
                heroId = request.heroId,
                rankId = hero.startingRankId,
                potential = HeroPotential.AUTHORED_LEGENDARY,
                renown = HeroRenown.DISTINGUISHED,
                assignedFormationId = request.formationId,
                learnedTraitIds = setOf(signatureId),
            )
        return definition to state
    }

    private const val LEGENDARY_AGE = 30
}
