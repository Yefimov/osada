package org.osada.hero

import org.osada.model.Leaders

/**
 * The player-facing payload of a new-leader event — design brief §14.1.
 *
 * A plain view-model built in the hero layer and drained by the UI, so the "presented after combat
 * resolution, never during attack animation" requirement is met by *when the UI reads it*, not by
 * the model reaching into the UI. Every field is source text for a future i18n pass (§29.20); the
 * fancy portrait/dossier-button presentation of §14.1 waits on Phase 4/5 (portraits are the Phase 5
 * art blocker), so a Phase 2 event is these strings in a message dialog with a placeholder frame.
 */
data class HeroEmergenceAnnouncement(
    val formationId: FormationId,
    val formationName: String,
    val heroName: String,
    val rankId: String,
    val characterLabel: String,
    val reason: String,
    val backgroundTitle: String?,
    val effects: List<Pair<String, String>>,
    val potential: HeroPotential,
    val guaranteed: Boolean,
    val portrait: List<String>,
    val portraitSeed: Int,
    /** Painted portrait asset for an authored hero; null means render [portrait] instead. */
    val portraitArt: String? = null,
) {
    companion object {
        /** Assembles the announcement from the emergence result and the formation it attached to. */
        internal fun from(
            emerged: LeaderAcquisitionService.EmergenceResult.Emerged,
            formation: CoreFormation,
        ): HeroEmergenceAnnouncement {
            val background = HeroBackgrounds.byId(emerged.definition.backgroundId)
            val effects = mutableListOf<Pair<String, String>>()
            background?.grantedTrait?.let { Leaders.description[it]?.let(effects::add) }
            emerged.state.learnedTraitIds
                .mapNotNull { LegacyTraitMapping.fromTraitId(it) }
                .forEach { trait -> Leaders.description[trait]?.let(effects::add) }
            return HeroEmergenceAnnouncement(
                formationId = formation.id,
                formationName = formation.displayName,
                heroName = emerged.definition.displayName,
                rankId = emerged.state.rankId,
                characterLabel = emerged.event.characterLabel,
                reason = emerged.event.reason,
                backgroundTitle = background?.title,
                effects = effects,
                potential = emerged.state.potential,
                guaranteed = emerged.guaranteed,
                portrait = PortraitComposerV2.forHero(emerged.definition, emerged.state, formation.unitClass),
                portraitSeed = emerged.definition.portrait.seed,
                portraitArt = HeroPortraitArt.pathFor(emerged.definition.portrait.artId),
            )
        }
    }
}
