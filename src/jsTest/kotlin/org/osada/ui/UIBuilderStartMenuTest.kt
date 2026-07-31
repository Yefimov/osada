package org.osada.ui

import kotlinx.browser.document
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.uiSettings
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [UIBuilder.buildStartMenu] and [UIBuilder.buildGameStateMenu].
 */
class UIBuilderStartMenuTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
        UIBuilder.resetStartMenuBuilt()
        installFixtureData()
        ensureFixtureDom()
    }

    private fun installFixtureData() {
        js(
            """
            window.campaignlist = [
                {title:'Campaign A', desc:'First campaign', flag:0, eqp:'eqp-adlerkorps', scenarios:'2', prestige:100},
                {title:'Campaign B', desc:'Second campaign', flag:1, eqp:'eqp-adlerkorps', scenarios:'3', prestige:200}
            ];
            window.scenariolist = [
                ['European Theater'],
                ['scn1.scn','Scenario One','Description One',[{id:0,country:0}],[{id:1,country:1}],'eqp-adlerkorps'],
                ['scn2.scn','Scenario Two','Description Two',[{id:2,country:0}],[{id:3,country:1}],'eqp-adlerkorps']
            ];
        """,
        )
    }

    private fun ensureFixtureDom() {
        listOf(
            "smButtons",
            "smLogoText",
            "smCredits",
            "smCampSel",
            "smCampDif",
            "smCampDifHelp",
            "smCampDesc",
            "smCampCountry",
            "smCampScenarios",
            "smCampPrestige",
            "smCamp",
            "smCBackBut",
            "smCPlayBut",
            "smCFlowBut",
            "smScenSel",
            "smScenDesc",
            "smScenPlayers",
            "smSide0",
            "smSide1",
            "smVS",
            "smScen",
            "smSBackBut",
            "smSPlayBut",
            "smSettingsContainer",
            "smSetOkBut",
            "smSettings",
            "smState",
            "smMain",
            "smStOkBut",
            "startmenu",
            "gameToolTip",
            "options",
            "statusbar",
            "unit-info",
            "container-unitlist",
            "equipment",
            "combatLog",
            "dossier",
            "eqInfoText",
            "eqSortInfo",
            "eqSelClass",
            "menu",
            "eqUpgradeText",
            "eqNewText",
            "eqSellText",
        ).forEach { id -> ensureFixtureElement(id) }
    }

    private fun ensureFixtureElement(id: String) {
        val existing = byId(id)
        if (existing == null) {
            val container = document.createElement("div") as HTMLElement
            container.id = id
            document.body?.appendChild(container)
        } else {
            clearTag(existing)
            // Re-flatten: StartMenuBuilder.buildScenarioScreen/buildCampaignScreen permanently
            // reparent fixture leaves like smScenDesc/smCampDesc into a per-build dossier
            // subtree under #smScen/#smCamp. Karma reuses one page across all @Test methods
            // in this class, so on the NEXT test's setup(), clearTag on the (now-parent)
            // "smScen"/"smCamp" id below removes that whole subtree — deleting the reparented
            // leaf along with it, permanently, since it's never recreated after. appendChild
            // moves an existing node (detaching it from wherever it now lives) rather than
            // creating a duplicate, so this undoes prior tests' reparenting every run.
            document.body?.appendChild(existing)
        }
    }

    private fun HTMLElement.selectElement(): HTMLElement? = this.firstElementChild as? HTMLElement

    @Test
    fun buildStartMenuCreatesMainButtons() {
        // OSADA: the old #smNewGame sub-panel (Campaigns / Scenarios / Tutorial) is gone —
        // "New Campaign"/"Single Scenario"/"Tutorial" are direct #smButtons entries now, and
        // there is no standalone "newgame" id anymore (StartMenuBuilder.buildStartMenu).
        UIBuilder.buildStartMenu()
        val smButtons = byId("smButtons") ?: return
        val ids =
            (0 until smButtons.childNodes.length).mapNotNull { i ->
                (smButtons.childNodes.asDynamic()[i] as? HTMLElement)?.id
            }
        assertTrue(ids.contains("continuegame"))
        assertTrue(ids.contains("newcampaign"))
        assertTrue(ids.contains("newscenario"))
        assertTrue(ids.contains("saveload"))
        assertTrue(ids.contains("settings"))
        assertTrue(ids.contains("tutorial"))
    }

    @Test
    fun buildStartMenuSetsTaglineAndCredits() {
        // OSADA: the logo subtitle is the game's tagline (the old "1941 - 1945" era line was
        // misleading — the campaigns span 1936-1954), and #smCredits is the display version only.
        // Upstream attribution ("Nicu Pavel") is deliberately gone from the UI.
        UIBuilder.buildStartMenu()
        assertTrue((byId("smLogoText")?.innerHTML ?: "").contains("Turn-based strategy"))
        val credits = byId("smCredits")?.innerHTML ?: ""
        assertTrue(credits.contains("v0.5"))
        assertTrue(!credits.contains("Nicu Pavel"))
    }

    @Test
    fun buildStartMenuShowsAQuote() {
        UIBuilder.buildStartMenu()
        val quote = byId("smQuote")
        assertNotNull(quote)
        assertTrue((quote.innerHTML).contains("osada-quote__author"))
    }

    @Test
    fun buildStartMenuPopulatesCampaignSelect() {
        UIBuilder.buildStartMenu()
        val select = byId("smCampSel")?.selectElement() ?: return
        val options = select.childNodes.length
        assertTrue(options >= 2)
        val firstText = (select.childNodes.asDynamic()[0] as? HTMLElement)?.textContent ?: ""
        assertEquals("Campaign A", firstText)
    }

    @Test
    fun buildStartMenuPopulatesScenarioSelect() {
        UIBuilder.buildStartMenu()
        val select = byId("smScenSel")?.selectElement() ?: return
        val options = select.childNodes.length
        assertTrue(options >= 2)
    }

    @Test
    fun buildStartMenuCreatesSettingsSliders() {
        UIBuilder.buildStartMenu()
        assertNotNull(byId("uiresize"))
        assertNotNull(byId("uiscale"))
        assertNotNull(byId("mapscale"))
        val sliderWrap = assertNotNull(byId("uiscale")?.parentElement?.parentElement)
        assertTrue(sliderWrap.firstElementChild?.getAttribute("title")?.contains("Decrease") == true)
        assertTrue(sliderWrap.lastElementChild?.getAttribute("title")?.contains("Increase") == true)
    }

    @Test
    fun buildStartMenuCreatesSettingsCheckboxes() {
        UIBuilder.buildStartMenu()
        listOf("showGridTerrain", "quickAnimation", "stalinRegime", "muteUnitSounds", "noFOW", "useRetina")
            .forEach { id ->
                assertNotNull(byId(id), "Missing checkbox $id")
            }
    }

    @Test
    fun buildStartMenuCampaignSelectionUpdatesDescription() {
        UIBuilder.buildStartMenu()
        val select = byId("smCampSel")?.selectElement() ?: return
        select.asDynamic().selectedIndex = 1
        (select.asDynamic().onchange)()
        assertTrue((byId("smCampDesc")?.innerHTML ?: "").contains("Second campaign"))
        assertEquals(1, byId("smCamp")?.asDynamic()?.selectedCampaign)
    }

    @Test
    fun buildStartMenuScenarioSelectionUpdatesDescription() {
        UIBuilder.buildStartMenu()
        val select = byId("smScenSel")?.selectElement() ?: return
        select.asDynamic().selectedIndex = 1
        (select.asDynamic().onchange)()
        assertTrue((byId("smScenDesc")?.innerHTML ?: "").contains("Description One"))
        assertTrue((byId("smSide0")?.childNodes?.length ?: 0) > 0)
        assertTrue(byId("smSide0")?.title?.contains("Play this scenario") == true)
    }

    @Test
    fun startMenuNavigationAndCampaignPathExplainTheirActions() {
        UIBuilder.buildStartMenu()
        assertTrue(byId("newcampaign")?.title?.contains("nation") == true)
        assertTrue(byId("smCPlayBut")?.title?.contains("selected campaign") == true)
        val pathSummary = byId("smCampPath")?.querySelector(".osadaCollapseSummary")
        assertTrue(pathSummary?.getAttribute("title")?.contains("outcome-dependent") == true)
    }

    // ---- Scenario side picker (#smScenPlayers / #smSide0 / #smVS / #smSide1) ----------------
    // Fixture: scn1 side0=[{id:0,country:0}] side1=[{id:1,country:1}] — country 0/1 are real,
    // distinct, non-overridden Equipment.countryNames entries ("New Zealand"/"Irregular Forces"),
    // so labels resolve without relying on any curated override or fallback text.

    private fun selectScenario(
        select: HTMLElement,
        domIndex: Int,
    ) {
        select.asDynamic().selectedIndex = domIndex
        (select.asDynamic().onchange)()
    }

    private fun fireKeydown(
        el: HTMLElement,
        key: String,
    ) {
        val event: dynamic = js("({})")
        event.key = key
        event.preventDefault = {}
        el.onkeydown?.invoke(event.unsafeCast<KeyboardEvent>())
    }

    @Test
    fun sidePickerHasRadiogroupRolesAndIds() {
        // The four ids the launch wiring and legacy CSS depend on must survive the redesign.
        UIBuilder.buildStartMenu()
        assertNotNull(byId("smScenPlayers"))
        assertNotNull(byId("smSide0"))
        assertNotNull(byId("smVS"))
        assertNotNull(byId("smSide1"))
        val select = byId("smScenSel")?.selectElement() ?: return
        selectScenario(select, 1)
        assertEquals("radiogroup", byId("smScenPlayers")?.getAttribute("role"))
        assertEquals("radio", byId("smSide0")?.getAttribute("role"))
        assertEquals("radio", byId("smSide1")?.getAttribute("role"))
        assertTrue((byId("smVS")?.innerHTML ?: "").contains("VS"))
    }

    @Test
    fun sidePickerDefaultsToPlayerZeroSideAndUpdatesAria() {
        UIBuilder.buildStartMenu()
        val select = byId("smScenSel")?.selectElement() ?: return
        selectScenario(select, 1) // scn1: side0 carries player id 0 -> side0 is human by default
        assertEquals("true", byId("smSide0")?.getAttribute("aria-checked"))
        assertEquals("false", byId("smSide1")?.getAttribute("aria-checked"))
        assertEquals(0, uiSettings.isAI[0])
        assertEquals(1, uiSettings.isAI[1])
        // Exactly one card is selected.
        val checkedCount = listOf("smSide0", "smSide1").count { byId(it)?.getAttribute("aria-checked") == "true" }
        assertEquals(1, checkedCount)
    }

    @Test
    fun sidePickerClickSelectsTheOtherSide() {
        UIBuilder.buildStartMenu()
        val select = byId("smScenSel")?.selectElement() ?: return
        selectScenario(select, 1)
        byId("smSide1")?.click()
        assertEquals("false", byId("smSide0")?.getAttribute("aria-checked"))
        assertEquals("true", byId("smSide1")?.getAttribute("aria-checked"))
        assertEquals(1, uiSettings.isAI[0])
        assertEquals(0, uiSettings.isAI[1])
        assertTrue((byId("smSide1")?.className ?: "").contains("is-selected"))
        assertTrue((byId("smSide0")?.className ?: "").contains("is-selected") == false)
    }

    @Test
    fun sidePickerKeyboardEnterSelectsFocusedCard() {
        UIBuilder.buildStartMenu()
        val select = byId("smScenSel")?.selectElement() ?: return
        selectScenario(select, 1)
        val side1 = byId("smSide1") ?: return
        fireKeydown(side1, "Enter")
        assertEquals("true", byId("smSide1")?.getAttribute("aria-checked"))
        assertEquals(0, uiSettings.isAI[1])
    }

    @Test
    fun sidePickerStartButtonLabelTracksSelectedSide() {
        UIBuilder.buildStartMenu()
        val select = byId("smScenSel")?.selectElement() ?: return
        selectScenario(select, 1)
        val labelForSide0 = byId("smSPlayBut")?.getAttribute("data-label")
        assertNotNull(labelForSide0)
        assertTrue(labelForSide0.startsWith("Start as"))
        byId("smSide1")?.click()
        val labelForSide1 = byId("smSPlayBut")?.getAttribute("data-label")
        assertNotNull(labelForSide1)
        assertTrue(labelForSide1 != labelForSide0)
    }

    @Test
    fun sidePickerResetsOnScenarioChange() {
        UIBuilder.buildStartMenu()
        val select = byId("smScenSel")?.selectElement() ?: return
        selectScenario(select, 1) // scn1
        byId("smSide1")?.click()
        assertEquals("true", byId("smSide1")?.getAttribute("aria-checked"))

        selectScenario(select, 2) // scn2: neither side carries player id 0 -> falls back to side0
        // The new scenario's own players must be resolved fresh, not the previous scenario's pick.
        assertEquals("true", byId("smSide0")?.getAttribute("aria-checked"))
        assertEquals("false", byId("smSide1")?.getAttribute("aria-checked"))
        val checkedCount = listOf("smSide0", "smSide1").count { byId(it)?.getAttribute("aria-checked") == "true" }
        assertEquals(1, checkedCount)
    }

    @Test
    fun sidePickerDisabledSideCannotBeSelected() {
        js(
            """
            window.scenariolist = [
                ['European Theater'],
                ['scnEmpty.scn', 'No Opponent', 'No opposing force', [{id:0,country:0}], [], 'eqp-adlerkorps']
            ];
        """,
        )
        UIBuilder.resetStartMenuBuilt()
        UIBuilder.buildStartMenu()
        val select = byId("smScenSel")?.selectElement() ?: return
        selectScenario(select, 1)
        val side1 = byId("smSide1") ?: return
        assertEquals("true", side1.getAttribute("aria-disabled"))
        assertTrue(side1.className.contains("is-disabled"))
        assertEquals(-1, side1.tabIndex)
        assertNull(side1.onclick)
        assertEquals("true", byId("smSide0")?.getAttribute("aria-checked"))
        assertEquals(0, uiSettings.isAI[0])
    }

    @Test
    fun sidePickerSelectionFeedsScenarioLaunch() {
        // startNewScenario's click handler reads scenario[0] via #smScen.selectedScenario, and
        // Game.newScenario reads who's human purely from uiSettings.isAI — the exact same state
        // the picker just set, with no second selection state in between. (Actually invoking the
        // #smSPlayBut click handler isn't exercised here — it goes through the real, shared
        // `game` global other test files in this suite install, which isn't a fake with a
        // newScenario stub; asserting the state it reads is the meaningful check.)
        UIBuilder.buildStartMenu()
        val select = byId("smScenSel")?.selectElement() ?: return
        selectScenario(select, 1)
        byId("smSide1")?.click()
        assertEquals(1, byId("smScen")?.asDynamic()?.selectedScenario)
        assertEquals(0, uiSettings.isAI[1])
        assertEquals(1, uiSettings.isAI[0])
    }

    // OSADA: cloud save/load (GitHub gist-backed, using the original PM author's token) was
    // removed — disk save/load only now (see GameStatePersistence.kt's class doc).
    @Test
    fun buildGameStateMenuCreatesDiskStateButtons() {
        UIBuilder.buildStartMenu()
        val smState = byId("smState") ?: return
        val ids =
            (0 until smState.childNodes.length)
                .mapNotNull { i ->
                    val row = smState.childNodes.asDynamic()[i] as? HTMLElement
                    val children = row?.childNodes ?: return@mapNotNull null
                    (0 until children.length).mapNotNull { j ->
                        (children.asDynamic()[j] as? HTMLElement)?.id
                    }
                }.flatten()
        assertTrue(ids.contains("disksave"))
        assertTrue(ids.contains("diskload"))
        assertTrue(!ids.contains("cloudsave"))
        assertTrue(!ids.contains("cloudload"))
    }
}
