@file:Suppress(
    "ComplexCondition",
    "ComplexMethod",
    "LargeClass",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "NestedBlockDepth",
    "ReturnCount",
    "TooManyFunctions",
)

package org.osada.ui

import kotlinx.browser.window
import org.osada.GameHolder
import org.osada.GroundCondition
import org.osada.PlayerType
import org.osada.UNIT_MAX_EXPERIENCE
import org.osada.WeatherCondition
import org.osada.hero.HeroCampaign
import org.osada.i18n.GameText
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.getCountryName
import org.osada.rules.GameRules
import org.osada.rules.SupplyRules
import org.osada.rules.airGroundedByWeather
import org.osada.rules.getReinforceValue
import org.osada.rules.getResupplyValue
import org.osada.rules.isAir
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * Explicit refreshers for already-rendered gameplay surfaces.
 *
 * This intentionally does not scan arbitrary DOM text and does not use a MutationObserver. Each
 * supported surface is refreshed by stable element ids/classes after its normal renderer runs.
 */
internal object GameplayLocalization {
    private const val WEATHER_TOOLTIP_FALLBACK_TOP = 40.0
    private const val WEATHER_TOOLTIP_GAP_PX = 6

    private var currentUnit: GameUnit? = null

    fun refreshAll() {
        refreshStatusBar()
        refreshEquipment()
        refreshUnitInfo(currentUnit)
    }

    fun refreshStatusBar() =
        safely("status bar") {
            refreshStatusBarChrome()
            refreshStatusLine()
            refreshWeather()
            refreshPrestige()
            refreshObjectives()
        }

    fun refreshEquipment() =
        safely("equipment") {
            refreshEquipmentChrome()
            refreshEquipmentHeading()
            refreshEquipmentDetail()
            refreshEquipmentCards()
        }

    fun refreshUnitInfo(unit: GameUnit?) {
        if (unit != null) currentUnit = unit
        safely("unit info") {
            val selected = currentUnit
            refreshUnitStatMetadata()
            if (selected != null) {
                refreshUnitIdentity(selected)
                refreshUnitLeader(selected)
                refreshUnitActions(selected)
                refreshUnitSlots(selected)
                refreshUnitFormationSummary(selected)
            }
        }
    }

    private fun safely(
        surface: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (error: Throwable) {
            console.error("[i18n] $surface localization refresh failed", error)
        }
    }

    private fun refreshStatusBarChrome() {
        byId("statusmsg")?.title = I18n.t("hud.status.scenario.help")
        byId("locmsg")?.title = I18n.t("hud.status.location.help")
        byId("osadaObserverBadge")?.apply {
            textContent = I18n.t("hud.observer.label")
            title = I18n.t("hud.observer.help")
        }
        byId("combatLogButton")?.title = I18n.t("hud.turn_report.help")
        byId("osadaPrestige")?.title = I18n.t("hud.prestige.help")
        byId("buy")?.apply {
            title = I18n.t("hud.reserves.help")
            querySelector(".osada-tb-reserves__label")?.textContent = I18n.t("hud.reserves.label")
        }
        byId("zoom")?.title = I18n.t("hud.strategic_map.help")
        byId("osadaHqBtn")?.title = I18n.t("hud.headquarters.help")
        byId("options")?.title = I18n.t("hud.options.help")
        byId("osadaNav")?.title = I18n.t("hud.ready_units.help")
        byId("osadaNavPrev")?.title = I18n.t("hud.ready_units.previous.help")
        byId("osadaNavCount")?.title = I18n.t("hud.ready_units.count.help")
        byId("osadaNavNext")?.title = I18n.t("hud.ready_units.next.help")
        byId("osadaEndTurn")?.apply {
            title = I18n.t("hud.end_turn.help")
            querySelector(".osada-et__label")?.textContent = I18n.t("hud.end_turn.label")
        }
        byId("statusBarButton")?.title = I18n.t("hud.deploy_strip.close.help")
        byId("unitsBarButton")?.title = I18n.t("hud.deploy_strip.open.help")
    }

    private fun refreshStatusLine() {
        val scenario = GameHolder.instance?.scenario ?: return
        val map = scenario.map
        val currentPlayer = map.currentPlayer ?: return
        val phaseChip =
            if (currentPlayer.hasUndeployedUnits() && currentPlayer.type == PlayerType.HUMAN_LOCAL) {
                "<span class=\"osada-tb-field osada-tb-field--phase\" " +
                    "title=\"${I18n.t("hud.phase.deploy.help")}\"><b>${I18n.t("hud.phase.label")}</b>" +
                    I18n.t("hud.phase.deploy.label") + "</span>"
            } else {
                ""
            }
        val dateText =
            I18n.t(
                "hud.date",
                mapOf(
                    "day" to scenario.date.getDate(),
                    "month" to GameText.monthShort(scenario.date.getMonth()),
                    "year" to scenario.date.getFullYear(),
                ),
            )
        byId("statusmsg")?.innerHTML =
            "<span class=\"osada-tb-op\" title=\"${scenario.name}\">${scenario.name}</span>" +
                "<span class=\"osada-tb-field\"><b>${I18n.t("hud.turn.label")}</b>" +
                "${I18n.formatNumber(map.turn)}/${I18n.formatNumber(map.maxTurns)}</span>" +
                "<span class=\"osada-tb-field osada-tb-date\">$dateText</span>" +
                phaseChip
    }

    private fun refreshWeather() {
        val scenario = GameHolder.instance?.scenario ?: return
        val atmos = scenario.atmosferic
        val ground = scenario.ground
        byId("weathermsg")?.let { element ->
            element.innerHTML =
                org.osada.weatherIconImg(atmos, "osada-tb-weather-img") +
                org.osada.groundIconImg(ground, "osada-tb-weather-img") +
                "<span class=\"osada-tb-weather-txt\">${GameText.weatherShort(atmos)} · " +
                "${GameText.ground(ground)}</span>"
            element.title = ""
            element.onmouseenter = { _: MouseEvent -> showWeatherTooltip(element) }
            element.onmouseleave = { _: MouseEvent -> byId("osadaWeatherTip")?.style?.display = "none" }
        }
    }

    private fun showWeatherTooltip(anchor: HTMLElement) {
        val scenario = GameHolder.instance?.scenario ?: return
        val tip =
            byId("osadaWeatherTip") ?: addTag("mainbody", "div").also {
                it.id = "osadaWeatherTip"
                it.className = "osada-wtip"
            }
        tip.innerHTML = weatherTooltipHtml()
        tip.style.display = "block"
        val rect = anchor.asDynamic().getBoundingClientRect()
        val left =
            ((rect.left as? Number)?.toDouble() ?: 0.0)
                .coerceAtMost(window.innerWidth.toDouble() - 360.0)
                .coerceAtLeast(6.0)
        tip.style.left = "${left.toInt()}px"
        tip.style.top =
            "${((rect.bottom as? Number)?.toDouble() ?: WEATHER_TOOLTIP_FALLBACK_TOP).toInt() + WEATHER_TOOLTIP_GAP_PX}px"
    }

    private fun weatherTooltipHtml(): String {
        val scenario = GameHolder.instance?.scenario ?: return ""
        val atmos = scenario.atmosferic
        val ground = scenario.ground
        val title =
            I18n.t(
                "hud.weather.tooltip.title",
                mapOf("weather" to GameText.weather(atmos), "ground" to GameText.ground(ground)),
            )
        val story =
            I18n.t("hud.weather.story.${weatherStoryKey(atmos)}") + " " +
                I18n.t("hud.ground.story.${groundStoryKey(ground)}")
        val lines = mutableListOf<Pair<String, String>>()
        if (atmos == WeatherCondition.FAIR.value) {
            lines += "good" to I18n.t("hud.weather.effect.aircraft_free")
        } else {
            lines += "bad" to I18n.t("hud.weather.effect.aircraft_grounded")
        }
        when (ground) {
            GroundCondition.FROZEN.value -> {
                lines += "good" to I18n.t("hud.weather.effect.frozen_crossing")
                lines += "bad" to I18n.t("hud.weather.effect.frozen_wheeled")
            }
            GroundCondition.MUD.value -> {
                lines += "bad" to I18n.t("hud.weather.effect.mud_movement")
                lines += "bad" to I18n.t("hud.weather.effect.mud_swamps")
            }
            else -> lines += "good" to I18n.t("hud.weather.effect.dry_movement")
        }
        if (scenario.weatherCanChangeGround) {
            when (atmos) {
                WeatherCondition.RAIN.value -> lines += "dim" to I18n.t("hud.weather.effect.rain_ground_change")
                WeatherCondition.SNOW.value -> lines += "dim" to I18n.t("hud.weather.effect.snow_ground_change")
            }
        }
        return "<div class=\"osada-wtip__title\">$title</div>" +
            "<div class=\"osada-wtip__story\">$story</div>" +
            lines.joinToString("") { (kind, text) ->
                "<div class=\"osada-wtip__line osada-wtip__line--$kind\">$text</div>"
            }
    }

    private fun weatherStoryKey(atmos: Int): String =
        when (atmos) {
            WeatherCondition.FAIR.value -> "clear"
            WeatherCondition.OVERCAST.value -> "overcast"
            WeatherCondition.RAIN.value -> "rain"
            else -> "snow"
        }

    private fun groundStoryKey(ground: Int): String =
        when (ground) {
            GroundCondition.FROZEN.value -> "frozen"
            GroundCondition.MUD.value -> "mud"
            else -> "dry"
        }

    private fun refreshPrestige() {
        val map = GameHolder.instance?.scenario?.map ?: return
        val player = map.currentPlayer ?: return
        val delta = player.prestigePerTurn.getOrElse(map.turn) { 0 }
        byId("osadaPrestige")?.title =
            if (delta > 0) {
                I18n.t("hud.prestige.value_with_income", mapOf("prestige" to player.prestige, "delta" to delta))
            } else {
                I18n.t("hud.prestige.value", mapOf("prestige" to player.prestige))
            }
    }

    private fun refreshObjectives() {
        val container = byId("osadaObjectives") ?: return
        val rows = container.querySelectorAll(".osada-obj")
        for (index in 0 until rows.length) {
            val row = rows.item(index) as? HTMLElement ?: continue
            val held = row.classList.contains("osada-obj--held")
            row.querySelector(".osada-obj__state span:last-child")?.textContent =
                I18n.t(if (held) "hud.objective.held.label" else "hud.objective.enemy.label")
            val name = row.querySelector(".osada-obj__name")?.textContent.orEmpty()
            row.title =
                I18n.t(
                    if (held) "hud.objective.held.help" else "hud.objective.enemy.help",
                    mapOf("name" to name),
                )
        }
        (container.querySelector(".osada-side-empty") as? HTMLElement)?.textContent =
            I18n.t("hud.objective.none_visible")
    }

    private fun refreshEquipmentChrome() {
        byId("eqPrestigeWrap")?.title = I18n.t("equipment.prestige.help")
        val modes = listOf("purchase", "upgrade", "reserve")
        modes.forEach { mode ->
            byId("eqModeTab-$mode")?.apply {
                textContent = I18n.t("equipment.mode.$mode.label")
                title = I18n.t("equipment.mode.$mode.help")
            }
        }
        val classKeys =
            mapOf(
                "1" to "infantry",
                "2" to "tanks",
                "3" to "recon",
                "4" to "anti_tank",
                "8" to "artillery",
                "9" to "air_defence",
                "10" to "fighters",
                "11" to "bombers",
            )
        classKeys.forEach { (id, key) ->
            byId("eqclass-$id")?.apply {
                querySelector(".osada-eqtab__label")?.textContent = I18n.t("equipment.class_tab.$key.label")
                title = I18n.t("equipment.class_tab.$key.help")
            }
        }
        byId("eqReserveHint")?.textContent = I18n.t("equipment.reserve.hint")
        byId("eqUpgradeHint")?.let { hint ->
            val hasReserve = GameHolder.instance?.scenario?.map?.currentPlayer?.hasUndeployedUnits() == true
            hint.textContent =
                I18n.t(
                    if (hasReserve) "equipment.upgrade.reserve_hint" else "equipment.upgrade.hint",
                )
        }
        byId("eqReserveEmpty")?.textContent = I18n.t("equipment.reserve.empty")
        byId("osadaEqCountry")?.title = I18n.t("equipment.country_filter.help")
        byId("osadaEqSort")?.title = I18n.t("equipment.sort.help")
        byId("eqSortOrderBut")?.title = I18n.t("equipment.sort.reverse.help")
        byId("eqSortOptionsBut")?.title = I18n.t("equipment.sort.choose.help")
        byId("eqSelCountry")?.title = I18n.t("equipment.country_cycle.help")
        byId("eqNewBut")?.apply {
            title = I18n.t("equipment.action.buy.help")
            setAttribute("aria-label", I18n.t("equipment.action.buy.label"))
        }
        byId("eqUpgradeBut")?.apply {
            title = I18n.t("equipment.action.upgrade.help")
            setAttribute("aria-label", I18n.t("equipment.action.upgrade.label"))
        }
        byId("eqSellBut")?.apply {
            title = I18n.t("equipment.action.sell.help")
            setAttribute("aria-label", I18n.t("equipment.action.sell.label"))
        }
        byId("eqCloseBut")?.apply {
            title = I18n.t("common.close_esc.help")
            setAttribute("aria-label", I18n.t("common.close.label"))
        }
        localizeEquipmentSelects()
    }

    private fun localizeEquipmentSelects() {
        val countrySelect = byId("osadaEqCountry")
        if (countrySelect != null) {
            val countryOptions = countrySelect.asDynamic().options
            val countryLength = countryOptions.length as? Int ?: 0
            if (countryLength > 0) {
                countryOptions[0].text = I18n.t("equipment.all_countries")
            }
        }
        val options = byId("osadaEqSort")?.asDynamic()?.options ?: return
        val length = options.length as? Int ?: 0
        for (index in 0 until length) {
            val option = options[index]
            val value = option.value as? String ?: continue
            option.text =
                I18n.t(
                    "equipment.sort.option",
                    mapOf("stat" to equipmentSortLabel(value)),
                )
        }
    }

    private fun equipmentSortLabel(property: String): String =
        I18n.t(
            when (property) {
                "cost" -> "equipment.sort.stat.cost"
                "ammo" -> "equipment.sort.stat.ammo"
                "hardatk" -> "equipment.sort.stat.hard_attack"
                "softatk" -> "equipment.sort.stat.soft_attack"
                "airatk" -> "equipment.sort.stat.air_attack"
                "navalatk" -> "equipment.sort.stat.naval_attack"
                "grounddef" -> "equipment.sort.stat.ground_defence"
                "airdef" -> "equipment.sort.stat.air_defence"
                "closedef" -> "equipment.sort.stat.close_defence"
                "rangedefmod" -> "equipment.sort.stat.range_defence"
                "gunrange" -> "equipment.sort.stat.range"
                "movpoints" -> "equipment.sort.stat.movement"
                "initiative" -> "equipment.sort.stat.initiative"
                "spotrange" -> "equipment.sort.stat.spotting"
                else -> "common.unknown"
            },
        )

    private fun refreshEquipmentHeading() {
        val scenario = GameHolder.instance?.scenario ?: return
        val ui = GameHolder.instance?.ui ?: return
        val userSel = byId("eqUserSel")?.asDynamic()
        val selectedClass = userSel?.eqclass as? Int ?: 0
        val all = selectedClass == 0
        val countryIndex = byId("eqSelCountry")?.asDynamic()?.country as? Int ?: -1
        val country =
            if (countryIndex == -1) {
                I18n.t("equipment.all_countries")
            } else {
                ui.countriesOnSpotSide.getOrNull(countryIndex)?.let { Equipment.getCountryName(it) }
                    ?: I18n.t("common.unknown")
            }
        byId("eqInfoText")?.textContent =
            I18n.t(
                "equipment.heading",
                mapOf(
                    "year" to scenario.date.getFullYear(),
                    "class" to if (all) I18n.t("equipment.all_classes") else GameText.unitClass(selectedClass),
                    "country" to country,
                ),
            )
    }

    private fun selectedEquipment(): org.osada.model.EquipmentData? {
        val state = byId("eqUserSel")?.asDynamic() ?: return null
        val id =
            if ((state.detailfocus as? String) == "transport") {
                state.eqtransport as? Int
            } else {
                state.equnit as? Int
            } ?: -1
        return Equipment.getEquipment(id)
    }

    private fun refreshEquipmentDetail() {
        val detail = selectedEquipment()
        if (detail == null) {
            byId("eqDetailBody")?.querySelector(".osada-eqd-empty")?.textContent =
                I18n.t("equipment.detail.select_prompt")
            return
        }
        val classLine = byId("eqDetailBody")?.querySelector(".osada-eqd-class") as? HTMLElement
        classLine?.textContent = "${GameText.unitClass(detail.uclass)} · ${Equipment.getCountryName(detail.country - 1)}"
        (byId("eqDetailBody")?.querySelector(".osada-eqd-avail") as? HTMLElement)?.textContent =
            I18n.t(
                "equipment.availability",
                mapOf(
                    "month" to GameText.monthShort(detail.monthavailable - 1),
                    "year" to detail.yearavailable,
                ),
            )
        val rows = byId("eqDetailBody")?.querySelectorAll(".osada-eqd-stat") ?: return
        for (index in 0 until rows.length) {
            val row = rows.item(index) as? HTMLElement ?: continue
            row.querySelector("b")?.textContent = GameText.equipmentStatLabel(index)
            row.title = GameText.equipmentStatHelp(index)
            if (index == 10) {
                row.querySelector("span")?.textContent = GameText.movementType(detail.movmethod)
            }
        }
    }

    private fun refreshEquipmentCards() {
        listOf("eqUnitList", "eqTransportList").forEach { id ->
            val boxes = byId(id)?.querySelectorAll(".eqUnitBox") ?: return@forEach
            for (index in 0 until boxes.length) {
                val box = boxes.item(index) as? HTMLElement ?: continue
                val name = box.querySelector("div:nth-of-type(2)")?.textContent.orEmpty()
                box.title = I18n.t("equipment.card.inspect.help", mapOf("name" to name))
            }
        }
    }

    private fun refreshUnitStatMetadata() {
        val groupKeys =
            listOf(
                "status" to "Status",
                "attack" to "Attack",
                "defence" to "Defence",
                "mobility_recon" to "Mobility & Recon",
            )
        val groups = byId("statsRow")?.querySelectorAll(".osada-stat-group")
        groupKeys.forEachIndexed { index, (key, _) ->
            val group = groups?.item(index) as? HTMLElement ?: return@forEachIndexed
            (group.querySelector(".osada-stat-group__label") as? HTMLElement)?.apply {
                textContent = I18n.t("unit_info.group.$key.label")
                title = I18n.t("unit_info.group.$key.help")
            }
        }
        unitStatIds.forEach { id ->
            val value = byId(id) ?: return@forEach
            (value.parentElement as? HTMLElement)?.takeIf { it.classList.contains("statsGlyph") }?.title =
                GameText.unitStatHelp(id)
        }
    }

    private fun refreshUnitIdentity(unit: GameUnit) {
        val data = unit.unitData(true)
        byId("osadaUcClass")?.apply {
            val serviceKey =
                when {
                    unit.isTemporaryBorrowed || unit.nodossier -> "auxiliary"
                    unit.formationId != null -> "core"
                    else -> "scenario"
                }
            textContent =
                I18n.t(
                    "unit_info.identity.class_service",
                    mapOf(
                        "class" to GameText.unitClass(data.uclass),
                        "service" to I18n.t("unit_info.identity.service.$serviceKey"),
                    ),
                )
            title = I18n.t("unit_info.identity.class_service.help")
        }
        val states = mutableListOf<String>()
        if (unit.hits > 0) states += I18n.t("unit_info.state.suppressed.label", mapOf("hits" to unit.hits))
        if (unit.isSurprised) states += I18n.t("unit_info.state.surprised.label")
        if (unit.isMounted) states += I18n.t("unit_info.state.mounted.label")
        if (!unit.isDeployed) states += I18n.t("unit_info.state.reserve.label")
        byId("osadaUcTempState")?.apply {
            style.display = if (states.isEmpty()) "none" else "inline-flex"
            textContent = states.joinToString(" · ")
            title =
                buildList {
                    add(I18n.t("unit_info.state.help"))
                    if (unit.hits > 0) add(I18n.t("unit_info.state.suppressed.help", mapOf("hits" to unit.hits)))
                    if (unit.isSurprised) add(I18n.t("unit_info.state.surprised.help"))
                    if (unit.isMounted) add(I18n.t("unit_info.state.mounted.help"))
                    if (!unit.isDeployed) add(I18n.t("unit_info.state.reserve.help"))
                }.joinToString("\n")
        }
        byId("osadaUcStars")?.title =
            I18n.t("unit_info.experience.value", mapOf("experience" to unit.experience, "max" to UNIT_MAX_EXPERIENCE))
        byId("osadaUcEnt")?.title = I18n.t("unit_info.entrenchment.value", mapOf("value" to unit.entrenchment))
        val grounded = GameRules.isAir(unit) && GameRules.airGroundedByWeather(unit)
        byId("osadaUcWeather")?.title = if (grounded) I18n.t("unit_info.grounded.help") else ""
    }

    private fun refreshUnitLeader(unit: GameUnit) {
        val leader = byId("uLeader") ?: return
        val dossier = HeroCampaign.dossier(unit)
        when {
            dossier != null -> {
                val label =
                    I18n.t(
                        "unit_info.leader.led_by",
                        mapOf("rank" to dossier.rank, "name" to dossier.name),
                    )
                leader.textContent = label
                leader.title = "$label\n${I18n.t("unit_info.leader.open_dossier.help")}"
                leader.setAttribute("aria-label", I18n.t("unit_info.leader.open_dossier.aria", mapOf("label" to label)))
            }
            unit.leader >= 0 -> {
                val descriptions = org.osada.model.Leaders.getUnitLeaderDescriptions(unit)
                val trait = descriptions.firstOrNull()?.first ?: I18n.t("unit_info.leader.authored_commander")
                val label = I18n.t("unit_info.leader.legacy", mapOf("trait" to trait))
                leader.textContent = label
                if (descriptions.isEmpty()) leader.title = label
                leader.setAttribute("aria-label", label)
            }
            unit.isTemporaryBorrowed || unit.nodossier -> {
                leader.textContent = I18n.t("unit_info.leader.temporary.label")
                leader.title = I18n.t("unit_info.leader.temporary.help")
                leader.setAttribute("aria-label", leader.title)
            }
            unit.formationId == null -> {
                leader.textContent = I18n.t("unit_info.leader.scenario_only.label")
                leader.title = I18n.t("unit_info.leader.scenario_only.help")
                leader.setAttribute("aria-label", leader.title)
            }
            else -> {
                val progress = HeroCampaign.recognitionProgress(unit)
                val label = I18n.t("unit_info.leader.candidate.label")
                leader.textContent = label
                leader.title =
                    if (progress == null) {
                        I18n.t("unit_info.leader.candidate.no_record")
                    } else {
                        val chance =
                            when {
                                progress.recognition < progress.target ->
                                    I18n.t(
                                        "unit_info.leader.checks_unlock",
                                        mapOf("target" to progress.target),
                                    )
                                progress.drought >= progress.guaranteedAfterFailures ->
                                    I18n.t("unit_info.leader.guaranteed")
                                else ->
                                    I18n.t(
                                        "unit_info.leader.chance",
                                        mapOf("chance" to progress.chancePercent),
                                    )
                            }
                        I18n.t(
                            "unit_info.leader.candidate.help",
                            mapOf(
                                "recognition" to progress.recognition,
                                "chanceLine" to chance,
                                "drought" to progress.drought,
                                "guaranteedAfter" to progress.guaranteedAfterFailures,
                            ),
                        )
                    }
                leader.setAttribute("aria-label", "$label. ${leader.title}")
            }
        }
    }

    private fun refreshUnitActions(unit: GameUnit) {
        val map = GameHolder.instance?.scenario?.map ?: return
        val currentPlayer = map.currentPlayer ?: return
        val buttons = byId("unit-context")?.querySelectorAll("[data-action]") ?: return
        for (index in 0 until buttons.length) {
            val button = buttons.item(index) as? HTMLElement ?: continue
            val action = button.getAttribute("data-action") ?: continue
            val asleep = action == "sleep" && button.getAttribute("data-action-variant") == "wake"
            val mounted = action == "mount" && unit.isMounted
            val labelKey =
                when {
                    action == "mount" && mounted -> "unit_info.action.dismount.label"
                    action == "sleep" && asleep -> "unit_info.action.wake.label"
                    else -> "unit_info.action.$action.label"
                }
            val help =
                when (action) {
                    "mount" -> I18n.t(if (mounted) "unit_info.action.dismount.help" else "unit_info.action.mount.help")
                    "embark" -> I18n.t(if (unit.carrier > 0) "unit_info.action.disembark.help" else "unit_info.action.embark.help")
                    "resupply" -> {
                        val value = GameRules.getResupplyValue(map, unit)
                        val context = SupplyRules.getSupplyContext(map, unit)
                        I18n.t(
                            "unit_info.action.resupply.help",
                            mapOf(
                                "ammo" to value.ammo,
                                "fuel" to value.fuel,
                                "context" to GameText.supplyContext(context.label),
                                "efficiency" to context.efficiencyPercent,
                            ),
                        )
                    }
                    "reinforce" -> {
                        val strength = GameRules.getReinforceValue(map, unit, false)
                        val context = SupplyRules.getSupplyContext(map, unit)
                        I18n.t(
                            "unit_info.action.reinforce.help",
                            mapOf(
                                "strength" to strength,
                                "context" to GameText.supplyContext(context.label),
                                "efficiency" to context.efficiencyPercent,
                            ),
                        )
                    }
                    "overstrength" -> I18n.t("unit_info.action.overstrength.help")
                    "undo" -> I18n.t("unit_info.action.undo.help")
                    "sleep" -> I18n.t(if (asleep) "unit_info.action.wake.help" else "unit_info.action.sleep.help")
                    else -> button.title
                }
            val label = I18n.t(labelKey)
            button.querySelector(".osada-action__label")?.textContent = label
            button.title = help
            button.setAttribute("aria-label", label)
        }
    }

    private fun refreshUnitSlots(unit: GameUnit) {
        if (unit.carrier > 0) byId("uCarrier")?.title = I18n.t("unit_info.carrier.help")
        if (unit.transport != null) {
            byId("uTransport")?.title =
                I18n.t(
                    if (unit.isMounted) "unit_info.transport.mounted.help" else "unit_info.transport.dismounted.help",
                )
        }
    }

    private fun refreshUnitFormationSummary(unit: GameUnit) {
        val formation = HeroCampaign.formationFor(unit) ?: return
        val detail = byId("osadaFormationDetail") ?: return
        val rows = detail.querySelectorAll(".osada-formation-detail__summary")
        val scenarios = formation.history.map { it.scenarioId }.filter(String::isNotBlank).distinct().size
        val victories = formation.history.count { it.eventId.contains("destroy", true) || it.eventId.contains("kill", true) }
        val objectives = formation.history.count { it.eventId.contains("capture", true) || it.eventId.contains("objective", true) }
        val commander = HeroCampaign.dossier(unit)?.let { "${it.rank} ${it.name}" } ?: I18n.t("common.none")
        val honours = formation.battleHonors.takeIf { it.isNotEmpty() }?.joinToString() ?: I18n.t("common.none")
        val values = listOf(formation.recognition, scenarios, victories, objectives, commander, honours)
        val keys = listOf("recognition", "scenarios", "destroyed", "objectives", "commander", "honours")
        for (index in 0 until minOf(rows.length, keys.size)) {
            (rows.item(index) as? HTMLElement)?.textContent =
                I18n.t(
                    "unit_info.formation.summary",
                    mapOf("label" to I18n.t("unit_info.formation.${keys[index]}.label"), "value" to values[index]),
                )
        }
        (detail.querySelector(".osada-service-record-button") as? HTMLElement)?.apply {
            textContent = I18n.t("unit_info.formation.service_record.label")
            title = I18n.t("unit_info.formation.service_record.help")
            setAttribute(
                "aria-label",
                I18n.t("unit_info.formation.service_record.aria", mapOf("formation" to formation.displayName)),
            )
        }
    }

    private val unitStatIds =
        listOf(
            "uStr",
            "uFuel",
            "uAmmo",
            "uExp",
            "uEnt",
            "uAHard",
            "uASoft",
            "uAAir",
            "uANaval",
            "uDHard",
            "uDAir",
            "uDClose",
            "uDRange",
            "uGunRange",
            "uMovement",
            "uIni",
            "uSpot",
            "uMoveType",
            "uTarget",
        )
}
