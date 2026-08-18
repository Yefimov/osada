package org.osada.ui

import kotlinx.browser.document
import org.osada.hero.HeroDeskFilter
import org.osada.hero.HeroDeskModel
import org.osada.hero.HeroDeskRecord
import org.osada.hero.HeroDeskSource
import org.osada.hero.LegacyHeroRecord
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * The main-menu **Hero Desk** (`docs/design/hero-desk-and-profile-archive.md` §6): the one
 * cross-campaign place to inspect regular, legendary, active, wounded, missing, fallen and retired
 * commanders, drawn from live campaign-run snapshots and the profile archive together.
 *
 * The Hall of Fame is a FILTER here, not a separate collection — the old summary-only presenter is
 * gone, and its store survives only as this desk's migration source.
 *
 * Constraints this file honours literally:
 *
 * - an inspection/archive surface only: nothing here recruits, transfers, edits or awards anything;
 * - reading a card restores no game and changes no selection — every fact comes through
 *   [HeroDeskSource.deskData], which parses snapshots and never touches `GameHolder`;
 * - keyboard, mouse and coarse pointer share ONE activation path ([asButton]), and closing a
 *   dossier returns focus to the card that opened it with the filter and scroll state intact.
 *
 * Card rendering lives in [HeroDeskCards] and the limited legacy dossier in
 * [HeroDeskLegacyDossier], to keep each object inside the project's function budget.
 */
internal object HeroDeskPresenter {
    private const val BOX_ID = "uiHeroDesk"
    private const val LIST_ID = "uiHeroDeskList"
    private const val SEARCH_ID = "uiHeroDeskSearch"

    private var filter = HeroDeskFilter.ALL
    private var query = ""
    private var records: List<HeroDeskRecord> = emptyList()
    private var unreadableRuns: List<String> = emptyList()
    private var focusAfterDossier: HTMLElement? = null

    fun isOpen(): Boolean = byId(BOX_ID) != null

    fun close() {
        LeaderDossierPresenter.close()
        HeroDeskLegacyDossier.close()
        delTag(byId(BOX_ID))
    }

    fun open() {
        close()
        val parent = document.body ?: return
        val data = HeroDeskSource.deskData(legacySummaries())
        records = data.records
        unreadableRuns = data.unreadableRuns

        val box = addTag(parent, "div")
        box.id = BOX_ID
        box.className = "osada-dossier osada-hero-roster osada-hero-desk"

        buildHeader(box)
        buildControls(box)
        val list = addTag(box, "div")
        list.id = LIST_ID
        list.className = "osada-hero-tabbody osada-hero-desk-list"
        renderList()
    }

    private fun buildHeader(box: HTMLElement) {
        val header = addTag(box, "div")
        header.className = "osada-hero-header"
        val title = addTag(header, "div")
        title.className = "osada-hero-id"
        heroDeskText(title, "osada-hero-name", I18n.t("hero.desk.title"))
        heroDeskText(
            title,
            "osada-hero-sub",
            I18n.t("hero.desk.count", mapOf("count" to records.size)),
        ).title = I18n.t("hero.desk.help")
        // A run whose every generation failed validation is NAMED, never silently omitted: the desk
        // raises the same recovery warning Campaign Selection does rather than presenting an
        // incomplete roster as complete (§2/§8).
        if (unreadableRuns.isNotEmpty()) {
            heroDeskText(
                title,
                "osada-hero-desk-warning",
                I18n.t("hero.desk.unreadable", mapOf("campaigns" to unreadableRuns.joinToString(", "))),
            )
        }
        val closeIcon = addTag(header, "span")
        closeIcon.className = "osada-ico osada-ico--close osada-hero-close"
        closeIcon.title = I18n.t("common.close.label")
        closeIcon.asButton(ariaLabel = I18n.t("common.close.label")) { close() }
    }

    private fun buildControls(box: HTMLElement) {
        val bar = addTag(box, "div")
        bar.className = "osada-hero-tabs osada-hero-desk-filters"
        val buttons =
            HeroDeskFilter.entries.map { entry ->
                val button = addTag(bar, "div")
                button.className = "osada-hero-tab"
                button.textContent = HeroDeskModel.filterLabel(entry)
                button.title = I18n.t("hero.desk.filter.help")
                button
            }
        buttons.forEachIndexed { index, button ->
            val entry = HeroDeskFilter.entries[index]
            button.asButton {
                filter = entry
                buttons.forEachIndexed { i, b -> toggleButton(b, i == index) }
                renderList()
            }
            toggleButton(button, entry == filter)
        }

        val searchRow = addTag(box, "div")
        searchRow.className = "osada-hero-desk-search"
        val input = addTag(searchRow, "input") as HTMLInputElement
        input.id = SEARCH_ID
        input.type = "search"
        input.value = query
        input.placeholder = I18n.t("hero.desk.search.placeholder")
        input.title = I18n.t("hero.desk.search.help")
        input.setAttribute("aria-label", I18n.t("hero.desk.search.help"))
        input.oninput = { _ ->
            query = input.value
            renderList()
        }
    }

    private fun renderList() {
        val list = byId(LIST_ID) ?: return
        clearTag(list)
        val visible = HeroDeskModel.view(records, filter, query)
        if (visible.isEmpty()) {
            heroDeskText(list, "osada-hero-empty", emptyStateText(records.isEmpty(), query))
            return
        }
        visible.forEach { record ->
            val card = HeroDeskCards.render(list, record, onArchiveDeleted = ::open)
            // Focus target captured here, at build time: closing the dossier must return the player
            // to the card they opened, with the desk's filter and scroll state untouched (§6).
            card.asButton(ariaLabel = card.title) {
                focusAfterDossier = card
                openDossier(record)
            }
        }
    }

    // ---------------------------------------------------------------------- dossier

    fun isDossierOpen(): Boolean = isOpen() && (LeaderDossierPresenter.isOpen() || HeroDeskLegacyDossier.isOpen())

    fun closeDossier() {
        LeaderDossierPresenter.close()
        HeroDeskLegacyDossier.close()
        restoreFocus()
    }

    private fun openDossier(record: HeroDeskRecord) {
        val dossier = record.dossier
        if (dossier == null) {
            LeaderDossierPresenter.close()
            HeroDeskLegacyDossier.open(record, ::restoreFocus)
            return
        }
        HeroDeskLegacyDossier.close()
        LeaderDossierPresenter.openForView(
            dossier,
            parent = document.body,
            // Nothing on the main menu has a map to locate an officer on.
            allowLocate = false,
            onClose = ::restoreFocus,
        )
    }

    private fun restoreFocus() {
        focusAfterDossier?.focus()
        focusAfterDossier = null
    }
}

// Top-level (not object members) so they do not count against the object's function budget.

/** Distinguishes "you have no commanders yet" from "this filter/search matches none of them". */
private fun emptyStateText(
    noRecordsAtAll: Boolean,
    query: String,
): String =
    when {
        noRecordsAtAll -> I18n.t("hero.desk.empty")
        query.isNotBlank() -> I18n.t("hero.desk.empty.search")
        else -> I18n.t("hero.desk.empty.filter")
    }

/**
 * Migrated Hall of Fame summaries (§5).
 *
 * Read here rather than inside the archive service: the migration source is a UI-package store, and
 * the hero package must not depend on the UI package to answer "who is in the archive?".
 */
private fun legacySummaries(): List<LegacyHeroRecord> =
    HallOfFame.all().map {
        LegacyHeroRecord(
            name = it.name,
            rank = it.rank,
            renown = it.renown,
            potential = it.potential,
            status = it.status,
            campaignName = it.campaign,
        )
    }
