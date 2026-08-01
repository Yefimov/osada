package org.osada.ui

import org.osada.hero.CoreFormation
import org.osada.hero.HeroBalance
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroId
import org.osada.hero.HeroTransferService
import org.osada.i18n.I18n

/**
 * The "post this officer somewhere else" dialog behind the roster's Transfer action
 * (DEFERRED.md §1.10).
 *
 * Split out of [CommanderRosterPresenter] to keep that object inside the project's function-count
 * limit, following the same sibling-file convention as the briefing package — and because the two
 * now answer different questions. The roster lists officers; this file is the whole of the
 * reassignment UI, including the two things that were previously missing from it:
 *
 * - **why the action is unavailable.** The Transfer button used to appear for any benched officer
 *   regardless of timing, so outside the initial deployment window it opened a picker that just
 *   said "no formation available" — true, but not the reason. The reason is the window, and it is
 *   now stated ([HeroCampaign.isCommanderTransferWindowOpen]).
 * - **what the move costs.** Every officer who changes formation stops granting it their traits
 *   for a few turns; §26 forbids hidden modifiers, so the dialog says so before the player commits,
 *   not after.
 *
 * Reuses [HeroPromotionPresenter]'s `.osada-hpp` dialog shape rather than the legacy
 * `.smallButton heroPromotionChoice` (DEFERRED.md §4.10/§4.12: same ICON-font trap, where real
 * words render as glyphs, and the same hardcoded z-index that opens behind `#equipment`).
 */
internal object CommanderTransferPicker {
    private const val BOX_ID = "uiHeroTransferBox"

    fun isOpen(): Boolean = byId(BOX_ID) != null

    fun close() = delTag(byId(BOX_ID))

    /** [onTransferred] re-renders the caller's list; it runs only when a move actually happened. */
    fun open(
        heroId: HeroId,
        heroName: String,
        onTransferred: () -> Unit,
    ) {
        val mainBody = byId("mainbody") ?: return
        close()
        val choices = HeroTransferService.transferableFormations(heroId)

        val box = addTag(mainBody, "div")
        box.id = BOX_ID
        box.className = "osada-hpp"

        val titleEl = addTag(box, "div")
        titleEl.className = "osada-hpp__title"
        titleEl.textContent = I18n.t("hero.roster.transfer.title", mapOf("name" to heroName))

        val bodyEl = addTag(box, "div")
        bodyEl.className = "osada-hpp__body"
        bodyEl.textContent = bodyText(choices.isEmpty())

        choices.forEach { formation ->
            val option = addTag(box, "div")
            option.className = "osada-hpp__choice"
            option.textContent = optionLabel(formation)
            option.asButton {
                val moved = HeroTransferService.transferCommander(heroId, formation.id)
                close()
                if (moved) onTransferred()
            }
        }

        val cancel = addTag(box, "div")
        cancel.className = "osada-hpp__choice"
        cancel.textContent = I18n.t("common.cancel.label")
        cancel.asButton { close() }
    }

    /** Names the incumbent on a led formation, so an exchange never looks like a plain move —
     *  the player is deciding the fate of two officers, and both are settling in afterwards. */
    private fun optionLabel(formation: CoreFormation): String {
        val incumbent = formation.assignedHeroId?.let { HeroCampaign.dossier(it) } ?: return formation.displayName
        return I18n.t(
            "hero.roster.transfer.swap",
            mapOf(
                "formation" to formation.displayName,
                "rank" to incumbent.rank,
                "name" to incumbent.name,
            ),
        )
    }

    /**
     * An empty list has two quite different causes and the player can only act on one of them, so
     * they must not share a message: the window being shut is a *timing* problem (end this battle
     * and reassign at the start of the next one), while an open window with nothing to offer means
     * this officer has nowhere to go.
     */
    private fun bodyText(empty: Boolean): String =
        when {
            !HeroTransferService.isWindowOpen() -> I18n.t("hero.roster.transfer.closed")
            empty -> I18n.t("hero.roster.transfer.empty")
            else ->
                I18n.t(
                    "hero.roster.transfer.cost",
                    mapOf("turns" to HeroBalance.DEFAULT.transferSettlingTurns),
                )
        }
}
