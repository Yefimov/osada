package org.osada.ui

import org.osada.DEBUG_CAMPAIGN
import org.osada.model.Equipment
import org.osada.model.getCountryNameByEqp
import org.osada.scenario.Campaign
import org.w3c.dom.HTMLElement

/**
 * [StartMenuBuilder]'s campaign-selection screen: the difficulty picker and the campaign
 * register (select dropdown, synced list, dossier). Split out purely to keep [StartMenuBuilder]
 * within the project's function-count/class-size limits -- not expected to be called from
 * elsewhere. Display/data helpers live in [StartMenuCampaignData]; shared register plumbing in
 * [StartMenuListToolbar].
 */
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
            option.textContent = campaign.title as? String ?: ""
        }
        return campSelect
    }

    private fun buildDifficultySelector() {
        val difficultyOptions =
            listOf(
                Triple(StartMenuCampaignData.DIFFICULTY_HISTORICAL, "Historical", false),
                Triple(StartMenuCampaignData.DIFFICULTY_TACTICAL, "Tactical", true),
                Triple(StartMenuCampaignData.DIFFICULTY_OPERATIONAL, "Operational", false),
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
        difficultyOptions.forEach { (value, text, selected) ->
            val seg = addTag("smCampDif", "div")
            seg.className = "osada-seg" + if (selected) " osada-seg--on" else ""
            seg.textContent = text
            seg.title = text
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
        campSelect.asDynamic().onchange = { onCampSelectChange(campSelect) }
        buildCampaignScreen(campSelect)

        byId("smCBackBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            makeHidden("smCamp")
            makeVisible("smMain")
        }

        byId("smCPlayBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            val selectedCampaign = byId("smCamp")?.asDynamic()?.selectedCampaign as? Int
            val difficulty =
                byId("smCamp")?.asDynamic()?.selectedDifficulty as? Int ?: StartMenuCampaignData.DIFFICULTY_HISTORICAL
            selectedCampaign?.let { StartMenuBuilder.startNewCampaign(it, difficulty) }
        }

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

    private fun onCampSelectChange(campSelect: HTMLElement) {
        val selectedIndex = campSelect.asDynamic().selectedIndex as? Int ?: -1
        val value = if (selectedIndex < 0) null else campSelect.asDynamic().options[selectedIndex].value as? String
        val campaign = value?.let { StartMenuBuilder.campaignList().getOrNull(it.toInt()) }
        if (value == null || campaign == null) return
        val country = Equipment.getCountryNameByEqp(campaign.flag as? Int ?: 0, campaign.eqp as? String ?: "")
        byId("smCampDesc")?.innerHTML = campaign.desc as? String ?: ""
        byId("smCampCountry")?.innerHTML = "<b>Country</b><br/>" + country
        byId("smCampScenarios")?.innerHTML = "<b>Operations</b><br/>" +
            (campaign.scenarios as? Int ?: (campaign.scenarios as? String ?: ""))
        byId("smCamp")?.asDynamic()?.selectedCampaign = value.toInt()
        StartMenuCampaignData.updateCampaignPrestigeDisplay()
        updateCampaignDossierHead(campSelect, campaign, country)
    }

    // OSADA dossier head + register highlight (single source of truth = the hidden select).
    // Campaign title shows just the name (no date); date is in smCampDossierSub below.
    private fun updateCampaignDossierHead(
        campSelect: HTMLElement,
        campaign: dynamic,
        country: String,
    ) {
        val campaignTitleClean =
            (campaign.title as? String ?: "")
                .replace(
                    Regex("\\s*\\([^)]*\\d{1,4}[^)]*\\)\\s*"),
                    "",
                ).trim()
        byId("smCampTitle")?.innerHTML = campaignTitleClean
        byId("smCampDossierSub")?.innerHTML =
            listOfNotNull(
                country.ifBlank { null },
                StartMenuListToolbar.extractYears(campaign.title as? String ?: "").ifBlank { null },
            ).joinToString(" &middot; ")
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
        header.textContent = "Campaign Selection"

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

        byId("smCampButtons")?.let { root.appendChild(it) }
        // The native flow glyph is superseded by the collapsible "Campaign path" line.
        byId("smCFlowBut")?.style?.display = "none"

        val progress = StartMenuCampaignData.activeCampaignProgress()
        StartMenuListToolbar.buildSyncedList(campSelect, list) { option, index, row, _ ->
            renderCampaignRow(option, index, row, progress)
        }
        StartMenuListToolbar.buildListToolbar(
            register,
            list,
            listOf(
                StartMenuListToolbar.SORT_DEFAULT,
                StartMenuListToolbar.SORT_NAME,
                StartMenuListToolbar.SORT_YEAR,
                StartMenuListToolbar.SORT_SIZE,
            ),
            "Filter campaigns…",
            "campaigns",
        )
    }

    private fun buildCampaignDossierHead(dossier: HTMLElement) {
        val head = addTag(dossier, "div")
        head.id = "smCampDossierHead"
        head.className = "osadaDossierHead"
        StartMenuListToolbar.theaterPlaceholder(head)
        val headText = addTag(head, "div")
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
        summary.innerHTML = "Campaign path"
        val pathBody = addTag(path, "div")
        pathBody.id = "smCampPathBody"
        pathBody.className = "osadaCollapseBody"
        summary.onclick = { _: org.w3c.dom.events.MouseEvent -> StartMenuCampaignData.toggleCampaignPath() }
    }

    private fun renderCampaignRow(
        option: org.w3c.dom.HTMLOptionElement,
        index: Int,
        row: HTMLElement,
        progress: Pair<String, Int>?,
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
        name.textContent = option.text
        val rowSub = addTag(text, "div")
        rowSub.className = "osadaListRowSub"
        val ops = campaign?.scenarios as? Int
        rowSub.innerHTML =
            listOfNotNull(
                StartMenuListToolbar.extractYears(option.text).ifBlank { null },
                ops?.let { "$it operations" },
            ).joinToString(" &middot; ")
        // In-progress annotation, right-aligned. Only ever ONE campaign can carry it: the
        // storage holds a single campaign slot (the one Continue resumes) — there is no
        // per-campaign progress history, and therefore no "Completed" state to show either.
        val file = campaign?.file as? String
        if (progress != null && file != null && progress.first == file) {
            val note = addTag(row, "div")
            note.className = "osadaListRowNote"
            val operation = progress.second + 1
            note.textContent =
                if (ops != null) {
                    "In progress · operation $operation/$ops"
                } else {
                    "In progress · operation $operation"
                }
            note.title = "This is the campaign Continue resumes"
        }
        // Country is searchable too, so "soviet"/"spain" finds a campaign whose title says neither.
        val country = Equipment.getCountryNameByEqp(flagId, eqp)
        val sideKey = StartMenuListToolbar.countryDisplayLabel(flagId)
        StartMenuListToolbar.tagRow(
            row,
            index,
            option.text,
            "${option.text} $country",
            startYear(option.text),
            ops,
            sides = listOfNotNull(sideKey),
            forceHidden = file != null && file in StartMenuCampaignData.hiddenCampaignFiles,
        )
    }

    /** First 4-digit year in a campaign title ("Red Army Campaign (1936-1945)" -> 1936), used as
     *  the chronological sort key. Spartacus is dated "(73-71 BC)" — no 4-digit year to find, and
     *  it must sort FIRST, not last, so map any BC title to year 0 rather than to "unknown". */
    private fun startYear(title: String): Int? {
        if (title.contains("BC")) return 0
        return Regex("\\b(\\d{4})\\b")
            .find(title)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }
}
