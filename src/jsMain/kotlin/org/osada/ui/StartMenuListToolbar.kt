package org.osada.ui

import org.osada.model.Equipment
import org.osada.model.getCountryName
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.events.Event

/**
 * [StartMenuBuilder]'s shared campaign/scenario register plumbing: the synced-list-to-select
 * wiring, dossier theater placeholder, and the sort/filter/side-chip toolbar (with its row
 * tagging and country-label lookups). Split out purely to keep [StartMenuBuilder] within the
 * project's function-count/class-size limits -- shared by [StartMenuCampaignScreen] and
 * [StartMenuScenarioScreen].
 */
internal object StartMenuListToolbar {
    // Flag sprite cell width in the flags_med.png strip (matches RenderContext.flagIconWidth).
    const val FLAG_SPRITE_WIDTH = 21

    const val SORT_DEFAULT = "Default"
    const val SORT_NAME = "A–Z"
    const val SORT_YEAR = "Year"
    const val SORT_SIZE = "Length"
    private const val SIDE_ALL = "all"

    // Sortable-list rows with no date/size sort last, not first -- padded so string comparison
    // still orders numerically.
    private const val UNDATED_SORT_YEAR = 9999
    private const val SORT_YEAR_PAD_WIDTH = 4
    private const val UNSIZED_SORT_VALUE = 99999

    /**
     * Builds a visible row list mirrored to a hidden native <select> (the source of truth).
     * Clicking a row sets selectedIndex and dispatches a real `change` event so the existing
     * onchange handler runs untouched; [syncListHighlight] keeps the rows in sync when the
     * selection is set programmatically. Disabled options render as non-clickable group rows.
     */
    fun buildSyncedList(
        select: HTMLElement,
        container: HTMLElement,
        renderRow: (option: HTMLOptionElement, index: Int, row: HTMLElement, selectable: Boolean) -> Unit,
    ) {
        clearTag(container)
        val options = select.asDynamic().options
        val length = options.length as? Int ?: 0
        for (i in 0 until length) {
            val option = options[i] as? HTMLOptionElement ?: continue
            val selectable = option.disabled != true
            val row = addTag(container, "div")
            row.className = if (selectable) "osadaListRow" else "osadaListRow osadaListRow--group"
            row.asDynamic().optionIndex = i
            renderRow(option, i, row, selectable)
            if (selectable) {
                row.onclick = { _: org.w3c.dom.events.MouseEvent ->
                    select.asDynamic().selectedIndex = i
                    select.dispatchEvent(Event("change"))
                }
            }
        }
        syncListHighlight(select, container)
    }

    /** Re-highlights the row whose optionIndex matches the select's current selectedIndex. */
    fun syncListHighlight(
        select: HTMLElement,
        container: HTMLElement,
    ) {
        val selected = select.asDynamic().selectedIndex as? Int ?: -1
        val rows = container.children
        for (i in 0 until rows.length) {
            val row = rows.asDynamic()[i] as? HTMLElement ?: continue
            val idx = row.asDynamic().optionIndex as? Int ?: -1
            if (idx == selected) {
                row.classList.add("osadaListRow--selected")
            } else {
                row.classList.remove("osadaListRow--selected")
            }
        }
    }

    fun theaterPlaceholder(parent: HTMLElement) {
        val theater = addTag(parent, "div")
        theater.className = "osadaTheater"
    }

    /** Extract a "1936-1945"-style year span from a campaign/scenario title's parentheses. */
    fun extractYears(title: String): String {
        // Match (content with digits) - handles both 4-digit years and 2-digit BC years like (73-71 BC)
        val m = Regex("\\(([^)]*\\d{1,4}[^)]*)\\)").find(title) ?: return ""
        return m.groupValues[1].trim()
    }

    /** Stamps the sort/search keys a row is ranked and filtered by. [year] and [size] are absent
     *  for scenario rows, which sort by campaign (document order) or by name only. [sides] is the
     *  full set of side-filter labels ([countryDisplayLabel]) for every country playable in this
     *  row's scenario/campaign — a row matches the dropdown if ANY of them is selected. Empty
     *  (group headers) never matches a side filter. [forceHidden] permanently excludes the row
     *  regardless of filter/search/sort state (used to hide specific campaigns from this
     *  register — see [StartMenuCampaignScreen.hiddenCampaignFiles]). */
    fun tagRow(
        row: HTMLElement,
        index: Int,
        name: String,
        searchText: String = name,
        year: Int? = null,
        size: Int? = null,
        sides: List<String> = emptyList(),
        forceHidden: Boolean = false,
    ) {
        // Sort keys are strings, and numeric ones are zero-padded to a fixed width, so one plain
        // string comparison serves every mode (no per-mode comparator plumbing).
        fun pad(
            value: Int,
            width: Int = 5,
        ) = value.toString().padStart(width, '0')

        val dyn = row.asDynamic()
        dyn.sortDefault = pad(index)
        dyn.sortName = name.lowercase()
        // Undated/unsized rows sort last rather than silently first. Name is appended as a
        // tie-breaker so equal years / equal lengths still come out in a stable, readable order.
        dyn.sortYear = pad(year ?: UNDATED_SORT_YEAR, SORT_YEAR_PAD_WIDTH) + name.lowercase()
        dyn.sortSize = pad(size ?: UNSIZED_SORT_VALUE) + name.lowercase()
        dyn.searchText = searchText.lowercase()
        dyn.sideKeys = sides.toTypedArray()
        dyn.forceHidden = forceHidden
    }

    // ---- Side filter dropdown ---------------------------------------------------------------
    // Labels are keyed by the numeric country CODE, not the resolved name: several factions in
    // Equipment.countryNames share a literal name ("Germany" appears 3 times, "USSR"/"Soviet
    // Union" 3 times) while others read confusingly on their own ("Red Russia" / "White Russia" /
    // "Cossack Hosts" don't visually cluster as "the same country" in an alphabetical dropdown).
    // countryDisplayLabel re-labels the handful of ids where that actually matters (user request);
    // everything else falls back to Equipment.getCountryName so no country can silently vanish.

    /** Curated overrides for country ids whose raw [Equipment.countryNames] entry either collides
     *  with another id's name, or would otherwise scatter alphabetically away from the other
     *  factions of the same nation. The dominant/"default" id for a nation (e.g. plain Germany,
     *  id 7, reused across every era after the eqp-merge) is deliberately left unlabeled — only
     *  the rarer, colliding ids get a "Nation — Faction" suffix so they cluster under it. Verified
     *  against the ~400 scenarios in scenariolist.js (2026-07-14): 55 distinct country ids appear;
     *  every id below was confirmed by checking which scenario(s) actually use it. */
    private val countryDisplayOverrides =
        mapOf(
            // Germany: id 7 stays plain "Germany" (113 scenarios, every era); id 86 is the same
            // regime under a different eqp-lxf code (RD Road To/Siege Of Berlin, 1945) — merge it.
            86 to "Germany",
            117 to "Germany — Empire", // German Empire (Kaiserreich, WW1-era campaigns)
            196 to "Germany — Revolutionaries", // German Revolutionaries (1918-19 Räterepublik)
            188 to "Germany — Communists", // Red Germany
            303 to "Germany — Waffen SS",
            // Russia: id 19/61/89 are three efiles' spelling of the same Soviet Union and stay
            // merged as before; the OTHER Russia-named factions are civil-war-era and distinct from
            // each other AND from the USSR, but read better clustered under "Russia — X".
            19 to "Soviet Union",
            61 to "Soviet Union",
            89 to "Soviet Union",
            103 to "Russia — Communists", // Red Russia
            100 to "Russia — Whites", // White Russia
            189 to "Russia — Greens", // Russian Green Armies
            191 to "Russia — Cossacks", // Cossack Hosts
            // Hungary: id 4 stays plain; Red Hungary is the 1919 Soviet Republic.
            187 to "Hungary — Communists",
            // Spain: bn9s00 "Battle of Sesena" (eqp-lxf, id 28) confirmed vs a Soviet Union opponent —
            // a 1936 Nationalist offensive on Madrid, so id 28 is the Nationalist side, same as id 225.
            28 to "Spain — Nationalists",
            225 to "Spain — Nationalists",
            226 to "Spain — Republicans (Popular Army)",
            91 to "Spain — Republicans",
            // USA: id 9 stays plain; the Civil War factions don't share the "USA" word at all.
            150 to "USA — Confederacy", // Confederate States
            162 to "USA — Union", // Union States
        )

    /** The side-filter label for country [id], or null (never matches a filter) for an invalid/
     *  blank/"Unknown" code — mirrors the old name-based blank check. */
    fun countryDisplayLabel(id: Int): String? {
        val override = countryDisplayOverrides[id]
        if (override != null) return override
        val name = Equipment.getCountryName(id)
        return if (name.isBlank() || name == "Unknown") null else name
    }

    private fun rowsOf(list: HTMLElement): List<HTMLElement> {
        val children = list.children
        return (0 until children.length).mapNotNull { children.asDynamic()[it] as? HTMLElement }
    }

    /** Re-sorts and re-filters [list] from the mode/query/side-chip/story-chip stashed on it by
     *  the toolbar (and by [StartMenuCampaignStory] for the story marker), and updates the
     *  results counter. Not private: callers whose story-detection result lands asynchronously,
     *  after the initial render, re-invoke this to fold the new information into the current
     *  view without the player touching a control. */
    fun applyListView(list: HTMLElement) {
        val mode = list.asDynamic().sortMode as? String ?: SORT_DEFAULT
        val query = (list.asDynamic().filterQuery as? String ?: "").trim().lowercase()
        val side = list.asDynamic().sideFilter as? String ?: SIDE_ALL
        val storyOnly = list.asDynamic().storyOnly as? Boolean ?: false
        val rows = rowsOf(list)

        // A campaign-group header only means something in the campaign-ordered view; any other
        // sort interleaves campaigns, so the headers are hidden and the list reads as one flat run.
        val grouped = mode == SORT_DEFAULT
        val sorted =
            rows.sortedBy { row ->
                val dyn = row.asDynamic()
                when (mode) {
                    SORT_NAME -> dyn.sortName as? String
                    SORT_YEAR -> dyn.sortYear as? String
                    SORT_SIZE -> dyn.sortSize as? String
                    else -> dyn.sortDefault as? String
                } ?: ""
            }
        sorted.forEach { list.appendChild(it) }

        val matches = applyRowVisibility(sorted, grouped, query, side, storyOnly)

        (list.asDynamic().counterEl as? HTMLElement)?.let { counter ->
            val noun = list.asDynamic().counterNoun as? String ?: "entries"
            val singular = noun.removeSuffix("s")
            counter.textContent = "$matches ${if (matches == 1) singular else noun}"
        }
    }

    /** Hides non-matching rows, then hides any group header left with nothing under it. Returns
     *  the number of matching (non-group) rows. */
    private fun applyRowVisibility(
        sorted: List<HTMLElement>,
        grouped: Boolean,
        query: String,
        side: String,
        storyOnly: Boolean,
    ): Int {
        var currentGroup: HTMLElement? = null
        var groupHasMatch = false
        var matches = 0

        fun closeGroup() {
            currentGroup?.style?.display = if (grouped && groupHasMatch) "" else "none"
        }
        for (row in sorted) {
            if (row.classList.contains("osadaListRow--group")) {
                closeGroup()
                currentGroup = row
                groupHasMatch = false
                continue
            }
            if (applyRowMatch(row, query, side, storyOnly)) {
                groupHasMatch = true
                matches++
            }
        }
        closeGroup()
        return matches
    }

    /** Sets [row]'s visibility from [query]/[side]/[storyOnly], and reports whether it matched. */
    private fun applyRowMatch(
        row: HTMLElement,
        query: String,
        side: String,
        storyOnly: Boolean,
    ): Boolean {
        val forceHidden = row.asDynamic().forceHidden as? Boolean ?: false
        val text = row.asDynamic().searchText as? String ?: ""
        val rowSides = (row.asDynamic().sideKeys as? Array<String>) ?: emptyArray()
        val isStory = row.asDynamic().storyFlag as? Boolean ?: false
        val match =
            !forceHidden &&
                (query.isEmpty() || text.contains(query)) &&
                (side == SIDE_ALL || side in rowSides) &&
                (!storyOnly || isStory)
        row.style.display = if (match) "" else "none"
        return match
    }

    /** Filter box + sort segments + side chips + results counter, inserted above [list] inside
     *  its register column. */
    fun buildListToolbar(
        register: HTMLElement,
        list: HTMLElement,
        modes: List<String>,
        placeholder: String,
        counterNoun: String,
    ) {
        val tools = addTag(register, "div")
        tools.className = "osadaListTools"
        // The register is a flex column whose list already exists — put the toolbar above it.
        register.insertBefore(tools, list)

        val filter = addTag(tools, "input")
        filter.className = "osadaListFilter"
        filter.setAttribute("type", "search")
        filter.setAttribute("placeholder", placeholder)
        filter.asDynamic().oninput = {
            list.asDynamic().filterQuery = filter.asDynamic().value as? String ?: ""
            applyListView(list)
        }

        // Second row: side dropdown (left, same control style as the equipment window's country
        // select) + results counter (right).
        val chipRow = addTag(register, "div")
        chipRow.className = "osadaChipRow"
        register.insertBefore(chipRow, list)
        val sideSelect = addTag(chipRow, "select")
        sideSelect.className = "osadaSideSelect"
        sideSelect.title = "Filter by country (any side — including AI-only factions)"
        // Options = every faction actually playable in this register (rows are already built and
        // tagged by the time the toolbar is inserted, one row can carry several sideKeys),
        // alphabetical after "All countries".
        addSelectOption(sideSelect, "All countries", SIDE_ALL, true)
        rowsOf(list)
            .flatMap { (it.asDynamic().sideKeys as? Array<String>)?.toList() ?: emptyList() }
            .distinct()
            .sortedBy { it.lowercase() }
            .forEach { addSelectOption(sideSelect, it, it, false) }
        sideSelect.asDynamic().onchange = {
            val key = sideSelect.asDynamic().value as? String ?: SIDE_ALL
            list.asDynamic().sideFilter = key
            applyListView(list)
        }
        val counter = addTag(chipRow, "div")
        counter.className = "osadaListCount"
        list.asDynamic().counterEl = counter
        list.asDynamic().counterNoun = counterNoun
        list.asDynamic().sideFilter = SIDE_ALL

        val segs = addTag(tools, "div")
        segs.className = "osadaListSorts"
        modes.forEach { mode ->
            val seg = addTag(segs, "div")
            seg.className = "osada-seg" + if (mode == SORT_DEFAULT) " osada-seg--on" else ""
            seg.textContent = mode
            seg.title =
                when (mode) {
                    SORT_DEFAULT -> "Campaign order"
                    SORT_NAME -> "Alphabetical"
                    SORT_YEAR -> "Earliest year first"
                    SORT_SIZE -> "Fewest operations first"
                    else -> mode
                }
            seg.onclick = { _: org.w3c.dom.events.MouseEvent ->
                rowsOf(segs).forEach { it.classList.remove("osada-seg--on") }
                seg.classList.add("osada-seg--on")
                list.asDynamic().sortMode = mode
                applyListView(list)
            }
        }
        list.asDynamic().sortMode = SORT_DEFAULT
        list.asDynamic().filterQuery = ""
        // Apply the initial view once: without this, forceHidden rows stayed visible and the
        // results counter stayed empty until the user first touched a filter/sort control.
        applyListView(list)
    }
}
