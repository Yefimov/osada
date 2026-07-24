package org.osada.ui

import org.osada.CombatLog
import org.osada.GameHolder
import org.osada.hero.FormationIdentity
import org.osada.hero.HeroEmergenceAnnouncement
import org.osada.hero.HeroPotential
import org.osada.model.getUnits

/**
 * Presents new-leader events (§14.1) after a combat has fully resolved.
 *
 * Reads the announcements the hero system queued during combat and shows one message dialog each.
 * Presenting here — from the UI, after the attack animation is done — is what satisfies "trigger
 * after combat resolution, not during attack animation"; the model side only queued data.
 *
 * This is the Phase 2 presentation: the authored strings in a message box. The large portrait, the
 * dossier button and the potential frame of §14.1 are Phase 4/5 (portrait art is the Phase 5
 * blocker); a placeholder is used until then. The §6.3 rule is honoured — a guaranteed drought
 * emergence is shown exactly like an organic one, never labelled "guaranteed".
 */
internal object HeroEmergencePresenter {
    fun announce(pending: List<HeroEmergenceAnnouncement>) {
        pending.forEach {
            UIBuilder.messageDynamic("A New Commander Emerges", body(it))
            // The layered portrait (§14.1, §15) loads into the placeholder after the dialog is built.
            PortraitRenderer.render(byId("heroEmergencePortrait"), it.portrait, it.portraitSeed)
            val unit =
                GameHolder.instance
                    ?.scenario
                    ?.map
                    ?.getUnits()
                    ?.firstOrNull { unit -> FormationIdentity.of(unit) == it.formationId }
            val rank = rankLabel(it.rankId)
            if (unit != null) {
                CombatLog.addHero(unit, it.heroName, rank, it.formationName)
                val message = "$rank ${it.heroName} took command of ${it.formationName}."
                unit.getPos()?.let { pos -> HudLog.addAt(pos.row, pos.col, HudLog.Segment(message)) }
                    ?: HudLog.add(HudLog.Segment(message))
            }
        }
    }

    private fun body(a: HeroEmergenceAnnouncement): String {
        val sb = StringBuilder()
        sb.append("<div id='heroEmergencePortrait' class='heroEmergencePortrait'></div>")
        sb.append("<div class='heroEmergenceName'><b>${rankLabel(a.rankId)} ${a.heroName}</b></div>")
        sb.append("<div class='heroEmergenceSub'>${a.characterLabel} · ${potentialLabel(a.potential)}</div>")
        sb.append("<div class='heroEmergenceFormation'>Commander of ${a.formationName}</div>")
        sb.append("<div class='heroEmergenceReason'><i>${a.reason}</i></div>")
        a.backgroundTitle?.let { sb.append("<div class='heroEmergenceBackground'>Background: $it</div>") }
        if (a.effects.isNotEmpty()) {
            sb.append("<div class='heroEmergenceEffects'>")
            a.effects.forEach { (title, desc) -> sb.append("<div><b>$title</b> — $desc</div>") }
            sb.append("</div>")
        }
        return sb.toString()
    }

    private fun rankLabel(rankId: String): String = rankId.replaceFirstChar { it.uppercaseChar() }

    private fun potentialLabel(potential: HeroPotential): String =
        when (potential) {
            HeroPotential.LINE_OFFICER -> "Line Officer"
            HeroPotential.PROMISING -> "Promising Officer"
            HeroPotential.DISTINGUISHED -> "Distinguished Officer"
            HeroPotential.AUTHORED_LEGENDARY -> "Legendary"
        }
}
