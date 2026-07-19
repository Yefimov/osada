package org.osada.ui

import kotlinx.browser.document
import org.osada.CombatLog
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.Hex
import org.osada.terrainNames
import org.w3c.dom.HTMLElement

/**
 * [UICombatLog]'s Combat feed group (the busiest and most detailed row kind: per-unit
 * assault/defend/casualty/XP/ammo/entrenchment reporting). Split out purely to keep
 * [UICombatLog] within the project's function-count/class-size limits -- not expected to be
 * called from elsewhere.
 */
internal object CombatLogCombatGroup {
    private const val LOW_AMMO_WARNING_DIVISOR = 4

    fun buildCombatGroup(map: Array<Array<Hex>>): CombatLogFeed.FeedGroup {
        val game = gameRef() ?: return CombatLogFeed.emptyGroup("attack", "Combat")
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        val combatKeys = js("Object.keys")(CombatLog.log.combat) as Array<String>

        // Core units surface first (same grouping the legacy layout pinned via DOM insertion
        // order) — re-appending an already-attached child MOVES it, so this reorders in place.
        val built = mutableListOf<Pair<HTMLElement, Boolean>>()
        for (key in combatKeys) {
            val entry = CombatLog.log.combat[key]
            if (entry == null || entry.side != game.spotSide) continue
            built.add(buildCombatRow(body, entry, map))
        }
        built.sortedByDescending { it.second }.forEach { body.appendChild(it.first) }
        return CombatLogFeed.FeedGroup("attack", "Combat", body, built.size)
    }

    private fun buildCombatRow(
        body: HTMLElement,
        entry: dynamic,
        map: Array<Array<Hex>>,
    ): Pair<HTMLElement, Boolean> {
        val eqData = Equipment.equipment[entry.eqid as Int]
        val icon = CombatLogFeed.resolveUnitIcon(eqData)
        val isCore = entry.isCore as? Boolean == true
        val pos = entry.pos as? Cell ?: Cell(0, 0)
        val location = combatLocationName(map, pos)
        val title = combatRowTitle(entry, eqData, isCore, location)
        val detail = combatDetailParts(entry, eqData).joinToString(" · ")
        val str = entry.str as? Int ?: 0
        val row = CombatLogFeed.addFeedRow(body, icon, title, detail, isCore, str == 0, pos)
        return row to isCore
    }

    private fun combatLocationName(
        map: Array<Array<Hex>>,
        pos: Cell,
    ): String {
        val hexName = map[pos.row][pos.col].name
        val terrain = map[pos.row][pos.col].terrain
        return if (hexName.isNotEmpty()) hexName else (terrainNames.getOrNull(terrain) ?: "")
    }

    private fun combatRowTitle(
        entry: dynamic,
        eqData: dynamic,
        isCore: Boolean,
        location: String,
    ): String {
        val assaults = entry.assaults as? Int ?: 0
        val defends = entry.defends as? Int ?: 0
        val supports = entry.supports as? Int ?: 0
        val actionVerb =
            when {
                assaults >= defends || supports >= defends ->
                    if (supports >= assaults) "provided fire support from" else "assaulted from"
                else -> "defended against an attack near"
            }
        val corePrefix = if (isCore) CombatLogFeed.numSpan("Core ") else ""
        return "$corePrefix${UIBuilder.unitIDToOrdinal(entry.id as Int)} <b>${eqData.name}</b> $actionVerb $location"
    }

    private fun combatDetailParts(
        entry: dynamic,
        eqData: dynamic,
    ): List<String> = casualtyParts(entry) + statusParts(entry, eqData)

    private fun casualtyParts(entry: dynamic): List<String> {
        val kills = entry.kills as? Int ?: 0
        val losses = entry.losses as? Int ?: 0
        val str = entry.str as? Int ?: 0
        val parts = mutableListOf<String>()
        if (kills > 0) parts.add("inflicted ${CombatLogFeed.numSpan(kills)} casualties")
        if (losses != 0) {
            parts.add(
                if (str > 0) {
                    "lost ${CombatLogFeed.numSpan("-$losses")} (${CombatLogFeed.numSpan(str)} remain)"
                } else {
                    "lost ${CombatLogFeed.numSpan("-$losses")} — destroyed"
                },
            )
        }
        return parts
    }

    private fun statusParts(
        entry: dynamic,
        eqData: dynamic,
    ): List<String> = combatActivityParts(entry, eqData) + entrenchmentParts(entry)

    private fun combatActivityParts(
        entry: dynamic,
        eqData: dynamic,
    ): List<String> {
        val str = entry.str as? Int ?: 0
        val assaults = entry.assaults as? Int ?: 0
        val defends = entry.defends as? Int ?: 0
        val xp = entry.xp as? Int ?: 0
        val ammo = entry.ammo as? Int ?: 0
        val maxAmmo = eqData.ammo as? Int ?: 0
        val parts = mutableListOf<String>()
        if (assaults > 0) parts.add("$assaults assaults")
        if (defends > 0) parts.add("defended $defends attacks")
        if (xp != 0 && str > 0) parts.add("+${CombatLogFeed.numSpan(xp)} XP")
        if (ammo < maxAmmo / LOW_AMMO_WARNING_DIVISOR) {
            parts.add("low ammo ${CombatLogFeed.numSpan("$ammo/$maxAmmo")}")
        }
        return parts
    }

    private fun entrenchmentParts(entry: dynamic): List<String> {
        val entrench = entry.entrench as? Int ?: 0
        val entrenchLost = entry.entrenchLost as? Int ?: 0
        val parts = mutableListOf<String>()
        if (entrenchLost > 0) {
            parts.add(
                "${CombatLogFeed.numSpan(entrenchLost)} entrenchments lost (${CombatLogFeed.numSpan(entrench)} remain)",
            )
        } else if (entrench > 0) {
            parts.add("${CombatLogFeed.numSpan(entrench)} entrenchments")
        }
        return parts
    }
}
