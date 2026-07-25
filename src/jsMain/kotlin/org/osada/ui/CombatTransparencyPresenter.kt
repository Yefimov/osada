@file:Suppress("MaxLineLength", "LongMethod", "ComplexMethod")

package org.osada.ui

import org.osada.GameHolder
import org.osada.hero.HeroCampaign
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.getCountryName
import org.osada.model.getUnits
import org.osada.rules.UnitCapabilities
import org.osada.terrainNames
import org.osada.unitClassNames
import org.w3c.dom.HTMLElement

/** Adds identified live state and the largest active combat modifiers to the enemy card. */
internal object CombatTransparencyPresenter {
    // TODO(detekt): CyclomaticComplexMethod (20) — assembles every identified-state chip and
    // active-modifier line for the enemy card; deliberately deferred rather than rushed.
    @Suppress("CyclomaticComplexMethod")
    fun presentEnemy(unit: GameUnit) {
        UnitIdentityStyles.ensureInstalled()
        val main = byId("ecMain") ?: return
        val chips = ensureChips(main)
        val data = unit.unitData(true)
        val terrain = unit.getHex()?.terrain?.let { terrainNames.getOrNull(it) } ?: "Unknown terrain"
        val game = GameHolder.instance
        val spotSide = game?.spotSide ?: -1
        val visibleUnits =
            game
                ?.scenario
                ?.map
                ?.getUnits()
                ?.filter { candidate ->
                    candidate === unit || candidate.tempSpotted || candidate.getHex()?.isSpotted(spotSide) == true
                }.orEmpty()
        val supportBars = UnitCapabilities.combatSupportBars(visibleUnits, unit)
        val dossier = HeroCampaign.dossier(unit)
        val legacyLeader =
            if (unit.leader >= 0) Leaders.getUnitLeaderDescriptions(unit).firstOrNull()?.first ?: "Commander" else null
        val commander = dossier?.let { "${it.rank} ${it.name}" } ?: legacyLeader

        setChip(chips, "ecExperience", "EXP ${unit.experience}", true, "Enemy formation experience")
        setChip(chips, "ecEntrenchment", "ENT ${unit.entrenchment}", unit.entrenchment > 0, "Entrenchment")
        setChip(chips, "ecSuppression", "SUPP ${unit.hits}", unit.hits > 0, "Temporary suppression")
        setChip(chips, "ecSupport", "SUPPORTED +$supportBars", supportBars > 0, "Combat Support is active")
        setChip(chips, "ecMounted", "MOUNTED", unit.isMounted, "Mounted in organic transport")
        setChip(chips, "ecSurprised", "SURPRISED", unit.isSurprised, "Surprise penalties are active")
        setChip(chips, "ecCommander", "LEADER", commander != null, commander ?: "")

        val country = Equipment.getCountryName(unit.flag - 1)
        val className = unitClassNames.getOrNull(data.uclass) ?: "Unit"
        byId("ecSub")?.textContent = "$className · $country · $terrain"
        byId("ecStat")?.textContent =
            "STR ${unit.strength}/10 · EXP ${unit.experience} · ENT ${unit.entrenchment} · " +
            "DEF ${data.grounddef} ground / ${data.airdef} air"

        val factors =
            buildList {
                add("Combat factors")
                add("Terrain: $terrain")
                if (unit.entrenchment > 0) add("Entrenchment: ${unit.entrenchment}")
                if (unit.experience > 0) add("Experience: ${unit.experience}")
                if (unit.hits > 0) add("Suppression: ${unit.hits}")
                if (supportBars > 0) add("Combat Support: +$supportBars effective experience bar(s)")
                if (commander != null) add("Leader: $commander")
                if (unit.isMounted) add("Mounted state is active")
                if (unit.isSurprised) add("Surprise state is active")
                add("Exact ammo and fuel remain hidden by current spotting rules.")
            }.joinToString("\n• ", postfix = "", prefix = "• ")
        byId("ecStat")?.title = factors
        byId("ecName")?.title =
            listOfNotNull(byId("ecName")?.title, factors)
                .filter(String::isNotBlank)
                .joinToString("\n\n")
    }

    private fun ensureChips(main: HTMLElement): HTMLElement =
        (byId("ecLiveState") ?: addTag(main, "div")).also { container ->
            container.id = "ecLiveState"
            container.className = "osada-ec-chips"
            val strengthRow = byId("ecStrRow")
            if (strengthRow != null && container.parentElement === main) main.insertBefore(container, strengthRow)
        }

    private fun setChip(
        parent: HTMLElement,
        id: String,
        text: String,
        visible: Boolean,
        title: String,
    ) {
        val chip =
            (byId(id) ?: addTag(parent, "span")).also {
                it.id = id
                it.className = "osada-ec-chip"
            }
        chip.style.display = if (visible) "inline-flex" else "none"
        chip.textContent = if (visible) text else ""
        chip.title = title
    }
}
