package org.osada.ui.keyboard

/**
 * Where a command belongs. Doubles as the F1 card's four groups
 * (`docs/design/keyboard-shortcuts-and-help.md` §6).
 */
enum class CommandGroup {
    UNIT,
    MAP,
    PANELS,
    GENERAL,
}

/**
 * One physical binding.
 *
 * [code] is a `KeyboardEvent.code` (`KeyM`, `KeyA`): letter commands MUST match on the physical key
 * so they keep working under a Cyrillic layout, where `event.key` produces `ь`, not `m` (§1).
 * [key] is a `KeyboardEvent.key` and is used only for semantic keys that have no stable physical
 * position worth binding -- `Escape`, `F1`, `?`, `+`, `-`, arrows.
 *
 * [cap] is the locale-independent label drawn on the key cap; it is never translated.
 */
data class ShortcutBinding(
    val cap: String,
    val code: String? = null,
    val key: String? = null,
    val ctrl: Boolean = false,
)

/**
 * One command with a stable id. The id is the only thing dispatch, help, tooltips and tests share;
 * a future remapping layer (deliberately out of scope, §9) would rebind [bindings] and change
 * nothing else.
 */
data class GameCommand(
    val id: String,
    val group: CommandGroup,
    val bindings: List<ShortcutBinding>,
    val repeatable: Boolean = false,
    /** Commands that share a row on the F1 card. The four pan directions are four dispatchable
     *  commands but one line of help, exactly as the design's table writes them. */
    val cardRowId: String = id,
) {
    val labelKey: String get() = "controls.command.$id.label"
    val helpKey: String get() = "controls.command.$id.help"

    /** `M`, or `U / Ctrl+Z` when a command has more than one binding. */
    val capLabel: String get() = bindings.joinToString(" / ") { it.cap }
}

/**
 * The single fixed command catalog (§4). Key dispatch, the F1 card, the key caps appended to action
 * tooltips and the `manual.html` consistency check all read this list; nothing else declares a
 * binding.
 *
 * Deliberately absent, and not oversights:
 * - no Disband/Delete/Restart-Turn/quick-load binding -- destructive or save-scumming (§3);
 * - no separate `C` Combat Log: OSADA has exactly one `#combatLog` window and it is the Turn
 *   Report, already bound to `T`. Binding two keys to one window would advertise a second panel
 *   that does not exist;
 * - no `Alt+E` End Turn and no Space fast-forward until their confirmation/animation semantics are
 *   designed (§3).
 */
object CommandCatalog {
    const val MOUNT = "mount"
    const val EMBARK = "embark"
    const val SUPPLY = "supply"
    const val REINFORCE = "reinforce"
    const val OVERSTRENGTH = "overstrength"
    const val UNDO = "undo"
    const val NEXT_UNIT = "next_unit"
    const val PREV_UNIT = "prev_unit"

    const val AIR_MODE = "air_mode"
    const val HEX_GRID = "hex_grid"
    const val MAP_LABELS = "map_labels"
    const val STRATEGIC_MAP = "strategic_map"
    const val ZOOM_IN = "zoom_in"
    const val ZOOM_OUT = "zoom_out"
    const val PAN = "pan"
    const val PAN_UP = "pan_up"
    const val PAN_DOWN = "pan_down"
    const val PAN_LEFT = "pan_left"
    const val PAN_RIGHT = "pan_right"

    const val EQUIPMENT = "equipment"
    const val INSPECTOR = "inspector"
    const val RESERVES = "reserves"
    const val TURN_REPORT = "turn_report"

    const val HELP = "help"
    const val CLOSE = "close"

    /** Unit commands, in the same order as the action chip strip. */
    private val unitCommands =
        listOf(
            GameCommand(MOUNT, CommandGroup.UNIT, listOf(ShortcutBinding("M", code = "KeyM"))),
            GameCommand(EMBARK, CommandGroup.UNIT, listOf(ShortcutBinding("E", code = "KeyE"))),
            GameCommand(SUPPLY, CommandGroup.UNIT, listOf(ShortcutBinding("S", code = "KeyS"))),
            GameCommand(REINFORCE, CommandGroup.UNIT, listOf(ShortcutBinding("R", code = "KeyR"))),
            GameCommand(OVERSTRENGTH, CommandGroup.UNIT, listOf(ShortcutBinding("O", code = "KeyO"))),
            GameCommand(
                UNDO,
                CommandGroup.UNIT,
                listOf(ShortcutBinding("U", code = "KeyU"), ShortcutBinding("Ctrl+Z", code = "KeyZ", ctrl = true)),
            ),
            GameCommand(NEXT_UNIT, CommandGroup.UNIT, listOf(ShortcutBinding("N", code = "KeyN"))),
            GameCommand(PREV_UNIT, CommandGroup.UNIT, listOf(ShortcutBinding("P", code = "KeyP"))),
        )

    private val mapCommands =
        listOf(
            GameCommand(AIR_MODE, CommandGroup.MAP, listOf(ShortcutBinding("A", code = "KeyA"))),
            GameCommand(HEX_GRID, CommandGroup.MAP, listOf(ShortcutBinding("H", code = "KeyH"))),
            GameCommand(MAP_LABELS, CommandGroup.MAP, listOf(ShortcutBinding("L", code = "KeyL"))),
            GameCommand(STRATEGIC_MAP, CommandGroup.MAP, listOf(ShortcutBinding("Z", code = "KeyZ"))),
            GameCommand(
                ZOOM_IN,
                CommandGroup.MAP,
                listOf(ShortcutBinding("+", key = "+"), ShortcutBinding("=", key = "=")),
            ),
            GameCommand(ZOOM_OUT, CommandGroup.MAP, listOf(ShortcutBinding("-", key = "-"))),
            panCommand(PAN_UP, "↑", "ArrowUp"),
            panCommand(PAN_DOWN, "↓", "ArrowDown"),
            panCommand(PAN_LEFT, "←", "ArrowLeft"),
            panCommand(PAN_RIGHT, "→", "ArrowRight"),
        )

    private fun panCommand(
        id: String,
        cap: String,
        key: String,
    ) = GameCommand(
        id,
        CommandGroup.MAP,
        listOf(ShortcutBinding(cap, key = key)),
        repeatable = true,
        cardRowId = PAN,
    )

    private val panelCommands =
        listOf(
            GameCommand(EQUIPMENT, CommandGroup.PANELS, listOf(ShortcutBinding("B", code = "KeyB"))),
            GameCommand(INSPECTOR, CommandGroup.PANELS, listOf(ShortcutBinding("I", code = "KeyI"))),
            GameCommand(RESERVES, CommandGroup.PANELS, listOf(ShortcutBinding("D", code = "KeyD"))),
            GameCommand(TURN_REPORT, CommandGroup.PANELS, listOf(ShortcutBinding("T", code = "KeyT"))),
        )

    private val generalCommands =
        listOf(
            GameCommand(
                HELP,
                CommandGroup.GENERAL,
                listOf(ShortcutBinding("F1", key = "F1"), ShortcutBinding("?", key = "?")),
            ),
            GameCommand(CLOSE, CommandGroup.GENERAL, listOf(ShortcutBinding("Esc", key = "Escape"))),
        )

    val commands: List<GameCommand> = unitCommands + mapCommands + panelCommands + generalCommands

    /** Panel toggles that stay live while a non-blocking panel (equipment, pause menu) is open. */
    val panelScopeIds: Set<String> = setOf(EQUIPMENT, RESERVES, TURN_REPORT, HELP, CLOSE)

    /** The chip `data-action` id each unit command activates, or `null` for non-unit commands. */
    val unitActionFor: Map<String, String> =
        mapOf(
            MOUNT to "mount",
            EMBARK to "embark",
            SUPPLY to "resupply",
            REINFORCE to "reinforce",
            OVERSTRENGTH to "overstrength",
            UNDO to "undo",
        )

    fun byId(id: String): GameCommand? = commands.firstOrNull { it.id == id }

    fun byGroup(group: CommandGroup): List<GameCommand> = commands.filter { it.group == group }

    /** One printable line of the F1 card. */
    data class CardRow(
        val id: String,
        val cap: String,
        val labelKey: String,
        val helpKey: String,
    )

    fun cardRows(group: CommandGroup): List<CardRow> =
        byGroup(group)
            .groupBy { it.cardRowId }
            .map { (rowId, rowCommands) ->
                CardRow(
                    id = rowId,
                    cap = rowCommands.joinToString(" / ") { it.capLabel },
                    labelKey = "controls.command.$rowId.label",
                    helpKey = "controls.command.$rowId.help",
                )
            }

    /**
     * Resolves one key press. [ctrl] should already fold in the Meta key so Cmd+Z behaves like
     * Ctrl+Z. A binding without `ctrl` refuses any modifier press, so Ctrl+S stays the browser's
     * Save rather than becoming Supply.
     */
    fun match(
        code: String?,
        key: String?,
        ctrl: Boolean,
        alt: Boolean,
    ): GameCommand? =
        commands.firstOrNull { command ->
            command.bindings.any { binding -> matches(binding, code, key, ctrl, alt) }
        }

    private fun matches(
        binding: ShortcutBinding,
        code: String?,
        key: String?,
        ctrl: Boolean,
        alt: Boolean,
    ): Boolean =
        !alt &&
            binding.ctrl == ctrl &&
            ((binding.code != null && binding.code == code) || (binding.key != null && binding.key == key))
}
