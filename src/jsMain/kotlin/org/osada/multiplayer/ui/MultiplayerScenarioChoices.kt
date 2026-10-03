package org.osada.multiplayer.ui

import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.getCountryNameByEqp
import org.osada.ui.StartMenuBuilder
import org.osada.ui.StartMenuListToolbar

data class MultiplayerSideChoice(
    val playerId: Int,
    val label: String,
)

/** Same stable group order as the solo register, with the scripted tutorial excluded. */
internal object MultiplayerScenarioChoices {
    fun scenarios(): List<ScenarioChoice> {
        val choices = mutableListOf<ScenarioChoice>()
        var group = ""
        StartMenuBuilder.scenarioList().forEach { row ->
            if ((row.length as? Int) == 1) {
                group = row[0] as? String ?: ""
            } else {
                val file = row[0] as? String ?: return@forEach
                if (!group.contains("Tutorial", ignoreCase = true) && file != "tutorial.xml") {
                    choices += ScenarioChoice(file, row[1] as? String ?: file, group)
                }
            }
        }
        return choices.sortedBy { if (it.group.contains("Standalone", ignoreCase = true)) 0 else 1 }
    }

    /** The same primary command per side that the solo side picker offers; auxiliary players stay AI. */
    fun sides(file: String?): List<MultiplayerSideChoice> {
        val row = StartMenuBuilder.scenarioList().firstOrNull { (it[0] as? String) == file } ?: return emptyList()
        val eqp = row[5] as? String ?: ""
        return (0..1).mapNotNull { side ->
            val players = row[3 + side] as? Array<dynamic> ?: return@mapNotNull null
            val player = players.firstOrNull() ?: return@mapNotNull null
            val id = player.id as? Int ?: return@mapNotNull null
            val country = player.country as? Int
            val label =
                country?.let { StartMenuListToolbar.countryDisplayLabel(it) ?: Equipment.getCountryNameByEqp(it, eqp) }
                    ?: I18n.t("scenario.side.number", mapOf("number" to side + 1))
            MultiplayerSideChoice(id, label)
        }
    }
}
