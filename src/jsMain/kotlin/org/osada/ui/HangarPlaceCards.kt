package org.osada.ui

import org.osada.UnitClass
import org.osada.i18n.I18n
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.rules.CarrierHangars
import org.osada.rules.SupplyRules
import org.w3c.dom.HTMLElement

/** Texts that read differently for a fortification's garrison or a vessel's passengers than for a hangar. */
private val GARRISON_KEYS =
    setOf(
        "title",
        "lead",
        "launch",
        "launch.hint",
        "status.ready",
        "status.landed",
        "empty",
        "pick",
        "blocked.deployment",
        "blocked.landed",
        "blocked.no_room",
    )

/**
 * The text key for [key] in [container]'s window: `hangar.*` where aircraft are kept, `garrison.*`
 * for a fortification, and `aboard.*` for anything else that carries formations -- a ship or
 * submarine with an authored passenger (`bn5s04`), which used to read as a fortification's garrison.
 */
internal fun hangarKey(
    container: GameUnit,
    key: String,
): String =
    when {
        key !in GARRISON_KEYS || CarrierHangars.holdsAircraft(container) -> "hangar.$key"
        container.unitData(true).uclass == UnitClass.FORTIFICATION.value -> "garrison.$key"
        else -> "aboard.$key"
    }

/**
 * The places of [HangarDialog]: one card per formation aboard and a dashed card per free place, so
 * the hangar's capacity is visible as its shape. A card states strength, fuel, ammunition and
 * whether the formation can take off now.
 */

internal object HangarPlaceCards {
    private const val PERCENT = 100

    fun build(
        box: HTMLElement,
        map: GameMap,
        container: GameUnit,
        selected: GameUnit?,
        onPick: (GameUnit) -> Unit,
    ) {
        val grid = addTag(box, "div")
        grid.className = "osada-hgr__places"
        container.hangar.forEach { passenger -> card(grid, map, container, passenger, passenger === selected, onPick) }
        repeat((CarrierHangars.capacity(container) - container.hangar.size).coerceAtLeast(0)) {
            val empty = addTag(grid, "div")
            empty.className = "osada-hgr__place osada-hgr__place--free"
            empty.textContent = I18n.t("hangar.free_place")
        }
    }

    @Suppress("LongParameterList") // one card, and every argument is a different fact about it
    private fun card(
        grid: HTMLElement,
        map: GameMap,
        container: GameUnit,
        passenger: GameUnit,
        chosen: Boolean,
        onPick: (GameUnit) -> Unit,
    ) {
        val data = passenger.unitData(true)
        val card = addTag(grid, "div")
        card.className = "osada-hgr__place" + if (chosen) " osada-hgr__place--selected" else ""
        card.setAttribute("role", "button")
        card.setAttribute("tabindex", "0")
        card.setAttribute("aria-pressed", chosen.toString())
        val sprite = addTag(card, "div")
        sprite.className = "osada-hgr__sprite"
        sprite.style.backgroundImage = "url(${UnitIconResolver.forCurrentScenario(passenger.eqid, data.icon)})"
        val name = addTag(card, "div")
        name.className = "osada-hgr__name"
        name.textContent = passenger.customName ?: data.name
        val strength = addTag(card, "div")
        strength.className = "osada-hgr__strength"
        strength.textContent = passenger.strength.toString()
        meter(card, I18n.t("hangar.fuel"), passenger.fuel, SupplyRules.maxFuel(passenger))
        meter(card, I18n.t("hangar.ammo"), passenger.ammo, SupplyRules.maxAmmo(passenger))
        val status = addTag(card, "div")
        val ready = CarrierHangars.canLaunch(map, passenger, container)
        status.className = "osada-hgr__status osada-hgr__status--" + if (ready) "ready" else "waiting"
        status.textContent = I18n.t(hangarKey(container, if (ready) "status.ready" else "status.landed"))
        card.onclick = { onPick(passenger) }
        card.onkeydown = { event ->
            if (event.key == "Enter" || event.key == " ") {
                event.preventDefault()
                onPick(passenger)
            }
        }
    }

    /**
     * A labelled bar. A record that carries none -- many efiles give aircraft no fuel at all, so they
     * never run dry -- says so in words: an empty bar read as an empty tank (reported 2026-10-07).
     */
    private fun meter(
        card: HTMLElement,
        label: String,
        value: Int,
        max: Int,
    ) {
        val row = addTag(card, "div")
        row.className = "osada-hgr__meter"
        val caption = addTag(row, "span")
        caption.className = "osada-hgr__meter-label"
        caption.textContent = label
        if (max <= 0) {
            val none = addTag(row, "span")
            none.className = "osada-hgr__meter-none"
            none.textContent = I18n.t("hangar.not_used")
            return
        }
        val bar = addTag(row, "span")
        bar.className = "osada-hgr__bar"
        val fill = addTag(bar, "span")
        fill.className = "osada-hgr__fill"
        val percent = (value * PERCENT / max).coerceIn(0, PERCENT)
        fill.style.width = "$percent%"
        val figure = addTag(row, "span")
        figure.className = "osada-hgr__meter-value"
        figure.textContent = "$value/$max"
    }
}
