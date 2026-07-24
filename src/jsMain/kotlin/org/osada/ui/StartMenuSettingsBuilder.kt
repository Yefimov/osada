@file:Suppress("MaxLineLength")

package org.osada.ui

import kotlinx.browser.window
import org.osada.GameHolder
import org.osada.uiSettings
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * [StartMenuBuilder]'s settings screen: interface/map-scale sliders, the checkbox sections, and
 * the "Done" handler that applies and persists them. Split out purely to keep [StartMenuBuilder]
 * within the project's function-count/class-size limits -- not expected to be called from
 * elsewhere.
 */
internal object StartMenuSettingsBuilder {
    // Auto-bump the UI scale slider when Retina displays are detected/toggled off, matching
    // the readable-at-a-glance size PM itself defaults to on high-DPI screens.
    private const val RETINA_UI_SCALE = 1.6
    private const val RETINA_DOWNSCALE_THRESHOLD = 1.5

    // Task 5: regrouped from one flat list into named sections. Same keys/labels (plus the
    // new confirmEndTurn toggle) — CSS + markup only, no checkbox logic changed.
    private data class SettingSection(
        val title: String,
        val caption: String?,
        val items: List<Pair<String, String>>,
    )

    // Settings that change what the CANVAS draws — re-rendered on click, not deferred to
    // "Done" (see [wireSettingsOkHandler]). useRetina is deliberately excluded: it needs a
    // page reload (see its own branch), not a render call.
    private val liveRenderSettingIds =
        setOf(
            "showGridTerrain",
            "markOwnUnits",
            "markEnemyUnits",
            "noFOW",
            "showHiddenVictoryHexes",
            "showDetailInfoToolTips",
        )

    private val settingSections =
        listOf(
            SettingSection(
                "Map View",
                null,
                listOf(
                    "showGridTerrain" to "Show terrain with Hex Grid",
                    "markOwnUnits" to "Mark own units on map",
                    "markEnemyUnits" to "Mark enemy strength in red",
                    "useRetina" to "Zoom to full device resolution",
                ),
            ),
            SettingSection(
                "Gameplay",
                null,
                listOf(
                    "quickAnimation" to "Quick combat and move animations",
                    "showDetailInfoToolTips" to "Show optional objectives tooltips",
                    "confirmEndTurn" to "Confirm end of turn",
                ),
            ),
            SettingSection(
                "Sound",
                null,
                listOf(
                    "muteUnitSounds" to "Mute unit combat sounds",
                ),
            ),
            SettingSection(
                "Observer Mode",
                "Affects game balance",
                listOf(
                    "noFOW" to "Disable Fog of War",
                    "showHiddenVictoryHexes" to "Show hidden victory hexes",
                ),
            ),
        )

    private val settingHelp =
        mapOf(
            "showGridTerrain" to
                "Colours each hex by terrain type when the Grid overlay is enabled, making movement terrain easier to read.",
            "markOwnUnits" to
                "Adds a clear marker beneath your units so stacked sprites and aircraft are easier to distinguish.",
            "markEnemyUnits" to
                "Uses red strength markers for spotted enemy units. This does not reveal units hidden by fog of war.",
            "useRetina" to
                "Renders the map at the display's full device-pixel resolution. Sharper on high-DPI screens, but uses more graphics memory and requires a reload.",
            "quickAnimation" to "Shortens movement and combat animations. Rules and results are unchanged.",
            "showDetailInfoToolTips" to "Shows name labels over non-objective owned hexes such as towns and airfields.",
            "confirmEndTurn" to
                "Asks for confirmation before ending the turn and warns when units still have actions available.",
            "muteUnitSounds" to
                "Mutes discrete movement, weapon and combat sounds. Ambient weather audio has its own volume control.",
            "noFOW" to "Removes fog of war and reveals all enemy units. This changes game balance.",
            "showHiddenVictoryHexes" to
                "Reveals secret objectives that have no flag on the map. Most scenarios have none; regular bordered objectives are always visible. This changes game balance.",
        )

    private val sliderHelp =
        mapOf(
            "uiresize" to
                "Legacy interface-width value retained for compatibility; the current HUD automatically follows the viewport.",
            "uiscale" to "Scales menus, panels and HUD controls without changing the tactical map zoom.",
            "mapscale" to
                "Scales the tactical map and unit sprites. This is the same zoom controlled beneath the minimap.",
            "soundvolume" to "Volume for movement, weapon and combat sound effects.",
            "ambientvolume" to "Volume for continuous ambient audio such as rain and wind.",
        )

    fun buildSettingsScreen() {
        UILayout.resizeUI(uiSettings.uiSize)
        UILayout.scaleUI(uiSettings.uiScale)
        UILayout.setLayoutConstrains(false)
        buildTopSliders()
        settingSections.forEach { buildSettingSection(it) }
        wireSettingsOkHandler()
    }

    // Slider rows need the same row scaffold as the checkbox rows below (and as PM): a
    // `settingContainer left` wrapper with a `settingText left` label and a right-floated div
    // holding the slider. Without it the three sliders had no label and no alignment ("съехали").
    private fun sliderSetting(
        id: String,
        label: String,
        value: Double,
        step: Double,
        min: Double,
        max: Double,
        onInput: () -> Unit,
    ): HTMLElement {
        val container = addTag("smSettingsContainer", "div")
        container.className = "settingContainer left"
        val textDiv = addTag(container, "div")
        textDiv.className = "settingText left"
        textDiv.textContent = label
        val help = sliderHelp[id] ?: label
        container.title = help
        textDiv.title = help
        val sliderWrap = addTag(container, "div")
        sliderWrap.style.cssFloat = "right"
        sliderWrap.title = help
        UILayout.createSlider(sliderWrap, id, value, step, min, max, onInput)
        return container
    }

    private fun buildTopSliders() {
        // Interface width is obsolete: the HUD is viewport-based now. The row is hidden, not
        // removed — the Settings-OK handler (and UILayout) still read #uiresize, so the slider
        // element stays in the DOM with its stored value.
        sliderSetting(
            "uiresize",
            "Interface width (px)",
            uiSettings.uiSize.toDouble(),
            step = 10.0,
            min = uiSettings.uiSmallSize.toDouble(),
            max = 1920.0,
        ) {
            UILayout.resizeUI((byId("uiresize")?.asDynamic()?.value as? String)?.toIntOrNull() ?: uiSettings.uiSize)
        }.style.display = "none"
        sliderSetting("uiscale", "Interface scale", uiSettings.uiScale, step = 0.1, min = 0.5, max = 3.0) {
            UILayout.scaleUI((byId("uiscale")?.asDynamic()?.value as? String)?.toDoubleOrNull() ?: uiSettings.uiScale)
        }
        sliderSetting(
            "mapscale",
            "Game Map scale",
            uiSettings.zoomLevel,
            step = 0.1,
            min = MapZoom.MIN,
            max = MapZoom.MAX,
        ) {
            MapZoom.set((byId("mapscale")?.asDynamic()?.value as? String)?.toDoubleOrNull() ?: uiSettings.zoomLevel)
        }
    }

    private fun buildSettingSection(section: SettingSection) {
        val header = addTag("smSettingsContainer", "div")
        header.className = "osada-settings-header"
        val title = addTag(header, "span")
        title.className = "osada-settings-header__title"
        title.textContent = section.title
        section.caption?.let { cap ->
            header.classList.add("osada-settings-header--observer")
            val caption = addTag(header, "span")
            caption.className = "osada-settings-header__caption"
            caption.textContent = cap
        }
        section.items.forEach { (id, label) -> buildSettingCheckbox(id, label) }
        // Volume sliders live inside the Sound section, right after its checkbox — a
        // continuation of the section's own items, not separate top-level controls.
        // Two levels (user request): discrete unit/fire cues vs the continuous weather loop.
        if (section.title == "Sound") buildSoundSliders()
    }

    private fun buildSettingCheckbox(
        id: String,
        label: String,
    ) {
        val container = addTag("smSettingsContainer", "div")
        container.className = "settingContainer left"
        val textDiv = addTag(container, "div")
        // Tooltips that EXPLAIN, not repeat the label. showHiddenVictoryHexes especially:
        // most scenarios have no hidden objectives at all (all their victory hexes carry
        // visible flags), so the toggle legitimately changes nothing there — without this
        // explanation that reads as "the setting is broken" (user report).
        textDiv.title = settingHelp[id] ?: label
        textDiv.className = "settingText left"
        textDiv.textContent = label
        val valueDiv = addTag(container, "div")
        valueDiv.id = id
        // Image-based checkbox (resources/ui/osada/ico_check_on/off.png, asset-sheet
        // extracts) replaces the osada-menu icon-font C/c glyph pair. Driven by a
        // "checked" class rather than the shared toggleCheckbox() case-flip helper (still
        // used unchanged by the per-player AI toggle elsewhere in this file), since there's
        // no text content left here to flip the case of.
        valueDiv.className = "settingValue right osada-checkbox"
        valueDiv.title = textDiv.title
        val enabled = uiSettings.getFlag(id)
        valueDiv.classList.toggle("checked", enabled)
        valueDiv.onclick = { _: MouseEvent -> onSettingCheckboxClick(id, valueDiv) }
    }

    private fun onSettingCheckboxClick(
        id: String,
        valueDiv: HTMLElement,
    ) {
        val current = uiSettings.getFlag(id)
        uiSettings.setFlag(id, !current)
        valueDiv.classList.toggle("checked", !current)
        if (id == "useRetina") applyRetinaScaleAdjustment()
        // Observer badge (Task 5): the settings dialog covers the top bar anyway, so
        // updating it live vs. on close is invisible to the player either way — but
        // do it here too for the instant the dialog closes, not just on the next
        // turn-change/selection-driven updateStatusBar refresh.
        if (id == "noFOW" || id == "showHiddenVictoryHexes") {
            gameRef()?.ui?.updateStatusBar()
        }
        // Live-apply map-visual toggles (Stage 3.5 follow-up): re-render the canvas
        // immediately instead of only on Settings "Done" — the game map is visible
        // in the background behind this dialog (it isn't full-screen), and clicking
        // the checkbox with no visible effect until closing reads as broken.
        if (id in liveRenderSettingIds) {
            GameHolder.instance
                ?.ui
                ?.render
                ?.render()
            GameHolder.instance?.ui?.let { ui ->
                ui.removeAllSmallToolTips()
                ui.addSmallToolTips()
            }
        }
    }

    private fun applyRetinaScaleAdjustment() {
        byId("smSettings")?.asDynamic()?.needPageReload = true
        if (window.devicePixelRatio >= 1.0) {
            if (uiSettings.useRetina && uiSettings.uiScale <= 1.0) {
                byId("uiscale")?.asDynamic()?.value = RETINA_UI_SCALE
            }
            if (!uiSettings.useRetina && uiSettings.uiScale >= RETINA_DOWNSCALE_THRESHOLD) {
                byId("uiscale")?.asDynamic()?.value = 1.0
            }
        }
    }

    private fun buildSoundSliders() {
        sliderSetting(
            "soundvolume",
            "Effects volume",
            uiSettings.soundVolume,
            step = 0.05,
            min = 0.0,
            max = 1.0,
        ) {
            uiSettings.soundVolume =
                (byId("soundvolume")?.asDynamic()?.value as? String)?.toDoubleOrNull() ?: uiSettings.soundVolume
        }
        sliderSetting(
            "ambientvolume",
            "Ambient volume",
            uiSettings.ambientVolume,
            step = 0.05,
            min = 0.0,
            max = 1.0,
        ) {
            uiSettings.ambientVolume =
                (byId("ambientvolume")?.asDynamic()?.value as? String)?.toDoubleOrNull()
                    ?: uiSettings.ambientVolume
            Sound.refreshAmbientVolume() // hear it while adjusting, not on next weather change
        }
    }

    private fun wireSettingsOkHandler() {
        byId("smSetOkBut")?.title =
            "Apply these settings and return. Some display-resolution changes require a page reload."
        byId("smSetOkBut")?.onclick = { _: MouseEvent ->
            makeHidden("smSettings")
            // Settings is reached two ways: the PRE-GAME main menu's own "Settings" button (where
            // #startmenu, the fixed full-screen backdrop, was already showing #smMain underneath —
            // re-showing it here is correct and restores that view), or the IN-GAME "options" gear
            // icon (mainMenuButton("options")), which shows #startmenu+#smMain itself as a pause
            // overlay before Settings even opens. Unconditionally showing #startmenu here matched
            // the pre-game case but broke the in-game one: #smMain isn't necessarily showing at
            // this point (a second "options" press hides it), so the player could land on a bare
            // black #startmenu backdrop with no visible menu content and no way back into the game
            // without a page refresh. Mid-game, return to the game instead — matching what the
            // "options" toggle's own close branch already does.
            if (GameHolder.instance?.gameStarted == true) {
                makeHidden("smMain")
                makeHidden("startmenu")
                byId("options")?.let { toggleButton(it, false) }
            } else {
                makeVisible("startmenu")
            }
            UILayout.resizeUI((byId("uiresize")?.asDynamic()?.value as? String)?.toIntOrNull() ?: uiSettings.uiSize)
            UILayout.scaleUI((byId("uiscale")?.asDynamic()?.value as? String)?.toDoubleOrNull() ?: uiSettings.uiScale)
            gameRef()?.state?.saveSettings()
            // Redraw the map so toggles that affect rendering (show hidden victory hexes, mark own
            // units, hex grid, FoW) take effect immediately instead of only after a scenario restart.
            // Typed access (NOT gameRef()?.ui?.render?.render()): UI.render is an internal val and
            // Render.render() is overloaded, so a dynamic-typed call through gameRef() resolves to a
            // mangled name and silently no-ops — the FoW/hidden-victory-hexes/mark-own-units/hex-grid
            // toggles all appeared to do nothing on Settings OK because of this (only a REAL render,
            // e.g. the Grid button's own render() call, ever cleared the fog veil).
            GameHolder.instance
                ?.ui
                ?.render
                ?.render()
            gameRef()?.ui?.updateStatusBar()
            // Hex-name/objective labels (e.g. "Show optional objectives tooltips") are a separate
            // DOM overlay, not part of the canvas render() above — rebuild it too or the toggle
            // has no visible effect until some unrelated trigger (Grid toggle, zoom) forces one.
            GameHolder.instance?.ui?.let { ui ->
                ui.removeAllSmallToolTips()
                ui.addSmallToolTips()
            }
            if (byId("smSettings")?.asDynamic()?.needPageReload == true) {
                window.location.reload()
            }
            if (byId("smSettings")?.asDynamic()?.messageHidden == true) {
                makeVisible("ui-message")
            }
        }
    }
}
