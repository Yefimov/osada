package org.osada.ui

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.osada.rules.ruleset.ResolvedRuleset
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RulesetProfile
import org.osada.rules.ruleset.RulesetProfileStore
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.MouseEvent

/**
 * The separate profile editor (`docs/design/ruleset-profiles.md` §7).
 *
 * Opened by **Edit copy…** or **Rename**, never inline in the Rules window. OG's interception
 * bitmask is a labelled choice list, flak range is a bounded stepper and the rest are labelled
 * switches: an unexplained number input would be a rule nobody can check.
 *
 * Saving requires a non-blank, unique display name. Ids stay stable across a rename, so a save or a
 * room that already references the profile keeps resolving.
 *
 * 12 vs. the 11-function budget: one form, one control builder per control TYPE, plus save/close.
 * Splitting the controls from the form they validate would separate a dialog from its own rules.
 */
@Suppress("TooManyFunctions")
internal object RulesEditorWindow {
    const val WINDOW_ID = "osadaRulesEditor"
    const val NAME_ID = "osadaRulesEditorName"
    const val SAVE_ID = "osadaRulesEditorSave"

    private var editing: RulesetProfile? = null
    private var surface = RulesetSelection.Surface.SCENARIO
    private var draft: MutableMap<RuleKey, Int> = mutableMapOf()
    private var renameOnly = false
    private var previouslyFocused: HTMLElement? = null

    /** **Edit copy…**: a new profile seeded from whatever is selected, so the editor starts from
     *  what the player was already looking at rather than an unrelated baseline. */
    fun openCopyOf(forSurface: RulesetSelection.Surface) {
        val resolved = RulesetSelection.resolve(forSurface)
        val seeded =
            RulesetProfile(
                id = RulesetProfileStore.nextId(),
                name = suggestedName(resolved),
                overrides = RuleKey.entries.associateWith { rule -> resolved.effective(rule) },
            )
        open(forSurface, seeded, rename = false)
    }

    /** **Rename**: the same form with the rule controls suppressed, so a rename cannot smuggle a
     *  rule change past the confirmation the player thinks they are giving. */
    fun openRename(forSurface: RulesetSelection.Surface) {
        val profile = RulesetSelection.selectedProfile(forSurface)
        open(forSurface, profile, rename = true)
    }

    fun isOpen(): Boolean = byId(WINDOW_ID) != null

    fun close() {
        byId(WINDOW_ID)?.let {
            clearTag(it)
            delTag(it)
        }
        editing = null
        previouslyFocused?.focus()
        previouslyFocused = null
    }

    private fun open(
        forSurface: RulesetSelection.Surface,
        profile: RulesetProfile,
        rename: Boolean,
    ) {
        close()
        val host = byId("mainbody") ?: return
        surface = forSurface
        editing = profile
        renameOnly = rename
        draft = profile.overrides.toMutableMap()
        previouslyFocused = document.activeElement as? HTMLElement

        val window = addTag(host, "div")
        window.id = WINDOW_ID
        window.className = "osadaRulesWindow osadaRulesEditorWindow"
        window.setAttribute("role", "dialog")
        window.setAttribute("aria-modal", "true")
        window.setAttribute("aria-label", I18n.t("rules.editor.title"))

        val title = addTag(window, "div")
        title.className = "osadaRulesWindow__title"
        title.textContent = I18n.t(if (rename) "rules.editor.title.rename" else "rules.editor.title")

        buildNameField(window, profile)
        if (!rename) RuleKey.entries.forEach { rule -> buildControl(window, rule) }
        buildFooter(window)
        validate()
    }

    private fun buildNameField(
        window: HTMLElement,
        profile: RulesetProfile,
    ) {
        val row = addTag(window, "div")
        row.className = "osadaRulesRow"
        val label = addTag(row, "label")
        label.className = "osadaRulesRow__label"
        label.textContent = I18n.t("rules.profile.name.label")
        label.setAttribute("for", NAME_ID)
        val input = addTag(row, "input") as HTMLInputElement
        input.id = NAME_ID
        input.type = "text"
        input.value = profile.name
        input.oninput = {
            validate()
            Unit
        }
    }

    /** One control per rule, shaped by what the rule IS: labelled choices for the interception
     *  bitmask, a bounded stepper for range, a switch for a boolean. */
    private fun buildControl(
        window: HTMLElement,
        rule: RuleKey,
    ) {
        val row = addTag(window, "div")
        row.className = "osadaRulesRow"
        row.setAttribute("data-rule", rule.key)
        val label = addTag(row, "label")
        label.className = "osadaRulesRow__label"
        label.textContent = RulesText.ruleLabel(rule)
        label.title = RulesText.ruleHelp(rule)
        when (rule) {
            RuleKey.AA_INTERCEPT_MODE, RuleKey.GROUND_FOLLOWS_WEATHER -> choiceControl(row, rule)
            RuleKey.FLAK_RANGE, RuleKey.GROUND_CHANGE_TURNS -> stepperControl(row, rule)
            else -> switchControl(row, rule)
        }
    }

    private fun choiceControl(
        row: HTMLElement,
        rule: RuleKey,
    ) {
        val select = addTag(row, "select") as HTMLSelectElement
        select.className = "osadaRulesSelect"
        for (mode in rule.editorMin..rule.editorMax) {
            val option = addTag(select, "option")
            option.asDynamic().value = mode.toString()
            val text = RulesText.value(rule, mode)
            option.textContent = text
            // Some choices (aa_intercept_mode) are full sentences; the CSS ellipsises the closed
            // select, so the untruncated text has to survive as a tooltip -- on the option for the
            // open dropdown, and kept in sync on the select itself for the closed control.
            option.title = text
        }
        select.asDynamic().value = current(rule).toString()
        select.title = RulesText.value(rule, current(rule))
        select.asDynamic().onchange = {
            val mode = (select.asDynamic().value as? String)?.toIntOrNull() ?: current(rule)
            draft[rule] = mode
            select.title = RulesText.value(rule, mode)
            Unit
        }
    }

    private fun stepperControl(
        row: HTMLElement,
        rule: RuleKey,
    ) {
        val input = addTag(row, "input") as HTMLInputElement
        input.type = "number"
        input.min = rule.editorMin.toString()
        input.max = rule.editorMax.toString()
        input.step = "1"
        input.value = current(rule).toString()
        input.onchange = {
            val value = rule.clampForEditor(input.value.toIntOrNull() ?: current(rule))
            input.value = value.toString()
            draft[rule] = value
            Unit
        }
    }

    private fun switchControl(
        row: HTMLElement,
        rule: RuleKey,
    ) {
        val input = addTag(row, "input") as HTMLInputElement
        input.type = "checkbox"
        input.checked = current(rule) != 0
        input.setAttribute("aria-label", RulesText.ruleLabel(rule))
        input.onchange = {
            draft[rule] = if (input.checked) 1 else 0
            Unit
        }
    }

    private fun buildFooter(window: HTMLElement) {
        val footer = addTag(window, "div")
        footer.className = "osadaRulesActions"
        val error = addTag(window, "div")
        error.id = ERROR_ID
        error.className = "osadaRulesError"
        error.setAttribute("role", "alert")

        val cancel = addTag(footer, "button")
        cancel.className = "osada-button"
        cancel.textContent = I18n.t("common.cancel.label")
        cancel.onclick = { _: MouseEvent -> close() }

        val save = addTag(footer, "button")
        save.id = SAVE_ID
        save.className = "osada-button osadaRulesActionPrimary"
        save.textContent = I18n.t("rules.editor.save.label")
        save.onclick = { _: MouseEvent -> saveDraft() }
        (byId(NAME_ID) as? HTMLInputElement)?.focus()
    }

    /** A non-blank, unique display name is the one hard requirement (§7). */
    private fun validate(): Boolean {
        val profile = editing ?: return false
        val name = (byId(NAME_ID) as? HTMLInputElement)?.value.orEmpty()
        val ok = RulesetProfileStore.isNameAvailable(name, exceptId = profile.id)
        byId(ERROR_ID)?.textContent =
            when {
                ok -> ""
                name.isBlank() -> I18n.t("rules.editor.error.blank_name")
                else -> I18n.t("rules.editor.error.duplicate_name")
            }
        byId(SAVE_ID)?.asDynamic()?.disabled = !ok
        return ok
    }

    private fun saveDraft() {
        val profile = editing ?: return
        if (!validate()) return
        val name = (byId(NAME_ID) as? HTMLInputElement)?.value.orEmpty().trim()
        val overrides = if (renameOnly) profile.overrides else draft.toMap()
        val stored = RulesetProfileStore.save(profile.copy(name = name, overrides = overrides))
        RulesetSelection.select(surface, stored.id)
        close()
        RulesWindow.refresh()
    }

    private fun current(rule: RuleKey): Int = draft[rule] ?: RulesetSelection.resolve(surface).effective(rule)

    /** "Author's Vision (copy)" reads better than "custom-3" and is still unique-checked on save. */
    private fun suggestedName(resolved: ResolvedRuleset): String {
        val seedName = resolved.name.ifBlank { RulesText.profileName(RulesetSelection.selectedProfile(surface)) }
        val base = I18n.t("rules.editor.copy_name", mapOf("name" to seedName))
        if (RulesetProfileStore.isNameAvailable(base)) return base
        var index = 2
        while (!RulesetProfileStore.isNameAvailable("$base $index")) index++
        return "$base $index"
    }

    internal fun resetForTest() {
        close()
        draft = mutableMapOf()
        renameOnly = false
    }

    private const val ERROR_ID = "osadaRulesEditorError"
}
