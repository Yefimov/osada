package org.osada.storage

import org.osada.BIZERTE_SAVE_JSON
import org.osada.Game
import org.osada.GameHolder
import org.osada.GameState
import org.osada.GameStatePersistence
import org.osada.GameStateRestore
import org.osada.GameStateSerializer
import org.osada.VERSION
import org.osada.model.Equipment
import org.osada.model.resetEquipment
import org.osada.save.SaveStatus
import org.osada.save.SaveStatusBus
import org.osada.ui.ProfileBackup
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserStorageTest {
    private val previousGame = GameHolder.instance

    @AfterTest
    fun restoreRealStorage() {
        BrowserStorage.simulateDeniedForTests(false)
        GameHolder.instance = previousGame
    }

    @Test
    fun aDeniedStoreFallsBackToSessionMemoryInsteadOfThrowing() {
        BrowserStorage.simulateDeniedForTests(true)
        assertFalse(BrowserStorage.persistent)
        assertNull(localStorage.getItem("osada-x"))
        localStorage.setItem("osada-x", "1")
        localStorage.setItem("osada-y", "2")
        assertEquals("1", localStorage.getItem("osada-x"))
        assertEquals(2, localStorage.length)
        assertEquals(
            setOf("osada-x", "osada-y"),
            (0 until localStorage.length).mapNotNull { localStorage.key(it) }.toSet(),
        )
        localStorage.removeItem("osada-x")
        assertNull(localStorage.getItem("osada-x"))
    }

    @Test
    fun aSessionOnlyProfileIsNeitherDurableNorImportable(): Promise<Unit> {
        BrowserStorage.simulateDeniedForTests(true)
        assertEquals("save_load.backup.import_session_only", ProfileBackup.importRefusalKey())
        return Promise<Boolean> { resolve, _ -> BrowserStorage.whenDurable(resolve) }.then { durable ->
            assertFalse(durable, "a reload would erase memory-only writes")
        }
    }

    @Test
    fun aReachableStoreIsUsedDirectly() {
        BrowserStorage.simulateDeniedForTests(false)
        assertTrue(BrowserStorage.persistent)
        localStorage.setItem("osada-browser-storage-test", "v")
        assertEquals("v", kotlinx.browser.localStorage.getItem("osada-browser-storage-test"))
        localStorage.removeItem("osada-browser-storage-test")
    }

    @Test
    fun aSessionOnlySaveIsNeverReportedAsSaved(): Promise<Unit> {
        Equipment.resetEquipment()
        Equipment.asyncLoad = false
        js(
            """
            if (typeof window.scenariolist === 'undefined') {
                window.scenariolist = [
                    ['Test Theater'],
                    ['bizerte.xml','Bizerte','Bizerte scenario',[],[],'eqp-adlerkorps']
                ];
            }
        """,
        )
        BrowserStorage.simulateDeniedForTests(true)
        val payload = JSON.parse<dynamic>(BIZERTE_SAVE_JSON)
        payload.fmt = GameStateSerializer.SAVE_FORMAT_VERSION
        payload.campaign = null
        val game = Game()
        game.state = GameState(game)
        val persistence = GameStatePersistence(game, GameStateRestore(game))
        return Promise<Unit> { resolve, _ ->
            persistence.restoreFromString(JSON.stringify(payload)) { resolve(Unit) }
        }.then {
            SaveStatusBus.update(SaveStatus.Idle)
            persistence.save()
            val status = SaveStatusBus.current
            assertTrue(
                status is SaveStatus.Failed && status.message == SaveStatus.SESSION_ONLY,
                "memory-only storage must report session-only, not Saved: $status",
            )
            assertTrue(localStorage.getItem("osada-standalone-session-" + majorVersion()) != null, "kept in memory")
        }
    }

    private fun majorVersion() = VERSION.split(".").take(2).joinToString(".")
}
