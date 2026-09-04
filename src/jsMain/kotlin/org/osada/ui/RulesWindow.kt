package org.osada.ui

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.osada.rules.ruleset.ResolvedRuleset
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RulesetProfile
import org.osada.rules.ruleset.RulesetProfileStore
import org.osada.rules.ruleset.RulesetSource
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.MouseEvent

/**
 * The page-level Rules window (`docs/design/ruleset-profiles.md` §§1, 7).
 *
 * Opened from a **Rules** button on Campaign Selection, standalone Scenario Selection and the
 * multiplayer host's Scenario Selection -- never as a modal in front of a launch. It shows the
 * picker with its provenance note, one plain-language row per consequential rule, any
 * content-unavailable warning, the deterministic hash for multiplayer diagnosis, and the
 * create/rename/delete actions for custom profiles.
 *
 * A guest in a room, and an in-progress campaign, see the same window read-only: those rules are
 * already locked.
 *
 * 12 vs. the 11-function budget: the window is picker + summary + actions, and the wording lives in
 * [RulesText] and the editor in [RulesEditorWindow] already. Splitting again would scatter one
 * dialog's construction.
 */
@Suppress("TooManyFunctions")
internal object RulesWindow {
    const val WINDOW_ID = "osadaRulesWindow"
    const val SELECT_ID = "osadaRulesSelect"
    const val BUTTON_ID = "osadaRulesButton"

    private var surface = RulesetSelection.Surface.SCENARIO
    private var readOnly = false
    private var previouslyFocused: HTMLElement? = null

    /** Adds the Rules button to [host]; safe to call more than once.
     *
     * Campaign Selection, Scenario Selection and the multiplayer host's Scenario Selection are
     * all built once and stay in the DOM (only one is ever visible), so each needs its OWN button
     * rather than sharing [BUTTON_ID]: a single shared id meant the last screen built silently
     * stole the only button element, leaving the earlier screen's host empty (the button existed
     * in the DOM, just re-parented under a hidden sibling screen).
     */
    fun installButton(
        host: HTMLElement?,
        forSurface: RulesetSelection.Surface,
        readOnlyWindow: Boolean = false,
    ): HTMLElement? {
        val parent = host ?: return null
        surface = forSurface
        readOnly = readOnlyWindow
        val id = buttonId(forSurface)
        byId(id)?.let { delTag(it) }
        val button = addTag(parent, "div")
        button.id = id
        button.className = "osada-button osadaRulesButton"
        button.textContent = I18n.t("rules.button.label")
        button.title = I18n.t(if (readOnlyWindow) "rules.button.help.locked" else "rules.button.help")
        button.asButton(onActivate = { open(forSurface, readOnlyWindow) })
        return button
    }

    fun isOpen(): Boolean = byId(WINDOW_ID) != null

    fun close() {
        RulesEditorWindow.close()
        byId(WINDOW_ID)?.let {
            clearTag(it)
            delTag(it)
        }
        previouslyFocused?.focus()
        previouslyFocused = null
    }

    fun open(
        forSurface: RulesetSelection.Surface = surface,
        readOnlyWindow: Boolean = readOnly,
    ) {
        if (isOpen()) return
        val host = byId("mainbody") ?: return
        surface = forSurface
        readOnly = readOnlyWindow
        previouslyFocused = document.activeElement as? HTMLElement

        val window = addTag(host, "div")
        window.id = WINDOW_ID
        window.className = "osadaRulesWindow"
        window.setAttribute("role", "dialog")
        window.setAttribute("aria-modal", "true")
        window.setAttribute("aria-label", I18n.t("rules.title"))

        val title = addTag(window, "div")
        title.className = "osadaRulesWindow__title"
        title.textContent = I18n.t("rules.title")

        // Read-only was documented ("a guest in a room, and an in-progress campaign, see the same
        // window read-only") but only ever WIRED for the multiplayer guest case -- Campaign
        // Selection always installed its button with readOnlyWindow=false regardless of whether the
        // selected campaign already had a run, so picking a different profile there silently did
        // nothing (rules lock in at campaign start; resuming a run never re-resolves the
        // selection). Now that StartMenuCampaignScreen reinstalls the button read-only for a
        // campaign that already has one, say WHY in the window itself, not just the button's hover
        // tooltip -- a tooltip is exactly what got missed (2026-08-19 user report).
        if (readOnly) {
            val banner = addTag(window, "div")
            banner.className = "osadaRulesLockedBanner"
            banner.textContent =
                I18n.t(
                    if (forSurface == RulesetSelection.Surface.CAMPAIGN) {
                        "rules.locked.campaign_in_progress"
                    } else {
                        "rules.locked.multiplayer_guest"
                    },
                )
        }

        buildPicker(window)
        addTag(window, "div").apply {
            id = NOTE_ID
            className = "osadaRulesNote"
        }
        addTag(window, "div").apply {
            id = GAPS_ID
            className = "osadaRulesGaps"
        }
        addTag(window, "div").apply {
            id = SUMMARY_ID
            className = "osadaRulesSummary"
        }
        buildActions(window)
        buildHashDetails(window)
        refresh()

        val closeButton = addTag(window, "button")
        closeButton.className = "osada-button osadaRulesWindow__close"
        closeButton.textContent = I18n.t("common.close.label")
        closeButton.onclick = { _: MouseEvent -> close() }
        closeButton.focus()
    }

    /** Rebuilt from scratch after any change, so the picker, the note, the summary and the hash can
     *  never disagree about which profile is selected. */
    fun refresh() {
        if (!isOpen()) return
        val resolved = RulesetSelection.resolve(surface)
        byId(NOTE_ID)?.let { note ->
            note.textContent = RulesText.sourceNote(resolved)
        }
        byId(GAPS_ID)?.let(::refreshGaps)
        byId(SUMMARY_ID)?.let { summary ->
            clearTag(summary)
            RuleKey.entries.forEach { rule -> summaryRow(summary, rule, resolved) }
        }
        byId(HASH_ID)?.textContent = resolved.deterministicHash
        refreshActions(resolved)
    }

    private fun buildPicker(window: HTMLElement) {
        val row = addTag(window, "div")
        row.className = "osadaRulesRow"
        val label = addTag(row, "label")
        label.className = "osadaRulesRow__label"
        label.textContent = I18n.t("rules.profile.label")
        label.setAttribute("for", SELECT_ID)
        val select = addTag(row, "select") as HTMLSelectElement
        select.id = SELECT_ID
        select.className = "osadaRulesSelect"
        select.disabled = readOnly
        RulesetProfileStore.all().forEach { profile -> addOption(select, profile) }
        select.asDynamic().value = RulesetSelection.selectedId(surface)
        select.asDynamic().onchange = {
            // A refused selection (unsupported schema, unknown rules) snaps back rather than
            // pretending the pick took effect.
            RulesetSelection.select(surface, select.asDynamic().value as? String ?: "")
            select.asDynamic().value = RulesetSelection.selectedId(surface)
            refresh()
        }
    }

    private fun addOption(
        select: HTMLSelectElement,
        profile: RulesetProfile,
    ) {
        val option = addTag(select, "option")
        option.asDynamic().value = profile.id
        val unsupported = RulesText.unsupportedReason(profile)
        option.textContent = RulesText.profileName(profile)
        if (unsupported != null) {
            option.asDynamic().disabled = true
            option.title = unsupported
            option.textContent = RulesText.profileName(profile) + " — " + unsupported
        }
    }

    private fun summaryRow(
        summary: HTMLElement,
        rule: RuleKey,
        resolved: ResolvedRuleset,
    ) {
        val entry = resolved.rule(rule)
        val row = addTag(summary, "div")
        val unavailable = if (entry.unavailable) " osadaRulesSummary__row--unavailable" else ""
        // Greyed rather than hidden or silently left looking live: a rule whose prerequisite is off
        // still HAS the value the profile gave it, and it starts working again the moment that
        // prerequisite comes back. Shell craters beside a switched-off barrage were what made the
        // difference between "set" and "doing something" worth showing at all.
        val inert = RulesText.inertNote(rule) { other -> resolved.effective(other) }
        val dimmed = if (inert != null) " osadaRulesSummary__row--inert" else ""
        row.className = "osadaRulesSummary__row$unavailable$dimmed"
        row.setAttribute("data-rule", rule.key)
        val name = addTag(row, "span")
        name.className = "osadaRulesSummary__name"
        name.textContent = RulesText.ruleLabel(rule)
        name.title = RulesText.ruleHelp(rule)
        val value = addTag(row, "span")
        value.className = "osadaRulesSummary__value"
        value.textContent = RulesText.summary(rule, entry)
        value.title = inert ?: RulesText.provenance(entry)
    }

    /**
     * Clears the gap panel.
     *
     * It used to render the Open General Fidelity profile's list of systems that profile did not
     * reproduce. That profile was retired on 2026-08-28 (`docs/og-fidelity-plan.md` §AC): Author's
     * Vision now honours each scenario's own authored switches, so it IS what Open General runs,
     * and a player who wants a specific rule builds a custom ruleset instead of buying a bundle.
     *
     * The function survives as a clear so a list rendered by an older build cannot outlive a
     * reselect, and so the panel and its CSS keep one owner.
     */
    private fun refreshGaps(host: HTMLElement) {
        clearTag(host)
    }

    private fun buildActions(window: HTMLElement) {
        val actions = addTag(window, "div")
        actions.id = ACTIONS_ID
        actions.className = "osadaRulesActions"
    }

    private fun refreshActions(resolved: ResolvedRuleset) {
        val actions = byId(ACTIONS_ID) ?: return
        clearTag(actions)
        if (readOnly) return
        actionButton(actions, "rules.action.edit_copy") { RulesEditorWindow.openCopyOf(surface) }
        // Rename and Delete exist only for a profile the player owns; the two built-ins are not
        // theirs to change.
        if (resolved.source == RulesetSource.CUSTOM) {
            actionButton(actions, "rules.action.rename") { RulesEditorWindow.openRename(surface) }
            actionButton(actions, "rules.action.delete") { confirmDelete(resolved) }
        }
    }

    private fun actionButton(
        actions: HTMLElement,
        key: String,
        onActivate: () -> Unit,
    ) {
        val button = addTag(actions, "div")
        button.className = "osada-button osadaRulesAction"
        button.textContent = I18n.t("$key.label")
        button.title = I18n.t("$key.help")
        button.asButton(onActivate = onActivate)
    }

    /** §7: deleting a profile that a future launch is pointing at confirms first, and says plainly
     *  that saves already written stay reproducible. */
    private fun confirmDelete(resolved: ResolvedRuleset) {
        ConfirmCard.open(
            I18n.t("rules.action.delete.confirm.title"),
            I18n.t("rules.action.delete.confirm.body", mapOf("name" to resolved.name.ifBlank { resolved.id })),
            I18n.t("rules.action.delete.label"),
        ) {
            RulesetProfileStore.delete(resolved.id)
            RulesetSelection.forget(resolved.id)
            close()
            open(surface, readOnly)
        }
    }

    private fun buildHashDetails(window: HTMLElement) {
        val details = addTag(window, "details")
        details.className = "osadaRulesHash"
        val summary = addTag(details, "summary")
        summary.textContent = I18n.t("rules.hash.label")
        summary.title = I18n.t("rules.hash.help")
        val value = addTag(details, "code")
        value.id = HASH_ID
        value.className = "osadaRulesHash__value"
    }

    internal fun resetForTest() {
        close()
        readOnly = false
        surface = RulesetSelection.Surface.SCENARIO
        RulesetSelection.Surface.entries.forEach { byId(buttonId(it))?.let { button -> delTag(button) } }
    }

    private fun buttonId(forSurface: RulesetSelection.Surface): String = "$BUTTON_ID-${forSurface.name.lowercase()}"

    private const val NOTE_ID = "osadaRulesNote"
    private const val GAPS_ID = "osadaRulesGaps"
    private const val SUMMARY_ID = "osadaRulesSummaryBody"
    private const val ACTIONS_ID = "osadaRulesActionsBody"
    private const val HASH_ID = "osadaRulesHashValue"
}
