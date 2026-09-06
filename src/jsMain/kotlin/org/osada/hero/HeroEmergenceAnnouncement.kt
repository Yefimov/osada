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
    /**
     * One unusual fact from the new officer's life path — the biography design's §13.5.
     *
     * "The new-hero dialog should reveal an unusual fact without dumping the entire personnel
     * record." So this is at most a sentence, chosen for rarity, and the full four-sentence
     * biography stays in the dossier. Null for a hero whose life path has nothing uncommon in it,
     * which is most of them and is the point: a fact everyone has is not a discovery.
     */
    val biographyHighlight: String?,
    val effects: List<Pair<String, String>>,
    val potential: HeroPotential,
    val guaranteed: Boolean,
    val portrait: List<String>,
    val portraitSeed: Int,
    /** Painted portrait asset for an authored hero; null means render [portrait] instead. */
    val portraitArt: String? = null,
) {
    companion object {
        /**
         * §13.5's "unusual fact": a rare prior conflict if the officer has one, otherwise their
         * pre-war occupation.
         *
         * In that order because §8.6 says the prior-service facts "should be uncommon enough to
         * feel discovered", while an occupation is universal and merely period-bearing. A hero with
         * neither gets nothing rather than a padded sentence about having been to school.
         */
        @Suppress("ReturnCount") // legacy hero, rare service, occupation -- three independent answers
        private fun highlight(definition: HeroDefinition): String? {
            val facts = definition.biographyFacts
            if (facts.biographyPackId == null) return null
            val female =
                definition.portrait.female ?: (PortraitComposerV2.genderFor(definition.portrait.seed) == "female")
            facts.priorServiceIds.firstOrNull()?.let { id ->
                return I18n.t(
                    "hero.emergence.highlight.service",
                    mapOf("service" to genderedFact("hero.bio.service.$id", female)),
                )
            }
            return facts.prewarProfessionId?.let { id ->
                I18n.t(
                    "hero.emergence.highlight.profession",
                    mapOf("profession" to genderedFact("hero.bio.profession.$id", female)),
                )
            }
        }

        private fun genderedFact(
            baseKey: String,
            female: Boolean,
        ): String = (if (female) I18n.tOrNull("${baseKey}_f") else null) ?: I18n.t(baseKey)

        /** Assembles the announcement from the emergence result and the formation it attached to. */
        internal fun from(
            emerged: LeaderAcquisitionService.EmergenceResult.Emerged,
            formation: CoreFormation,
        ): HeroEmergenceAnnouncement {
            val background = HeroBackgrounds.byId(emerged.definition.backgroundId)
            val effects = mutableListOf<Pair<String, String>>()
            val backgroundTrait = background?.grantedTrait
            backgroundTrait?.let { trait ->
                HeroDisplay.trait(trait, "").let { effects += it.title to it.effect }
            }
            emerged.state.learnedTraitIds
                .mapNotNull { LegacyTraitMapping.fromTraitId(it) }
                // The background's trait is already the first line. A learned/signature trait that
                // happens to equal it is the SAME ability, not a second one -- listing it twice
                // read as "this commander gets Tank Killer, and also Tank Killer" (user report,
                // Pham Van Cuong on Raid at Binh Gia). [HeroDossierAssembler] filters the same way;
                // this box did not, which is why the dossier was right and the announcement wrong.
                .filter { it != backgroundTrait }
                .forEach { trait -> HeroDisplay.trait(trait, "").let { effects += it.title to it.effect } }
            return HeroEmergenceAnnouncement(
                formationId = formation.id,
                formationName = formation.displayName,
                heroName = emerged.definition.displayName,
                rankId = emerged.state.rankId,
                characterLabel = I18n.t("hero.emergence.event.${emerged.event.eventId}.character"),
                reason = I18n.t("hero.emergence.event.${emerged.event.eventId}.reason"),
                backgroundTitle = background?.let { I18n.t("hero.background.${it.id}.title") },
                biographyHighlight = highlight(emerged.definition),
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
