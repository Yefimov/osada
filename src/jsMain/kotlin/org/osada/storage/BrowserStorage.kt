package org.osada.storage

import kotlinx.browser.window
import org.w3c.dom.Storage

/**
 * The one door to the browser's site storage.
 *
 * A browser that blocks site data (privacy mode, a "block all cookies" setting, a sandboxed
 * embed) throws a `SecurityError` on the mere ACCESS to `window.localStorage`, not only on writes.
 * Before this existed the very first read at startup threw and the game never booted -- guest play
 * was impossible even though nothing in a battle needs storage.
 *
 * When the real store cannot be reached, this falls back to an in-memory map for the page's
 * lifetime: settings, autosaves and checkpoints work within the session, and the profile backup can
 * still be exported from them. [persistent] is false in that mode, and callers that report saving
 * (`GameStatePersistence`) must say so instead of claiming "Saved".
 *
 * Profile domains (saves, checkpoints, hero archive, rulesets, the standalone session, the import
 * journal -- [isProfileKey]) live in IndexedDB once [openDurable] has run at startup
 * ([IndexedDbProfileStore]); device preferences stay in localStorage, where other tabs' `storage`
 * events still reach them. The first IndexedDB boot migrates the profile from localStorage and
 * verifies it on disk before switching; from then on the switch is sticky
 * ([BACKEND_KEY]): if IndexedDB later fails to open, the profile becomes session-only rather than
 * silently reading the stale localStorage copies.
 *
 * Only an unreachable store switches to memory. A quota error from a reachable localStorage
 * propagates unchanged: the import journal and autosave paths depend on seeing it.
 */
@Suppress("TooManyFunctions") // Storage-shaped surface + routing + fencing + migration: one decision
object BrowserStorage {
    private val memory = LinkedHashMap<String, String>()

    /** Null until first use, so tests and early boot pay nothing and a late denial is still seen. */
    private var real: Storage? = null
    private var probed = false

    private var durable: IndexedDbProfileStore? = null

    /** IndexedDB owns the profile but could not be opened this session: profile keys are memory-only. */
    private var profileUnavailable = false

    internal const val BACKEND_KEY = "osada-profile-backend"
    private const val INDEXED_DB = "indexeddb"

    private val profilePrefixes =
        listOf(
            "osada-save-run-",
            "osada-save-index-",
            "osada-mission-start-",
            "osada-hero-archive-",
            "osada-hero-run-epoch-",
            "osada-ruleset-profiles",
            "osada-standalone-session-",
            "osada-resume-target-",
            "osada-profile-import-journal-",
        )

    fun isProfileKey(key: String): Boolean = profilePrefixes.any { key.startsWith(it) }

    /** True when values outlive the page. False when the browser denied site storage. */
    val persistent: Boolean get() = backing() != null && !profileUnavailable

    /** Which store holds the profile now: "indexeddb", "localStorage" or "memory". */
    val profileBackend: String
        get() =
            when {
                durable != null -> INDEXED_DB
                backing() == null || profileUnavailable -> "memory"
                else -> "localStorage"
            }

    /** Set once another tab took the profile (`save/ProfileWriterLock`): every write is refused. */
    var writesSuspended = false
        private set

    /** This page's claim on the IndexedDB profile; a different token in the database fences us out. */
    private val ownerToken = "${kotlin.js.Date.now()}-${kotlin.random.Random.nextDouble()}"

    /** Told when a write finds another tab owns the profile (before or without the lock's own notice). */
    var onProfileLost: (() -> Unit)? = null

    /**
     * Told after any profile key is written or removed -- the cloud-sync engine's cue that the
     * committed profile may have changed. Runs synchronously; listeners must only schedule work.
     */
    var onProfileWrite: (() -> Unit)? = null

    /** Stop writing for good: refuse wrapper writes and drop the durable store's pending work. */
    fun suspendWrites() {
        writesSuspended = true
        durable?.halt()
    }

    private fun fencedOut() {
        if (writesSuspended) return
        suspendWrites()
        onProfileLost?.invoke()
    }

    private fun adopt(store: IndexedDbProfileStore) {
        store.onFenced = ::fencedOut
        durable = store
    }

    private fun checkWritable() {
        check(!writesSuspended) { "OSADA is now saving in another tab; this tab no longer writes" }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun backing(): Storage? {
        if (!probed) {
            real =
                try {
                    window.localStorage.also { it.length }
                } catch (error: Throwable) {
                    console.warn("[osada] site storage unavailable; this session will not persist", error)
                    null
                }
            probed = true
        }
        return real
    }

    /** Profile keys held in memory: everything when storage is denied, profile keys when IndexedDB failed. */
    private fun inMemory(key: String): Boolean = backing() == null || (profileUnavailable && isProfileKey(key))

    fun getItem(key: String): String? {
        val profile = durable?.takeIf { isProfileKey(key) }
        return when {
            profile != null -> profile.get(key)
            inMemory(key) -> memory[key]
            else -> backing()?.getItem(key)
        }
    }

    fun setItem(
        key: String,
        value: String,
    ) {
        checkWritable()
        val profile = durable?.takeIf { isProfileKey(key) }
        when {
            profile != null -> profile.put(key, value)
            inMemory(key) -> memory[key] = value
            else -> backing()?.setItem(key, value)
        }
        if (isProfileKey(key)) onProfileWrite?.invoke()
    }

    fun removeItem(key: String) {
        checkWritable()
        val profile = durable?.takeIf { isProfileKey(key) }
        when {
            profile != null -> profile.remove(key)
            inMemory(key) -> memory.remove(key)
            else -> backing()?.removeItem(key)
        }
        if (isProfileKey(key)) onProfileWrite?.invoke()
    }

    /** Every key across localStorage, the profile store and memory, in a stable order. */
    private fun keys(): List<String> {
        val store = backing() ?: return memory.keys.toList()
        val local = (0 until store.length).mapNotNull { store.key(it) }
        val profile = durable?.cache?.keys ?: if (profileUnavailable) memory.keys else null
        return if (profile == null) local else local.filterNot(::isProfileKey) + profile
    }

    fun key(index: Int): String? = keys().elementAtOrNull(index)

    val length: Int get() = keys().size

    /**
     * Calls [onDone] with true once every write so far is on disk (immediately for localStorage).
     * A session-only profile ([persistent] false) answers false at once: nothing it holds survives
     * a reload, so no caller may reload on the strength of it.
     */
    fun whenDurable(onDone: (Boolean) -> Unit) {
        val profile = durable
        if (profile == null) onDone(persistent) else profile.whenDurable(onDone)
    }

    /** The last IndexedDB failure's DOMException name, e.g. "QuotaExceededError". */
    val lastDurableErrorName: String? get() = durable?.lastError?.name as? String

    /**
     * Startup: move the profile to IndexedDB (first run: migrate and verify), then [onReady].
     * Must run after the writer lock is held and before the game reads any save. [onReady] runs on
     * every path, including failures: storage trouble must degrade the session, never stop it. It
     * is skipped only when this tab lost the profile meanwhile -- then nothing here may continue.
     */
    @Suppress("TooGenericExceptionCaught")
    fun openDurable(onReady: () -> Unit) {
        var done = false
        val ready = {
            if (!done) {
                done = true
                if (!writesSuspended) onReady()
            }
        }
        try {
            val local = backing() ?: return ready()
            val sticky = local.getItem(BACKEND_KEY) == INDEXED_DB
            IndexedDbProfileStore.open(ownerToken, { writesSuspended }) { store ->
                try {
                    when {
                        writesSuspended -> store?.halt()
                        store == null && sticky -> {
                            console.error(
                                "[osada] the profile lives in IndexedDB, which could not be opened; session-only",
                            )
                            profileUnavailable = true
                        }
                        store == null -> Unit
                        sticky -> {
                            adopt(store)
                            dropLegacyCopies(local)
                        }
                        else -> return@open migrate(local, store, ready)
                    }
                } catch (error: Throwable) {
                    console.error("[osada] opening the profile store failed; continuing with what is available", error)
                }
                ready()
            }
        } catch (error: Throwable) {
            console.error("[osada] profile storage setup failed; continuing with what is available", error)
            ready()
        }
    }

    /** A previous boot verified the migration; the old localStorage copies are now only stale. */
    @Suppress("TooGenericExceptionCaught")
    private fun dropLegacyCopies(local: Storage) {
        try {
            val stale = (0 until local.length).mapNotNull { local.key(it) }.filter(::isProfileKey)
            for (key in stale) local.removeItem(key)
        } catch (error: Throwable) {
            // Harmless: stale copies are ignored while the marker says IndexedDB; retried next boot.
            console.warn("[osada] could not remove the old localStorage profile copies yet", error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun migrate(
        local: Storage,
        store: IndexedDbProfileStore,
        ready: () -> Unit,
    ) {
        val source =
            (0 until local.length)
                .mapNotNull { local.key(it) }
                .filter(::isProfileKey)
                .associateWith { local.getItem(it)!! }
        // Merge, never delete. IndexedDB can hold data without the marker in two ways: an earlier
        // migration whose marker write failed (then both copies agree), or a localStorage that was
        // cleared on its own, marker included (then IndexedDB holds the ONLY copy). Deleting
        // IndexedDB keys missing from localStorage would wipe every save in the second case.
        source.forEach { (key, value) -> store.put(key, value) }
        val expected = LinkedHashMap(store.cache)
        store.whenDurable { written ->
            when {
                writesSuspended -> ready()
                !written -> {
                    console.error("[osada] profile migration to IndexedDB failed; staying on localStorage")
                    ready()
                }
                // Verify against what is actually on disk, not against the cache just written.
                else ->
                    IndexedDbProfileStore.open(
                        ownerToken,
                        { writesSuspended },
                    ) { reread -> finishMigration(local, reread, expected, source.size, ready) }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun finishMigration(
        local: Storage,
        reread: IndexedDbProfileStore?,
        expected: Map<String, String>,
        migrated: Int,
        ready: () -> Unit,
    ) {
        when {
            writesSuspended -> reread?.halt()
            reread == null || reread.cache != expected ->
                console.error("[osada] IndexedDB readback differed from the profile; staying on localStorage")
            else ->
                try {
                    // The marker is the switch. If it cannot be written (localStorage full), stay on
                    // localStorage this session: running on IndexedDB without it would let the next
                    // boot's merge put these older localStorage values over newer IndexedDB saves.
                    local.setItem(BACKEND_KEY, INDEXED_DB)
                    adopt(reread)
                    console.log(
                        "[osada] profile moved to IndexedDB ($migrated from localStorage, ${expected.size} total)",
                    )
                } catch (error: Throwable) {
                    reread.halt()
                    console.error("[osada] could not record the IndexedDB switch; staying on localStorage", error)
                }
        }
        ready()
    }

    /** Tests only: behave as a browser that denies site storage ([denied]) or re-probe normally. */
    internal fun simulateDeniedForTests(denied: Boolean) {
        writesSuspended = false
        durable = null
        profileUnavailable = false
        memory.clear()
        real = null
        probed = denied
    }

    /** Tests only: route the profile through [store] as if [openDurable] had switched to it. */
    internal fun useProfileStoreForTests(store: IndexedDbProfileStore?) {
        durable = store
    }

    fun clear() {
        checkWritable()
        val profile = durable
        if (profile != null) {
            profile.cache.keys
                .toList()
                .forEach(profile::remove)
        }
        memory.clear()
        backing()?.clear()
    }
}

/**
 * Drop-in name for call sites that used `kotlinx.browser.localStorage` or `window.localStorage`:
 * importing this instead routes them through [BrowserStorage] with no other change.
 */
val localStorage: BrowserStorage get() = BrowserStorage
