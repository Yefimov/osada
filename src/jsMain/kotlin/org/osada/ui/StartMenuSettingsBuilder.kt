@file:Suppress("MaxLineLength")

package org.osada.ui

import kotlinx.browser.window
import org.osada.GameHolder
import org.osada.i18n.I18n
import org.osada.synchronizeStalinRegimeUnits
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
    //
    // THIS LIST IS THE SOLE SOURCE OF TRUTH for the settings screen's structure. [LiveLocalization]
    // re-labels the already-built DOM on a language change by walking the same list; it used to keep
    // a second, hand-maintained copy, which silently went stale when the Mobile section was added.
    // Because that copy matched headers BY INDEX, every title from Mobile onwards was written into
    // the wrong header: Mobile was captioned "Sound", Sound was captioned "Observer Mode", and the
    // real Observer Mode header was never reached at all.
    internal data class SettingSection(
        val titleKey: String,
        val captionKey: String?,
        val items: List<Pair<String, String>>,
        /** Draws the red "affects game balance" treatment. Observer Mode only — a caption alone is
         *  not a warning, and Mobile's caption is ordinary explanatory text. */
        val balanceWarning: Boolean = false,
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

    /** Title of the section the top sliders belong to. They are built by [buildTopSliders] rather
     *  than from [settingSections] (they are sliders, not checkboxes), but they still need a header
     *  of their own — without one they sat above the first title and read as belonging to Map View,
     *  or to nothing at all. */
    internal const val DISPLAY_SECTION_TITLE_KEY = "settings.section.display.title"

    internal val settingSections =
        listOf(
            SettingSection(
                "settings.section.map_view.title",
                null,
                listOf(
                    "showGridTerrain" to "settings.map.show_grid_terrain.label",
                    "markOwnUnits" to "settings.map.mark_own_units.label",
                    "markEnemyUnits" to "settings.map.mark_enemy_units.label",
                    "useRetina" to "settings.map.use_retina.label",
                ),
            ),
            SettingSection(
                "settings.section.gameplay.title",
                null,
                listOf(
                    "quickAnimation" to "settings.gameplay.quick_animation.label",
                    "showDetailInfoToolTips" to "settings.gameplay.optional_objectives.label",
                    "confirmEndTurn" to "settings.gameplay.confirm_end_turn.label",
                ),
            ),
            SettingSection(
                "settings.section.mobile.title",
                "settings.section.mobile.caption",
                listOf(
                    "reducedEffects" to "settings.mobile.reduced_effects.label",
                ),
            ),
            SettingSection(
                "settings.section.sound.title",
                null,
                listOf(
                    "muteUnitSounds" to "settings.sound.mute_unit_sounds.label",
                ),
            ),
            // Stalin Regime lives here, not under Gameplay. It multiplies every combat, movement
            // and prestige number for the local player by ten — the largest balance override in the
            // game, and squarely what this section's "affects game balance" warning is for. It also
            // now raises the same persistent OBSERVER badge the other two do
            // ([StatusBarController.updateObserverBadge]).
            SettingSection(
                "settings.section.observer.title",
                "settings.section.observer.caption",
                listOf(
                    "stalinRegime" to "settings.gameplay.stalin_regime.label",
                    "noFOW" to "settings.observer.no_fow.label",
                    "showHiddenVictoryHexes" to "settings.observer.hidden_victory_hexes.label",
                ),
                balanceWarning = true,
            ),
        )

    internal val settingHelpKeys =
        mapOf(
            "showGridTerrain" to "settings.map.show_grid_terrain.help",
            "markOwnUnits" to "settings.map.mark_own_units.help",
            "markEnemyUnits" to "settings.map.mark_enemy_units.help",
            "useRetina" to "settings.map.use_retina.help",
            "quickAnimation" to "settings.gameplay.quick_animation.help",
            "showDetailInfoToolTips" to "settings.gameplay.optional_objectives.help",
            "confirmEndTurn" to "settings.gameplay.confirm_end_turn.help",
            "stalinRegime" to "settings.gameplay.stalin_regime.help",
            "reducedEffects" to "settings.mobile.reduced_effects.help",
            "muteUnitSounds" to "settings.sound.mute_unit_sounds.help",
            "noFOW" to "settings.observer.no_fow.help",
            "showHiddenVictoryHexes" to "settings.observer.hidden_victory_hexes.help",
        )

    internal val sliderLabelKeys =
        mapOf(
            "uiresize" to "settings.slider.interface_width.label",
            "uiscale" to "settings.slider.interface_scale.label",
            "mapscale" to "settings.slider.map_scale.label",
            "soundvolume" to "settings.slider.effects_volume.label",
            "ambientvolume" to "settings.slider.ambient_volume.label",
        )

    internal val sliderHelpKeys =
        mapOf(
            "uiresize" to "settings.slider.interface_width.help",
            "uiscale" to "settings.slider.interface_scale.help",
            "mapscale" to "settings.slider.map_scale.help",
            "soundvolume" to "settings.slider.effects_volume.help",
            "ambientvolume" to "settings.slider.ambient_volume.help",
        )

    fun buildSettingsScreen() {
        UILayout.resizeUI(uiSettings.uiSize)
        UILayout.scaleUI(uiSettings.uiScale)
        UILayout.setLayoutConstrains(false)
        LanguageSelector.buildSettingsControl()
        buildSectionHeader(SettingSection(DISPLAY_SECTION_TITLE_KEY, null, emptyList()))
        buildTopSliders()
        settingSections.forEach { buildSettingSection(it) }
        wireSettingsOkHandler()
    }

    // Slider rows need the same row scaffold as the checkbox rows below (and as PM): a
    // `settingContainer left` wrapper with a `settingText left` label and a right-floated div
    // holding the slider. Without it the three sliders had no label and no alignment ("съехали").
    private fun sliderSetting(
        id: String,
        labelKey: String,
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
        val label = I18n.t(labelKey)
        textDiv.textContent = label
        val help = sliderHelpKeys[id]?.let { I18n.t(it) } ?: label
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
            "settings.slider.interface_width.label",
            uiSettings.uiSize.toDouble(),
            step = 10.0,
            min = uiSettings.uiSmallSize.toDouble(),
            max = 1920.0,
        ) {
            UILayout.resizeUI((byId("uiresize")?.asDynamic()?.value as? String)?.toIntOrNull() ?: uiSettings.uiSize)
        }.style.display = "none"
        sliderSetting(
            "uiscale",
            "settings.slider.interface_scale.label",
            uiSettings.uiScale,
            step = 0.1,
            min = 0.5,
            max = 3.0,
        ) {
            UILayout.scaleUI((byId("uiscale")?.asDynamic()?.value as? String)?.toDoubleOrNull() ?: uiSettings.uiScale)
        }
        sliderSetting(
            "mapscale",
            "settings.slider.map_scale.label",
            uiSettings.zoomLevel,
            step = 0.1,
            min = MapZoom.MIN,
            max = MapZoom.MAX,
        ) {
            MapZoom.set((byId("mapscale")?.asDynamic()?.value as? String)?.toDoubleOrNull() ?: uiSettings.zoomLevel)
        }
    }

    private fun buildSectionHeader(section: SettingSection) {
        val header = addTag("smSettingsContainer", "div")
        header.className = "osada-settings-header"
        // The red treatment is driven by balanceWarning, NOT by "has a caption". Keying it to the
        // caption meant adding an explanatory line to the Mobile section also painted its title as
        // a balance warning, which it is not.
        if (section.balanceWarning) header.classList.add("osada-settings-header--observer")
        val title = addTag(header, "span")
        title.className = "osada-settings-header__title"
        title.textContent = I18n.t(section.titleKey)
        section.captionKey?.let { captionKey ->
            val caption = addTag(header, "span")
            caption.className = "osada-settings-header__caption"
            caption.textContent = I18n.t(captionKey)
        }
    }

    private fun buildSettingSection(section: SettingSection) {
        buildSectionHeader(section)
        section.items.forEach { (id, labelKey) -> buildSettingCheckbox(id, labelKey) }
        // Volume sliders live inside the Sound section, right after its checkbox — a
        // continuation of the section's own items, not separate top-level controls.
        // Two levels (user request): discrete unit/fire cues vs the continuous weather loop.
        if (section.titleKey == "settings.section.sound.title") buildSoundSliders()
        // Same continuation pattern: the mobile selects belong to the mobile section, and live in
        // their own builder only because this object is at the project's function-count limit.
        if (section.titleKey == "settings.section.mobile.title") MobileSettingsBuilder.buildControls()
    }

    private fun buildSettingCheckbox(
        id: String,
        labelKey: String,
    ) {
        val container = addTag("smSettingsContainer", "div")
        container.className = "settingContainer left"
        val textDiv = addTag(container, "div")
        // Tooltips that EXPLAIN, not repeat the label. showHiddenVictoryHexes especially:
        // most scenarios have no hidden objectives at all (all their victory hexes carry
        // visible flags), so the toggle legitimately changes nothing there — without this
        // explanation that reads as "the setting is broken" (user report).
        val label = I18n.t(labelKey)
        textDiv.title = settingHelpKeys[id]?.let { I18n.t(it) } ?: label
        container.title = textDiv.title
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
        if (id == "stalinRegime") {
            GameHolder.instance?.synchronizeStalinRegimeUnits()
        }
        if (id == "useRetina") applyRetinaScaleAdjustment()
        // Observer badge (Task 5): the settings dialog covers the top bar anyway, so
        // updating it live vs. on close is invisible to the player either way — but
        // do it here too for the instant the dialog closes, not just on the next
        // turn-change/selection-driven updateStatusBar refresh.
        if (id == "noFOW" || id == "showHiddenVictoryHexes" || id == "stalinRegime") {
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

    /**
     * Retina changes the canvas BACKING resolution; on a phone or tablet it must not also change
     * how large the controls are (spec §42). The interface-scale nudge below therefore applies to
     * desktop only — on a touch layout, control size is owned by the mobile density setting.
     */
    private fun applyRetinaScaleAdjustment() {
        byId("smSettings")?.asDynamic()?.needPageReload = true
        if (MobileLayoutController.mode.isMobileShell) return
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
            "settings.slider.effects_volume.label",
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
            "settings.slider.ambient_volume.label",
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
        byId("smSetOkBut")?.apply {
            title = I18n.t("settings.done.help")
            setAttribute("data-label", I18n.t("common.done.label"))
        }
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
