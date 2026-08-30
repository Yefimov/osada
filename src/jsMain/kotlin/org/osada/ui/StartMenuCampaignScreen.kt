package org.osada.ui

import org.osada.DEBUG_CAMPAIGN
import org.osada.Game
import org.osada.current
import org.osada.hero.HeroArchiveService
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.getCountryNameByEqp
import org.osada.scenario.Campaign
import org.w3c.dom.HTMLElement

/**
 * [StartMenuBuilder]'s campaign-selection screen: the difficulty picker and the campaign
 * register (select dropdown, synced list, dossier). Split out purely to keep [StartMenuBuilder]
 * within the project's function-count/class-size limits -- not expected to be called from
 * elsewhere. Display/data helpers live in [StartMenuCampaignData]; shared register plumbing in
 * [StartMenuListToolbar]. Still 12 vs. the 11-function budget after that split.
 */
@Suppress("TooManyFunctions")
internal object StartMenuCampaignScreen {
    fun buildCampaignSelection() {
        val campSelect = buildCampaignSelectDropdown()
        buildDifficultySelector()
        wireCampaignHandlers(campSelect)
    }

    private fun buildCampaignSelectDropdown(): HTMLElement {
        val campSelect = addTag("smCampSel", "select")
        StartMenuBuilder.campaignList().forEachIndexed { index, campaign ->
            // Hidden campaigns get no <option> at all (not just a hidden row): this also keeps
            // them out of the default selection and the search index. Options carry the ORIGINAL
            // campaignlist index in `value`, which is what every consumer reads — position-based
            // lookups against campaignList() are wrong once the list is filtered here.
            if ((campaign.file as? String) in StartMenuCampaignData.hiddenCampaignFiles) return@forEachIndexed
            val option = addTag(campSelect, "option")
            option.asDynamic().value = index.toString()
            option.textContent = CampaignContentLocalization.title(campaign)
        }
        return campSelect
    }

    private fun buildDifficultySelector() {
        val difficultyOptions =
            listOf(
                Triple(StartMenuCampaignData.DIFFICULTY_HISTORICAL, "campaign.difficulty.historical.label", false),
                Triple(StartMenuCampaignData.DIFFICULTY_TACTICAL, "campaign.difficulty.tactical.label", true),
                Triple(StartMenuCampaignData.DIFFICULTY_OPERATIONAL, "campaign.difficulty.operational.label", false),
            )
        // The "?" help button used to REPLACE the campaign description with a wall of static
        // text (a hidden mode the player had to discover, and it clobbered the actual campaign
        // blurb). Folded into a hint line that lives right under the control instead — updates
        // on hover (preview) and stays on the SELECTED difficulty otherwise, so touch users
        // (no hover) still see it. The button itself is now redundant; hide rather than delete
        // (it's static HTML in index.html, not builder-created).
        byId("smCampDifHelp")?.style?.display = "none"
        val difHint = addTag(byId("smCampDif")?.parentElement, "div")
        difHint.id = "smCampDifHint"
        difHint.className = "osada-dif-hint"

        // Custom segmented difficulty control (replaces the native <select>). The chosen
        // value is stashed on #smCamp and read by the Start handler.
        difficultyOptions.forEach { (value, labelKey, selected) ->
            val seg = addTag("smCampDif", "div")
            seg.className = "osada-seg" + if (selected) " osada-seg--on" else ""
            seg.textContent = I18n.t(labelKey)
            seg.title = StartMenuCampaignData.difficultyHint(value)
            seg.asDynamic().diffValue = value
            if (selected) {
                byId("smCamp")?.asDynamic()?.selectedDifficulty = value
                difHint.textContent = StartMenuCampaignData.difficultyHint(value)
            }
            seg.onmouseenter = { _: org.w3c.dom.events.Event ->
                difHint.textContent = StartMenuCampaignData.difficultyHint(value)
            }
            seg.onmouseleave = { _: org.w3c.dom.events.Event ->
                val current =
                    byId("smCamp")?.asDynamic()?.selectedDifficulty as? Int
                        ?: StartMenuCampaignData.DIFFICULTY_HISTORICAL
                difHint.textContent = StartMenuCampaignData.difficultyHint(current)
            }
            seg.onclick = { _: org.w3c.dom.events.MouseEvent ->
                byId("smCamp")?.asDynamic()?.selectedDifficulty = value
                byId("smCampDif")?.children?.let { segs ->
                    for (i in 0 until segs.length) {
                        (segs.asDynamic()[i] as? HTMLElement)?.classList?.remove("osada-seg--on")
                    }
                }
                seg.classList.add("osada-seg--on")
                difHint.textContent = StartMenuCampaignData.difficultyHint(value)
                // 0b: shown prestige must track difficulty, matching what Start will grant.
                StartMenuCampaignData.updateCampaignPrestigeDisplay()
            }
        }
    }

    private fun wireCampaignHandlers(campSelect: HTMLElement) {
        campSelect.title = I18n.t("campaign.select.help")
        campSelect.asDynamic().onchange = { onCampSelectChange(campSelect) }
        buildCampaignScreen(campSelect)

        byId("smCBackBut")?.title = I18n.t("campaign.back.help")
        byId("smCBackBut")?.setAttribute("data-label", I18n.t("common.back.label"))
        byId("smCBackBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            makeHidden("smCamp")
            makeVisible("smMain")
        }

        byId("smCPlayBut")?.title = I18n.t("campaign.start.help")
        byId("smCPlayBut")?.setAttribute("data-label", I18n.t("campaign.start.label"))
        byId("smCPlayBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            val selectedCampaign = byId("smCamp")?.asDynamic()?.selectedCampaign as? Int
            val difficulty =
                byId("smCamp")?.asDynamic()?.selectedDifficulty as? Int ?: StartMenuCampaignData.DIFFICULTY_HISTORICAL
            val campaign = selectedCampaign?.let { StartMenuBuilder.campaignList().getOrNull(it) }
            val file = campaign?.file as? String
            val existingRun = file?.let { StartMenuCampaignData.campaignRunsByFile()[it] }
            when {
                selectedCampaign == null -> Unit
                // Pressing Play/Start on an already-started campaign resumes it, never silently
                // replaces it (save-recovery.md sec 2). Starting over is a separate, guarded
                // action -- see the row's "Start over" link, built in renderCampaignRow.
                existingRun != null -> StartMenuBuilder.resumeCampaignRun(existingRun.campaignRunId)
                // No live run, but a career from a completed or cleared run may still be archived
                // — and starting over replaces it (`hero-desk-and-profile-archive.md` §4). That
                // needs the same one explicit confirmation the live-run path already gives.
                else ->
                    confirmArchiveReplacement(file, campaign?.title as? String) {
                        StartMenuBuilder.startNewCampaign(selectedCampaign, difficulty)
                    }
            }
        }

        byId("smCFlowBut")?.title = I18n.t("campaign.flow.help")
        byId("smCFlowBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            val selectedCampaign = byId("smCamp")?.asDynamic()?.selectedCampaign as? Int
            if (selectedCampaign != null) {
                var campaignRef: Campaign? = null
                val campaign =
                    Campaign(selectedCampaign, StartMenuCampaignData.DIFFICULTY_HISTORICAL) {
                        campaignRef?.let { byId("smCampDesc")?.innerHTML = it.getCampaignFlow() }
                    }
                campaignRef = campaign
            }
        }

        if (DEBUG_CAMPAIGN) {
            byId("smCV")?.onclick = { StartMenuBuilder.continueCampaign("victory") }
            byId("smCVB")?.onclick = { StartMenuBuilder.continueCampaign("briliant") }
            byId("smCVT")?.onclick = { StartMenuBuilder.continueCampaign("tactical") }
            byId("smCL")?.onclick = { StartMenuBuilder.continueCampaign("lose") }
        }
    }

    /** Campaign-list descriptions are JavaScript strings: HTML collapses their newlines unless converted. */
    private fun formatCampaignDescription(raw: String): String =
        raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
            .split(Regex("\\n{2,}"))
            .joinToString("<br/><br/>") { paragraph -> paragraph.replace("\n", "<br/>") }

    private fun onCampSelectChange(campSelect: HTMLElement) {
        val selectedIndex = campSelect.asDynamic().selectedIndex as? Int ?: -1
        val value = if (selectedIndex < 0) null else campSelect.asDynamic().options[selectedIndex].value as? String
        val campaign = value?.let { StartMenuBuilder.campaignList().getOrNull(it.toInt()) }
        if (value == null || campaign == null) return
        val flagId = campaign.flag as? Int ?: 0
        val country =
            StartMenuListToolbar.countryDisplayLabel(flagId)
                ?: Equipment.getCountryNameByEqp(flagId, campaign.eqp as? String ?: "")
        // Structured credits ride in their own row, never inside the synopsis.
        byId("smCampDesc")?.innerHTML =
            AuthorRow.html(campaign.file as? String) +
            formatCampaignDescription(CampaignContentLocalization.description(campaign))
        byId("smCampCountry")?.innerHTML = "<b>${I18n.t("campaign.country.label")}</b><br/>" + country
        val operations = campaign.scenarios as? Int
        byId("smCampScenarios")?.innerHTML = "<b>${I18n.t("campaign.operations.label")}</b><br/>" +
            (operations?.let(I18n::formatNumber) ?: (campaign.scenarios as? String ?: ""))
        byId("smCamp")?.asDynamic()?.selectedCampaign = value.toInt()
        CampaignBackupButtons.refresh()
        refreshRulesLockForSelection(campaign.file as? String)
        StartMenuCampaignData.updateCampaignPrestigeDisplay()
        updateCampaignDossierHead(campSelect, campaign, country)
    }

    /** Rewrites localized campaign metadata without rebuilding the selection or losing filters. */
    fun refreshLocalizedContent() {
        val select = byId("smCampSel")?.querySelector("select") as? HTMLElement ?: return
        val options = select.asDynamic().options
        for (index in 0 until (options.length as Int)) {
            val option = options[index]
            // One `continue` rather than two: detekt refuses a loop with more than one jump, and
            // the two guards are the same guard -- "this option does not name a campaign".
            val campaign =
                (option.value as? String)
                    ?.toIntOrNull()
                    ?.let { StartMenuBuilder.campaignList().getOrNull(it) } ?: continue
            option.textContent = CampaignContentLocalization.title(campaign)
        }
        onCampSelectChange(select)
    }

    /**
     * A campaign's ruleset locks in at its own start (`docs/design/ruleset-profiles.md` §4) and
     * survives Continue -- so once a campaign already has a saved run, picking a different profile
     * in this window has no effect on it. `RulesWindow.installButton` only ever took a static
     * readOnlyWindow at screen-build time, before any campaign was selected, so the button was
     * always editable regardless of which row was actually picked. Reinstalling it here, on every
     * selection change, keeps it in sync with the CURRENTLY selected row instead
     * (2026-08-19 user report: changing rules on an in-progress campaign silently did nothing).
     */
    private fun refreshRulesLockForSelection(file: String?) {
        val hasRun = file != null && file in StartMenuCampaignData.campaignRunsByFile()
        byId("smCampButtons")?.let {
            RulesWindow.installButton(it, RulesetSelection.Surface.CAMPAIGN, readOnlyWindow = hasRun)
        }
    }

    // OSADA dossier head + register highlight (single source of truth = the hidden select).
    // Campaign title shows just the name (no date); date is in smCampDossierSub below.
    private fun updateCampaignDossierHead(
        campSelect: HTMLElement,
        campaign: dynamic,
        country: String,
    ) {
        val campaignTitleClean =
            StartMenuListToolbar.campaignDisplayTitle(
                CampaignContentLocalization.title(campaign),
            )
        byId("smCampTitle")?.innerHTML = campaignTitleClean
        byId("smCampDossierSub")?.innerHTML =
            listOfNotNull(
                country.ifBlank { null },
                StartMenuListToolbar.extractYears(CampaignContentLocalization.title(campaign)).ifBlank { null },
            ).joinToString(" &middot; ")
        byId("smCampDossierHeadText")?.let {
            StartMenuCampaignStory.applyDossierBadge(it, campaign.file as? String)
        }
        StartMenuCampaignData.setTheaterArt(campaign)
        byId("osadaCampList")?.let { StartMenuListToolbar.syncListHighlight(campSelect, it) }
        byId("smCampPath")?.let { StartMenuCampaignData.collapsePath(it) }
    }

    /** Restructures #smCamp into header / register / dossier / footer and fills the register. */
    private fun buildCampaignScreen(campSelect: HTMLElement) {
        val root = byId("smCamp") ?: return
        byId("smCampSel")?.classList?.add("osadaHiddenSelect")

        val header = addTag(root, "div")
        header.id = "smCampHeader"
        header.className = "osadaScreenHeader"
        header.textContent = I18n.t("campaign.selection.title")
        byId("smCampDifficultyLabel")?.textContent = I18n.t("campaign.difficulty.label")

        val body = addTag(root, "div")
        body.id = "smCampBody"
        body.className = "osadaScreenBody"

        val register = addTag(body, "div")
        register.id = "smCampRegister"
        register.className = "osadaRegister"
        val list = addTag(register, "div")
        list.id = "osadaCampList"
        list.className = "osadaList"

        val dossier = addTag(body, "div")
        dossier.id = "smCampDossier"
        dossier.className = "osadaDossier"
        buildCampaignDossierHead(dossier)
        buildCampaignPathCollapse(dossier)

        byId("smCampButtons")?.let { buttons ->
            root.appendChild(buttons)
            // Page-level Rules button, never an automatic modal in front of the launch
            // (`docs/design/ruleset-profiles.md` §6).
            RulesWindow.installButton(buttons, RulesetSelection.Surface.CAMPAIGN)
            // Per-campaign file export/import, acting on the register's selected campaign
            // (`docs/design/save-recovery.md` §2).
            CampaignBackupButtons.install(buttons)
        }
        // The native flow glyph is superseded by the collapsible "Campaign path" line.
        byId("smCFlowBut")?.style?.display = "none"

        refreshRegisterRows(campSelect, list)
        StartMenuListToolbar.buildListToolbar(
            register,
            list,
            listOf(
                StartMenuListToolbar.SORT_DEFAULT,
                StartMenuListToolbar.SORT_NAME,
                StartMenuListToolbar.SORT_YEAR,
                StartMenuListToolbar.SORT_SIZE,
            ),
            "campaign.filter.placeholder",
            "campaign.counter",
        )
        (register.querySelector(".osadaChipRow") as? HTMLElement)?.let { chipRow ->
            StartMenuCampaignStory.buildStoryOnlyChip(chipRow, list)
        }
    }

    /** Rebuilds the register's rows against a FRESH [StartMenuCampaignData.campaignRunsByFile]
     *  snapshot. Existing filter/sort state on [list] itself (`filterQuery`/`sideFilter`, set by
     *  [StartMenuListToolbar.buildListToolbar]) survives the rebuild -- it lives on the list
     *  element, not the rows -- but must be re-applied to the newly built rows. */
    private fun refreshRegisterRows(
        campSelect: HTMLElement,
        list: HTMLElement,
    ) {
        val runs = StartMenuCampaignData.campaignRunsByFile()
        StartMenuListToolbar.buildSyncedList(campSelect, list) { option, index, row, _ ->
            renderCampaignRow(option, index, row, list, runs)
        }
        StartMenuListToolbar.applyListView(list)
        StartMenuListToolbar.syncListHighlight(campSelect, list)
    }

    /**
     * Re-renders the campaign register's rows so a run started (or started over, or cleared) since
     * the screen was last built shows up immediately -- [buildCampaignScreen] itself only ever runs
     * ONCE per page load, so the "in progress" note and "Start over" link (`renderCampaignRow`) were
     * baked in from whatever [StartMenuCampaignData.campaignRunsByFile] returned at that first build
     * and never refreshed afterward. A campaign played earlier in the SAME session therefore showed
     * no note and no Start-over link when the player came back to this screen -- indistinguishable
     * from never having been played, and with no way back to Start over short of a page reload
     * (2026-08-19 user report: "can't find button to restart campaign"). Called every time this
     * screen becomes visible ([org.osada.ui.StartMenuButtonHandler.onNewCampaignButton]).
     */
    fun refreshRegister() {
        val campSelect = byId("smCampSel")?.firstChild as? HTMLElement ?: return
        val list = byId("osadaCampList") ?: return
        refreshRegisterRows(campSelect, list)
    }

    private fun buildCampaignDossierHead(dossier: HTMLElement) {
        val head = addTag(dossier, "div")
        head.id = "smCampDossierHead"
        head.className = "osadaDossierHead"
        StartMenuListToolbar.theaterPlaceholder(head)
        val headText = addTag(head, "div")
        headText.id = "smCampDossierHeadText"
        headText.className = "osadaDossierHeadText"
        val title = addTag(headText, "div")
        title.id = "smCampTitle"
        title.className = "osadaDossierTitle"
        val sub = addTag(headText, "div")
        sub.id = "smCampDossierSub"
        sub.className = "osadaDossierSub"

        byId("smCampInfo")?.let { dossier.appendChild(it) }
        byId("smCampDesc")?.let { dossier.appendChild(it) }
    }

    private fun buildCampaignPathCollapse(dossier: HTMLElement) {
        val path = addTag(dossier, "div")
        path.id = "smCampPath"
        path.className = "osadaCollapse"
        val summary = addTag(path, "div")
        summary.className = "osadaCollapseSummary"
        summary.textContent = I18n.t("campaign.path.label")
        summary.title = I18n.t("campaign.path.help")
        val pathBody = addTag(path, "div")
        pathBody.id = "smCampPathBody"
        pathBody.className = "osadaCollapseBody"
        summary.onclick = { _: org.w3c.dom.events.MouseEvent -> StartMenuCampaignData.toggleCampaignPath() }
    }

    private fun renderCampaignRow(
        option: org.w3c.dom.HTMLOptionElement,
        index: Int,
        row: HTMLElement,
        list: HTMLElement,
        runs: Map<String, org.osada.save.CampaignRunMetadata>,
    ) {
        // option.value = the campaign's ORIGINAL campaignlist index; `index` is only the
        // option's position, and the two diverge once hidden campaigns are skipped at
        // option-build time (buildCampaignSelectDropdown).
        val campaignIndex = (option.asDynamic().value as? String)?.toIntOrNull() ?: index
        val campaign = StartMenuBuilder.campaignList().getOrNull(campaignIndex)
        val flag = addTag(row, "div")
        flag.className = "osadaFlag"
        val eqp = campaign?.eqp as? String ?: ""
        val flagId = campaign?.flag as? Int ?: 0
        if (eqp.isNotBlank()) {
            flag.style.backgroundImage = "url('resources/ui/flags/${Equipment.UNITED_NAME}/flags_med.png')"
            flag.style.backgroundPosition = "${-StartMenuListToolbar.FLAG_SPRITE_WIDTH * flagId}px 0px"
        }
        val text = addTag(row, "div")
        text.className = "osadaListRowText"
        val name = addTag(text, "div")
        name.className = "osadaListRowName"
        name.textContent = StartMenuListToolbar.campaignDisplayTitle(option.text)
        val rowSub = addTag(text, "div")
        rowSub.className = "osadaListRowSub"
        val ops = campaign?.scenarios as? Int
        rowSub.innerHTML =
            listOfNotNull(
                StartMenuListToolbar.extractYears(option.text).ifBlank { null },
                ops?.let { I18n.plural("campaign.row.operations", it) },
            ).joinToString(" &middot; ")
        // Every campaign can independently show In progress/Completed now (was: at most one
        // campaign ever carried this, because storage held a single shared slot).
        val file = campaign?.file as? String
        val runMetadata = file?.let { runs[it] }
        if (runMetadata != null) {
            val note = addTag(row, "div")
            note.className = "osadaListRowNote"
            val (text, title) = StartMenuCampaignData.progressNoteText(runMetadata, ops)
            note.textContent = text
            note.title = title
            // Explicit, guarded "start over": the default Play action resumes (safe), so
            // discarding progress needs its own affordance + confirmation (design doc sec 9),
            // never a silent side effect of pressing the primary button.
            val startOver = addTag(row, "div")
            startOver.className = "osadaListRowStartOver"
            startOver.textContent = I18n.t("campaign.replace_run.start_over.label")
            startOver.title = I18n.t("campaign.replace_run.start_over.help")
            startOver.setAttribute("tabindex", "0")
            startOver.setAttribute("role", "button")
            startOver.onclick = { e ->
                e.stopPropagation()
                confirmStartOver(campaignIndex, runMetadata, text)
            }
        }
        // Country is searchable too, so "soviet"/"spain" finds a campaign whose title says neither.
        // Both the raw name and the curated labels go in, so the pre-rename spelling keeps working.
        val country = Equipment.getCountryNameByEqp(flagId, eqp)
        val sideKey = StartMenuListToolbar.countryGroupLabel(flagId)
        val searchNames =
            listOfNotNull(country, StartMenuListToolbar.countryDisplayLabel(flagId), sideKey).distinct()
        StartMenuListToolbar.tagRow(
            row,
            index,
            option.text,
            "${option.text} ${searchNames.joinToString(" ")}",
            endYear(option.text),
            ops,
            sides = listOfNotNull(sideKey),
            forceHidden = file != null && file in StartMenuCampaignData.hiddenCampaignFiles,
        )
        StartMenuCampaignStory.applyRowBadge(row, list, file)
    }

    /** Guarded "start over" for a campaign that already has a run: names the campaign and its
     *  current progress before permanently replacing it (action-affordances-and-objectives.md
     *  sec 5's confirmation shape, reused here for a run replacement rather than a unit sale). */
    private fun confirmStartOver(
        campaignIndex: Int,
        run: org.osada.save.CampaignRunMetadata,
        progressText: String,
    ) {
        val campaign = StartMenuBuilder.campaignList().getOrNull(campaignIndex) ?: return
        val name = (campaign.title as? String) ?: run.campaignName
        // One confirmation covering BOTH losses (§4): the resumable run and this campaign's
        // archived roster/history, which starting over also replaces. The archive line appears only
        // when there is a career to lose, so the dialog never overstates what it is about to do.
        val archivedHeroes = HeroArchiveService.archivedHeroCount(run.campaignRunId)
        val body =
            I18n.t("campaign.replace_run.confirm.body", mapOf("progress" to progressText)) +
                if (archivedHeroes > 0) {
                    "<br>" + I18n.t("campaign.replace_run.confirm.archive", mapOf("count" to archivedHeroes))
                } else {
                    ""
                }
        ConfirmCard.open(
            I18n.t("campaign.replace_run.confirm.title", mapOf("campaign" to name)),
            body,
            I18n.t("campaign.replace_run.confirm.confirm_button"),
        ) {
            Game.current?.state?.clearCampaignRun(run.campaignRunId)
            // Clears the save and stops there -- it used to launch straight into a fresh run with
            // whatever ruleset was already selected, which left no way back to Rules: the window's
            // own locked banner says "use Start Over" for exactly this campaign, and Start Over
            // immediately re-locked a (possibly still wrong) profile in again. Now the player lands
            // back on this same row with a clean slate -- the note and this link gone, Play reading
            // "Start", Rules editable again -- and picks the profile before pressing Start themselves
            // (2026-08-19 user report).
            refreshRegister()
            refreshRulesLockForSelection(campaign.file as? String)
            StartMenuCampaignData.updateCampaignPrestigeDisplay()
        }
    }

    /**
     * The archived-career half of the replay confirmation (`hero-desk-and-profile-archive.md` §4).
     *
     * Clearing a campaign's slot deliberately KEEPS its archived roster — that is how a fallen
     * officer survives an abandoned run — so a campaign with no live run can still have a complete
     * career behind it, and starting over is what finally replaces that career. When there is
     * nothing archived this asks nothing and starts immediately: a confirmation with no loss behind
     * it teaches players to dismiss the ones that do.
     */
    private fun confirmArchiveReplacement(
        file: String?,
        title: String?,
        start: () -> Unit,
    ) {
        val heroes = file?.let(HeroArchiveService::archivedHeroCount) ?: 0
        if (heroes == 0) {
            start()
            return
        }
        val name = title ?: HeroArchiveService.archivedCampaign(file!!)?.campaignName ?: file
        ConfirmCard.open(
            I18n.t("campaign.replace_archive.confirm.title", mapOf("campaign" to name)),
            I18n.t("campaign.replace_archive.confirm.body", mapOf("count" to heroes)),
            I18n.t("campaign.replace_archive.confirm.confirm_button"),
        ) { start() }
    }

    /** LAST 4-digit year in a campaign title ("Red Army Campaign (1936-1945)" -> 1945), used as
     *  the chronological sort key. The end date, not the start: campaigns overlap heavily at their
     *  openings, so sorting by first year buried the ones that run longest among the ones that only
     *  begin alongside them — "Bolshevik Cavalry (1918-1920)" sorted level with "The November
     *  Revolution (1918)", and "Greece (1940-1949)" ahead of "Soviet Black Sea Fleet (1941-1944)"
     *  (2026-08-16 user report). Single-year titles are unaffected (start == end).
     *
     *  Spartacus is dated "(73-71 BC)" — no 4-digit year to find, and it must sort FIRST, not
     *  last, so map any BC title to year 0 rather than to "unknown". */
    private fun endYear(title: String): Int? {
        if (title.contains("BC")) return 0
        return Regex("\\b(\\d{4})\\b")
            .findAll(title)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }
}
