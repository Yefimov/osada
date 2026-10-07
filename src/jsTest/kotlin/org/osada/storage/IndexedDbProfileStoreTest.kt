package org.osada.storage

import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The durable profile store against the browser's real IndexedDB, in a database of its own. */
class IndexedDbProfileStoreTest {
    private var counter = 0

    @BeforeTest
    fun useTestDatabase() {
        IndexedDbProfileStore.databaseName = "osada-profile-test-${kotlin.js.Date.now()}-${counter++}"
        BrowserStorage.simulateDeniedForTests(false)
        js("window.__osadaPut = window.__osadaPut || IDBObjectStore.prototype.put")
        js("window.__osadaSetItem = window.__osadaSetItem || Storage.prototype.setItem")
    }

    @AfterTest
    fun restore() {
        js("IDBObjectStore.prototype.put = window.__osadaPut")
        js("Storage.prototype.setItem = window.__osadaSetItem")
        BrowserStorage.useProfileStoreForTests(null)
        BrowserStorage.simulateDeniedForTests(false)
        kotlinx.browser.localStorage.removeItem(BrowserStorage.BACKEND_KEY)
        listOf(
            "osada-save-run-t-a.json-current",
            "osada-save-index-t",
        ).forEach(kotlinx.browser.localStorage::removeItem)
        IndexedDbProfileStore.databaseName = IndexedDbProfileStore.DB_NAME
    }

    private fun open(token: String = "test-owner"): Promise<IndexedDbProfileStore> =
        Promise { resolve, reject ->
            IndexedDbProfileStore.open(token) { store ->
                if (store == null) reject(Error("IndexedDB unavailable")) else resolve(store)
            }
        }

    private fun durable(store: IndexedDbProfileStore): Promise<Boolean> =
        Promise { resolve, _ -> store.whenDurable(resolve) }

    @Test
    fun writesMadeInOneTaskReachDiskTogether(): Promise<Unit> =
        open()
            .then { store ->
                store.put("osada-save-run-t-a.json-current", "A")
                store.put("osada-save-index-t", "I")
                store.put("osada-tmp", "gone")
                store.remove("osada-tmp")
                durable(store).then { ok ->
                    assertTrue(ok)
                    open().then { reread ->
                        assertEquals(
                            mapOf("osada-save-run-t-a.json-current" to "A", "osada-save-index-t" to "I"),
                            reread.cache.toMap(),
                        )
                    }
                }
            }.unsafeCast<Promise<Unit>>()

    @Test
    fun aFailedTransactionIsReportedAndRetriedNotLost(): Promise<Unit> =
        open()
            .then { store ->
                js(
                    "IDBObjectStore.prototype.put = " +
                        "function () { throw new DOMException('quota', 'QuotaExceededError'); }",
                )
                store.put("osada-save-index-t", "first")
                durable(store).then { ok ->
                    js("IDBObjectStore.prototype.put = window.__osadaPut")
                    assertFalse(ok, "a write that never reached disk must not be reported durable")
                    assertEquals("QuotaExceededError", store.lastError?.name as? String)
                    // Asking again retries the retained write; this time the device accepts it.
                    durable(store).then { retried ->
                        assertTrue(retried)
                        open().then { reread -> assertEquals("first", reread.cache["osada-save-index-t"]) }
                    }
                }
            }.unsafeCast<Promise<Unit>>()

    @Test
    fun aRetryNeverPutsAnOlderSaveBackOverANewerOne(): Promise<Unit> =
        open()
            .then { store ->
                // Save A is in flight and fails; save B of the same key is written meanwhile.
                js(
                    """
                    IDBObjectStore.prototype.put = function (value, key) {
                        if (value === 'A') throw new DOMException('quota', 'QuotaExceededError');
                        return window.__osadaPut.call(this, value, key);
                    }
                    """,
                )
                store.put("osada-save-index-t", "A")
                // Two microtasks later the flush has started (A in flight) but no IDB event ran yet.
                Promise
                    .resolve(Unit)
                    .then { }
                    .then {
                        store.put("osada-save-index-t", "B")
                        durable(store).then { ok ->
                            assertTrue(ok, "the newer save committed")
                            // Ask again: nothing older may still be waiting to be retried.
                            durable(store).then {
                                open().then { reread ->
                                    assertEquals(
                                        "B",
                                        reread.cache["osada-save-index-t"],
                                        "the older failed save came back",
                                    )
                                }
                            }
                        }
                    }
            }.unsafeCast<Promise<Unit>>()

    @Test
    fun aTabFencedOutByANewOwnerCannotWrite(): Promise<Unit> =
        open("old-tab")
            .then { oldTab ->
                var fenced = false
                oldTab.onFenced = { fenced = true }
                open("new-tab").then { newTab ->
                    oldTab.put("osada-save-index-t", "stale")
                    durable(oldTab).then { ok ->
                        assertFalse(ok, "the old owner's write must be refused")
                        assertTrue(fenced, "the old owner must be told")
                        newTab.put("osada-save-run-t-a.json-current", "current")
                        durable(newTab).then { newOk ->
                            assertTrue(newOk)
                            open("verifier").then { reread ->
                                assertNull(reread.cache["osada-save-index-t"], "a fenced write reached disk")
                                assertEquals("current", reread.cache["osada-save-run-t-a.json-current"])
                            }
                        }
                    }
                }
            }.unsafeCast<Promise<Unit>>()

    @Test
    fun anOpenOvertakenByATakeoverNeverClaimsTheProfile(): Promise<Unit> =
        open("new-tab")
            .then { newTab ->
                var fenced = false
                newTab.onFenced = { fenced = true }
                // The old tab's startup open was still pending when "Play here" took the profile.
                var lost = false
                Promise<IndexedDbProfileStore?> { resolve, _ ->
                    IndexedDbProfileStore.open("old-tab", { lost }) { resolve(it) }
                    lost = true
                }.then { oldTab ->
                    assertNull(oldTab, "a tab that lost the profile must not get a store")
                    newTab.put("osada-save-index-t", "after the late callback")
                    durable(newTab).then { ok ->
                        assertTrue(ok, "the late callback took the profile back")
                        assertFalse(fenced)
                    }
                }
            }.unsafeCast<Promise<Unit>>()

    @Test
    fun haltDropsPendingWritesAndRefusesNewOnes(): Promise<Unit> =
        open()
            .then { store ->
                store.put("osada-save-index-t", "pending")
                store.halt()
                store.put("osada-save-run-t-a.json-current", "after halt")
                durable(store).then { ok ->
                    assertFalse(ok)
                    open("verifier").then { reread -> assertTrue(reread.cache.isEmpty(), "halted writes reached disk") }
                }
            }.unsafeCast<Promise<Unit>>()

    @Test
    fun migrationMergesAndNeverDeletesAProfileTheMarkerForgot(): Promise<Unit> =
        open()
            .then { store ->
                // IndexedDB holds the only copy; localStorage lost everything, marker included.
                store.put("osada-save-index-t", "only copy")
                durable(store).then {
                    kotlinx.browser.localStorage.removeItem(BrowserStorage.BACKEND_KEY)
                    kotlinx.browser.localStorage.setItem("osada-save-run-t-a.json-current", "from localStorage")
                    Promise<Unit> { resolve, _ -> BrowserStorage.openDurable { resolve(Unit) } }.then {
                        assertEquals("indexeddb", BrowserStorage.profileBackend)
                        assertEquals(
                            "only copy",
                            BrowserStorage.getItem("osada-save-index-t"),
                            "must survive the migration",
                        )
                        assertEquals("from localStorage", BrowserStorage.getItem("osada-save-run-t-a.json-current"))
                        assertNotNull(kotlinx.browser.localStorage.getItem(BrowserStorage.BACKEND_KEY))
                    }
                }
            }.unsafeCast<Promise<Unit>>()

    @Test
    fun aMarkerThatCannotBeWrittenKeepsTheSessionOnLocalStorageAndStillStarts(): Promise<Unit> {
        kotlinx.browser.localStorage.removeItem(BrowserStorage.BACKEND_KEY)
        kotlinx.browser.localStorage.setItem("osada-save-index-t", "local profile")
        // IndexedDB accepts the copy; localStorage is full when the switch is recorded.
        js(
            """
            Storage.prototype.setItem = function (key, value) {
                if (key === 'osada-profile-backend') throw new DOMException('full', 'QuotaExceededError');
                return window.__osadaSetItem.call(this, key, value);
            }
            """,
        )
        return Promise<Unit> { resolve, _ -> BrowserStorage.openDurable { resolve(Unit) } }.then {
            assertEquals("localStorage", BrowserStorage.profileBackend, "no marker, no switch")
            assertEquals("local profile", BrowserStorage.getItem("osada-save-index-t"))
            assertNull(kotlinx.browser.localStorage.getItem(BrowserStorage.BACKEND_KEY))
        }
    }
}
