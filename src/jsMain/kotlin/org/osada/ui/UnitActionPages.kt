package org.osada.ui

import org.osada.i18n.I18n

/** Local navigation only: these ids are never game commands or save data. */
internal object UnitActionPages {
    const val BUILD = "build_menu"
    const val BACK = "actions_back"
    const val MORE = "actions_more"
    private const val CAPACITY = 6

    val construction = setOf("build_bridge", "build_fortification", "build_airfield", "build_port", "build_station")

    fun shortLabel(
        action: String,
        fullLabel: String,
    ): String = if (action in construction) I18n.t("unit_info.action.$action.short_label") else fullLabel

    fun main(actions: List<String>): List<String> {
        var addedBuild = false
        return actions.mapNotNull { action ->
            if (action !in construction) {
                action
            } else if (!addedBuild) {
                addedBuild = true
                BUILD
            } else {
                null
            }
        }
    }

    /** Back/More count towards the same six slots as commands. */
    fun pages(actions: List<String>): List<List<String>> {
        val pages = mutableListOf<List<String>>()
        var remaining = actions
        do {
            val back = if (pages.isEmpty()) emptyList() else listOf(BACK)
            val slots = CAPACITY - back.size
            val more = remaining.size > slots
            val count = slots - if (more) 1 else 0
            pages += back + remaining.take(count) + if (more) listOf(MORE) else emptyList()
            remaining = remaining.drop(count)
        } while (remaining.isNotEmpty())
        return pages
    }
}
