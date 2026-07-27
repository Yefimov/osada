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
    fun rank(rankId: String): String =
        rankId.split('_', ' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

    fun potential(p: HeroPotential): String =
        when (p) {
            HeroPotential.LINE_OFFICER -> "Line Officer"
            HeroPotential.PROMISING -> "Promising Officer"
            HeroPotential.DISTINGUISHED -> "Distinguished Officer"
            HeroPotential.AUTHORED_LEGENDARY -> "Legendary"
        }

    fun renown(r: HeroRenown): String =
        when (r) {
            HeroRenown.UNKNOWN -> "Unknown"
            HeroRenown.EXPERIENCED -> "Experienced"
            HeroRenown.DISTINGUISHED -> "Distinguished"
            HeroRenown.HERO -> "Hero"
            HeroRenown.LEGEND -> "Legend"
        }

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

    fun status(s: HeroStatus): String =
        when (s) {
            HeroStatus.ACTIVE -> "Active"
            HeroStatus.RESERVE -> "Reserve"
            HeroStatus.WOUNDED -> "Wounded"
            HeroStatus.SERIOUSLY_WOUNDED -> "Seriously Wounded"
            HeroStatus.MISSING -> "Missing in Action"
            HeroStatus.CAPTURED -> "Captured"
            HeroStatus.RETIRED -> "Retired"
            HeroStatus.KILLED -> "Killed in Action"
        }

    /** The roster tab a status belongs under (§14.3). */
    fun rosterTab(s: HeroStatus): String =
        when (s) {
            HeroStatus.ACTIVE -> "Active"
            HeroStatus.RESERVE, HeroStatus.RETIRED -> "Reserve"
            HeroStatus.WOUNDED, HeroStatus.SERIOUSLY_WOUNDED -> "Wounded"
            HeroStatus.MISSING, HeroStatus.CAPTURED -> "Missing"
            HeroStatus.KILLED -> "Fallen"
        }

    /** The tab order the roster renders (§14.3). */
    val ROSTER_TABS = listOf("Active", "Reserve", "Wounded", "Missing", "Fallen")

    fun disposition(d: HeroCasualtyService.Disposition): String =
        when (d) {
            HeroCasualtyService.Disposition.EVACUATED -> "Evacuated to reserve"
            HeroCasualtyService.Disposition.LIGHTLY_WOUNDED -> "Lightly wounded"
            HeroCasualtyService.Disposition.SERIOUSLY_WOUNDED -> "Seriously wounded"
            HeroCasualtyService.Disposition.MISSING -> "Missing in action"
            HeroCasualtyService.Disposition.CAPTURED -> "Captured"
            HeroCasualtyService.Disposition.KILLED -> "Killed in action"
        }

    fun injury(injuryId: String): String =
        when (injuryId) {
            HeroCasualtyService.LIGHT_WOUND_ID -> "Light wound"
            HeroCasualtyService.SERIOUS_WOUND_ID -> "Serious wound (permanent)"
            else -> injuryId
        }

    fun trait(
        leader: LeaderType,
        source: String,
    ): HeroTraitLine {
        val (title, effect) = Leaders.description[leader] ?: (leader.name to "")
        return HeroTraitLine(title = title, effect = effect, activation = activation(leader), source = source)
    }

    /** A short activation condition for a trait, so no bonus is unexplained (§26). */
    @Suppress("CyclomaticComplexMethod")
    private fun activation(leader: LeaderType): String =
        when (leader) {
            LeaderType.TENACIOUS_DEFENSE, LeaderType.DETERMINED_DEFENSE, LeaderType.FEROCIOUS_DEFENSE,
            LeaderType.RESILIENCE,
            -> "While defending."
            LeaderType.AGGRESSIVE_ATTACK, LeaderType.OVERWHELMING_ATTACK, LeaderType.FIRST_STRIKE,
            -> "While attacking."
            LeaderType.TANK_KILLER -> "When engaging armored targets."
            LeaderType.STREET_FIGHTER -> "While fighting in urban terrain."
            LeaderType.INFILTRATION_TACTICS -> "In forest and concealment."
            LeaderType.AGGRESSIVE_MANEUVER, LeaderType.AGGRESSIVE_TANK_MANEUVER, LeaderType.SUPERIOR_MANEUVER,
            -> "Affects movement each turn."
            LeaderType.ELITE_RECON_VETERAN, LeaderType.BATTLEFIELD_INTELLIGENCE, LeaderType.SKILLED_RECONNAISSANCE,
            -> "Improves spotting each turn."
            LeaderType.MARKSMAN -> "When firing at range."
            LeaderType.SKILLED_INTERCEPTOR -> "When intercepting aircraft."
            else -> "Passive."
        }
}

/** Assembles the read-side views (pure). One place turns roster records into UI-ready data. */
object HeroDossierAssembler {
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
                backgroundTrait?.let { add(HeroDisplay.trait(it, "Background")) }
                state.learnedTraitIds
                    .mapNotNull(LegacyTraitMapping::fromTraitId)
                    .filter { it != backgroundTrait }
                    .forEach { trait ->
                        val isSignature = LegacyTraitMapping.toTraitId(trait) == definition.signatureTraitId
                        add(HeroDisplay.trait(trait, if (isSignature) "Signature" else "Earned"))
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
            background = background?.let { it.title to it.description },
            traits = traits,
            attributes = attributeLines(state.attributes),
            leaderExperience = state.experience,
            evidence = evidenceLines(state.specializationEvidence),
            medals = state.medals.map { (HeroMedals.title(it.medalId) ?: it.medalId) to it.scenarioId },
            injuries = state.injuries.map { "${HeroDisplay.injury(it.injuryId)} — scenario ${it.scenarioId}" },
            inMemoriam = state.status == HeroStatus.KILLED,
            serviceRecord = serviceLines(state, definition),
            formation = formation?.let { formationView(it, unitExperience) },
            portrait = PortraitComposerV2.forHero(definition, state, formation?.unitClass ?: 0),
            portraitSeed = definition.portrait.seed,
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
            "Offense" to a.offense,
            "Defense" to a.defense,
            "Maneuver" to a.maneuver,
            "Coordination" to a.coordination,
        )

    private fun evidenceLines(evidence: Map<String, Int>): List<Pair<String, Int>> =
        evidence.entries
            .filter { it.value > 0 }
            .mapNotNull { e -> EvidenceCategory.byName(e.key)?.let { it.title to e.value } }
            .sortedByDescending { it.second }

    private fun serviceLines(
        state: HeroState,
        definition: HeroDefinition,
    ): List<String> =
        buildList {
            addAll(HeroBiographyNarrator.narrate(definition.biographyFacts, state.rankId, definition.portrait.seed))
            state.serviceEvents.forEach {
                add(
                    HeroEventDisplay.title(it.eventId) +
                        HeroEventDisplay.context(it.scenarioId, it.turn, it.date, it.location) + ".",
                )
            }
            if (state.promotionsAwarded > 0) add("Promotions awarded: ${state.promotionsAwarded}.")
        }
}
