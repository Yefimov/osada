package org.osada.ui

import org.osada.hero.HeroCasualtyAnnouncement
import org.osada.hero.HeroCasualtyService
import org.osada.hero.HeroDisplay
import org.osada.i18n.I18n

/**
 * Presents heroic-commander casualty events (design brief §11) after combat resolution — the same
 * timing rule and message-dialog treatment as [HeroEmergencePresenter], its counterpart for the
 * opposite emotional beat. The fate text is resolved through [HeroDisplay] so it stays
 * localization-ready; a fallen commander's memorial tradition (§11.2) is shown when one is left
 * behind.
 */
internal object HeroCasualtyPresenter {
    fun present(pending: List<HeroCasualtyAnnouncement>) {
        pending.forEach { UIBuilder.messageDynamic(title(it), body(it)) }
    }

    private fun title(a: HeroCasualtyAnnouncement): String =
        when (a.disposition) {
            HeroCasualtyService.Disposition.KILLED -> I18n.t("hero.casualty.killed.title")
            HeroCasualtyService.Disposition.MISSING,
            HeroCasualtyService.Disposition.CAPTURED,
            -> I18n.t("hero.casualty.lost.title")
            else -> I18n.t("hero.casualty.report.title")
        }

    private fun body(a: HeroCasualtyAnnouncement): String {
        val sb = StringBuilder()
        sb.append("<div class='heroEmergenceName'><b>${rankLabel(a.rankId)} ${a.heroName}</b></div>")
        sb.append("<div class='heroEmergenceSub'>${HeroDisplay.disposition(a.disposition)}</div>")
        sb.append(
            "<div class='heroEmergenceFormation'>" +
                I18n.t("hero.emergence.formation", mapOf("formation" to a.formationName)) +
                "</div>",
        )
        a.memorial?.let {
            sb.append(
                "<div class='heroEmergenceReason'><i>" +
                    I18n.t("hero.casualty.memorial", mapOf("memorial" to it)) +
                    "</i></div>",
            )
        }
        return sb.toString()
    }

    private fun rankLabel(rankId: String): String = HeroDisplay.rank(rankId)
}
