@file:Suppress("MaxLineLength")

package org.osada.ui

import org.osada.UnitClass
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

    /**
     * The class-tab row, in display order: `class value -> (osada-menu glyph, English name)`.
     *
     * "All" ([UnitClass.NONE]) is the leftmost tab and Naval and Fortification close the row, so
     * every one of the 21 classes is now reachable by clicking a VISIBLE tab — see
     * [eqClassTabGroups] for the history. `#eqSelClass` scrolls horizontally, which is what makes
     * an 11-tab row affordable; before that, room in the header was the binding constraint.
     *
     * A blank glyph renders label-only. Only `` (the naval-transport pin, also used by
     * `UIToolTips` for ports) is a verified meaning in `osada-menu.ttf` — the font's remaining
     * codepoints are named after their own hex value, so there is no way to look up an "All" or
     * "Fortification" icon without eyeballing the font. Left blank rather than guessed; fill them
     * in if the glyph map is ever recovered.
     */
    val eqClassButtons =
        linkedMapOf(
            UnitClass.NONE.value.toString() to Pair("", "All"),
            "1" to Pair("(", "Infantry"),
            "2" to Pair("]", "Tank"),
            "3" to Pair("=", "Recon"),
            "4" to Pair("/", "Anti-tank"),
            "8" to Pair(")", "Artillery"),
            "9" to Pair("*", "Air defence"),
            "10" to Pair("%", "Air Fighter"),
            "11" to Pair("4", "Air Bomber"),
            UnitClass.DESTROYER.value.toString() to Pair("", "Naval"),
            UnitClass.FORTIFICATION.value.toString() to Pair("", "Fortification"),
        )

    /** The Naval tab's own class. The other seven naval classes merge into it via
     *  [eqClassTabGroups]; `EquipmentWindowController` hides the whole tab on a map with no water. */
    val navalTabClass: Int = UnitClass.DESTROYER.value

    /**
     * Extra [UnitClass] values each tab shows beyond its own, so all 21 classes are reachable.
     *
     * PM had 8 tabs for 21 classes and no mapping, which left 13 classes — Flak, Fortification,
     * both transports, level bombers and every naval class — with no tab at all. The first fix
     * MERGED them into related tabs, the way Panzer Corps 2 does (its Anti-Aircraft tab covers what
     * OG splits into Flak and Air Defense), because a visible "All" tab had been tried and rejected
     * for crowding the row. Making the row scrollable removed that constraint, so the two classes
     * whose merge was purely a space compromise — Fortification and the naval group — got their own
     * tabs back on 2026-07-26. What is still merged is merged because it genuinely belongs together:
     *  - Flak onto Air defence — already collapsed for strip filtering, see normalizeUnitClass;
     *  - Ground Transport onto Tank, Air Transport onto Air Fighter, Level Bomber onto Air Bomber.
     *
     * The naval group hangs off Destroyer, the most representative surface class, so the existing
     * tab machinery ([classesForTab], `normalizeUnitClass`) handles all eight with no special case.
     */
    val eqClassTabGroups: Map<String, List<UnitClass>> =
        mapOf(
            UnitClass.TANK.value.toString() to listOf(UnitClass.GROUND_TRANSPORT),
            UnitClass.AIR_DEFENCE.value.toString() to listOf(UnitClass.FLAK),
            UnitClass.FIGHTER.value.toString() to listOf(UnitClass.AIR_TRANSPORT),
            UnitClass.TACTICAL_BOMBER.value.toString() to listOf(UnitClass.LEVEL_BOMBER),
            UnitClass.DESTROYER.value.toString() to
                listOf(
                    UnitClass.SUBMARINE,
                    UnitClass.BATTLESHIP,
                    UnitClass.BATTLE_CRUISER,
                    UnitClass.CRUISER,
                    UnitClass.LIGHT_CRUISER,
                    UnitClass.CARRIER,
                    UnitClass.NAVAL_TRANSPORT,
                ),
        )

    /**
     * Hides the whole Naval tab on a map with no water, rather than offering a tab whose list is
     * always empty. Same judgement `EquipmentWindowState.isUndeployableOnThisMap` already makes per
     * ship; this just spares the player the click. [hasWater] should be the BROAD water check —
     * a river-only map still floats gunboats, even though it floats no battleship.
     */
    fun syncNavalTabVisibility(hasWater: Boolean) {
        byId("eqclass-$navalTabClass")?.style?.display = if (hasWater) "" else "none"
    }

    /** Every [UnitClass] the tab for [classValue] should list: the tab's own class first, then any
     *  merged in by [eqClassTabGroups]. */
    fun classesForTab(classValue: Int): List<UnitClass> {
        val own = UnitClass.entries.find { it.value == classValue } ?: UnitClass.TANK
        return listOf(own) + (eqClassTabGroups[classValue.toString()] ?: emptyList())
    }

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
            UnitStatEntry(
                "uStr",
                "Strength: remaining soldiers or vehicles. Damage reduces combat effectiveness; reinforce to restore it.",
                ":",
                false,
                "str",
                false,
                "Status",
            ),
            UnitStatEntry(
                "uFuel",
                "Fuel: spent while moving. At zero, fuel-using units cannot move; resupply restores it.",
                ";",
                false,
                "fuel",
                false,
                "Status",
            ),
            UnitStatEntry(
                "uAmmo",
                "Ammo: attacks and defensive fire consume it. A unit with no ammo cannot fire; resupply restores it.",
                "<",
                false,
                "ammo",
                true,
                "Status",
            ),
            UnitStatEntry(
                "uExp",
                "Experience: earned in combat. Higher experience improves combat performance and carries over with core units.",
                "@",
                false,
                "experience",
                false,
                "Status",
            ),
            UnitStatEntry(
                "uEnt",
                "Entrenchment: defensive preparation gained by holding position. Moving usually removes it.",
                "\"",
                false,
                "entrenchment",
                false,
                "Status",
            ),
            UnitStatEntry(
                "uAHard",
                "Hard attack: attack power against tanks and other armoured targets.",
                "{",
                false,
                "hardatk",
                true,
                "Attack",
            ),
            UnitStatEntry(
                "uASoft",
                "Soft attack: attack power against infantry, artillery and unarmoured targets.",
                "\$",
                false,
                "softatk",
                true,
                "Attack",
            ),
            UnitStatEntry("uAAir", "Air attack: attack power against aircraft.", "&", false, "airatk", true, "Attack"),
            UnitStatEntry(
                "uANaval",
                "Naval attack: attack power against ships and landing craft.",
                "}",
                false,
                "navalatk",
                true,
                "Attack",
            ),
            UnitStatEntry(
                "uDHard",
                "Ground defence: protection when attacked by a ground unit.",
                "5",
                false,
                "grounddef",
                true,
                "Defence",
            ),
            UnitStatEntry(
                "uDAir",
                "Air defence: protection when attacked by an aircraft.",
                "3",
                false,
                "airdef",
                true,
                "Defence",
            ),
            UnitStatEntry(
                "uDClose",
                "Close defence: protection in adjacent close combat.",
                "6",
                false,
                "closedef",
                true,
                "Defence",
            ),
            UnitStatEntry(
                "uDRange",
                "Ranged defence modifier: extra protection against ground fire from outside close-combat range.",
                "7",
                false,
                "rangedefmod",
                true,
                "Defence",
            ),
            UnitStatEntry(
                "uGunRange",
                "Firing range in hexes. A value of 1 means the target must be adjacent.",
                ">",
                false,
                "gunrange",
                true,
                "Mobility & Recon",
            ),
            UnitStatEntry(
                "uMovement",
                "Movement points available each turn; terrain and enemy zones of control change their cost.",
                "?",
                false,
                "movpoints",
                true,
                "Mobility & Recon",
            ),
            UnitStatEntry(
                "uIni",
                "Initiative: higher initiative usually fires first, reducing the enemy's return fire.",
                "|",
                false,
                "initiative",
                true,
                "Mobility & Recon",
            ),
            UnitStatEntry(
                "uSpot",
                "Spotting range: maximum distance at which this unit can reveal terrain and hidden enemies.",
                "'",
                false,
                "spotrange",
                true,
                "Mobility & Recon",
            ),
            UnitStatEntry(
                "uMoveType",
                "Movement type: determines terrain costs and whether roads, rails or special transport are required.",
                "~",
                false,
                group = "Mobility & Recon",
            ),
            UnitStatEntry(
                "uTarget",
                "Target type: soft, hard, air or naval; determines which enemy attack value is used against this unit.",
                "`",
                false,
                group = "Mobility & Recon",
            ),
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
