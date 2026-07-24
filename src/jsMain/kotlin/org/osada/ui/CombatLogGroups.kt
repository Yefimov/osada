package org.osada.ui

import kotlinx.browser.document
import org.osada.CombatLog
import org.osada.LeaderType
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.Hex
import org.osada.model.Leaders
import org.osada.prestigeGains
import org.w3c.dom.HTMLElement

/**
 * [UICombatLog]'s Resupply, Reinforcements, Unit Leaders and Objectives feed groups. Split out
 * purely to keep [UICombatLog] within the project's function-count/class-size limits -- not
 * expected to be called from elsewhere. The (busier) Combat group lives in
 * [CombatLogCombatGroup].
 */
internal object CombatLogGroups {
    fun buildResupplyGroup(): CombatLogFeed.FeedGroup {
        val game = gameRef() ?: return CombatLogFeed.emptyGroup("supply", "Resupply")
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        val keys = js("Object.keys")(CombatLog.log.resupply) as Array<String>
        var count = 0
        for (key in keys) {
            val entry = CombatLog.log.resupply[key]
            if (entry == null || entry.side != game.spotSide) continue
            buildResupplyRow(body, key, entry)
            count++
        }
        return CombatLogFeed.FeedGroup("supply", "Resupply", body, count)
    }

    private fun buildResupplyRow(
        body: HTMLElement,
        key: String,
        entry: dynamic,
    ) {
        val eqData = Equipment.equipment[entry.eqid as Int]
        val icon = CombatLogFeed.resolveUnitIcon(eqData)
        val isCore = entry.isCore as? Boolean == true
        val corePrefix = if (isCore) CombatLogFeed.numSpan("Core ") else ""
        val title = "$corePrefix${UIBuilder.unitIDToOrdinal(key.toInt())} <b>${eqData.name}</b>"
        val ammo = entry.ammo as? Int ?: 0
        val fuel = entry.fuel as? Int ?: 0
        val maxAmmo = eqData.ammo as? Int ?: 0
        val maxFuel = eqData.fuel as? Int ?: 0
        val detailParts = mutableListOf<String>()
        if (ammo > 0) detailParts.add("${CombatLogFeed.numSpan("$ammo/$maxAmmo")} ammo")
        if (fuel > 0 && maxFuel > 0) detailParts.add("${CombatLogFeed.numSpan("$fuel/$maxFuel")} fuel")
        CombatLogFeed.addFeedRow(
            body,
            icon,
            title,
            "Resupplied automatically: " + detailParts.joinToString(" · "),
            isCore,
            false,
            null,
        )
    }

    fun buildReinforceGroup(): CombatLogFeed.FeedGroup {
        val game = gameRef()
        val list = CombatLog.log.reinforce as? Array<dynamic>
        if (game == null || list == null) return CombatLogFeed.emptyGroup("upgrade", "Reinforcements")
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        var count = 0
        for (i in 0 until list.size) {
            val entry = list[i]
            if (entry.side != game.spotSide) continue
            val eqData = Equipment.equipment[entry.eqid as Int]
            val icon = CombatLogFeed.resolveUnitIcon(eqData)
            val pos = entry.pos as? Cell ?: Cell(0, 0)
            val title = "<b>${eqData.name}</b> arrived as reinforcement"
            CombatLogFeed.addFeedRow(body, icon, title, "", false, false, pos)
            count++
        }
        return CombatLogFeed.FeedGroup("upgrade", "Reinforcements", body, count)
    }

    /** Units that surrendered to the viewing player — encirclement kills, kept apart from ordinary
     *  combat losses so "Destroyed / Surrendered" is legible in the Turn Report as well as the AAR. */
    fun buildSurrenderGroup(): CombatLogFeed.FeedGroup {
        val game = gameRef()
        val list = CombatLog.log.surrenders as? Array<dynamic>
        if (game == null || list == null) return CombatLogFeed.emptyGroup("attack", "Surrenders")
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        var count = 0
        for (i in 0 until list.size) {
            val entry = list[i]
            if (entry.side != game.spotSide) continue
            val eqData = Equipment.equipment[entry.eqid as Int]
            val icon = CombatLogFeed.resolveUnitIcon(eqData)
            val pos = entry.pos as? Cell ?: Cell(0, 0)
            val title = "<b>${eqData.name}</b> surrendered — encircled, no retreat"
            val awarded = entry.prestige as? Int ?: 0
            val detail =
                if (awarded > 0) {
                    "Prestige +${CombatLogFeed.numSpan(awarded)}&nbsp;${UIBuilder.currencyIcon}"
                } else {
                    ""
                }
            CombatLogFeed.addFeedRow(body, icon, title, detail, false, false, pos)
            count++
        }
        return CombatLogFeed.FeedGroup("attack", "Surrenders", body, count)
    }

    fun buildLeadersGroup(): CombatLogFeed.FeedGroup {
        val game = gameRef()
        val list = CombatLog.log.leaders as? Array<dynamic>
        if (game == null || list == null) return CombatLogFeed.emptyGroup("star", "Unit Leaders")
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        var count = 0
        for (i in 0 until list.size) {
            val entry = list[i]
            if (entry.side != game.spotSide) continue
            buildLeaderRow(body, entry)
            count++
        }
        return CombatLogFeed.FeedGroup("star", "Unit Leaders", body, count)
    }

    private fun buildLeaderRow(
        body: HTMLElement,
        entry: dynamic,
    ) {
        val eqData = Equipment.equipment[entry.eqid as Int]
        val isCore = entry.isCore as? Boolean == true
        val corePrefix = if (isCore) CombatLogFeed.numSpan("Core ") else ""
        val title =
            "$corePrefix${UIBuilder.unitIDToOrdinal(
                entry.id as Int,
            )} <b>${eqData.name}</b> received a new leader"
        val pos = entry.pos as? Cell ?: Cell(0, 0)
        val classLeader = LeaderType.entries.find { it.value == entry.classLeader as? Int }
        val unitLeader = LeaderType.entries.find { it.value == entry.leader as? Int }
        val classDesc = classLeader?.let { Leaders.description[it]?.first } ?: ""
        val leaderDesc = unitLeader?.let { Leaders.description[it]?.first } ?: ""
        val detail = "${CombatLogFeed.numSpan(classDesc)} and ${CombatLogFeed.numSpan(leaderDesc)} abilities"
        CombatLogFeed.addFeedRow(body, eqData.icon as? String ?: "", title, detail, isCore, false, pos)
    }

    fun buildObjectiveGroup(map: Array<Array<Hex>>): CombatLogFeed.FeedGroup {
        val game = gameRef()
        val list = CombatLog.log.objectives as? Array<dynamic>
        if (game == null || list == null) return CombatLogFeed.emptyGroup("map", "Objectives")
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        var count = 0
        for (i in 0 until list.size) {
            val entry = list[i]
            val pos = entry.pos as? Cell ?: Cell(0, 0)
            val hex = map.getOrNull(pos.row)?.getOrNull(pos.col) ?: continue
            buildObjectiveRow(body, entry, hex, pos, game)
            count++
        }
        return CombatLogFeed.FeedGroup("map", "Objectives", body, count)
    }

    private fun buildObjectiveRow(
        body: HTMLElement,
        entry: dynamic,
        hex: Hex,
        pos: Cell,
        game: dynamic,
    ) {
        val isFriendly = entry.side == game.spotSide
        val title: String
        val detail: String
        if (isFriendly) {
            title = "Captured <b>${hex.name}</b>"
            // The amount actually awarded (recorded by addObjectiveCapture), falling back to the
            // constant only for entries logged before it carried the real figure.
            val awarded = entry.prestige as? Int ?: prestigeGains["objectiveCapture"] ?: 0
            detail = "Prestige +${CombatLogFeed.numSpan(awarded)}&nbsp;${UIBuilder.currencyIcon}"
        } else {
            title = "Lost <b>${hex.name}</b>"
            detail = ""
        }
        // Objectives have no per-unit icon; addFeedRow hides the icon box for an empty src.
        CombatLogFeed.addFeedRow(body, "", title, detail, false, !isFriendly, pos)
    }
}
