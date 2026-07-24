package org.osada.ui

import org.osada.hero.HeroCampaign
import org.osada.hero.HeroId
import org.osada.hero.HeroPromotionAnnouncement

/**
 * Presents promotion choices (§8.5) after a combat has fully resolved, same timing rule as
 * [HeroEmergencePresenter]. Unlike the emergence event this one is a real decision: each option is
 * a clickable row that calls back into [HeroCampaign.applyPromotionChoice] with the chosen trait
 * id before dismissing itself.
 *
 * This is the Phase 3 presentation — a functional two-choice box, not yet the styled promotion
 * card a later UI pass may give it. It borrows [MessageDialogs]' dynamic-box DOM shape rather than
 * `message()`'s single OK button, because a promotion needs two independent actions instead of one.
 */
internal object HeroPromotionPresenter {
    fun present(pending: List<HeroPromotionAnnouncement>) {
        pending.forEach(::show)
    }

    private fun show(announcement: HeroPromotionAnnouncement) {
        val mainBody = byId("mainbody") ?: return
        val box = addTag(mainBody, "div")
        box.className = "uiMessageBox heroPromotionBox"
        box.id = "uiHeroPromotionBox"
        box.style.zIndex = "98"

        val titleEl = addTag(box, "div")
        titleEl.className = "uiMessageBoxTitle"
        titleEl.innerHTML = "${announcement.heroName} — Promotion to ${rankLabel(announcement.newRankId)}"

        val bodyEl = addTag(box, "div")
        bodyEl.className = "uiMessageBoxBody"
        bodyEl.innerHTML =
            "<div>${announcement.formationName}'s commander has earned a promotion. Choose one:</div>"

        announcement.choices.forEach { choice -> addChoice(bodyEl, box, announcement.heroId.value, choice) }
        makeVisible("uiHeroPromotionBox")
    }

    private fun addChoice(
        bodyEl: dynamic,
        box: dynamic,
        heroId: String,
        choice: HeroPromotionAnnouncement.Choice,
    ) {
        val option = addTag(bodyEl, "div")
        option.className = "smallButton heroPromotionChoice"
        option.innerHTML =
            "<b>${choice.title}</b><br>${choice.effectDescription}<br><i>${choice.justification}</i>"
        option.onclick = { _: org.w3c.dom.events.MouseEvent ->
            HeroCampaign.applyPromotionChoice(HeroId(heroId), choice.traitId)
            clearTag(box)
            delTag(box)
        }
    }

    private fun rankLabel(rankId: String): String = rankId.replaceFirstChar { it.uppercaseChar() }
}
