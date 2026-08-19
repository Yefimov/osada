package org.osada.ui

import kotlinx.browser.document
import org.osada.CombatLog
import org.osada.LeaderType
import org.osada.i18n.GameText
import org.osada.i18n.I18n
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
        val game = gameRef() ?: return CombatLogFeed.emptyGroup("supply", I18n.t("turn_report.group.resupply"))
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
        return CombatLogFeed.FeedGroup("supply", I18n.t("turn_report.group.resupply"), body, count)
    }

    private fun buildResupplyRow(
        body: HTMLElement,
        key: String,
        entry: dynamic,
    ) {
        val eqData = Equipment.equipment[entry.eqid as Int]
        val icon = CombatLogFeed.resolveUnitIcon(eqData)
        val isCore = entry.isCore as? Boolean == true
        val corePrefix = if (isCore) CombatLogFeed.numSpan(I18n.t("turn_report.core_prefix")) else ""
        val title = "$corePrefix${UIBuilder.unitIDToOrdinal(key.toInt())} <b>${eqData.name}</b>"
        val ammo = entry.ammo as? Int ?: 0
        val fuel = entry.fuel as? Int ?: 0
        val maxAmmo = eqData.ammo as? Int ?: 0
        val maxFuel = eqData.fuel as? Int ?: 0
        val detailParts = mutableListOf<String>()
        if (ammo > 0) {
            detailParts.add(
                I18n.t("turn_report.resupply.ammo", mapOf("value" to CombatLogFeed.numSpan("$ammo/$maxAmmo"))),
            )
        }
        if (fuel > 0 && maxFuel > 0) {
            detailParts.add(
                I18n.t("turn_report.resupply.fuel", mapOf("value" to CombatLogFeed.numSpan("$fuel/$maxFuel"))),
            )
        }
        val source = GameText.supplyContextSummary(entry.source as? String, entry.sourceAdjacentEnemies as? Int ?: 0)
        if (source.isNotBlank()) detailParts.add(source)
        CombatLogFeed.addFeedRow(
            body,
            icon,
            title,
            I18n.t("turn_report.resupply.detail", mapOf("details" to detailParts.joinToString(" · "))),
            isCore,
            false,
            null,
        )
    }

    fun buildReinforceGroup(): CombatLogFeed.FeedGroup {
        val game = gameRef()
        val list = CombatLog.log.reinforce as? Array<dynamic>
        if (game == null || list == null) {
            return CombatLogFeed.emptyGroup("upgrade", I18n.t("turn_report.group.reinforcements"))
        }
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        var count = 0
        for (i in 0 until list.size) {
            val entry = list[i]
            if (entry.side != game.spotSide) continue
            val eqData = Equipment.equipment[entry.eqid as Int]
            val icon = CombatLogFeed.resolveUnitIcon(eqData)
            val pos = entry.pos as? Cell ?: Cell(0, 0)
            val title = I18n.t("turn_report.reinforcement.arrived", mapOf("unit" to "<b>${eqData.name}</b>"))
            CombatLogFeed.addFeedRow(body, icon, title, "", false, false, pos)
            count++
        }
        return CombatLogFeed.FeedGroup("upgrade", I18n.t("turn_report.group.reinforcements"), body, count)
    }

    /** Units that surrendered to the viewing player — encirclement kills, kept apart from ordinary
     *  combat losses so "Destroyed / Surrendered" is legible in the Turn Report as well as the AAR. */
    fun buildSurrenderGroup(): CombatLogFeed.FeedGroup {
        val game = gameRef()
        val list = CombatLog.log.surrenders as? Array<dynamic>
        if (game == null || list == null) {
            return CombatLogFeed.emptyGroup("attack", I18n.t("turn_report.group.surrenders"))
        }
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        var count = 0
        for (i in 0 until list.size) {
            val entry = list[i]
            if (entry.side != game.spotSide) continue
            val eqData = Equipment.equipment[entry.eqid as Int]
            val icon = CombatLogFeed.resolveUnitIcon(eqData)
            val pos = entry.pos as? Cell ?: Cell(0, 0)
            val title = I18n.t("turn_report.surrendered", mapOf("unit" to "<b>${eqData.name}</b>"))
            val awarded = entry.prestige as? Int ?: 0
            val detail =
                if (awarded > 0) {
                    I18n.t(
                        "turn_report.prestige.gained",
                        mapOf("amount" to CombatLogFeed.numSpan(awarded), "icon" to UIBuilder.currencyIcon),
                    )
                } else {
                    ""
                }
            CombatLogFeed.addFeedRow(body, icon, title, detail, false, false, pos)
            count++
        }
        return CombatLogFeed.FeedGroup("attack", I18n.t("turn_report.group.surrenders"), body, count)
    }

    /**
     * Formations lost between turns rather than in an exchange -- today only aircraft that ran out
     * of fuel away from base under `air_fuel` (`rules/AirOperations`).
     *
     * Its own group, and shown to the OWNER rather than to an opponent: nobody took these units, and
     * a formation that vanishes with no line here is exactly the "loss with no visible cause"
     * `tools/og-import/DEFERRED.md` §1.1 forbids. Empty, and therefore invisible, for every player
     * who has not switched the rule on.
     */
    fun buildAttritionGroup(): CombatLogFeed.FeedGroup {
        val game = gameRef()
        val list = CombatLog.log.attrition as? Array<dynamic>
        if (game == null || list == null) {
            return CombatLogFeed.emptyGroup("attack", I18n.t("turn_report.group.attrition"))
        }
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        var count = 0
        for (i in 0 until list.size) {
            val entry = list[i]
            if (entry.side != game.spotSide) continue
            val eqData = Equipment.equipment[entry.eqid as Int]
            val icon = CombatLogFeed.resolveUnitIcon(eqData)
            val isCore = entry.isCore as? Boolean == true
            val corePrefix = if (isCore) CombatLogFeed.numSpan(I18n.t("turn_report.core_prefix")) else ""
            val title =
                I18n.t(
                    "turn_report.attrition.lost",
                    mapOf("unit" to "$corePrefix<b>${eqData.name}</b>"),
                )
            // The reason is a stable token, localized here, so a live language change re-renders it.
            val detail = I18n.t("turn_report.attrition.reason.${entry.reason as? String ?: "unknown"}")
            CombatLogFeed.addFeedRow(body, icon, title, detail, isCore, false, entry.pos as? Cell)
            count++
        }
        return CombatLogFeed.FeedGroup("attack", I18n.t("turn_report.group.attrition"), body, count)
    }

    fun buildLeadersGroup(): CombatLogFeed.FeedGroup {
        val game = gameRef()
        val list = CombatLog.log.leaders as? Array<dynamic>
        if (game == null || list == null) {
            return CombatLogFeed.emptyGroup("star", I18n.t("turn_report.group.commanders"))
        }
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        var count = 0
        for (i in 0 until list.size) {
            val entry = list[i]
            if (entry.side != game.spotSide) continue
            buildLeaderRow(body, entry)
            count++
        }
        return CombatLogFeed.FeedGroup("star", I18n.t("turn_report.group.commanders"), body, count)
    }

    @Suppress("LongMethod")
    private fun buildLeaderRow(
        body: HTMLElement,
        entry: dynamic,
    ) {
        val eqData = Equipment.equipment[entry.eqid as Int]
        val isCore = entry.isCore as? Boolean == true
        val corePrefix = if (isCore) CombatLogFeed.numSpan(I18n.t("turn_report.core_prefix")) else ""
        val pos = entry.pos as? Cell ?: Cell(0, 0)
        if (entry.isHero as? Boolean == true) {
            val title =
                I18n.t(
                    "turn_report.commander.distinguished",
                    mapOf(
                        "rank" to CombatLogFeed.numSpan(entry.rank as? String ?: I18n.t("hero.rank.commander")),
                        "name" to "<b>${entry.heroName as? String ?: ""}</b>",
                    ),
                )
            val formation =
                entry.formationName as? String
                    ?: eqData.name as? String
                    ?: I18n.t("turn_report.formation.fallback")
            val detail =
                I18n.t(
                    "turn_report.commander.formation",
                    mapOf("formation" to formation),
                )
            CombatLogFeed.addFeedRow(
                body,
                CombatLogFeed.resolveUnitIcon(eqData),
                title,
                detail,
                isCore,
                false,
                pos,
            )
            return
        }
        val title =
            I18n.t(
                "turn_report.commander.gained",
                mapOf(
                    "unit" to
                        "$corePrefix${UIBuilder.unitIDToOrdinal(entry.id as Int)} <b>${eqData.name}</b>",
                ),
            )
        val classLeader = LeaderType.entries.find { it.value == entry.classLeader as? Int }
        val unitLeader = LeaderType.entries.find { it.value == entry.leader as? Int }
        val classDesc = classLeader?.let { Leaders.description[it]?.first } ?: ""
        val leaderDesc = unitLeader?.let { Leaders.description[it]?.first } ?: ""
        val detail =
            I18n.t(
                "turn_report.commander.abilities",
                mapOf(
                    "first" to CombatLogFeed.numSpan(classDesc),
                    "second" to CombatLogFeed.numSpan(leaderDesc),
                ),
            )
        CombatLogFeed.addFeedRow(
            body,
            CombatLogFeed.resolveUnitIcon(eqData),
            title,
            detail,
            isCore,
            false,
            pos,
        )
    }

    fun buildObjectiveGroup(map: Array<Array<Hex>>): CombatLogFeed.FeedGroup {
        val game = gameRef()
        val list = CombatLog.log.objectives as? Array<dynamic>
        if (game == null || list == null) {
            return CombatLogFeed.emptyGroup("map", I18n.t("turn_report.group.objectives"))
        }
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
        return CombatLogFeed.FeedGroup("map", I18n.t("turn_report.group.objectives"), body, count)
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
            title = I18n.t("turn_report.objective.captured", mapOf("objective" to "<b>${hex.name}</b>"))
            // The amount actually awarded (recorded by addObjectiveCapture), falling back to the
            // constant only for entries logged before it carried the real figure.
            val awarded = entry.prestige as? Int ?: prestigeGains["objectiveCapture"] ?: 0
            detail =
                I18n.t(
                    "turn_report.prestige.gained",
                    mapOf("amount" to CombatLogFeed.numSpan(awarded), "icon" to UIBuilder.currencyIcon),
                )
        } else {
            title = I18n.t("turn_report.objective.lost", mapOf("objective" to "<b>${hex.name}</b>"))
            detail = ""
        }
        // Objectives have no per-unit icon; addFeedRow hides the icon box for an empty src.
        CombatLogFeed.addFeedRow(body, "", title, detail, false, !isFriendly, pos)
    }
}
