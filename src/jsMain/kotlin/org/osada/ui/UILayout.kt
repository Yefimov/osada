package org.osada.ui

import kotlinx.browser.window
import org.osada.i18n.I18n
import org.osada.uiSettings

/**
 * UI layout/scaling concerns: applies CSS zoom/transform scaling to the layout elements,
 * resizes the windows, builds the +/- sliders and applies small-screen constraints.
 * Extracted from the former `UIBuilder` god-object; owns the layout-element table.
 */
internal object UILayout {
    private const val SLIDER_QUANTIZE_SCALE = 100
    private const val SMALL_LAYOUT_WIDTH_THRESHOLD = 800
    private const val EQUIPMENT_PANEL_SMALL_TOP_PX = 112
    private const val DEFAULT_UI_SIZE_PX = 840
    private const val SMALL_LAYOUT_UI_SCALE = 0.9

    private val uiLayoutElements =
        mutableListOf(
            "gameToolTip" to null,
            "ui-message" to null,
            "startmenu" to null,
            // Task 1: #statusbar is now a full-viewport-width fixed top bar positioned purely by CSS,
            // and the floating #menu rail is dissolved — both are removed from UILayout's scale/offset
            // math (the intended incision) so the UI-scale slider no longer shrinks/centres them.
            "statusbar-extension" to 25,
            // Task 3: #unit-info/#unit-context now live inside the CSS-grid bottom zone (fixed ~92px
            // row, grid-area:card) — same incision as Task 1: removed here so the UI-scale slider's
            // transform:scale()/marginTop math doesn't fight the grid's own sizing.
            "container-unitlist" to 30,
            "equipment" to 125,
            // combatLog: removed — same incision as dossier below: the redesigned #combatLog.osada-tr
            // positions itself below the 40px topbar via its own CSS top, and the inline
            // element.style.top = "25px" written here on every settings-OK/UI-scale change was
            // silently overriding it (inline beats stylesheet), tucking the window back under the bar.
            "statusBarButton" to 15,
            "unitsBarButton" to 95,
            "uiToolTip" to null,
            // combatLogButton: removed — reparented into the topbar's flex icon cluster
            // (MainMenuBuilder), positioned by that flex layout, not this table.
            // dossier: removed — same incision as container-unitlist/unit-info above: the redesigned
            // #dossier.osada-dsr is centered via its own CSS (top:50%), which this table's
            // element.style.top = "25px" was silently overwriting on every settings-OK/UI-scale
            // change (an inline style always beats a stylesheet rule, regardless of specificity) —
            // the dossier rendered pinned to the very top of the screen, its header clipped under
            // the topbar, no matter what the CSS said.
        )

    fun scaleUI(scale: Double) {
        if (js("\"zoom\" in document.body.style") as? Boolean ?: false) {
            uiLayoutElements.forEach { (id, _) ->
                byId(id)?.style?.asDynamic()?.zoom = scale.toString()
            }
        } else {
            uiLayoutElements.forEach { (id, _) ->
                val element = byId(id) ?: return@forEach
                val transform = "scale($scale,$scale)"
                element.style.asDynamic().webkitTransform = transform
                element.style.asDynamic().MozTransform = transform
                element.style.asDynamic().transform = transform
                when (id) {
                    "menu", "startmenu", "ui-message" -> {
                        element.style.asDynamic().webkitTransformOrigin = "50% 50%"
                        element.style.asDynamic().mozTransformOrigin = "50% 50%"
                        element.style.asDynamic().transformOrigin = "50% 50%"
                    }

                    else -> {
                        element.style.asDynamic().webkitTransformOrigin = "50% 0"
                        element.style.asDynamic().mozTransformOrigin = "50% 0"
                        element.style.asDynamic().transformOrigin = "50% 0"
                    }
                }
            }
        }
        uiLayoutElements.forEach { (id, baseTop) ->
            val element = byId(id) ?: return@forEach
            if (baseTop != null) {
                val scaled = (baseTop * scale).toInt()
                if (baseTop >= 0) {
                    element.style.top = "${scaled}px"
                } else {
                    element.style.marginTop = "${scaled}px"
                }
            }
        }
        uiSettings.uiScale = scale
    }

    fun createSlider(
        container: dynamic,
        id: String,
        value: Double,
        step: Double,
        min: Double,
        max: Double,
        callback: (() -> Unit)?,
    ) {
        // Round to 2 decimals — NOT toFixed(1): with the volume slider's 0.05 step, one-decimal
        // rounding pulled every second press back where it started (0.5−0.05=0.45→"0.5", and
        // 0.75→"0.8"), so the value could never leave 0.5 / 0.8. Two decimals cover every step
        // this app uses (0.05, 0.1, 10).
        fun quantize(v: Double): Double = kotlin.math.round(v * SLIDER_QUANTIZE_SCALE) / SLIDER_QUANTIZE_SCALE

        fun showValue(v: Double) {
            val q = quantize(v)
            byId("$id-value")?.textContent =
                if (step >= 1.0) q.toInt().toString() else q.toString()
        }

        fun adjust(delta: Double) {
            val input = byId(id) ?: return
            var newValue = (input.asDynamic().value as? String)?.toDoubleOrNull() ?: min
            newValue += delta
            if (newValue < min) newValue = min
            if (newValue > max) newValue = max
            newValue = quantize(newValue)
            input.asDynamic().value = newValue.toString()
            showValue(newValue)
            callback?.invoke()
        }

        val minus = addTag(container, "div")
        minus.className = "smallButton"
        minus.style.cssFloat = "left"
        minus.style.marginBottom = "5px"
        minus.innerHTML = "-"
        minus.title = I18n.t("settings.slider.decrease.help", mapOf("step" to step))
        minus.onclick = { _: org.w3c.dom.events.MouseEvent -> adjust(-step) }

        // A real range slider (user request) with a live numeric readout; the +/- buttons stay
        // for single-step precision. Callers keep reading #<id>.value as a string, same as the
        // legacy free-text box this replaces.
        val inputContainer = addTag(container, "div")
        inputContainer.style.cssFloat = "left"
        inputContainer.className = "osada-slider"
        inputContainer.innerHTML =
            "<input type='range' id='$id' min='$min' max='$max' step='$step' value='$value'>" +
                "<span class='osada-slider__value' id='$id-value'></span>"
        showValue(value)
        byId(id)?.asDynamic()?.oninput = {
            showValue((byId(id)?.asDynamic()?.value as? String)?.toDoubleOrNull() ?: value)
            callback?.invoke()
        }

        val plus = addTag(container, "div")
        plus.className = "smallButton"
        plus.style.cssFloat = "left"
        plus.innerHTML = "+"
        plus.title = I18n.t("settings.slider.increase.help", mapOf("step" to step))
        plus.onclick = { _: org.w3c.dom.events.MouseEvent -> adjust(step) }
    }

    fun resizeUI(size: Int) {
        // #statusbar and #unit-info dropped: the former is a full-width top bar, the latter a
        // fixed CSS-grid item in the bottom zone (Task 3) — neither is sized by the UI-width
        // slider's centered-840px-box math (an inline width+marginLeft:-half here would fight
        // the grid's own column sizing). #dossier dropped the same way once redesigned: this set
        // inline width:840px + marginLeft:-420px UNCONDITIONALLY (every settings-OK click), which
        // silently overwrote the new #dossier.osada-dsr CSS's own width/centering (an inline style
        // always beats a stylesheet rule) — the panel rendered at the legacy 840px box position
        // regardless of what the new CSS specified.
        // combatLog dropped from this list too: the redesigned Turn Report keeps base.css's own
        // 800px centered box; the inline width/marginLeft written here overrode it on every
        // settings-OK click (same inline-beats-stylesheet failure as the dossier above).
        val ids = listOf("container-unitlist")
        ids.forEach { id ->
            byId(id)?.style?.width = "${size}px"
            byId(id)?.style?.marginLeft = "${-(size / 2)}px"
        }
        uiSettings.uiSize = size
    }

    fun setLayoutConstrains(small: Boolean) {
        if (window.innerWidth >= SMALL_LAYOUT_WIDTH_THRESHOLD && !small) return
        byId("eqInfoText")?.style?.width = "0px"
        byId("eqSortInfo")?.style?.width = "0px"
        byId("eqSortInfo")?.style?.height = "0px"
        byId("eqSelClass")?.style?.marginLeft = "0px"
        byId("menu")?.style?.right = "1px"
        val equipmentIndex = uiLayoutElements.indexOfFirst { it.first == "equipment" }
        if (equipmentIndex >= 0) {
            uiLayoutElements[equipmentIndex] = "equipment" to EQUIPMENT_PANEL_SMALL_TOP_PX
        }
        byId("equipment")?.style?.top = "${EQUIPMENT_PANEL_SMALL_TOP_PX}px"
        byId("eqUpgradeText")?.style?.display = "none"
        byId("eqNewText")?.style?.display = "none"
        byId("eqSellText")?.style?.display = "none"
        if (small && uiSettings.uiSize == DEFAULT_UI_SIZE_PX && uiSettings.uiScale == 1.0) {
            uiSettings.uiSize = uiSettings.uiSmallSize
            uiSettings.uiScale = SMALL_LAYOUT_UI_SCALE
        }
    }
}
