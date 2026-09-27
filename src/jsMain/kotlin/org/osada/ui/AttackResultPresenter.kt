package org.osada.ui

import org.osada.LeaderType
import org.osada.PlayerType
import org.osada.evaluateScenarioEvents
import org.osada.handleMoveVictory
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroDisplay
import org.osada.i18n.I18n
import org.osada.model.AttackOutcome
import org.osada.model.Cell
import org.osada.model.CombatResults
import org.osada.model.ForcedMove
import org.osada.model.ForcedMoveCause
import org.osada.model.GameUnit
import org.osada.model.delCurrentUnit
import org.osada.model.getEliminationWinner
import org.osada.model.updateUnitList
import org.osada.uiAnimationFinished

/**
 * Presents the outcome of a resolved attack: console log, bounce-text callouts, the sidebar HUD
 * combat-log entry, leader-gain callouts, current-unit selection cleanup and the final render.
 * Split from [AnimationOrchestrator] (SRP / function-count / complexity limits).
 */
internal class AttackResultPresenter(
    private val ui: UI,
) {
    fun present(
        attacker: GameUnit,
        defender: GameUnit,
        outcome: AttackOutcome,
        radius: Int,
    ) {
        val result = outcome.result
        console.log(
            "[osada] finishAttackAnimation losses=${result.losses} kills=${result.kills} " +
                "destroyed=atk${attacker.destroyed}/def${defender.destroyed}",
        )
        val attackerPos = attacker.getPos()
        val defenderPos = defender.getPos()

        val map = ui.game.scenario?.map
        if (attacker.destroyed || defender.destroyed) {
            map?.updateUnitList()
        }
        map?.getEliminationWinner()?.let(ui.game::handleMoveVictory)

        showCombatBounceTexts(result, attackerPos, defenderPos)
        logCombatToHud(attacker, defender, result, defenderPos)

        attackerPos?.let { ui.render.render(it.row, it.col, radius) }
        showLeaderGainBounceTexts(result, attackerPos, defenderPos)
        showLegacyLeaderGains(attacker, defender, result)
        showSupportLeaderGains(attacker, outcome)
        listOfNotNull(outcome.retreat, result.evasion).forEach(::presentForcedMove)
        // Present hero-emergence events queued during this combat — after the
        // animation, per §14.1, never mid-attack.
        presentPendingHeroEvents()
        // A retreat or an evasion changed positions, which an authored proximity condition may now
        // satisfy. The special hex a unit was pushed onto does NOT fire (`model/relocateForced`); the
        // scenario's conditions are still evaluated, as after any move.
        if (outcome.retreat != null || result.evasion != null) ui.game.evaluateScenarioEvents()

        refreshCurrentUnitSelection(attacker)
    }

    /**
     * Everything the exchange did besides trade strength points, as named callouts.
     *
     * Each of these changes the outcome in a way the numbers alone do not explain -- a defender
     * that is suddenly on another hex, a ship gone at full strength, an attacker that may walk
     * again, an attack that never went in -- and until 2026-09-26 only the first two rows ever
     * reached the screen. The rest were set on the result and read by nothing, so the rule worked
     * and looked like a bug. [logCombatToHud] writes the same list as sentences, because a bounce
     * text is gone in a second.
     */
    private fun combatEvents(result: CombatResults): List<CombatEvent> =
        listOfNotNull(
            CombatEvent(onAttacker = true, "combat.alert.overrun", good = true).takeIf { result.isOverrun },
            CombatEvent(onAttacker = true, "combat.alert.rugged_defense", good = false)
                .takeIf { result.isRugged && !result.isOverrun },
            CombatEvent(
                onAttacker = true,
                "combat.alert.attack_stopped",
                good = false,
                "hud.log.combat.note.attack_stopped",
            ).takeIf { result.isAttackStopped },
            CombatEvent(onAttacker = false, "combat.alert.evaded", good = false, "hud.log.combat.note.evaded").takeIf {
                result.isEvaded
            },
            CombatEvent(onAttacker = false, "combat.alert.sabotaged", good = true, "hud.log.combat.note.sabotaged")
                .takeIf { result.isSabotage },
            CombatEvent(
                onAttacker = false,
                "combat.alert.critical_hit",
                good = true,
                "hud.log.combat.note.critical_hit",
            ).takeIf { result.isCriticalHit },
            CombatEvent(
                onAttacker = true,
                "combat.alert.critical_hit",
                good = false,
                "hud.log.combat.note.attacker_critical_hit",
            ).takeIf { result.isAttackerCriticalHit },
            CombatEvent(onAttacker = true, "combat.alert.exploit", good = true, "hud.log.combat.note.exploit").takeIf {
                result.isExploit
            },
        )

    private fun showCombatBounceTexts(
        result: CombatResults,
        attackerPos: Cell?,
        defenderPos: Cell?,
    ) {
        // Stacked upward from just above the hex, so two callouts on one unit -- or a callout and
        // its strength loss -- never print over each other.
        var attackerStack = 0
        var defenderStack = 0
        combatEvents(result).forEach { event ->
            val cell = (if (event.onAttacker) attackerPos else defenderPos) ?: return@forEach
            val slot = if (event.onAttacker) ++attackerStack else ++defenderStack
            val pos = ui.render.cellToScreen(cell.row, cell.col, true)
            bounceText(pos.x, pos.y - CALLOUT_STEP_PX * slot, I18n.t(event.alertKey), event.good)
        }
        // What the attacker lost in total, as Panzer Marshal showed it (`oldstr - newstr`): the
        // main exchange plus whatever the defender's supporting units took off it first.
        val attackerLosses = result.losses + result.supportFireLosses
        if (attackerLosses > 0 && attackerPos != null) {
            val pos = ui.render.cellToScreen(attackerPos.row, attackerPos.col, true)
            bounceText(pos.x, pos.y, "-$attackerLosses", false)
        }
        if (result.kills > 0 && defenderPos != null) {
            val pos = ui.render.cellToScreen(defenderPos.row, defenderPos.col, true)
            bounceText(pos.x, pos.y, "-${result.kills}", false)
        }
    }

    // Sidebar log: a full sentence in the spirit of the legacy combatLogInfoBox (who attacked
    // whom, where, casualties inflicted/taken, survivors, XP) — the viewing side's own losses
    // render red (spec). Reuses only data already computed above for the bounce text /
    // showAttackInfo, so nothing new is revealed.
    private fun logCombatToHud(
        attacker: GameUnit,
        defender: GameUnit,
        result: CombatResults,
        defenderPos: Cell?,
    ) {
        val attackerLosses = result.losses + result.supportFireLosses
        val defenderLosses = result.kills
        val notes = combatEvents(result).mapNotNull { it.logKey }
        val hasNews =
            attackerLosses > 0 || defenderLosses > 0 || attacker.destroyed || defender.destroyed || notes.isNotEmpty()
        if (!hasNews) return

        val own = ui.game.spotSide
        val attackerIsOwn = attacker.player?.side == own
        val defenderIsOwn = defender.player?.side == own
        val atkName = "${UIBuilder.unitIDToOrdinal(attacker.id)} ${attacker.unitData(true).name}".trim()
        val defName = defender.unitData(true).name
        // Raw "(col,row)" dropped from the visible text (it was clutter, spec) — the row is
        // clickable instead (jumps to the defender's hex) with the coordinates only in its
        // tooltip, same treatment as the Turn Report's rows.
        val segments =
            mutableListOf(
                HudLog.Segment(I18n.t("hud.log.combat.attacked", mapOf("attacker" to atkName, "defender" to defName))),
            )

        val inflicted =
            if (defender.destroyed) {
                I18n.t("hud.log.combat.inflicted_destroyed", mapOf("losses" to defenderLosses, "defender" to defName))
            } else {
                I18n.t("hud.log.combat.inflicted", mapOf("losses" to defenderLosses))
            }
        segments.add(HudLog.Segment("$inflicted,", defenderIsOwn && defenderLosses > 0))

        val taken =
            if (attacker.destroyed) {
                I18n.t("hud.log.combat.lost_destroyed", mapOf("losses" to attackerLosses))
            } else {
                I18n.t(
                    "hud.log.combat.lost_remaining",
                    mapOf("losses" to attackerLosses, "remaining" to attacker.strength),
                )
            }
        segments.add(HudLog.Segment(taken, attackerIsOwn && attackerLosses > 0))

        if (result.atkExpGained > 0 && !attacker.destroyed) {
            segments.add(HudLog.Segment(I18n.t("hud.log.combat.experience", mapOf("value" to result.atkExpGained))))
        }
        val names = mapOf("attacker" to atkName, "defender" to defName)
        notes.forEach { segments.add(HudLog.Segment(I18n.t(it, names))) }

        if (defenderPos != null) {
            HudLog.addAt(defenderPos.row, defenderPos.col, *segments.toTypedArray())
        } else {
            HudLog.add(*segments.toTypedArray())
        }
    }

    /** Both flags now mean "a HERO emerged" and nothing else — see
     *  [org.osada.model.CombatLeaderAcquisition.acquire], which stopped reporting a legacy leader
     *  here so an enemy scenario unit could no longer bounce "Hero Emerged" over its own hex. */
    private fun showLeaderGainBounceTexts(
        result: CombatResults,
        attackerPos: Cell?,
        defenderPos: Cell?,
    ) {
        if (result.atkLeaderGain && attackerPos != null) {
            val pos = ui.render.cellToScreen(attackerPos.row, attackerPos.col, true)
            bounceText(pos.x, pos.y, I18n.t("hero.emergence.bounce"), true)
        }
        if (result.defLeaderGain && defenderPos != null) {
            val pos = ui.render.cellToScreen(defenderPos.row, defenderPos.col, true)
            bounceText(pos.x, pos.y, I18n.t("hero.emergence.bounce"), true)
        }
    }

    /**
     * A LEGACY leader (Panzer Marshal's "New Leader") gained in this exchange -- the mechanic of
     * every unit outside the campaign hero system. Kept apart from the hero callout above so the map
     * never claims a hero where only a trait was gained.
     *
     * Shown only for a unit the viewing side can see: its own, or an enemy on a spotted hex. The
     * same line goes to the HUD log, which is the explanation on a touch screen with no hover.
     */
    private fun showLegacyLeaderGains(
        attacker: GameUnit,
        defender: GameUnit,
        result: CombatResults,
    ) {
        if (result.atkLegacyLeaderGain) announceLegacyLeader(attacker)
        if (result.defLegacyLeaderGain) announceLegacyLeader(defender)
    }

    /**
     * The same two callouts for the support exchanges that came before the main one: in each, the
     * SUPPORTER was the attacker and the ordering attacker was the one fired upon. Their results used
     * to be dropped, so a leader gained in support fire reached only the combat log.
     */
    private fun showSupportLeaderGains(
        attacker: GameUnit,
        outcome: AttackOutcome,
    ) {
        outcome.supportShots.forEach { shot ->
            if (shot.result.atkLegacyLeaderGain) announceLegacyLeader(shot.supporter)
            if (shot.result.defLegacyLeaderGain) announceLegacyLeader(attacker)
            if (shot.result.atkLeaderGain) announceHeroEmergence(shot.supporter)
            if (shot.result.defLeaderGain) announceHeroEmergence(attacker)
        }
    }

    private fun announceHeroEmergence(unit: GameUnit) {
        val pos = unit.getPos() ?: return
        val screen = ui.render.cellToScreen(pos.row, pos.col, true)
        bounceText(screen.x, screen.y, I18n.t("hero.emergence.bounce"), true)
    }

    private fun announceLegacyLeader(unit: GameUnit) {
        val pos = unit.getPos() ?: return
        val own = unit.player?.side == ui.game.spotSide
        if (!own && unit.getHex()?.isSpotted(ui.game.spotSide) != true) return
        val screen = ui.render.cellToScreen(pos.row, pos.col, true)
        bounceText(screen.x, screen.y - CALLOUT_STEP_PX * 2, I18n.t("combat.alert.leader_gained"), own)
        val trait = LeaderType.entries.firstOrNull { it.value == unit.leader }
        val line =
            I18n.t(
                "hud.log.leader_gained",
                mapOf(
                    "unit" to unit.unitData(true).name,
                    "trait" to (trait?.let { HeroDisplay.trait(it, "").title } ?: ""),
                ),
            )
        HudLog.addAt(pos.row, pos.col, line)
    }

    /**
     * A unit pushed out of its hex. A retreat used to be the one combat outcome with no notice at
     * all -- the defender was simply on another hex (audit §5). An evasion already has its
     * "Evaded" callout, so it adds only what that callout cannot say: a minefield it landed in.
     * Nothing is shown for a unit the viewing side cannot see at either end of the move.
     */
    private fun presentForcedMove(move: ForcedMove) {
        val side = ui.game.spotSide
        val own = move.unit.player?.side == side
        val destination =
            ui.game.scenario
                ?.map
                ?.map
                ?.getOrNull(move.to.row)
                ?.getOrNull(move.to.col)
        if (!own && destination?.isSpotted(side) != true) return
        val name = move.unit.unitData(true).name
        if (move.cause == ForcedMoveCause.COMBAT_RETREAT) {
            val screen = ui.render.cellToScreen(move.to.row, move.to.col, true)
            bounceText(screen.x, screen.y - CALLOUT_STEP_PX, I18n.t("combat.alert.retreated"), !own)
            HudLog.addAt(move.to.row, move.to.col, I18n.t("hud.log.retreated", mapOf("unit" to name)))
        }
        if (move.minefieldWasHidden) {
            val key = if (move.minefieldLosses > 0) "combat.minefield.hidden" else "combat.minefield.hidden.nodamage"
            HudLog.addAt(
                move.to.row,
                move.to.col,
                HudLog.Segment(
                    I18n.t(key, mapOf("unit" to name, "losses" to move.minefieldLosses)),
                    ownLoss = own && move.minefieldLosses > 0,
                ),
            )
        }
    }

    private fun refreshCurrentUnitSelection(attacker: GameUnit) {
        try {
            val currentUnit =
                ui.game.scenario
                    ?.map
                    ?.currentUnit
            if (currentUnit != null) {
                if (currentUnit.id == attacker.id && attacker.destroyed) {
                    ui.game.scenario
                        ?.map
                        ?.delCurrentUnit()
                } else {
                    // Rebuild the card as well as the model selection. Combat clears undoState;
                    // merely reselecting left the old, inert Undo chip in the DOM.
                    ui.uiUnitSelect(currentUnit)
                }
            }
        } finally {
            // showAttackInfo() overwrote #statusmsg with the combat flags; restore the
            // scenario/turn/date line and refresh the navigator/End-Turn count.
            if (ui.game.scenario
                    ?.map
                    ?.currentPlayer
                    ?.type == PlayerType.HUMAN_LOCAL
            ) {
                ui.updateStatusBar()
            }
            ui.game.waitUIAnimation = false
            ui.game.uiAnimationFinished()
        }
    }
}

/** One named callout from [AttackResultPresenter.combatEvents]: which unit it belongs over, its
 *  map label, whether it is good news for the ATTACKER, and the log sentence (none for the two
 *  callouts whose effect the log line already states in numbers). */
private class CombatEvent(
    val onAttacker: Boolean,
    val alertKey: String,
    val good: Boolean,
    val logKey: String? = null,
)

/** Vertical spacing between stacked callouts over one hex. */
private const val CALLOUT_STEP_PX = 18.0

/**
 * Hero events queued by the model since they were last shown: an officer emerging, a promotion
 * choice (§8.5) on a formation that already has a commander, and a commander casualty (§11).
 * Shown after an animation, never in the middle of one (§14.1). Shared by the local attack path and
 * by the multiplayer host, which resolves commands without the attack animation.
 */
internal fun presentPendingHeroEvents() {
    HeroEmergencePresenter.announce(HeroCampaign.drainAnnouncements())
    HeroPromotionPresenter.present(HeroCampaign.drainPromotions())
    HeroCasualtyPresenter.present(HeroCampaign.drainCasualties())
}
