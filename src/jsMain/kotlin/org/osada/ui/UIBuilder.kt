package org.osada.ui

import org.osada.model.Equipment

/** Shared accessor for the JS `game` global, used by several UI builders. */
internal fun gameRef(): dynamic = js("typeof game !== 'undefined' ? game : null")

/**
 * Facade over the per-screen UI builders.
 *
 * This used to be a ~1260-line god-object building every screen. It has been split
 * (Single Responsibility / Open–Closed) into focused builders — [StartMenuBuilder],
 * [GameStateMenuBuilder], [MainMenuBuilder], [EquipmentWindowBuilder], [UnitInfoBuilder],
 * [UILayout], [MessageDialogs], [TooltipBuilder], [DossierBuilder] — so adding/altering a
 * screen no longer touches one giant object.
 *
 * This object now holds only the **shared UI data** (image paths, the equipment-class and
 * unit-stat metadata, the live small-tooltip/flag-stylesheet lists) plus a few small
 * standalone helpers, and forwards each screen's entry points to its builder. The public
 * surface (and the data other files read) is unchanged.
 */
object UIBuilder {
    var startMenuImgPath = "resources/ui/dialogs/startmenu/images/"
    var menuImgPath = "resources/ui/menu/images/"
    var eqImgPath = "resources/ui/dialogs/equipment/images/"
    var currencyIcon = "<img src='${eqImgPath}currency.png'/>"
    var navalReplacementIcon = "resources/units/images/le211.png"
    var smallToolTipList: MutableList<String> = mutableListOf()
    var flagStyleSheets: MutableList<dynamic> = mutableListOf()

    val unitContextButtons =
        mapOf(
            "mount" to "[",
            "embark" to "2",
            "resupply" to "!",
            "reinforce" to "#",
            "overstrength" to "J",
            "undo" to "_",
            "sleep" to "t",
        )

    // Order matches JS for...in integer-key iteration (numeric ascending: 1,2,3,4,8,9,10,11).
    val eqClassButtons =
        linkedMapOf(
            "1" to Pair("(", "Infantry"),
            "2" to Pair("]", "Tank"),
            "3" to Pair("=", "Recon"),
            "4" to Pair("/", "Anti-tank"),
            "8" to Pair(")", "Artillery"),
            "9" to Pair("*", "Air defence"),
            "10" to Pair("%", "Air Fighter"),
            "11" to Pair("4", "Air Bomber"),
        )

    data class UnitStatEntry(
        val id: String,
        val title: String,
        val glyph: String?,
        val isTopRow: Boolean,
        val property: String? = null,
        val isSortable: Boolean = false,
        // Section heading for the "All stats" expander (#statsRow); blank for entries that don't
        // land there (uLeader/uTransport/uCarrier go to the name-line sockets, uFlag/uCost
        // elsewhere) — see UnitInfoBuilder.buildUnitInfoWindow, the only reader of this field.
        val group: String = "",
    )

    val unitStats =
        listOf(
            UnitStatEntry("uStr", "Unit strength", ":", false, "str", false, "Status"),
            UnitStatEntry("uFuel", "Unit fuel", ";", false, "fuel", false, "Status"),
            UnitStatEntry("uAmmo", "Unit Ammo", "<", false, "ammo", true, "Status"),
            UnitStatEntry("uExp", "Combat Experience", "@", false, "experience", false, "Status"),
            UnitStatEntry("uEnt", "Entrenchment", "\"", false, "entrenchment", false, "Status"),
            UnitStatEntry("uAHard", "Power vs Hard targets", "{", false, "hardatk", true, "Attack"),
            UnitStatEntry("uASoft", "Power vs Soft targets", "\$", false, "softatk", true, "Attack"),
            UnitStatEntry("uAAir", "Power vs Air targets", "&", false, "airatk", true, "Attack"),
            UnitStatEntry("uANaval", "Power vs Naval targets", "}", false, "navalatk", true, "Attack"),
            UnitStatEntry("uDHard", "Defence vs ground attacker", "5", false, "grounddef", true, "Defence"),
            UnitStatEntry("uDAir", "Defence vs air attacker", "3", false, "airdef", true, "Defence"),
            UnitStatEntry("uDClose", "Defence in close combat", "6", false, "closedef", true, "Defence"),
            UnitStatEntry("uDRange", "Defence in ranged combat", "7", false, "rangedefmod", true, "Defence"),
            UnitStatEntry("uGunRange", "Firing range", ">", false, "gunrange", true, "Mobility & Recon"),
            UnitStatEntry("uMovement", "Movement range", "?", false, "movpoints", true, "Mobility & Recon"),
            UnitStatEntry("uIni", "Combat initiative", "|", false, "initiative", true, "Mobility & Recon"),
            UnitStatEntry("uSpot", "Spotting range", "'", false, "spotrange", true, "Mobility & Recon"),
            UnitStatEntry("uMoveType", "Movement type", "~", false, group = "Mobility & Recon"),
            UnitStatEntry("uTarget", "Target type", "`", false, group = "Mobility & Recon"),
            UnitStatEntry("uLeader", "See unit leader", null, true),
            UnitStatEntry("uTransport", "See unit/transport", null, true),
            UnitStatEntry("uCarrier", "See naval/air carrier", null, true),
            UnitStatEntry("uFlag", "country flag", null, false),
            UnitStatEntry("uCost", "Unit price", "B", true, "cost", false),
        )

    // Each screen's forwarders live as extension functions in the sibling
    // UIBuilderScreens.kt / UIBuilderEquipmentDossier.kt / UIBuilderDialogs.kt files
    // (same package), grouped by concern to stay within the function-count limits.
    // Call sites are unaffected: `UIBuilder.foo(...)` resolves the same either way.

    // --- Small standalone helpers kept on the facade ---

    private const val ORDINAL_MOD = 10
    private const val ORDINAL_LAST_DIGIT_RD = 3
    private const val ORDINAL_ELEVENTH = 11
    private const val ORDINAL_TWELFTH = 12
    private const val ORDINAL_THIRTEENTH = 13

    fun unitIDToOrdinal(id: Int): String {
        val last = id % ORDINAL_MOD
        return when {
            id < 0 -> ""
            id == 0 -> "101st"
            last == 1 && id != ORDINAL_ELEVENTH -> "${id}st"
            last == 2 && id != ORDINAL_TWELFTH -> "${id}nd"
            last == ORDINAL_LAST_DIGIT_RD && id != ORDINAL_THIRTEENTH -> "${id}rd"
            else -> "${id}th"
        }
    }

    // eqp param unused since the merge (one shared flags_med.png for every campaign) -- kept so
    // every call site (which passes the scenario's own eqp, now just its availability-set key)
    // doesn't need to change.
    @Suppress("UnusedParameter")
    fun setEquipmentFlags(eqp: String?) {
        if (flagStyleSheets.isEmpty()) {
            listOf("#eqSelCountry", ".playerCountry", ".uSmallFlag").forEach { selector ->
                val sheet = getStyleSheet(selector)
                if (sheet != null) flagStyleSheets.add(sheet)
            }
        }
        flagStyleSheets.forEach { sheet ->
            sheet.backgroundImage = "url('../resources/ui/flags/${Equipment.UNITED_NAME}/flags_med.png')"
        }
    }

    /** OSADA: the deploy-strip buttons this once swapped in (#statusBarButton/#unitsBarButton)
     *  are gone (Task 0 — the reserve list lives inside the equipment window now; both stay
     *  CSS-hidden regardless of what this sets). It used to also reassign `#statusbar`'s and
     *  `#weathermsg`'s onclick to open the combat log — that made ANY click bubbling up through
     *  the top bar (e.g. the ready-unit navigator arrows) reopen the log window, since Task 1's
     *  top-bar buttons don't stopPropagation and this ran on every equipment-window refresh,
     *  clobbering MainMenuBuilder's one-time `statusbar.onclick = null`. The log button is its
     *  own always-visible, always-clickable control now, so nothing needs to be swapped. */
    @Suppress("UnusedParameter")
    fun setDeployOrCombatLogState(deploy: Boolean) {
        makeVisible("combatLogButton")
    }
}
