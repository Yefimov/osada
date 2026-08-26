package org.osada.hero

import org.osada.GameHolder
import org.osada.hero.HeroCampaign.drainAnnouncements
import org.osada.hero.HeroCampaign.reconContactsCredited
import org.osada.hero.HeroCampaign.recordCombat
import org.osada.hero.HeroCampaign.reset
import org.osada.hero.HeroCampaign.restore
import org.osada.hero.HeroCampaign.setContext
import org.osada.hero.HeroCampaign.snapshot
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.rules.Attachments
import org.osada.rules.UnitCapabilities

/**
 * Campaign-run entry point for the hero system — the direct counterpart of
 * [org.osada.campaign.CampaignNarrative], and wired into the same three places:
 * `Game.newCampaign` ([reset]), `GameStateSerializer.buildCampaignData` ([snapshot]) and
 * `GameStateRestore.restoreCampaign` ([restore]).
 *
 * Global mutable state is the existing house pattern for per-run campaign data rather than a
 * preference; the roster is scoped to one campaign run and cleared when a new one starts.
 *
 * ## Phase 2 additions
 *
 * This object also owns the acquisition loop: [setContext] records the campaign id / scenario /
 * year that combat sits too deep to know, [recordCombat] turns a combat contribution into
 * recognition and a possible officer, and [drainAnnouncements] hands finished new-leader events to
 * the UI to present after the animation. The service and generator it drives are pure; all the
 * mutation of roster, drought and formations lives here so there is one writer.
 */
@Suppress("TooManyFunctions")
internal object HeroCampaign {
    private var roster = HeroRoster()
    private var context: EmergenceCampaignContext? = null
    private val pendingAnnouncements = mutableListOf<HeroEmergenceAnnouncement>()
    private val pendingPromotions = mutableListOf<HeroPromotionAnnouncement>()
    private val pendingCasualties = mutableListOf<HeroCasualtyAnnouncement>()

    // §7.43: (scenario, formation, turn) keys already credited with a reconnaissance contact. A
    // formation that walks out of spotting range and back in banks the evidence once per turn, not
    // once per trip. RECON_CONTACT is the only achievement type not anchored to a resolved combat,
    // so it is the only one that needs its own anti-farming guard -- movement is repeatable at will
    // in a way that being shot at is not. Cleared by [reset] with the rest of the run's state.
    private val reconContactsCredited = mutableSetOf<String>()

    /** The campaign facts an emergence needs that are not on the [GameUnit]. Set on scenario load. */
    data class EmergenceCampaignContext(
        val campaignId: String,
        val scenarioIndex: Int,
        val serviceYear: Int?,
        val country: Int?,
        val availableUnitClasses: Set<Int>,
    )

    /** A new run starts with no formations, no heroes, no drought and no pending events. */
    fun reset() {
        roster = HeroRoster()
        context = null
        pendingAnnouncements.clear()
        pendingPromotions.clear()
        pendingCasualties.clear()
        reconContactsCredited.clear()
    }

    /** Records the current campaign scenario so [recordCombat] can seed and date an emergence. */
    fun setContext(
        campaignId: String,
        scenarioIndex: Int,
        serviceYear: Int? = null,
        country: Int? = null,
        availableUnitClasses: Set<Int> = emptySet(),
    ) {
        context = EmergenceCampaignContext(campaignId, scenarioIndex, serviceYear, country, availableUnitClasses)
        if (!roster.legendarySpawned) {
            val current = roster.reservedLegendary
            if (current == null ||
                !LegendaryHeroPool.reservationCompatible(
                    current,
                    campaignId,
                    country,
                    serviceYear,
                    availableUnitClasses,
                )
            ) {
                roster.reservedLegendary =
                    LegendaryHeroPool.reserve(campaignId, country, serviceYear, availableUnitClasses)
            }
        }
    }

    /** Save payload, or null when nothing has been recorded so the key can be omitted entirely. */
    fun snapshot(): dynamic = if (roster.isEmpty) null else HeroSerializer.serialize(roster)

    /** Restores from the save's `campaign.heroes` block; null (a pre-hero save) yields an empty roster. */
    fun restore(heroes: dynamic) {
        roster = HeroSerializer.deserialize(heroes)
    }

    /** The live roster, for the migration and resolver collaborators. */
    fun roster(): HeroRoster = roster

    /**
     * The hero commanding [unit]'s formation, or null when the unit is not core, has no formation
     * id, or its formation has no hero.
     *
     * This is the single lookup the combat-side adapter goes through, so "does this unit have a
     * hero?" has exactly one answer everywhere.
     */
    fun heroFor(unit: GameUnit?): HeroState? {
        val formationId = unit?.let { FormationIdentity.of(it) } ?: return null
        return roster.assignedHero(formationId)
    }

    /**
     * True when [unit] is commanded at all, by either mechanic.
     *
     * Core formations that emerge an officer through the hero system deliberately leave
     * `unit.leader` at -1 (`CombatLeaderAcquisition`), so a plain `unit.leader != -1` test now means
     * "has a LEGACY leader", not "has a commander". Anything that just wants to know whether the
     * formation is led — a map badge, a name suffix — must ask this instead, or it will decorate
     * throwaway scenario units while ignoring the campaign's actual commanders.
     */
    fun hasAnyCommander(unit: GameUnit?): Boolean = heroFor(unit) != null || (unit?.leader ?: -1) != -1

    /** The coarse recognition status for [unit]'s formation, or null when it has none/has a hero (§7.1). */
    fun recognitionStatus(unit: GameUnit?): String? {
        val formation = unit?.let { FormationIdentity.of(it) }?.let { roster.formation(it) } ?: return null
        return RecognitionService.coarseStatus(formation)
    }

    /** Visible recognition state for every leaderless formation, including one with no combat record yet. */
    fun recognitionProgress(unit: GameUnit?): RecognitionService.Progress? {
        val formationId = unit?.let(FormationIdentity::of)
        val formation = formationId?.let(roster::formation)
        return if (formationId != null && formation?.assignedHeroId == null) {
            RecognitionService.progress(formation?.recognition ?: 0, roster.drought)
        } else {
            null
        }
    }

    /** Persistent formation record for Unit Info's expanded recognition/history section. */
    fun formationFor(unit: GameUnit?): CoreFormation? = unit?.let(FormationIdentity::of)?.let(roster::formation)

    /**
     * Ensures that every persistent player formation has a campaign record even before its first
     * notable combat, and refreshes equipment/name metadata without touching recognition, history
     * or commander assignment. A changed equipment id is recorded once in the service history.
     */
    fun synchronizeFormation(unit: GameUnit): CoreFormation? {
        val formationId = FormationIdentity.of(unit) ?: return null
        val current = roster.formation(formationId)
        val history =
            if (current != null && current.currentEquipmentId != unit.eqid) {
                val oldName =
                    Equipment.getEquipment(current.currentEquipmentId)?.name ?: "#${current.currentEquipmentId}"
                val newName = unit.unitData(true).name
                current.history +
                    FormationEvent(
                        eventId = "equipment_changed",
                        scenarioId = currentScenarioLabel(),
                        turn = currentTurn(),
                        date = currentDate(),
                        location = "$oldName → $newName",
                    )
            } else {
                current?.history.orEmpty()
            }
        val synchronized =
            if (current == null) {
                CoreFormation(
                    id = formationId,
                    ownerId = unit.owner,
                    country = unit.player?.country ?: -1,
                    displayName = unit.customName ?: unit.unitData(true).name,
                    currentEquipmentId = unit.eqid,
                    unitClass = unit.unitData(true).uclass,
                    history = history,
                )
            } else {
                current.copy(
                    displayName = unit.customName ?: current.displayName,
                    currentEquipmentId = unit.eqid,
                    unitClass = unit.unitData(true).uclass,
                    history = history,
                )
            }
        roster.putFormation(synchronized)
        return synchronized
    }

    /** Adds one idempotent chronological entry to a persistent formation's service record. */
    fun recordFormationEvent(
        unit: GameUnit,
        eventId: String,
        turn: Int = currentTurn(),
        location: String? = null,
    ): CoreFormation? {
        val formationId = FormationIdentity.of(unit) ?: return null
        val formation = ensureFormation(unit, formationId)
        val event =
            FormationEvent(
                eventId,
                currentScenarioLabel(),
                turn,
                currentDate(),
                location ?: currentLocation(unit),
            )
        val previous = formation.history.lastOrNull()
        return if (previous.isSameEvent(event)) {
            formation
        } else {
            formation.copy(history = formation.history + event).also(roster::putFormation)
        }
    }

    private fun FormationEvent?.isSameEvent(other: FormationEvent): Boolean =
        this?.eventId == other.eventId &&
            this.scenarioId == other.scenarioId &&
            this.turn == other.turn &&
            this.location == other.location

    /** Assignment lookup used by the roster/dossier Locate action. */
    fun formationIdForHero(heroId: HeroId): FormationId? = roster.state(heroId)?.assignedFormationId

    /**
     * Adds [slotNumber] to [unit]'s formation's attachments (DEFERRED.md §1.4). Refuses when: the
     * formation doesn't exist (a formationless scenario/auxiliary unit never gets attachments —
     * §3.2); the active efile has attachments off or no definition for this slot number, or
     * disables it (LXF disables Bridging); the formation is already at `Attachments.MAX_PER_UNIT`;
     * or it already has this slot. This is the actual gate, not the UI —
     * `Attachments.availableSlots` is expected to only ever offer legal choices, but re-checked
     * here independently, the same discipline `HeroTransferService.transferCommander` applies. Prestige is the
     * caller's concern (`Player.purchaseAttachment` mirrors `Player.upgradeUnit`'s own pattern of
     * checking cost, calling the mutation, then deducting).
     */
    fun purchaseAttachment(
        unit: GameUnit,
        slotNumber: Int,
    ): Boolean {
        val formation = FormationIdentity.of(unit)?.let(roster::formation)
        val slot = EfileConfig.attachments()?.slots?.get(slotNumber)
        val slotId = slotNumber.toString()
        val legal =
            formation != null &&
                slot != null &&
                !slot.disabled &&
                formation.attachmentIds.size < Attachments.MAX_PER_UNIT &&
                slotId !in formation.attachmentIds
        if (formation == null || !legal) return false
        roster.putFormation(formation.copy(attachmentIds = formation.attachmentIds + slotId))
        return true
    }

    // ---------------------------------------------------------- Phase 4 read side

    /** The dossier view for [unit]'s commander (§14.2/14.4), or null when the unit has no hero. */
    fun dossier(unit: GameUnit?): LeaderDossierView? {
        val experience = unit?.experience
        val formation = unit?.let { FormationIdentity.of(it) }?.let { roster.formation(it) }
        val heroId = formation?.assignedHeroId
        val definition = heroId?.let { roster.definition(it) }
        val state = heroId?.let { roster.state(it) }
        return if (formation != null && definition != null && state != null) {
            HeroDossierAssembler.dossier(
                definition,
                state,
                formation,
                experience,
                HeroTransferService.settlingTurnsLeft(state),
            )
        } else {
            null
        }
    }

    /** The dossier for a hero by id (§14.4), opened from the roster where no deployed unit is known. */
    fun dossier(heroId: HeroId): LeaderDossierView? {
        val definition = roster.definition(heroId)
        val state = roster.state(heroId)
        val formation = state?.assignedFormationId?.let { roster.formation(it) }
        return if (definition != null && state != null) {
            HeroDossierAssembler.dossier(
                definition,
                state,
                formation,
                null,
                HeroTransferService.settlingTurnsLeft(state),
            )
        } else {
            null
        }
    }

    /** Every commander in the campaign, for the Headquarters roster (§14.3). */
    fun commanders(): List<CommanderRow> =
        roster.allDefinitions().mapNotNull { definition ->
            val state = roster.state(definition.id) ?: return@mapNotNull null
            val formationName = state.assignedFormationId?.let { roster.formation(it)?.displayName }
            HeroDossierAssembler.commanderRow(
                definition,
                state,
                formationName,
                HeroTransferService.settlingTurnsLeft(state),
            )
        }

    /**
     * Feeds one core unit's part in a resolved combat into the hero system and reports whether an
     * officer emerged this call (so the caller can flag the leader-gain bounce).
     *
     * Routes to one of two mutually exclusive flows depending on whether the unit's formation
     * already has a commander (§4.5, one leader per formation): a leaderless formation runs the
     * Phase 2 emergence check; a led one runs Phase 3 progression instead. Returns false — no
     * "leader gained" bounce — for a scenario-only unit with no formation, or for any action on a
     * led formation (progression queues its own promotion announcement rather than this boolean).
     */
    fun recordCombat(
        unit: GameUnit,
        contribution: RecognitionService.Contribution,
        turn: Int = 0,
    ): Boolean {
        val formationId = FormationIdentity.of(unit) ?: return false
        val formation = ensureFormation(unit, formationId)
        val heroId = formation.assignedHeroId
        return when {
            // A destroyed unit whose formation has a commander faces a casualty outcome (§11); a
            // destroyed leaderless formation just loses its equipment and emerges nothing here.
            unit.destroyed && heroId != null -> {
                recordCasualty(unit, turn)
                false
            }

            unit.destroyed -> false
            heroId != null -> {
                progressCommander(unit, formation, heroId, contribution, turn)
                false
            }

            else -> attemptEmergence(unit, formation, contribution, turn)
        }
    }

    /**
     * Credits [unit]'s commander with reconnaissance evidence for revealing [newlySpotted]
     * previously unseen enemies (§8.4, `tools/og-import/DEFERRED.md` §7.43), and reports whether
     * anything was credited.
     *
     * **Evidence only** — no leader XP, no recognition, no promotion check, no service-history
     * entry. Spotting is not one of §7.1's notable actions, so it must neither move a leaderless
     * formation toward emergence nor pad a dossier with a line per hex revealed. It exists so
     * RECONNAISSANCE is a *fed* category and `sharp_eyes` can be earned on merit rather than handed
     * out as a §8.5.4 fallback, which is what §7.42 was reduced to doing.
     *
     * Counted once per formation per turn regardless of [newlySpotted]; see
     * [reconContactsCredited]. A leaderless formation is skipped outright — there is no one to
     * credit, and recognition deliberately does not accrue from this.
     */
    fun recordReconnaissance(
        unit: GameUnit,
        newlySpotted: Int,
        turn: Int = currentTurn(),
    ): Boolean {
        val formationId = unit.takeIf { newlySpotted > 0 }?.let(FormationIdentity::of)
        val hero =
            formationId
                ?.let(roster::formation)
                ?.assignedHeroId
                ?.let(roster::state)
                // Claimed last, so a leaderless or unknown formation never burns the turn's key.
                ?.takeIf { reconContactsCredited.add("${currentScenarioLabel()}|${formationId.value}|$turn") }
                ?: return false
        roster.updateState(
            hero.copy(
                specializationEvidence =
                    EvidenceRules.accrue(
                        hero.specializationEvidence,
                        listOf(AchievementType.RECON_CONTACT),
                    ),
            ),
        )
        return true
    }

    /** Processes a destruction that happens after the normal combat result, notably failed-retreat surrender. */
    fun recordCasualty(
        unit: GameUnit,
        turn: Int = 0,
    ): Boolean {
        val casualty =
            unit
                .takeIf { it.destroyed }
                ?.let(FormationIdentity::of)
                ?.let { ensureFormation(unit, it) }
                ?.let { formation -> formation.assignedHeroId?.let { formation to it } }
        return if (casualty != null) {
            applyCasualty(unit, casualty.first, casualty.second, turn)
            true
        } else {
            false
        }
    }

    /**
     * Resolves the fate of [formation]'s commander after its unit was destroyed (§11): sets the new
     * status, records any wound, detaches the leader, leaves a restrained memorial tradition on death
     * (§11.2), and queues the event for the UI.
     *
     * The detach is unconditional because this only runs on a destroyed unit, and a destroyed unit is
     * not campaign-persistent — the formation itself does not reach the next scenario. Keeping a
     * lightly wounded commander "with his formation" therefore stranded him: still `ACTIVE`, still
     * pointing at a formation no unit would ever carry again, and unreachable by any reassignment
     * (transfers are still deferred, see `docs/hero-leader-implementation-phases.md` Phase 4).
     */
    private fun applyCasualty(
        unit: GameUnit,
        formation: CoreFormation,
        heroId: HeroId,
        turn: Int,
    ) {
        val hero = roster.state(heroId) ?: return
        val definition = roster.definition(heroId) ?: return
        val scenarioId = currentScenarioLabel()
        val casualtyContext =
            HeroCasualtyService.Context(
                surrendered = unit.surrendered,
                safeSupply = !unit.surrendered,
                seed = SeededRandom.seedFrom(heroId.value, scenarioId, turn.toString()),
            )
        val outcome = HeroCasualtyService.resolve(casualtyContext, scenarioId)
        val event =
            HeroEvent(
                outcome.disposition.name.lowercase(),
                scenarioId,
                turn,
                currentDate(),
                currentLocation(unit),
            )
        roster.updateState(
            hero.copy(
                status = outcome.disposition.status,
                injuries = hero.injuries + listOfNotNull(outcome.injury),
                serviceEvents = hero.serviceEvents + event,
                assignedFormationId = null,
            ),
        )
        val killed = outcome.disposition == HeroCasualtyService.Disposition.KILLED
        val memorial = if (killed) "Tradition of ${definition.displayName}" else null
        val updatedFormation =
            formation.copy(
                assignedHeroId = null,
                battleHonors = if (memorial != null) formation.battleHonors + memorial else formation.battleHonors,
                history =
                    formation.history +
                        FormationEvent(
                            "commander_${outcome.disposition.name.lowercase()}",
                            scenarioId,
                            turn,
                            currentDate(),
                            currentLocation(unit),
                        ),
            )
        roster.putFormation(updatedFormation)
        pendingCasualties += HeroCasualtyAnnouncement.from(outcome, updatedFormation, definition, hero.rankId, memorial)
    }

    /**
     * Runs the emergence check for a leaderless [formation], or does nothing (beyond having
     * created the formation record) when the action was not notable — recognition (§7.1) only
     * accumulates from notable actions, and neither an ineligible nor an unremarkable combat should
     * feed the drought counter.
     */
    private fun attemptEmergence(
        unit: GameUnit,
        formation: CoreFormation,
        contribution: RecognitionService.Contribution,
        turn: Int,
    ): Boolean {
        // OG's `Cannot get a leader` (`attrEx` bit 0), wired 2026-08-26: equipment that never
        // produces a commander does not emerge one here either. Blocked BEFORE the recognition
        // record, so a formation issued such equipment neither emerges nor banks progress toward
        // emerging while it holds it. A formation that already HAS a commander takes
        // `progressCommander` instead and is deliberately untouched -- the hero belongs to the
        // formation, not to the equipment currently issued to it
        // (`rules/UnitCapabilities.canProduceLeader`).
        val producesLeaders = UnitCapabilities.canProduceLeader(unit.unitData(true))
        val assessment = RecognitionService.assess(contribution)
        if (!producesLeaders || !assessment.isNotable) return false
        val eventId = assessment.event?.eventId ?: EmergenceEvent.DISTINGUISHED_SERVICE.eventId
        val recorded = recordFormationEvent(unit, eventId, turn) ?: formation
        val checked =
            recorded.copy(
                recognition = recorded.recognition + assessment.points,
                emergenceChecks = recorded.emergenceChecks + 1,
            )
        return runCheck(unit, checked, assessment, turn) is LeaderAcquisitionService.EmergenceResult.Emerged
    }

    /** Runs Phase 3 progression for a formation that already has a commander. */
    private fun progressCommander(
        unit: GameUnit,
        formation: CoreFormation,
        heroId: HeroId,
        contribution: RecognitionService.Contribution,
        turn: Int,
    ) {
        val hero = roster.state(heroId) ?: return
        val definition = roster.definition(heroId) ?: return
        val ctx = context
        val result =
            HeroProgressionProcessor.process(
                contribution = contribution,
                hero = hero,
                definition = definition,
                formation = formation,
                campaignId = ctx?.campaignId ?: "",
                scenarioId = currentScenarioLabel(),
                turn = turn,
                eventDate = currentDate(),
                eventLocation = currentLocation(unit),
            )
        roster.updateState(result.hero)
        roster.putFormation(result.formation)
        result.promotion?.let {
            pendingPromotions += HeroPromotionAnnouncement.from(it, result.formation, definition, result.hero)
        }
    }

    /** New-leader events accumulated since the last drain, in order. Emptied by the caller. */
    fun drainAnnouncements(): List<HeroEmergenceAnnouncement> {
        if (pendingAnnouncements.isEmpty()) return emptyList()
        val out = pendingAnnouncements.toList()
        pendingAnnouncements.clear()
        return out
    }

    /** Promotion events accumulated since the last drain (§8.5), in order. Emptied by the caller. */
    fun drainPromotions(): List<HeroPromotionAnnouncement> {
        if (pendingPromotions.isEmpty()) return emptyList()
        val out = pendingPromotions.toList()
        pendingPromotions.clear()
        return out
    }

    /** Casualty events accumulated since the last drain (§11), in order. Emptied by the caller. */
    fun drainCasualties(): List<HeroCasualtyAnnouncement> {
        if (pendingCasualties.isEmpty()) return emptyList()
        val out = pendingCasualties.toList()
        pendingCasualties.clear()
        return out
    }

    /**
     * Drops every announcement not yet presented. Called on scenario teardown (`Game.cleanup`).
     *
     * [reset] clears these too, but only starts a NEW RUN — between two scenarios of one campaign
     * the queues simply carried over, and an event queued by the last combat of a battle (which
     * ends the battle, so no further combat ever drains it) surfaced in the next scenario instead.
     */
    fun discardPendingAnnouncements() {
        pendingAnnouncements.clear()
        pendingPromotions.clear()
        pendingCasualties.clear()
    }

    /** Applies the player's choice for a pending promotion (§8.5): learns the trait, gains one attribute point. */
    fun applyPromotionChoice(
        heroId: HeroId,
        traitId: String,
    ) {
        val hero = roster.state(heroId) ?: return
        val chosen = HeroTraitCatalog.byId(traitId) ?: return
        roster.updateState(
            hero.copy(
                learnedTraitIds = hero.learnedTraitIds + LegacyTraitMapping.toTraitId(chosen.legacyTrait),
                attributes = hero.attributes.increment(chosen.categoryId.attribute),
            ),
        )
    }

    /** Runs the emergence check on the (already recognition-updated) formation and applies the verdict. */
    private fun runCheck(
        unit: GameUnit,
        checked: CoreFormation,
        assessment: RecognitionService.Assessment,
        turn: Int,
    ): LeaderAcquisitionService.EmergenceResult {
        val ctx = context
        val reserved =
            roster.reservedLegendary
                ?.let { LegendaryHeroPool.byId(it) }
                ?.takeIf {
                    LegendaryHeroPool.compatible(
                        it,
                        ctx?.campaignId.orEmpty(),
                        ctx?.country,
                        checked.unitClass,
                        ctx?.serviceYear,
                    )
                }
        val proceduralFallback = roster.reservedLegendary == LegendaryHeroPool.PROCEDURAL_FALLBACK_ID
        val earlyLegendaryQualifyingCombats =
            roster
                .allFormations()
                .filterNot { it.id == checked.id }
                .sumOf { it.emergenceChecks } + checked.emergenceChecks
        val emergenceContext =
            LeaderAcquisitionService.EmergenceContext(
                campaignId = ctx?.campaignId ?: "",
                scenarioIndex = ctx?.scenarioIndex ?: 0,
                formation = checked,
                event = assessment.event ?: EmergenceEvent.DISTINGUISHED_SERVICE,
                campaignDrought = roster.drought,
                country = unit.player?.country ?: checked.country,
                unitExperience = unit.experience,
                serviceYear = ctx?.serviceYear,
                reservedLegendary = reserved,
                proceduralLegendaryFallback = proceduralFallback,
                earlyLegendaryQualifyingCombats = earlyLegendaryQualifyingCombats,
            )
        val result = LeaderAcquisitionService.tryGenerate(emergenceContext)
        applyResult(unit, checked, result, assessment, turn)
        return result
    }

    private fun applyResult(
        unit: GameUnit,
        checked: CoreFormation,
        result: LeaderAcquisitionService.EmergenceResult,
        assessment: RecognitionService.Assessment,
        turn: Int,
    ) {
        when (result) {
            is LeaderAcquisitionService.EmergenceResult.Emerged -> {
                val scenarioId = currentScenarioLabel()
                val date = currentDate()
                val location = currentLocation(unit)
                val eventId = assessment.event?.eventId ?: result.event.eventId
                val state =
                    result.state.copy(
                        serviceEvents =
                            result.state.serviceEvents + HeroEvent(eventId, scenarioId, turn, date, location),
                    )
                val formation = checked.copy(assignedHeroId = result.definition.id)
                roster.putHero(result.definition, state)
                roster.putFormation(formation)
                roster.drought = 0
                if (result.consumedReservation) {
                    roster.reservedLegendary = null
                    roster.legendarySpawned = true
                }
                pendingAnnouncements += HeroEmergenceAnnouncement.from(result, formation)
            }

            is LeaderAcquisitionService.EmergenceResult.NoLeader -> {
                roster.putFormation(checked)
                if (result.eligible) roster.drought += 1
            }
        }
    }

    // Not private: [HeroTransferService] dates its paperwork and scopes its settling period the
    // same way every other event in this system is dated, and there is no second definition of
    // "which scenario/turn is it now" to be had.
    fun currentTurn(): Int =
        GameHolder.instance
            ?.scenario
            ?.map
            ?.turn ?: 0

    fun currentScenarioLabel(): String =
        GameHolder.instance
            ?.scenario
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: context?.scenarioIndex?.toString().orEmpty()

    @Suppress("MagicNumber")
    fun currentDate(): String? {
        val date = GameHolder.instance?.scenario?.date ?: return null
        val month = (date.getMonth() + 1).toString().padStart(2, '0')
        val day = date.getDate().toString().padStart(2, '0')
        return "${date.getFullYear()}-$month-$day"
    }

    private fun currentLocation(unit: GameUnit): String? = unit.getHex()?.name?.takeIf { it.isNotBlank() }

    /** The formation record for [unit], created from the unit on first contact (fresh campaigns have none). */
    private fun ensureFormation(
        unit: GameUnit,
        formationId: FormationId,
    ): CoreFormation =
        roster.formation(formationId) ?: CoreFormation(
            id = formationId,
            ownerId = unit.owner,
            country = unit.player?.country ?: -1,
            displayName = unit.customName ?: unit.unitData().name,
            currentEquipmentId = unit.eqid,
            unitClass = unit.unitData().uclass,
        ).also(roster::putFormation)
}
