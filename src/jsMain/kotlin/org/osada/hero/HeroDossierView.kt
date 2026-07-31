package org.osada.hero

import org.osada.LeaderType
import org.osada.i18n.I18n
import org.osada.model.Leaders

/*
 * Read-side view-models for the Phase 4 UI (design brief §10, §14.2–14.5). These are pure data,
 * assembled from a hero's [HeroDefinition] / [HeroState] and its [CoreFormation], with every
 * player-facing string resolved here so the presenters stay dumb and the strings are localization-
 * ready in one place (§29.20). §26 forbids hidden bonuses, so a trait always carries its effect and
 * activation text.
 */

/** One trait line: its name, what it does, and when it applies (§26). */
data class HeroTraitLine(
    val title: String,
    val effect: String,
    val activation: String,
    val source: String,
)

/** The formation half of a dossier (§14.5), separate from the leader (§4.6, §9.3). */
data class FormationView(
    val name: String,
    val recognitionStatus: String,
    val unitExperience: Int?,
    val battleHonors: List<String>,
    val medals: List<String>,
    val history: List<String>,
    val attachments: List<String>,
)

/** The full leader dossier (§14.4). */
data class LeaderDossierView(
    val heroId: String,
    val name: String,
    val rank: String,
    val potential: String,
    val renown: String,
    val renownClass: String,
    val status: String,
    val nickname: String?,
    val background: Pair<String, String>?,
    val traits: List<HeroTraitLine>,
    val attributes: List<Pair<String, Int>>,
    val leaderExperience: Int,
    val evidence: List<Pair<String, Int>>,
    val medals: List<Pair<String, String>>,
    val injuries: List<String>,
    val inMemoriam: Boolean,
    val serviceRecord: List<String>,
    val formation: FormationView?,
    /** Ordered v2 portrait layer paths to stack (§15), and the seed for its palette. */
    val portrait: List<String>,
    val portraitSeed: Int,
    /** Painted portrait asset for an authored hero (§6.6/6a); null means render [portrait] instead. */
    val portraitArt: String? = null,
)

/** A single row in the campaign commander roster (§14.3). */
data class CommanderRow(
    val heroId: String,
    val name: String,
    val rank: String,
    val renown: String,
    val renownClass: String,
    val potential: String,
    val status: HeroStatus,
    val statusLabel: String,
    val formationName: String?,
    /** Worthy of the cross-campaign Hall of Fame (§14.6): a renowned, authored, or fallen commander. */
    val notable: Boolean,
)

/** Localization-ready label resolution — the one place enum/id → display text lives. */
object HeroDisplay {
    fun rank(rankId: String): String = I18n.t("hero.rank.${rankId.lowercase()}")

    fun potential(p: HeroPotential): String =
        I18n.t(
            when (p) {
                HeroPotential.LINE_OFFICER -> "hero.potential.line"
                HeroPotential.PROMISING -> "hero.potential.promising"
                HeroPotential.DISTINGUISHED -> "hero.potential.distinguished"
                HeroPotential.AUTHORED_LEGENDARY -> "hero.potential.legendary"
            },
        )

    fun renown(r: HeroRenown): String = I18n.t("hero.renown.${r.name.lowercase()}")

    /** CSS class for the renown portrait frame (`docs/design/hero-presentation.md` §1), applied
     *  identically wherever a hero's portrait appears (dossier, roster row, unit-card leader slot,
     *  Hall of Fame) so one tier means one appearance everywhere. [HeroRenown.UNKNOWN] gets no
     *  frame at all -- a tier system where every tier is decorated communicates nothing. */
    fun renownClass(r: HeroRenown): String =
        when (r) {
            HeroRenown.UNKNOWN -> ""
            HeroRenown.EXPERIENCED -> "osada-renown--experienced"
            HeroRenown.DISTINGUISHED -> "osada-renown--distinguished"
            HeroRenown.HERO -> "osada-renown--hero"
            HeroRenown.LEGEND -> "osada-renown--legend"
        }

    fun status(s: HeroStatus): String = I18n.t("hero.status.${s.name.lowercase()}")

    /** The roster tab a status belongs under (§14.3). */
    fun rosterTab(s: HeroStatus): String =
        I18n.t(
            when (s) {
                HeroStatus.ACTIVE -> "hero.roster.tab.active"
                HeroStatus.RESERVE, HeroStatus.RETIRED -> "hero.roster.tab.reserve"
                HeroStatus.WOUNDED, HeroStatus.SERIOUSLY_WOUNDED -> "hero.roster.tab.wounded"
                HeroStatus.MISSING, HeroStatus.CAPTURED -> "hero.roster.tab.missing"
                HeroStatus.KILLED -> "hero.roster.tab.fallen"
            },
        )

    /** The tab order the roster renders (§14.3). */
    val ROSTER_TABS: List<String>
        get() =
            listOf(
                I18n.t("hero.roster.tab.active"),
                I18n.t("hero.roster.tab.reserve"),
                I18n.t("hero.roster.tab.wounded"),
                I18n.t("hero.roster.tab.missing"),
                I18n.t("hero.roster.tab.fallen"),
            )

    fun disposition(d: HeroCasualtyService.Disposition): String = I18n.t("hero.disposition.${d.name.lowercase()}")

    fun injury(injuryId: String): String =
        I18n.t(
            when (injuryId) {
                HeroCasualtyService.LIGHT_WOUND_ID -> "hero.injury.light"
                HeroCasualtyService.SERIOUS_WOUND_ID -> "hero.injury.serious"
                else -> return injuryId
            },
        )

    fun trait(
        leader: LeaderType,
        source: String,
    ): HeroTraitLine {
        val key = leader.name.lowercase()
        val fallback = Leaders.description[leader] ?: (leader.name to "")
        val title = I18n.tOrNull("hero.trait.$key.title") ?: fallback.first
        val effect = I18n.tOrNull("hero.trait.$key.effect") ?: fallback.second
        return HeroTraitLine(title = title, effect = effect, activation = activation(leader), source = source)
    }

    /** A short activation condition for a trait, so no bonus is unexplained (§26). */
    @Suppress("CyclomaticComplexMethod")
    private fun activation(leader: LeaderType): String =
        when (leader) {
            LeaderType.TENACIOUS_DEFENSE, LeaderType.DETERMINED_DEFENSE, LeaderType.FEROCIOUS_DEFENSE,
            LeaderType.RESILIENCE,
                -> I18n.t("hero.trait.activation.defending")

            LeaderType.AGGRESSIVE_ATTACK, LeaderType.OVERWHELMING_ATTACK, LeaderType.FIRST_STRIKE,
                -> I18n.t("hero.trait.activation.attacking")

            LeaderType.TANK_KILLER -> I18n.t("hero.trait.activation.armored_targets")
            LeaderType.STREET_FIGHTER -> I18n.t("hero.trait.activation.urban")
            LeaderType.INFILTRATION_TACTICS -> I18n.t("hero.trait.activation.forest")
            LeaderType.AGGRESSIVE_MANEUVER, LeaderType.AGGRESSIVE_TANK_MANEUVER,
                -> I18n.t("hero.trait.activation.movement")

            LeaderType.SUPERIOR_MANEUVER -> I18n.t("hero.trait.activation.zoc")
            LeaderType.RECON_MOVEMENT -> I18n.t("hero.trait.activation.phased_movement")
            LeaderType.ELITE_RECON_VETERAN, LeaderType.BATTLEFIELD_INTELLIGENCE, LeaderType.SKILLED_RECONNAISSANCE,
                -> I18n.t("hero.trait.activation.spotting")

            LeaderType.MARKSMAN -> I18n.t("hero.trait.activation.ranged_fire")
            LeaderType.SKILLED_INTERCEPTOR -> I18n.t("hero.trait.activation.interception")
            LeaderType.SKILLED_GROUND_ATTACK -> I18n.t("hero.trait.activation.ground_attack")
            else -> I18n.t("hero.trait.activation.passive")
        }
}

/** Assembles the read-side views (pure). One place turns roster records into UI-ready data. */
object HeroDossierAssembler {
    @Suppress("LongMethod")
    fun dossier(
        definition: HeroDefinition,
        state: HeroState,
        formation: CoreFormation?,
        unitExperience: Int?,
    ): LeaderDossierView {
        val background = HeroBackgrounds.byId(definition.backgroundId)
        val backgroundTrait = background?.grantedTrait
        val traits =
            buildList {
                backgroundTrait?.let { add(HeroDisplay.trait(it, I18n.t("hero.trait.source.background"))) }
                state.learnedTraitIds
                    .mapNotNull(LegacyTraitMapping::fromTraitId)
                    .filter { it != backgroundTrait }
                    .forEach { trait ->
                        val isSignature = LegacyTraitMapping.toTraitId(trait) == definition.signatureTraitId
                        add(
                            HeroDisplay.trait(
                                trait,
                                I18n.t(
                                    if (isSignature) "hero.trait.source.signature" else "hero.trait.source.earned",
                                ),
                            ),
                        )
                    }
            }
        return LeaderDossierView(
            heroId = definition.id.value,
            name = definition.displayName,
            rank = HeroDisplay.rank(state.rankId),
            potential = HeroDisplay.potential(state.potential),
            renown = HeroDisplay.renown(state.renown),
            renownClass = HeroDisplay.renownClass(state.renown),
            status = HeroDisplay.status(state.status),
            nickname = state.nicknameId?.let(HeroNicknames::displayText),
            background =
                background?.let {
                    I18n.t("hero.background.${it.id}.title") to
                        I18n.t("hero.background.${it.id}.description")
                },
            traits = traits,
            attributes = attributeLines(state.attributes),
            leaderExperience = state.experience,
            evidence = evidenceLines(state.specializationEvidence),
            medals = state.medals.map { (HeroMedals.title(it.medalId) ?: it.medalId) to it.scenarioId },
            injuries =
                state.injuries.map {
                    I18n.t(
                        "hero.injury.record",
                        mapOf("injury" to HeroDisplay.injury(it.injuryId), "scenario" to it.scenarioId),
                    )
                },
            inMemoriam = state.status == HeroStatus.KILLED,
            serviceRecord = serviceLines(state, definition),
            formation = formation?.let { formationView(it, unitExperience) },
            portrait =
                PortraitComposerV2.forHero(
                    definition,
                    state,
                    formation?.unitClass ?: 0,
                    formation?.country,
                ),
            portraitSeed = definition.portrait.seed,
            portraitArt = HeroPortraitArt.pathFor(definition.portrait.artId),
        )
    }

    fun commanderRow(
        definition: HeroDefinition,
        state: HeroState,
        formationName: String?,
    ): CommanderRow =
        CommanderRow(
            heroId = definition.id.value,
            name = definition.displayName,
            rank = HeroDisplay.rank(state.rankId),
            renown = HeroDisplay.renown(state.renown),
            renownClass = HeroDisplay.renownClass(state.renown),
            potential = HeroDisplay.potential(state.potential),
            status = state.status,
            statusLabel = HeroDisplay.status(state.status),
            formationName = formationName,
            notable =
                state.renown == HeroRenown.HERO ||
                    state.renown == HeroRenown.LEGEND ||
                    state.potential == HeroPotential.AUTHORED_LEGENDARY ||
                    state.status == HeroStatus.KILLED,
        )

    private fun formationView(
        formation: CoreFormation,
        unitExperience: Int?,
    ): FormationView =
        FormationView(
            name = formation.displayName,
            recognitionStatus =
                RecognitionService.coarseStatus(formation) ?: I18n.t("hero.recognition.status.commander_assigned"),
            unitExperience = unitExperience,
            battleHonors = formation.battleHonors,
            medals = formation.medals.map { it.medalId },
            history =
                formation.history.map {
                    HeroEventDisplay.title(it.eventId) +
                        HeroEventDisplay.context(it.scenarioId, it.turn, it.date, it.location)
                },
            attachments = formation.attachmentIds,
        )

    private fun attributeLines(a: CommandAttributes): List<Pair<String, Int>> =
        listOf(
            I18n.t("hero.attribute.offense") to a.offense,
            I18n.t("hero.attribute.defense") to a.defense,
            I18n.t("hero.attribute.maneuver") to a.maneuver,
            I18n.t("hero.attribute.coordination") to a.coordination,
        )

    private fun evidenceLines(evidence: Map<String, Int>): List<Pair<String, Int>> =
        evidence.entries
            .filter { it.value > 0 }
            .mapNotNull { e ->
                EvidenceCategory.byName(e.key)?.let {
                    I18n.t("hero.evidence.${it.name.lowercase()}") to e.value
                }
            }.sortedByDescending { it.second }

    private fun serviceLines(
        state: HeroState,
        definition: HeroDefinition,
    ): List<String> =
        buildList {
            addAll(
                HeroBiographyNarrator.narrate(
                    definition.biographyFacts,
                    state.rankId,
                    definition.portrait.seed,
                    definition.portrait.female,
                ),
            )
            state.serviceEvents.forEach {
                add(
                    HeroEventDisplay.title(it.eventId) +
                        HeroEventDisplay.context(it.scenarioId, it.turn, it.date, it.location) + ".",
                )
            }
            if (state.promotionsAwarded > 0) {
                add(
                    I18n.t(
                        "hero.service.promotions",
                        mapOf("count" to state.promotionsAwarded),
                    ),
                )
            }
        }
}
