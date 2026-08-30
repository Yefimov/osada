package org.osada.hero

import org.osada.i18n.I18n

/**
 * The player-facing payload of a hero-emergence event — design brief §14.1.
 *
 * A plain view-model built in the hero layer and drained by the UI, so the "presented after combat
 * resolution, never during attack animation" requirement is met by *when the UI reads it*, not by
 * the model reaching into the UI. Player-facing enum values are resolved through i18n while this
 * view model is assembled, so the message contains no untranslated emergence labels or trait effects.
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
            background?.grantedTrait?.let { trait ->
                HeroDisplay.trait(trait, "").let { effects += it.title to it.effect }
            }
            emerged.state.learnedTraitIds
                .mapNotNull { LegacyTraitMapping.fromTraitId(it) }
                .forEach { trait -> HeroDisplay.trait(trait, "").let { effects += it.title to it.effect } }
            return HeroEmergenceAnnouncement(
                formationId = formation.id,
                formationName = formation.displayName,
                heroName = emerged.definition.displayName,
                rankId = emerged.state.rankId,
                characterLabel = I18n.t("hero.emergence.event.${emerged.event.eventId}.character"),
                reason = I18n.t("hero.emergence.event.${emerged.event.eventId}.reason"),
                backgroundTitle = background?.let { I18n.t("hero.background.${it.id}.title") },
                effects = effects,
                potential = emerged.state.potential,
                guaranteed = emerged.guaranteed,
                portrait =
                    PortraitComposerV2.forHero(
                        emerged.definition,
                        emerged.state,
                        formation.unitClass,
                        formation.country,
                    ),
                portraitSeed = emerged.definition.portrait.seed,
                portraitArt = HeroPortraitArt.pathFor(emerged.definition.portrait.artId),
            )
        }
    }
}
