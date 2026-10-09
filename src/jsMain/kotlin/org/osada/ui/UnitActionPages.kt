package org.osada.ui

import org.osada.i18n.I18n
import org.osada.rules.GreenReplacements

/** Local navigation only: these ids are never game commands or save data. */
internal object UnitActionPages {
    const val BUILD = "build_menu"

    /** The Reinforce choice (2026-10-09): veterans or recruits, opened like [BUILD]. */
    const val REINFORCE = "reinforce_menu"
    const val BACK = "actions_back"
    const val MORE = "actions_more"
    private const val CAPACITY = 6

    val construction = setOf("build_bridge", "build_fortification", "build_airfield", "build_port", "build_station")

    /** Elite (OG's "normal") and green replacements. Grouped only when both are offered: a single
     *  way to reinforce stays a single chip. */
    val replacement = listOf("reinforce", "green_reinforce")

    /** Each sub-menu's chip, its members and how many members must be present to fold them. */
    private val groups = listOf(Triple(BUILD, construction, 1), Triple(REINFORCE, replacement.toSet(), 2))

    fun isMenu(id: String): Boolean = groups.any { it.first == id }

    /** The members of [menu] present in [actions], in [actions]' order. */
    fun members(
        menu: String?,
        actions: Collection<String>,
    ): List<String> {
        val group = groups.firstOrNull { it.first == menu } ?: return emptyList()
        return actions.filter { it in group.second }.takeIf { it.size >= group.third }.orEmpty()
    }

    /** The menu [action] is folded into among [actions], or null when it shows on the main strip. */
    fun menuOf(
        action: String,
        actions: Collection<String>,
    ): String? = groups.firstOrNull { (menu, _, _) -> action in members(menu, actions) }?.first

    fun shortLabel(
        action: String,
        fullLabel: String,
    ): String =
        when (action) {
            in construction -> I18n.t("unit_info.action.$action.short_label")
            // Under the choice the two read as the pair they are; without it Reinforce is just that.
            in replacement ->
                if (GreenReplacements.enabled()) I18n.t("unit_info.action.$action.menu_label") else fullLabel
            else -> fullLabel
        }

    fun main(actions: List<String>): List<String> {
        val opened = mutableSetOf<String>()
        return actions.mapNotNull { action ->
            when (val menu = menuOf(action, actions)) {
                null -> action
                in opened -> null
                else -> menu.also { opened += it }
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
