package org.osada.hero

import org.osada.i18n.I18n

/**
 * Names for the ids a service or formation event references — biography design §13.3/§13.4.
 *
 * Lineage is STORED as ids (§5.3, so two brigades sharing a display name cannot be confused) and
 * has to be DISPLAYED as people, which needs a lookup the roster has and the assembler does not.
 * Passing it in keeps [HeroDossierAssembler] pure; an empty directory simply renders the lines
 * without names, which is what a hero archived from an older save has.
 */
data class HeroDirectory(
    val heroNames: Map<String, String> = emptyMap(),
    val formationNames: Map<String, String> = emptyMap(),
) {
    fun hero(id: HeroId?): String? = id?.let { heroNames[it.value] }

    fun formation(id: FormationId?): String? = id?.let { formationNames[it.value] }

    companion object {
        val EMPTY = HeroDirectory()
    }
}

/**
 * One conferral of a highest distinction, ready to render — §12.4/§12.5.
 *
 * [goldStars] is a COUNT of the same distinction, not a stack of modifiers (§12.4's closing
 * sentence), and is zero for an award dated before the Gold Star existed so the UI cannot show one
 * the recipient could not have been given.
 */
data class HeroDistinctionLine(
    val title: String,
    val components: String?,
    val citation: String,
    val goldStars: Int,
)

/**
 * The dossier's TEXT builders — the biography, the service chronology, the associations, the
 * tenure label and the highest distinction.
 *
 * Split out of [HeroDossierAssembler] for the project's functions-per-object budget, which the
 * biography design's five new sections pushed it past. The seam is real rather than arbitrary:
 * everything here turns stored ids into localized prose, while the assembler decides which parts
 * of a hero belong on which tab.
 */
internal object HeroDossierLines {
    /** §13.1: the biography, rendered from stored ids at display time. */
    fun personalRecord(
        definition: HeroDefinition,
        state: HeroState,
    ): List<String> =
        HeroBiographyNarrator.narrate(
            definition.biographyFacts,
            state.rankId,
            definition.portrait.seed,
            definition.portrait.female,
        )

    /**
     * §13.2: the chronology alone — the biography lives in [personalRecord].
     *
     * An event that names a formation says which one, so a Service Record spanning three postings
     * reads as a career rather than as a list of things that happened somewhere.
     */
    fun serviceRecord(
        state: HeroState,
        directory: HeroDirectory,
        promotionsAwarded: Int,
    ): List<String> =
        buildList {
            state.serviceEvents.forEach { event ->
                val where = directory.formation(event.formationId)
                val subject =
                    if (where == null) {
                        HeroEventDisplay.title(event.eventId)
                    } else {
                        I18n.t(
                            "hero.event.at_formation",
                            mapOf("event" to HeroEventDisplay.title(event.eventId), "formation" to where),
                        )
                    }
                add(
                    subject +
                        HeroEventDisplay.context(event.scenarioId, event.turn, event.date, event.location) + ".",
                )
            }
            if (promotionsAwarded > 0) {
                add(I18n.t("hero.service.promotions", mapOf("count" to promotionsAwarded)))
            }
        }

    /** §5.2's label for the officer's relationship with the formation they hold now. */
    fun tenure(state: HeroState): String = I18n.t("hero.tenure.${HeroFamiliarity.tenureFor(state).name.lowercase()}")

    /**
     * §13.4: one line per formal association, phrased from THIS officer's side.
     *
     * An association whose other officer is not in the directory is dropped rather than rendered
     * with a blank name — an archived hero can outlive the roster that named their endorser.
     */
    fun associations(
        state: HeroState,
        directory: HeroDirectory,
    ): List<String> =
        state.associations.mapNotNull { association ->
            val other = directory.hero(association.otherHeroId) ?: return@mapNotNull null
            I18n.t(
                "hero.association.${association.type.name.lowercase()}",
                mapOf("commander" to other, "scenario" to association.scenarioId),
            )
        }

    /**
     * §12.4/§12.5: the title, its period-correct components, and a citation built only from what
     * the triggering event recorded.
     *
     * The citation names the scenario and the date and nothing else. §12.5 forbids inventing
     * "rescued commanders, numbers of vehicles, locations, or acts the engine did not observe", so
     * a fact absent from the conferral is absent from the sentence.
     */
    fun distinction(distinction: HeroDistinction): HeroDistinctionLine {
        val goldStar = HeroDistinctions.includesGoldStar(distinction)
        return HeroDistinctionLine(
            title = I18n.t("hero.distinction.${distinction.distinctionId}.title"),
            components =
                if (goldStar) I18n.t("hero.distinction.${distinction.distinctionId}.components") else null,
            citation =
                I18n.t(
                    if (distinction.posthumous) {
                        "hero.distinction.citation.posthumous"
                    } else {
                        "hero.distinction.citation"
                    },
                    mapOf(
                        "date" to (distinction.date ?: distinction.scenarioId),
                        "scenario" to distinction.scenarioId,
                    ),
                ),
            goldStars = if (goldStar) distinction.sequence else 0,
        )
    }

    /**
     * §13.3: a formation's history names the officer each entry is about, so the tab shows a
     * succession rather than an anonymous run of "commander departed" lines.
     */
    fun formationHistory(
        formation: CoreFormation,
        directory: HeroDirectory,
    ): List<String> =
        formation.history.map { event ->
            val who = directory.hero(event.heroId)
            val title =
                if (who == null) {
                    HeroEventDisplay.title(event.eventId)
                } else {
                    I18n.t(
                        "hero.event.by_commander",
                        mapOf("event" to HeroEventDisplay.title(event.eventId), "commander" to who),
                    )
                }
            title + HeroEventDisplay.context(event.scenarioId, event.turn, event.date, event.location)
        }
}
