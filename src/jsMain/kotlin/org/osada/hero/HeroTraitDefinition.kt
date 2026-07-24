package org.osada.hero

import org.osada.LeaderType
import org.osada.UnitClass

/**
 * One entry in the data-driven promotion-choice catalogue (§20).
 *
 * [legacyTrait] is the seam that keeps this phase from touching combat code: every catalogue
 * entry grants an existing [LeaderType], which [HeroTraitResolver] already honours through
 * [LegacyTraitMapping] exactly as a migrated or Phase 2 personal trait does. `LegacyTraitMapping`
 * is not retired by this — per the phase plan it "becomes one catalogue entry among many" — this
 * catalogue is simply the layer above it that adds justification (category + evidence threshold)
 * and choice-time bookkeeping (conflicts, class compatibility) that a bare [LeaderType] has none of.
 *
 * [requiredEvidence] empty means the option is always available once eligible on class — the
 * §8.5.4 "class-general option" fallback a promotion falls back to when fewer than two specialised
 * choices are justified yet.
 */
data class HeroTraitDefinition(
    val id: String,
    val title: String,
    val categoryId: EvidenceCategory,
    val legacyTrait: LeaderType,
    val compatibleUnitClasses: Set<Int> = emptySet(),
    val requiredEvidence: Map<EvidenceCategory, Int> = emptyMap(),
)

/**
 * The catalogue itself. Every [legacyTrait] used here is either already reachable through the
 * class-signature/personal-trait paths ([HeroBackgrounds], [ProceduralHeroGenerator]) — offered
 * again here at a higher evidence bar for a formation that did not start with it — or one of the
 * traits `docs/leaders.md` §8 flags as "defined but unobtainable... nonetheless honoured in combat
 * code" (Resilience, Overwhelming Attack, Skilled Ground Attack, Street Fighter, Skilled
 * Reconnaissance). Phase 3 is what makes those five reachable at last, exactly the way the design
 * brief's §8.6 prefers: a real, already-implemented rule effect rather than a new numeric bonus
 * invented for the occasion.
 *
 * Two entries — [determinedCommand] is intentionally not one of them — carry no evidence
 * requirement, so [HeroTraitCatalog.choose] always has a fallback pair even for a hero whose
 * evidence is still thin at their first promotion.
 */
internal object HeroTraitCatalog {
    val ALL: List<HeroTraitDefinition> =
        listOf(
            HeroTraitDefinition(
                id = "determined_command",
                title = "Determined Command",
                categoryId = EvidenceCategory.DEFENSIVE_OPERATIONS,
                legacyTrait = LeaderType.DETERMINED_DEFENSE,
                requiredEvidence = mapOf(EvidenceCategory.DEFENSIVE_OPERATIONS to 30),
            ),
            HeroTraitDefinition(
                id = "hardened_command",
                title = "Hardened Command",
                categoryId = EvidenceCategory.DEFENSIVE_OPERATIONS,
                legacyTrait = LeaderType.RESILIENCE,
                requiredEvidence = mapOf(EvidenceCategory.DEFENSIVE_OPERATIONS to 60),
            ),
            HeroTraitDefinition(
                id = "aggressive_command",
                title = "Aggressive Command",
                categoryId = EvidenceCategory.OFFENSIVE_OPERATIONS,
                legacyTrait = LeaderType.AGGRESSIVE_ATTACK,
                requiredEvidence = mapOf(EvidenceCategory.OFFENSIVE_OPERATIONS to 30),
            ),
            HeroTraitDefinition(
                id = "overwhelming_command",
                title = "Overwhelming Command",
                categoryId = EvidenceCategory.OFFENSIVE_OPERATIONS,
                legacyTrait = LeaderType.OVERWHELMING_ATTACK,
                requiredEvidence = mapOf(EvidenceCategory.OFFENSIVE_OPERATIONS to 60),
            ),
            HeroTraitDefinition(
                id = "river_initiative",
                title = "River Initiative",
                categoryId = EvidenceCategory.RIVER_OPERATIONS,
                legacyTrait = LeaderType.FIRST_STRIKE,
                requiredEvidence = mapOf(EvidenceCategory.RIVER_OPERATIONS to 30),
            ),
            HeroTraitDefinition(
                id = "urban_veteran",
                title = "Urban Veteran",
                categoryId = EvidenceCategory.URBAN_COMBAT,
                legacyTrait = LeaderType.STREET_FIGHTER,
                requiredEvidence = mapOf(EvidenceCategory.URBAN_COMBAT to 30),
            ),
            HeroTraitDefinition(
                id = "forest_infiltrator",
                title = "Forest Infiltrator",
                categoryId = EvidenceCategory.FOREST_OPERATIONS,
                legacyTrait = LeaderType.INFILTRATION_TACTICS,
                requiredEvidence = mapOf(EvidenceCategory.FOREST_OPERATIONS to 30),
            ),
            HeroTraitDefinition(
                id = "armor_hunter",
                title = "Armor Hunter",
                categoryId = EvidenceCategory.ARMORED_COMBAT,
                legacyTrait = LeaderType.TANK_KILLER,
                compatibleUnitClasses = setOf(UnitClass.TANK.value),
                requiredEvidence = mapOf(EvidenceCategory.ARMORED_COMBAT to 30),
            ),
            HeroTraitDefinition(
                id = "steady_hand",
                title = "Steady Hand",
                categoryId = EvidenceCategory.MOBILE_WARFARE,
                legacyTrait = LeaderType.AGGRESSIVE_MANEUVER,
            ),
            HeroTraitDefinition(
                id = "veteran_instincts",
                title = "Veteran Instincts",
                categoryId = EvidenceCategory.RECONNAISSANCE,
                legacyTrait = LeaderType.BATTLEFIELD_INTELLIGENCE,
            ),
        )

    private val byId: Map<String, HeroTraitDefinition> = ALL.associateBy { it.id }

    fun byId(id: String): HeroTraitDefinition? = byId[id]

    /** Catalogue entries [hero] could still learn, given its background and its formation's class. */
    fun eligibleFor(
        hero: HeroState,
        backgroundTrait: LeaderType?,
        unitClass: Int,
    ): List<HeroTraitDefinition> {
        val known =
            hero.learnedTraitIds.mapNotNull(LegacyTraitMapping::fromTraitId).toSet() + setOfNotNull(backgroundTrait)
        return ALL.filter { def ->
            val classOk = def.compatibleUnitClasses.isEmpty() || unitClass in def.compatibleUnitClasses
            def.legacyTrait !in known && classOk
        }
    }

    /**
     * The two options a promotion offers (§8.5): the strongest-justified eligible traits first,
     * falling back to always-available class-general options per §8.5.4 when fewer than two
     * specialised choices are justified by evidence yet.
     */
    fun choose(
        hero: HeroState,
        backgroundTrait: LeaderType?,
        unitClass: Int,
    ): List<HeroTraitDefinition> {
        val eligible = eligibleFor(hero, backgroundTrait, unitClass)
        val evidence = hero.specializationEvidence
        val justified =
            eligible
                .filter { meetsRequirement(it, evidence) }
                .sortedWith(compareByDescending<HeroTraitDefinition> { justification(it, evidence) }.thenBy { it.id })
        val fallback = eligible.filter { it.requiredEvidence.isEmpty() }.sortedBy { it.id }
        return (justified + fallback).distinct().take(2)
    }

    private fun meetsRequirement(
        def: HeroTraitDefinition,
        evidence: Map<String, Int>,
    ): Boolean = def.requiredEvidence.all { (category, amount) -> (evidence[category.name] ?: 0) >= amount }

    private fun justification(
        def: HeroTraitDefinition,
        evidence: Map<String, Int>,
    ): Double =
        def.requiredEvidence.entries.sumOf { (category, amount) ->
            if (amount <= 0) 0.0 else (evidence[category.name] ?: 0).toDouble() / amount
        }
}
