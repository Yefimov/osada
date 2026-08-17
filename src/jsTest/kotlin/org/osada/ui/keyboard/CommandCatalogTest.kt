package org.osada.ui.keyboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fixed command catalog (`docs/design/keyboard-shortcuts-and-help.md` §§1, 3, 4, 8).
 *
 * The two contracts that break silently if they regress: a letter command must resolve from
 * `KeyboardEvent.code`, so it survives a Cyrillic layout; and no two commands may claim the same
 * binding within the same modifier state.
 */
class CommandCatalogTest {
    @Test
    fun aLetterCommandResolvesFromThePhysicalKeyUnderACyrillicLayout() {
        // What a RU layout actually delivers for the M key: code stays KeyM, key becomes "ь".
        val command = CommandCatalog.match(code = "KeyM", key = "ь", ctrl = false, alt = false)

        assertEquals(CommandCatalog.MOUNT, command?.id)
    }

    @Test
    fun theSameLetterUnderALatinLayoutResolvesToTheSameCommand() {
        assertEquals(
            CommandCatalog.MOUNT,
            CommandCatalog.match(code = "KeyM", key = "m", ctrl = false, alt = false)?.id,
        )
    }

    @Test
    fun noTwoCommandsClaimTheSameBinding() {
        val seen = mutableMapOf<String, String>()
        CommandCatalog.commands.forEach { command ->
            command.bindings.forEach { binding ->
                val slot = "${binding.code ?: binding.key}|ctrl=${binding.ctrl}"
                val previous = seen.put(slot, command.id)
                assertTrue(previous == null, "$slot is claimed by both $previous and ${command.id}")
            }
        }
    }

    @Test
    fun zIsStrategicMapAloneAndUndoOnlyWithControl() {
        assertEquals(
            CommandCatalog.STRATEGIC_MAP,
            CommandCatalog.match(code = "KeyZ", key = "z", ctrl = false, alt = false)?.id,
        )
        assertEquals(
            CommandCatalog.UNDO,
            CommandCatalog.match(code = "KeyZ", key = "z", ctrl = true, alt = false)?.id,
        )
    }

    @Test
    fun anUnmodifiedBindingRefusesEveryModifier() {
        // Ctrl+S stays the browser's Save, Alt+S stays a menu accelerator.
        assertNull(CommandCatalog.match(code = "KeyS", key = "s", ctrl = true, alt = false))
        assertNull(CommandCatalog.match(code = "KeyS", key = "s", ctrl = false, alt = true))
        assertNotNull(CommandCatalog.match(code = "KeyS", key = "s", ctrl = false, alt = false))
    }

    @Test
    fun noDestructiveCommandIsBound() {
        val forbidden = listOf("disband", "sell", "delete", "restart_turn", "quick_load", "end_turn")

        forbidden.forEach { id -> assertNull(CommandCatalog.byId(id), "$id must not be bound") }
        assertNull(
            CommandCatalog.match(code = "Delete", key = "Delete", ctrl = false, alt = false),
            "Delete must not activate a command",
        )
    }

    @Test
    fun everyUnitCommandMapsToAnActionChipTheStripCanRender() {
        val chipActions = setOf("mount", "embark", "resupply", "reinforce", "overstrength", "undo")

        assertEquals(chipActions, CommandCatalog.unitActionFor.values.toSet())
        CommandCatalog.unitActionFor.keys.forEach { id ->
            assertNotNull(CommandCatalog.byId(id), "$id is mapped to a chip but is not in the catalog")
        }
    }

    @Test
    fun theFourPanDirectionsCollapseIntoOneHelpRow() {
        val rows = CommandCatalog.cardRows(CommandGroup.MAP)
        val pan = rows.first { it.id == CommandCatalog.PAN }

        assertEquals(1, rows.count { it.id == CommandCatalog.PAN })
        assertEquals("↑ / ↓ / ← / →", pan.cap)
    }

    @Test
    fun panIsTheOnlyRepeatableCommand() {
        val repeatable =
            CommandCatalog.commands
                .filter { it.repeatable }
                .map { it.id }
                .toSet()

        assertEquals(
            setOf(
                CommandCatalog.PAN_UP,
                CommandCatalog.PAN_DOWN,
                CommandCatalog.PAN_LEFT,
                CommandCatalog.PAN_RIGHT,
            ),
            repeatable,
        )
    }

    @Test
    fun everyCommandHasACapAndAGroup() {
        CommandCatalog.commands.forEach { command ->
            assertTrue(command.bindings.isNotEmpty(), "${command.id} has no binding")
            assertTrue(command.capLabel.isNotBlank(), "${command.id} has no key cap")
            assertTrue(
                command.bindings.all { it.code != null || it.key != null },
                "${command.id} has a binding that matches nothing",
            )
        }
    }
}
