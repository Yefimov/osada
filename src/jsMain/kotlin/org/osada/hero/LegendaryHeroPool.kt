package org.osada.hero

import org.osada.LeaderType
import org.osada.UnitClass

/**
 * Authored legendary heroes and the early-campaign reservation logic — design brief §6, §23.
 *
 * A "legendary" here is the **authored-origin** meaning of §4.4: a handcrafted officer with a name,
 * a background, and one **rule-changing signature ability** (§6.5) — not merely bigger numbers. The
 * signature is an existing combat-honoured [LeaderType] (the same seam Phase 3 used to make the
 * "defined but unobtainable" traits reachable), so it takes effect through [HeroTraitResolver] with
 * no new combat wiring, while reading as a distinct, characterful power in the dossier.
 *
 * The starter pool is USSR · Operation Uranus, 1942. The heroes are **authored-fictional composites**
 * ([HeroOrigin.AUTHORED_FICTIONAL]) rather than named real people, so nothing risks being date- or
 * fact-incorrect (§26). Reservation filters campaign, nation, date and the player's available unit
 * classes before selecting; campaigns without an authored match receive a deterministic procedural
 * reservation rather than an incompatible Soviet officer (§23).
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
    )

    val ALL: List<LegendaryHero> =
        listOf(
            LegendaryHero(
                id = "ussr_breakthrough",
                name = "Major Dmitri Voroshin",
                campaignIds = setOf("062d.json", "camp6.json"),
                nationIds = setOf(61),
                yearRange = 1941..1943,
                compatibleUnitClasses = setOf(UnitClass.TANK.value),
                backgroundId = "armored_academy_graduate",
                signatureTrait = LeaderType.OVERWHELMING_ATTACK,
                signatureTitle = "Breakthrough Assault",
                signatureDescription = "Presses a shattered enemy without pause, opening the line.",
                startingRankId = "major",
            ),
            LegendaryHero(
                id = "ussr_stalingrad",
                name = "Sergeant Yakov Belov",
                campaignIds = setOf("062d.json", "camp6.json"),
                nationIds = setOf(61),
                yearRange = 1942..1943,
                compatibleUnitClasses = setOf(UnitClass.INFANTRY.value, UnitClass.RECON.value),
                backgroundId = "infantry_school_instructor",
                signatureTrait = LeaderType.STREET_FIGHTER,
                signatureTitle = "Stalingrad Veteran",
                signatureDescription = "Owns the rubble — deadly in the close, room-to-room fight of the city.",
                startingRankId = "captain",
            ),
            LegendaryHero(
                id = "ussr_ace",
                name = "Lieutenant Nadya Sokolova",
                campaignIds = setOf("062d.json", "camp6.json"),
                nationIds = setOf(61),
                yearRange = 1942..1944,
                compatibleUnitClasses = setOf(UnitClass.FIGHTER.value, UnitClass.TACTICAL_BOMBER.value),
                backgroundId = "fighter_squadron_leader",
                signatureTrait = LeaderType.FIRST_STRIKE,
                signatureTitle = "Ace's Advantage",
                signatureDescription = "Sees the merge first — strikes before the enemy can bring guns to bear.",
                startingRankId = "captain",
            ),
        )

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
                portrait =
                    PortraitComposerV2.composeFor(
                        seed = request.seed,
                        unitClass = request.unitClass,
                        rankId = hero.startingRankId,
                        birthYear = request.serviceYear?.let { it - LEGENDARY_AGE },
                        serviceYear = request.serviceYear,
                    ),
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
