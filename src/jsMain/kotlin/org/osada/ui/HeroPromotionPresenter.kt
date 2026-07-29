package org.osada.ui

import org.osada.hero.HeroCampaign
import org.osada.hero.HeroDisplay
import org.osada.hero.HeroId
import org.osada.hero.HeroPromotionAnnouncement
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement

/**
 * Presents promotion choices (§8.5) after a combat has fully resolved, same timing rule as
 * [HeroEmergencePresenter]. Unlike the emergence event this one is a real decision: each option is
 * a clickable row that calls back into [HeroCampaign.applyPromotionChoice] with the chosen trait
 * id before dismissing itself.
 *
 * This is the Phase 3 presentation — a functional two-choice box, not yet the styled promotion
 * card a later UI pass may give it.
 *
 * **Styling is `.osada-hpp-*`, not `.smallButton` (DEFERRED.md §4.12).** `.smallButton` sets
 * `font-family: osada-menu`, an ICON font, so every choice's real words drew as unrelated glyphs —
 * exactly the trap `AttachmentPickerPresenter`'s own doc comment warns about, because that picker's
 * DOM shape was originally copied from this file. Also dropped for the same reason: the hardcoded
 * `z-index: 98` (opens behind `#equipment`; the theme's `--z-msg` tier is for this) and
 * `makeVisible()` (sets `display: inline`, discarding this dialog's centring transform — a freshly
 * created `div` is already visible and needs no help).
 *
 * **One at a time (DEFERRED.md §4.17).** Two promotions can resolve from a single combat.
 * [present] used to build one `div` per announcement, all carrying the same element id and all
 * stacked at the same centring transform; [queue] now holds the rest and [show] is re-entered when
 * a choice is taken, so exactly one dialog owns [BOX_ID] at any moment. That also makes
 * [isOpen] answerable, which the Escape handler needs — this is the one `--z-msg` dialog Escape
 * must NOT close, because the player owes it a decision.
 */
internal object HeroPromotionPresenter {
    private const val BOX_ID = "uiHeroPromotionBox"

    private val queue = ArrayDeque<HeroPromotionAnnouncement>()

    fun present(pending: List<HeroPromotionAnnouncement>) {
        queue.addAll(pending)
        if (!isOpen()) showNext()
    }

    /** Whether a promotion dialog is currently on screen — see [MainMenuButtonHandler.handleGlobalEscape]. */
    fun isOpen(): Boolean = byId(BOX_ID) != null

    private fun showNext() {
        val announcement = queue.removeFirstOrNull() ?: return
        show(announcement)
    }

    private fun show(announcement: HeroPromotionAnnouncement) {
        val mainBody = byId("mainbody") ?: return
        val box = addTag(mainBody, "div")
        box.className = "osada-hpp"
        box.id = BOX_ID

        val titleEl = addTag(box, "div")
        titleEl.className = "osada-hpp__title"
        titleEl.textContent =
            I18n.t(
                "hero.promotion.title",
                mapOf("name" to announcement.heroName, "rank" to HeroDisplay.rank(announcement.newRankId)),
            )

        val bodyEl = addTag(box, "div")
        bodyEl.className = "osada-hpp__body"
        bodyEl.textContent =
            I18n.t(
                "hero.promotion.body",
                mapOf("formation" to announcement.formationName),
            )

        announcement.choices.forEach { choice -> addChoice(box, announcement.heroId.value, choice) }
    }

    private fun addChoice(
        box: HTMLElement,
        heroId: String,
        choice: HeroPromotionAnnouncement.Choice,
    ) {
        val option = addTag(box, "div")
        option.className = "osada-hpp__choice"

        val title = addTag(option, "div")
        title.className = "osada-hpp__choice-title"
        title.textContent = choice.title

        val effect = addTag(option, "div")
        effect.className = "osada-hpp__choice-effect"
        effect.textContent = choice.effectDescription

        val justification = addTag(option, "div")
        justification.className = "osada-hpp__choice-justification"
        justification.textContent = choice.justification

        option.asButton {
            HeroCampaign.applyPromotionChoice(HeroId(heroId), choice.traitId)
            delTag(box)
            showNext()
        }
    }
}
