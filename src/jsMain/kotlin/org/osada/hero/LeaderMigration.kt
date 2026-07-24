package org.osada.hero

import org.osada.model.GameUnit
import org.osada.model.Player

/**
 * Save migration — design brief §25.
 *
 * Runs once per campaign load, after the core roster has been restored. Two jobs:
 *
 * 1. **Every core unit gets a [CoreFormation] record.** Including units with no leader — a
 *    formation is the thing that accumulates recognition and eventually produces an officer
 *    (§25, "existing unit with leader == -1"), so it has to exist before anything can happen to it.
 * 2. **Every core unit that already carries a legacy leader integer gets a [HeroState].**
 *
 * ## What the reconstruction preserves
 *
 * The old integer granted two effective traits (`docs/leaders.md` §1). Both survive, by different
 * routes:
 *
 * - the **rolled trait** becomes a learned trait, carried verbatim as a [LegacyTraitMapping] id;
 * - the **hidden class-signature trait** becomes an explicit professional background
 *   ([HeroBackgrounds]) that grants the same effect with a stated reason.
 *
 * So a migrated unit fights exactly as it did before the migration — verified by
 * `HeroMigrationTest` — while the second trait stops being invisible.
 *
 * ## Idempotence and determinism
 *
 * Safe to run repeatedly: a formation that already has a hero is skipped, so re-loading a save
 * that was written after migration does not produce a second officer. Every generated value is
 * seeded from campaign id + formation id ([SeededRandom.seedFrom]), never from global RNG, so the
 * same save always reconstructs the same officer (§7.4, §29.17).
 */
internal object LeaderMigration {
    private const val PROMISING_EXPERIENCE = 300

    /** Reconstructs formations and legacy heroes for [player]'s core roster. */
    fun migrate(
        player: Player,
        campaignId: String,
    ) {
        val roster = HeroCampaign.roster()
        player.getCoreUnitList().forEach { unit ->
            val formationId = FormationIdentity.of(unit) ?: return@forEach
            val formation = roster.formation(formationId) ?: formationFor(unit, formationId).also(roster::putFormation)
            if (formation.assignedHeroId == null && unit.leader != -1) {
                attachLegacyHero(roster, formation, unit, campaignId)
            }
        }
    }

    private fun formationFor(
        unit: GameUnit,
        formationId: FormationId,
    ): CoreFormation =
        CoreFormation(
            id = formationId,
            ownerId = unit.owner,
            country = unit.player?.country ?: -1,
            displayName = unit.customName ?: unit.unitData().name,
            currentEquipmentId = unit.eqid,
            unitClass = unit.unitData().uclass,
        )

    private fun attachLegacyHero(
        roster: HeroRoster,
        formation: CoreFormation,
        unit: GameUnit,
        campaignId: String,
    ) {
        val seed = SeededRandom.seedFrom(campaignId, formation.id.value)
        val heroId = HeroId("H-${formation.id.value}")
        val background = HeroBackgrounds.forUnitClass(formation.unitClass)
        roster.putHero(
            definition(heroId, seed, background),
            state(heroId, formation.id, unit),
        )
        roster.putFormation(formation.copy(assignedHeroId = heroId))
    }

    private fun definition(
        heroId: HeroId,
        seed: Int,
        background: HeroBackgrounds.Background?,
    ): HeroDefinition =
        HeroDefinition(
            id = heroId,
            origin = HeroOrigin.PROCEDURAL,
            displayName = HeroNaming.nameFor(seed),
            // A class with no signature trait (the thirteen that could never get a leader) yields
            // no background, which is correct: there is no hidden effect there to attribute.
            backgroundId = background?.id.orEmpty(),
            biographyFacts = HeroBiographyFacts(emergenceEventId = "migrated_from_legacy_leader"),
            portrait = PortraitComposition(seed = seed),
        )

    /**
     * Career state. [HeroPotential] comes from the formation's veteran experience per §25 — the
     * only signal a reconstructed hero has. It is a starting quality, not a ceiling (§7.3).
     */
    private fun state(
        heroId: HeroId,
        formationId: FormationId,
        unit: GameUnit,
    ): HeroState =
        HeroState(
            heroId = heroId,
            rankId = HeroNaming.rankForExperience(unit.experience),
            potential =
                if (unit.experience >= PROMISING_EXPERIENCE) HeroPotential.PROMISING else HeroPotential.LINE_OFFICER,
            assignedFormationId = formationId,
            // The rolled trait, carried verbatim. Null only if the save holds a trait integer this
            // build no longer defines, in which case the hero keeps just its background.
            learnedTraitIds = setOfNotNull(LegacyTraitMapping.toTraitId(unit.leader)),
        )
}
