package org.osada.hero

import org.osada.GameHolder
import org.osada.PlayerType
import org.osada.model.isInitialDeploymentWindow

/**
 * Commander reassignment and its cost (DEFERRED.md §1.10) — a sibling of [HeroCasualtyService] and
 * [HeroPromotionService], split out of [HeroCampaign] once that object passed detekt's class-size
 * limit. Unlike those two it is not pure: posting an officer is a roster mutation, and the roster
 * has exactly one writer, so it mutates through [HeroCampaign.roster] rather than returning a
 * verdict for someone else to apply.
 *
 * ## What a transfer is
 *
 * Two moves, not one. An officer with no formation is **recalled** to an unled one — the "way back"
 * that `HeroCampaign.applyCasualty` leaves a lightly wounded commander needing, since it detaches
 * them from a formation that no longer exists. An officer who already has a formation may be
 * **moved** to an unled one or **exchanged** with another serving commander. The one move that does
 * not exist is an unassigned officer onto a led formation: that is not an exchange, it is
 * displacing a commander to nowhere.
 *
 * ## Why it costs something
 *
 * Every officer who changes formation, on both sides of an exchange, starts settling in: for
 * [HeroBalance.transferSettlingTurns] turns [HeroTraitResolver] grants the formation none of their
 * traits. Without that, reshuffling the whole command every battle would be strictly correct play
 * and the roster would stop being a set of relationships between officers and units.
 */
internal object HeroTransferService {
    // Statuses a transfer may move an officer OUT of. MISSING/CAPTURED have no confirmed fate,
    // RETIRED has left service and KILLED is final: none of those is an officer you can post
    // anywhere. ACTIVE belongs here because reassignment has to work in the direction players
    // actually want it; what restrains it is the settling cost, not a prohibition.
    private val transferEligibleStatuses =
        setOf(HeroStatus.ACTIVE, HeroStatus.RESERVE, HeroStatus.WOUNDED, HeroStatus.SERIOUSLY_WOUNDED)

    /** Whether [status] is one an officer can be posted out of — the single source of truth the
     *  commander-roster presenter queries instead of keeping its own copy of this set (DEFERRED.md
     *  §4.10: the two used to state the rule twice and could drift). */
    fun isTransferEligible(status: HeroStatus): Boolean = status in transferEligibleStatuses

    /**
     * Reassignment is a scenario-start setup action, not a mid-battle recovery tool: the window is
     * turn 1, before any of the player's units has moved or fired
     * ([org.osada.model.isInitialDeploymentWindow]).
     *
     * Public so the roster UI can say WHY the action is unavailable rather than opening a picker
     * that turns out to be empty — §4.10's "state the rule once" applies to the reason a rule fires
     * as much as to the rule.
     */
    fun isWindowOpen(): Boolean {
        val game = GameHolder.instance
        val map = game?.scenario?.map
        val player = game?.campaignPlayer ?: map?.currentPlayer
        return map != null &&
            player != null &&
            player.type == PlayerType.HUMAN_LOCAL &&
            map.isInitialDeploymentWindow(player)
    }

    /**
     * Whether [hero]'s traits are currently suppressed because they are still learning a formation
     * they were transferred into — read by [HeroTraitResolver], the single point every combat rule
     * already goes through, so one test disables every bonus at once.
     *
     * Scoped to the scenario the transfer happened in: a commander who has fought a whole battle
     * with a formation is no longer new to it, so a settling period can never survive into the next
     * scenario however the turn counter happens to line up.
     */
    fun isSettlingIn(hero: HeroState): Boolean =
        hero.settlingScenarioId != null &&
            hero.settlingScenarioId == HeroCampaign.currentScenarioLabel() &&
            HeroCampaign.currentTurn() < hero.settlingUntilTurn

    /** Turns left before [hero]'s traits apply again, or 0 when they already do. For the UI. */
    fun settlingTurnsLeft(hero: HeroState): Int =
        if (isSettlingIn(hero)) hero.settlingUntilTurn - HeroCampaign.currentTurn() else 0

    /**
     * Formations [heroId] could be posted to while the window is open, or empty when the officer or
     * the timing is not eligible. The hero's own formation is never a target.
     *
     * A formation not present on the map is still a valid target: the roster, not the live map, is
     * what deployment reads `heroFor` against, so an officer can be given a formation that is still
     * sitting in the reserve tray.
     */
    fun transferableFormations(heroId: HeroId): List<CoreFormation> {
        val roster = HeroCampaign.roster()
        val hero = roster.state(heroId)
        if (hero == null || !isTransferEligible(hero.status) || !isWindowOpen()) return emptyList()
        return roster.allFormations().filter { candidate ->
            candidate.id != hero.assignedFormationId &&
                // A led formation is only a target for an officer who has a seat to offer in
                // return; see the class doc on the move that does not exist.
                (candidate.assignedHeroId == null || hero.assignedFormationId != null)
        }
    }

    /**
     * Posts [heroId] to command [formationId], exchanging with its current commander when it has
     * one. Returns false when the move is not currently legal; `CommanderTransferPicker` is
     * expected to only ever offer legal choices, but this is the actual gate, not the UI.
     */
    fun transferCommander(
        heroId: HeroId,
        formationId: FormationId,
    ): Boolean {
        val roster = HeroCampaign.roster()
        val hero = roster.state(heroId)
        val target = roster.formation(formationId)
        // Re-checked against `transferableFormations` rather than restating its rules, so the gate
        // and the offer cannot drift apart.
        if (hero == null || target == null || transferableFormations(heroId).none { it.id == formationId }) {
            console.log(
                "[OSADA] commander transfer REFUSED ${heroId.value} -> ${formationId.value}: " +
                    "${refusalReason(hero, target)}",
            )
            return false
        }
        // Both sides are read BEFORE anything is written, so the exchange sees the state it was
        // decided against rather than the half-applied one.
        val source = hero.assignedFormationId?.let(roster::formation)
        val incumbent = target.assignedHeroId?.let(roster::state)
        post(hero, target)
        when {
            // `transferableFormations` guarantees `source != null` whenever the target is led, so
            // an incumbent always has a seat to move into and can never be orphaned.
            incumbent != null && source != null -> post(incumbent, source)
            // A plain move, with nobody coming the other way. The formation the officer LEFT has to
            // be vacated explicitly: `post` only ever writes the formation it is posting into, so
            // without this the old formation kept pointing at a commander who now leads something
            // else — two formations claiming one officer, and `heroFor` answering yes for both.
            source != null -> vacate(source)
        }
        console.log(
            "[OSADA] commander transfer: ${heroId.value} -> ${formationId.value}" +
                (incumbent?.let { " (exchange with ${it.heroId.value})" } ?: "") +
                "; both settle in for ${HeroBalance.DEFAULT.transferSettlingTurns} turns",
        )
        return true
    }

    /**
     * Why a refusal happened, for the log. Reassignment is invisible from a console log otherwise —
     * "I don't understand how to swap heroes" (2026-08-01) is a report that cannot be diagnosed
     * from a log that never mentions the window at all — and "the window is shut" and "this officer
     * has nowhere to go" want completely different advice.
     */
    private fun refusalReason(
        hero: HeroState?,
        target: CoreFormation?,
    ): String =
        when {
            hero == null -> "no such commander in the roster"
            target == null -> "no such formation in the roster"
            !isWindowOpen() -> "reassignment window shut (needs turn 1, before any unit has moved or fired)"
            !isTransferEligible(hero.status) -> "status ${hero.status.name} cannot be posted"
            else -> "formation is not a legal target for this officer"
        }

    /** Records a formation losing its commander to a transfer, leaving it unled and eligible to
     *  receive another (or to emerge its own). */
    private fun vacate(formation: CoreFormation) {
        HeroCampaign.roster().putFormation(
            formation.copy(
                assignedHeroId = null,
                history =
                    formation.history +
                        FormationEvent(
                            "commander_departed",
                            HeroCampaign.currentScenarioLabel(),
                            HeroCampaign.currentTurn(),
                            HeroCampaign.currentDate(),
                            null,
                        ),
            ),
        )
    }

    /** Moves one officer into [formation]: activates them, records both halves of the paperwork,
     *  and starts their settling-in period. */
    private fun post(
        hero: HeroState,
        formation: CoreFormation,
    ) {
        val roster = HeroCampaign.roster()
        val scenarioId = HeroCampaign.currentScenarioLabel()
        val turn = HeroCampaign.currentTurn()
        val date = HeroCampaign.currentDate()
        roster.updateState(
            hero.copy(
                status = HeroStatus.ACTIVE,
                assignedFormationId = formation.id,
                serviceEvents = hero.serviceEvents + HeroEvent("transferred", scenarioId, turn, date, null),
                settlingScenarioId = scenarioId,
                settlingUntilTurn = turn + HeroBalance.DEFAULT.transferSettlingTurns,
            ),
        )
        roster.putFormation(
            formation.copy(
                assignedHeroId = hero.heroId,
                history =
                    formation.history + FormationEvent("commander_transferred", scenarioId, turn, date, null),
            ),
        )
    }
}
