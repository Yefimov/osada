package org.osada.ui

import org.osada.hero.HeroArchiveService
import org.osada.hero.HeroDeskRecord
import org.osada.hero.HeroRecordSource
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * Renders one Hero Desk card (`docs/design/hero-desk-and-profile-archive.md` §6).
 *
 * Split from [HeroDeskPresenter] so the desk's shell (filters, search, dossier routing) and the
 * card itself stay within the project's per-object function budget, and so the card's rules —
 * status as text rather than colour, a provenance note only where it matters, the delete action
 * only where a career can actually be lost — read in one place.
 *
 * Every dynamic value goes in through `textContent`: a formation name is player-typed text.
 */
internal object HeroDeskCards {
    /**
     * Appends a card for [record] to [list] and returns it, labelled but not yet activated —
     * [HeroDeskPresenter] attaches the single [asButton] activation path so it can capture this
     * exact element as the focus target the dossier returns to.
     *
     * [onArchiveDeleted] refreshes the desk after a confirmed archive deletion.
     */
    fun render(
        list: HTMLElement,
        record: HeroDeskRecord,
        onArchiveDeleted: () -> Unit,
    ): HTMLElement {
        val card = addTag(list, "div")
        card.className = "osada-hero-rosterrow osada-hero-desk-card"

        buildPortrait(card, record)

        val copy = addTag(card, "div")
        copy.className = "osada-hero-rosterrow-copy"
        heroDeskText(copy, "osada-hero-rosterrow-name", "${record.rank} ${record.name}".trim())
        heroDeskText(copy, "osada-hero-rosterrow-sub", "${record.campaignName} · ${formationLabel(record)}")
        // Status and renown are TEXT, never colour alone (§6). The renown frame on the portrait is
        // a redundant second channel, not the only one.
        heroDeskText(copy, "osada-hero-rosterrow-sub", statusLine(record))
        sourceNote(record)?.let { heroDeskText(copy, "osada-hero-desk-source", it) }

        buildDeleteAction(card, record, onArchiveDeleted)

        card.title = I18n.t("hero.desk.card.open", mapOf("hero" to "${record.rank} ${record.name}".trim()))
        return card
    }

    private fun buildPortrait(
        card: HTMLElement,
        record: HeroDeskRecord,
    ) {
        val portrait = addTag(card, "div")
        portrait.className = "osada-hero-rosterrow-portrait ${record.renownClass}".trim()
        // The deterministic monogram sits under every portrait, so missing art degrades to initials
        // rather than to an empty frame (§8).
        portrait.textContent =
            record.name
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
                .take(2)
                .joinToString("") { it.take(1).uppercase() }
        portrait.setAttribute("aria-label", I18n.t("hero.desk.portrait.alt", mapOf("hero" to record.name)))
        record.dossier?.let { dossier ->
            PortraitRenderer.render(
                portrait,
                dossier.portrait,
                dossier.portraitSeed,
                gray = record.inMemoriam,
                artPath = dossier.portraitArt,
            )
        }
    }

    /**
     * "Delete archived career" (§4): offered only for a career no live run would rewrite, names the
     * campaign, and confirms the loss. Clearing a campaign slot deliberately does NOT do this —
     * that is how a fallen officer survives an abandoned run.
     */
    private fun buildDeleteAction(
        card: HTMLElement,
        record: HeroDeskRecord,
        onArchiveDeleted: () -> Unit,
    ) {
        if (record.source != HeroRecordSource.ARCHIVE || record.resumableRun) return
        val button = addTag(card, "button")
        button.className = "osada-hero-locate"
        button.textContent = I18n.t("hero.desk.delete.label")
        button.title = I18n.t("hero.desk.delete.help")
        button.onclick = { event: MouseEvent ->
            event.stopPropagation()
            confirmDelete(record, onArchiveDeleted)
        }
    }

    private fun confirmDelete(
        record: HeroDeskRecord,
        onArchiveDeleted: () -> Unit,
    ) {
        val campaign = record.campaignName
        ConfirmCard.open(
            I18n.t("hero.desk.delete.confirm.title", mapOf("campaign" to campaign)),
            I18n.t("hero.desk.delete.confirm.body", mapOf("campaign" to escapeHeroDeskHtml(campaign))),
            I18n.t("hero.desk.delete.confirm.confirm_button"),
        ) {
            HeroArchiveService.deleteCampaign(record.campaignRunId)
            onArchiveDeleted()
        }
    }
}

// Top-level (not object members) so they do not count against the objects' function budgets, in the
// same spirit as GameStatePersistence's own top-level helpers.

private fun formationLabel(record: HeroDeskRecord): String = record.formationName ?: I18n.t("hero.desk.formation.none")

private fun statusLine(record: HeroDeskRecord): String =
    listOfNotNull(
        // "Retired from this campaign" is a PRESENTATION of a survivor of a finished run; the
        // stored status is untouched and still shown beside it (§4).
        if (record.retiredFromRun) I18n.t("hero.desk.retired_from_run") else null,
        record.statusLabel.takeIf { it.isNotBlank() },
        record.renownLabel.takeIf { it.isNotBlank() },
        record.potentialLabel.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

/** Only where the difference would matter to the player (§6): a live run needs no note. */
private fun sourceNote(record: HeroDeskRecord): String? =
    when (record.source) {
        HeroRecordSource.LIVE -> null
        HeroRecordSource.ARCHIVE -> I18n.t("hero.desk.source.archive")
        HeroRecordSource.LEGACY -> I18n.t("hero.desk.source.legacy")
    }

/** The confirmation body is injected as HTML; a campaign name may come from an imported file. */
internal fun escapeHeroDeskHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

internal fun heroDeskText(
    parent: HTMLElement,
    className: String,
    text: String,
): HTMLElement {
    val el = addTag(parent, "div")
    el.className = className
    el.textContent = text
    return el
}
