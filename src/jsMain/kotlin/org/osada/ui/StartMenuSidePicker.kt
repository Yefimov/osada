package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.getCountryNameByEqp
import org.osada.uiSettings
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * [StartMenuBuilder]'s scenario human/AI side picker (#smScenPlayers / #smSide0 / #smVS /
 * #smSide1). Split out purely to keep [StartMenuBuilder] within the project's
 * function-count/class-size limits -- not expected to be called from elsewhere.
 *
 * A scenario always has exactly two sides (scenariolist indices 3 and 4); a side can carry more
 * than one player entry when it has an AI-controlled auxiliary/reinforcement force (e.g. bn9s06's
 * two Soviet commands) or a distinct supporting nationality (player.support, e.g. Makhno's Black
 * Army). Only ONE player is ever "the human" — the picker toggles that between each side's
 * PRIMARY player (the side's first array entry) and leaves any additional players on that side at
 * AI, exactly mirroring the old "player id 0 defaults to human, everyone else AI" rule this
 * replaces (see [selectScenarioSide]).
 */
internal object StartMenuSidePicker {
    /** The side whose primary player is id 0 — the scenario's own default human side. */
    fun defaultHumanSide(scenario: dynamic): Int {
        for (side in 0..1) {
            val players = scenario[3 + side] as? Array<dynamic> ?: continue
            if ((players.firstOrNull()?.id as? Int) == 0) return side
        }
        return 0
    }

    /** Distinct countries fighting on [side], primary player first, followed by any additional
     *  player/support countries — same de-dup rules as [StartMenuScenarioScreen.allCountriesOf]
     *  but scoped to one side. */
    private fun sideCountries(
        scenario: dynamic,
        side: Int,
    ): List<Int> {
        val players = scenario[3 + side] as? Array<dynamic> ?: return emptyList()
        val result = mutableListOf<Int>()
        for (player in players) {
            (player.country as? Int)?.let { if (it !in result) result.add(it) }
            (player.support as? Array<dynamic>)?.forEach { s ->
                (s as? Int)?.let { if (it !in result) result.add(it) }
            }
        }
        return result
    }

    /** (primary display name, count of additional distinct countries on that side) — falls back
     *  to "Side N" only when even the primary country fails to resolve. */
    private fun sideLabel(
        scenario: dynamic,
        side: Int,
        eqpName: String,
    ): Pair<String, Int> {
        val countries = sideCountries(scenario, side)
        val name =
            countries.firstOrNull()?.let { countryLabel(it, eqpName) }
                ?: I18n.t("scenario.side.number", mapOf("number" to side + 1))
        return Pair(name, maxOf(0, countries.size - 1))
    }

    /** Sets which side is human (mutating the SAME [uiSettings.isAI] the game launch reads —
     *  deliberately not a second selection state), then rebuilds the two cards and the Start
     *  button label. A side with no players at all (shouldn't happen in real data, but the UI
     *  contract requires it) can't be selected. */
    fun selectScenarioSide(
        scenario: dynamic,
        side: Int,
    ) {
        val players0 = scenario[3] as? Array<dynamic> ?: emptyArray()
        val players1 = scenario[4] as? Array<dynamic> ?: emptyArray()
        val target = if (side == 0) players0 else players1
        if (target.isNotEmpty()) {
            for (s in 0..1) {
                val players = if (s == 0) players0 else players1
                val primaryId = players.firstOrNull()?.id as? Int
                for (player in players) {
                    val pid = player.id as? Int ?: continue
                    uiSettings.isAI[pid] = if (pid == primaryId && s == side) 0 else 1
                }
            }
        }
        // renderSideCards falls back to whichever side IS available if [side] turns out to be
        // empty (shouldn't happen in real data); use its resolved side for the Start button too,
        // so the two never disagree.
        val effectiveSide = renderSideCards(scenario, side)
        updateStartButtonLabel(scenario, effectiveSide)
    }

    private fun updateStartButtonLabel(
        scenario: dynamic,
        side: Int,
    ) {
        val eqpName = scenario[5] as? String ?: ""
        val (name, _) = sideLabel(scenario, side, eqpName)
        byId("smSPlayBut")?.setAttribute(
            "data-label",
            I18n.t("scenario.side.start_as", mapOf("country" to name)),
        )
    }

    /** Rebuilds the two side cards + divider in place. Never recreates #smScenPlayers/#smSide0/
     *  #smSide1/#smVS themselves (load-bearing ids the launch wiring and legacy CSS depend on) —
     *  only their contents/attributes, exactly like the code this replaces did. */
    private fun renderSideCards(
        scenario: dynamic,
        selectedSide: Int,
    ): Int {
        val eqpName = scenario[5] as? String ?: ""
        val players0 = scenario[3] as? Array<dynamic> ?: emptyArray()
        val players1 = scenario[4] as? Array<dynamic> ?: emptyArray()
        val available = booleanArrayOf(players0.isNotEmpty(), players1.isNotEmpty())
        val effectiveSelected =
            if (available.getOrElse(selectedSide) { false }) {
                selectedSide
            } else {
                available.indexOfFirst { it }.coerceAtLeast(0)
            }
        val playersRoot = byId("smScenPlayers")
        playersRoot?.apply {
            className = "osada-side-picker"
            setAttribute("role", "radiogroup")
            setAttribute("aria-label", I18n.t("scenario.side.choose"))
        }
        val side0El = byId("smSide0")
        if (playersRoot != null && byId("osadaSidePickerHeading") == null) {
            // Real markup has #smSide0 already nested under #smScenPlayers -> insert before it so
            // it reads first; a flat/synthetic fixture (no such nesting) just appends instead,
            // since insertBefore requires the reference node to actually be a child of parentNode.
            val heading =
                if (side0El != null && side0El.parentNode === playersRoot) {
                    insertTag(playersRoot, "div", side0El)
                } else {
                    addTag(playersRoot, "div")
                }
            heading.id = "osadaSidePickerHeading"
            heading.className = "osada-side-picker__heading"
            heading.textContent = I18n.t("scenario.side.choose")
        }

        byId("smVS")?.apply {
            className = "osada-side-divider"
            setAttribute("aria-hidden", "true")
            innerHTML = "<span class=\"osada-side-divider__medallion\">${I18n.t("scenario.side.vs")}</span>"
        }

        for (side in 0..1) {
            val container = byId("smSide$side") ?: continue
            clearTag(container)
            buildSideCardContent(container, scenario, side, effectiveSelected, available[side], eqpName)
        }
        return effectiveSelected
    }

    private fun buildSideCardContent(
        container: HTMLElement,
        scenario: dynamic,
        side: Int,
        selectedSide: Int,
        available: Boolean,
        eqpName: String,
    ) {
        val (name, extra) = sideLabel(scenario, side, eqpName)
        val countries = sideCountries(scenario, side)
        val primaryCountry = countries.firstOrNull()
        val extraNames = countries.drop(1).mapNotNull { countryLabel(it, eqpName) }
        val isSelected = available && side == selectedSide

        applySideCardAttrs(container, isSelected, available, side == selectedSide)
        container.title =
            if (available) {
                I18n.t(
                    "scenario.side.play_as.help",
                    mapOf("country" to name),
                )
            } else {
                I18n.t(
                    "scenario.side.unavailable.help",
                    mapOf("country" to name),
                )
            }
        buildSideCardNameRow(container, name, primaryCountry)
        buildSideCardBadgeRow(container, extra, extraNames, available, isSelected)

        if (available) {
            container.onclick = { _: MouseEvent -> selectScenarioSide(scenario, side) }
            container.onkeydown = { e -> onSideCardKeydown(e, scenario, side) }
        } else {
            container.onclick = null
            container.onkeydown = null
        }
    }

    private fun applySideCardAttrs(
        container: HTMLElement,
        isSelected: Boolean,
        available: Boolean,
        hasFocus: Boolean,
    ) {
        container.className = "osada-side-card" +
            (if (isSelected) " is-selected" else "") +
            (if (!available) " is-disabled" else "")
        container.setAttribute("role", "radio")
        container.setAttribute("aria-checked", if (isSelected) "true" else "false")
        if (!available) container.setAttribute("aria-disabled", "true") else container.removeAttribute("aria-disabled")
        container.tabIndex = if (available && hasFocus) 0 else -1
    }

    private fun buildSideCardNameRow(
        container: HTMLElement,
        name: String,
        primaryCountry: Int?,
    ) {
        val row = addTag(container, "div")
        row.className = "osada-side-card__row"
        if (primaryCountry != null) {
            val flag = addTag(row, "div")
            flag.className = "playerCountry osada-side-card__flag"
            flag.style.backgroundPosition = "${-StartMenuListToolbar.FLAG_SPRITE_WIDTH * primaryCountry}px 0px"
        }
        val nameEl = addTag(row, "div")
        nameEl.className = "osada-side-card__name"
        nameEl.textContent = name
        nameEl.title = name
    }

    private fun buildSideCardBadgeRow(
        container: HTMLElement,
        extra: Int,
        extraNames: List<String>,
        available: Boolean,
        isSelected: Boolean,
    ) {
        val badgeRow = addTag(container, "div")
        badgeRow.className = "osada-side-card__badgerow"
        if (extra > 0) {
            val sub = addTag(badgeRow, "span")
            sub.className = "osada-side-card__sub"
            sub.textContent = "+$extra"
            sub.title =
                if (extraNames.isNotEmpty()) {
                    I18n.t("scenario.side.also_fighting", mapOf("countries" to extraNames.joinToString(", ")))
                } else {
                    I18n.plural("scenario.side.additional_nations", extra)
                }
        }
        val badge = addTag(badgeRow, "span")
        badge.className = "osada-side-card__badge"
        badge.textContent =
            when {
                !available -> I18n.t("scenario.side.ai_controlled")
                isSelected -> I18n.t("scenario.side.player")
                else -> I18n.t("scenario.side.select")
            }
    }

    private fun onSideCardKeydown(
        e: KeyboardEvent,
        scenario: dynamic,
        side: Int,
    ) {
        when (e.asDynamic().key as? String) {
            "Enter", " " -> {
                e.preventDefault()
                selectScenarioSide(scenario, side)
            }
            "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown" -> {
                e.preventDefault()
                val other = 1 - side
                byId("smSide$other")?.let { el -> if (el.getAttribute("aria-disabled") != "true") el.focus() }
            }
        }
    }
}

/** Naming fallback chain: curated [StartMenuListToolbar.countryDisplayLabel] -> raw country name
 *  (via the scenario's equipment set) -> null if neither resolves to something real. Top-level
 *  (not a [StartMenuSidePicker] member) purely to keep that object's function count in bounds. */
private fun countryLabel(
    country: Int,
    eqpName: String,
): String? =
    StartMenuListToolbar.countryDisplayLabel(country) ?: Equipment
        .getCountryNameByEqp(country, eqpName)
        .let { n -> if (n.isBlank() || n == "Unknown") null else n }
