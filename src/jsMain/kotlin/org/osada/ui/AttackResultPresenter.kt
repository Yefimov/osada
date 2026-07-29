package org.osada.ui

import org.osada.PlayerType
import org.osada.hero.HeroCampaign
import org.osada.i18n.I18n
import org.osada.model.Cell
import org.osada.model.CombatResults
import org.osada.model.GameUnit
import org.osada.model.delCurrentUnit
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
        result: CombatResults,
        radius: Int,
    ) {
        console.log(
            "[osada] finishAttackAnimation losses=${result.losses} kills=${result.kills} " +
                "destroyed=atk${attacker.destroyed}/def${defender.destroyed}",
        )
        val attackerPos = attacker.getPos()
        val defenderPos = defender.getPos()

        if (attacker.destroyed || defender.destroyed) {
            ui.game.scenario
                ?.map
                ?.updateUnitList()
        }

        showCombatBounceTexts(result, attackerPos, defenderPos)
        logCombatToHud(attacker, defender, result, defenderPos)

        attackerPos?.let { ui.render.render(it.row, it.col, radius) }
        showLeaderGainBounceTexts(result, attackerPos, defenderPos)
        // Present hero-emergence events queued during this combat — after the
        // animation, per §14.1, never mid-attack.
        HeroEmergencePresenter.announce(HeroCampaign.drainAnnouncements())
        // Same timing rule for a promotion choice (§8.5) on a formation that already has a commander.
        HeroPromotionPresenter.present(HeroCampaign.drainPromotions())
        // And for a commander casualty when a led formation's unit was destroyed (§11).
        HeroCasualtyPresenter.present(HeroCampaign.drainCasualties())

        refreshCurrentUnitSelection(attacker)
    }

    private fun showCombatBounceTexts(
        result: CombatResults,
        attackerPos: Cell?,
        defenderPos: Cell?,
    ) {
        if (result.isOverrun) {
            attackerPos?.let {
                val pos = ui.render.cellToScreen(it.row, it.col, true)
                bounceText(pos.x, pos.y, "Overrun", true)
            }
        }
        if (result.isRugged && !result.isOverrun) {
            attackerPos?.let {
                val pos = ui.render.cellToScreen(it.row, it.col, true)
                bounceText(pos.x, pos.y, "Rugged Defense", false)
            }
        }
        if (result.losses > 0 && attackerPos != null) {
            val pos = ui.render.cellToScreen(attackerPos.row, attackerPos.col, true)
            bounceText(pos.x, pos.y, "-${result.losses}", false)
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
        val attackerLosses = result.losses
        val defenderLosses = result.kills
        val hasNews = attackerLosses > 0 || defenderLosses > 0 || attacker.destroyed || defender.destroyed
        if (!hasNews) return

        val own = ui.game.spotSide
        val attackerIsOwn = attacker.player?.side == own
        val defenderIsOwn = defender.player?.side == own
        val atkName = "${UIBuilder.unitIDToOrdinal(attacker.id)} ${attacker.unitData(true).name}".trim()
        val defName = defender.unitData(true).name
        // Raw "(col,row)" dropped from the visible text (it was clutter, spec) — the row is
        // clickable instead (jumps to the defender's hex) with the coordinates only in its
        // tooltip, same treatment as the Turn Report's rows.
        val segments = mutableListOf(HudLog.Segment("$atkName attacked $defName:"))

        val inflicted = StringBuilder("inflicted $defenderLosses")
        if (defender.destroyed) inflicted.append(" — $defName destroyed")
        segments.add(HudLog.Segment("$inflicted,", defenderIsOwn && defenderLosses > 0))

        val taken = StringBuilder("lost $attackerLosses")
        if (attacker.destroyed) {
            taken.append(" — unit destroyed")
        } else {
            taken.append(" (${attacker.strength} remain)")
        }
        segments.add(HudLog.Segment(taken.toString(), attackerIsOwn && attackerLosses > 0))

        if (result.atkExpGained > 0 && !attacker.destroyed) {
            segments.add(HudLog.Segment("· +${result.atkExpGained} XP"))
        }

        if (defenderPos != null) {
            HudLog.addAt(defenderPos.row, defenderPos.col, *segments.toTypedArray())
        } else {
            HudLog.add(*segments.toTypedArray())
        }
    }

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
