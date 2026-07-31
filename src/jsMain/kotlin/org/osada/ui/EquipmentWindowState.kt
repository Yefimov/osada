package org.osada.ui

import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.getCountriesEquipmentAll
import org.osada.model.getCountriesEquipmentByClasses
import org.osada.model.getCountryEquipmentAll
import org.osada.model.getCountryEquipmentByClasses
import org.osada.model.getCountryName
import org.osada.model.getUnits
import org.osada.model.hasOpenWaterAccess
import org.osada.model.hasRailData
import org.osada.model.hasWaterAccess
import org.osada.uiSettings
import org.osada.unitClassNames
import org.w3c.dom.HTMLElement

/**
 * [EquipmentWindowController.updateEquipmentWindow]'s pure state-resolution helpers: country/
 * unit-list/equipment-list selection, labels and small shared building blocks. Split out purely
 * to keep [EquipmentWindowController] within the project's function-count/class-size limits --
 * not expected to be called from elsewhere.
 */
internal object EquipmentWindowState {
    // 1-based OG codes (equipmentIndexes' own keying — see Equipment.kt / README's "player.country
    // = OGcode - 1" note) for every country to include: every support country on "All", else just
    // the selected one.
    fun resolveCountryIds(
        ui: UI,
        allCountries: Boolean,
        countryIndex: Int,
    ): List<Int> =
        if (allCountries) {
            ui.countriesOnSpotSide.map { it + 1 }
        } else {
            listOfNotNull(ui.countriesOnSpotSide.getOrNull(countryIndex)?.plus(1))
        }

    fun resolveInitialSelectedUnitId(
        currentPlayer: Player,
        eqUserSel: dynamic,
    ): Int =
        if (currentPlayer.hasUndeployedUnits()) {
            (eqUserSel?.deployunit as? Int) ?: -1
        } else {
            (eqUserSel?.userunit as? Int) ?: -1
        }

    fun resolveUnitList(
        currentPlayer: Player,
        map: GameMap,
        coreList: List<GameUnit>,
    ): List<GameUnit> =
        if (currentPlayer.hasUndeployedUnits()) {
            coreList.sortedWith(unitComparator(false))
        } else {
            map.getUnits().sortedWith(unitComparator(true))
        }

    /** Recomputes `uiSettings.deployMode` from [currentPlayer], resolves the matching unit list,
     *  and re-renders the map when the deploy/combat-log mode actually changed. */
    fun updateDeployModeAndUnitList(
        ui: UI,
        currentPlayer: Player,
        map: GameMap,
        coreList: List<GameUnit>,
    ): List<GameUnit> {
        val previousDeployMode = uiSettings.deployMode
        uiSettings.deployMode = currentPlayer.hasUndeployedUnits()
        val unitList = resolveUnitList(currentPlayer, map, coreList)
        UIBuilder.setDeployOrCombatLogState(uiSettings.deployMode)
        if (previousDeployMode != uiSettings.deployMode) ui.render.render()
        return unitList
    }

    private fun unitComparator(byDeployed: Boolean): Comparator<GameUnit> =
        compareBy<GameUnit> { it.unitData(true).uclass }
            .thenBy { it.unitData(true).name }
            .thenBy { it.id }
            .thenBy { if (byDeployed) !it.isCore else false }

    fun classLabel(
        isAll: Boolean,
        selectedClass: Int,
    ): String = if (isAll) "All" else unitClassNames[selectedClass]

    fun countryLabel(
        allCountries: Boolean,
        countryId: Int,
    ): String = if (allCountries) "All Countries" else Equipment.getCountryName(countryId - 1)

    fun resolveEquipmentList(
        isAll: Boolean,
        allCountries: Boolean,
        countryIds: List<Int>,
        countryId: Int,
        selectedClass: Int,
        sortProperty: String,
        descending: Boolean,
    ): List<Int> =
        if (isAll) {
            if (allCountries) {
                Equipment.getCountriesEquipmentAll(countryIds, sortProperty, descending)
            } else {
                Equipment.getCountryEquipmentAll(countryId, sortProperty, descending)
            }
        } else {
            // A tab lists its own class plus any merged into it (UIBuilder.eqClassTabGroups) — so
            // Air defence also shows Flak, Naval shows all eight ship classes, etc. Without this,
            // 13 of the 21 classes had no tab at all.
            val eqClasses = UIBuilder.classesForTab(selectedClass)
            if (allCountries) {
                Equipment.getCountriesEquipmentByClasses(eqClasses, countryIds, sortProperty, descending)
            } else {
                Equipment.getCountryEquipmentByClasses(eqClasses, countryId, sortProperty, descending)
            }
        }

    /** The tab that owns [uclass]: itself if it has its own tab, otherwise the tab it was merged
     *  into (`UIBuilder.eqClassTabGroups` — Fortification -> Infantry, Flak -> Air defence, ...).
     *  Collapsing here keeps strip filtering and tab highlighting matched to the tab the player
     *  actually clicked. Was FLAK -> AIR_DEFENCE only, before the other 12 classes got a home. */
    fun normalizeUnitClass(uclass: Int): Int =
        UIBuilder.eqClassTabGroups.entries
            .firstOrNull { (_, merged) -> merged.any { it.value == uclass } }
            ?.key
            ?.toIntOrNull()
            ?: uclass

    /** Whether [eq] could never actually be deployed anywhere on [map] — a train with nowhere to
     *  run rail on, or a pure-naval ship on a land-locked map — and should therefore be hidden
     *  from the Purchase list rather than sold as something the player could never place (2026-07-14
     *  user request). AMPHIBIOUS is intentionally excluded: it's land-capable too, so a lack of
     *  water doesn't make it unusable. A DEEP_NAVAL-flagged GROUND-class unit is the legacy
     *  "repurpose the unused deep-naval slot for trains" convention (UnitPredicates.isTrain), not
     *  a real ship, so it's checked against rail, not water. DEEP_NAVAL/NAVAL need OPEN water
     *  (Ocean/Port); only COASTAL can also use a river (movTableDry rows 6/10 mark RIVER
     *  impassable for the other two) — a river-only map like Operation Uranus was wrongly letting
     *  submarines/destroyers/battleships onto the Purchase list before this split (2026-07-15). */
    fun isUndeployableOnThisMap(
        map: GameMap,
        eq: EquipmentData,
    ): Boolean {
        val isGroundClass = eq.uclass <= UnitClass.AIR_DEFENCE.value
        return when {
            eq.movmethod == MovMethod.RAIL.value -> !map.hasRailData()
            eq.movmethod == MovMethod.DEEP_NAVAL.value && isGroundClass -> !map.hasRailData()
            eq.movmethod == MovMethod.DEEP_NAVAL.value ||
                eq.movmethod == MovMethod.NAVAL.value -> !map.hasOpenWaterAccess()

            eq.movmethod == MovMethod.COASTAL.value -> !map.hasWaterAccess()
            else -> false
        }
    }

    /** One facing frame of the unit's 9-frame icon strip, as a background-image div — the legacy
     *  `<img>` + `clip: rect(...)` hack showed a fixed 240..320px window, which only equals frame 3
     *  when frames are exactly 80px wide; strips with other frame sizes (e.g. pl12.png's 72px
     *  frames) rendered as chopped slices of two neighbouring frames. background-size in frame
     *  units (900% = 9 frames scaled so one frame fills the box) is geometry-independent; the
     *  x-position formula for frame k is k/(9-1)*100%. */
    fun buildCardSprite(container: HTMLElement): HTMLElement {
        val sprite = addTag(container, "div")
        sprite.className = "eqUnitBoxSprite"
        return sprite
    }
}
