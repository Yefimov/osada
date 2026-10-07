package org.osada.storage

import kotlin.js.Promise

/**
 * The profile domains' durable home: an IndexedDB object store mirrored by an in-memory cache.
 *
 * The game's save code is synchronous (`SaveSnapshotStore`, the import batch, checkpoints), so
 * reads and writes stay synchronous against [cache]. Writes made in one JavaScript task are
 * committed together as ONE readwrite transaction with the `strict` durability hint: a multi-key
 * import ([org.osada.save.LocalStorageBatch]) lands all-or-nothing, and an autosave is handed to
 * the browser within milliseconds instead of waiting behind Chrome's rate-limited localStorage
 * write-behind (measured: a crash could lose well over 8 s of saves). "Durable" here means the
 * transaction completed with strict durability requested; the specification makes that a hint,
 * not a guarantee against every OS or device failure.
 *
 * Three rules keep that honest:
 *
 * - **One transaction at a time.** Writes made while one is in flight wait for it. A failed batch
 *   is re-queued only for keys with no newer write waiting, so a retry can never put an older save
 *   back over a newer one.
 * - **Owner fence.** Opening the store claims it by writing this tab's [ownerToken] into [META].
 *   Every write transaction first reads that token and aborts itself if another tab has claimed the
 *   database since. IndexedDB runs overlapping transactions in creation order, so a transaction the
 *   old tab queued before a takeover commits before the new owner reads, and anything queued after
 *   it is refused -- whether it came from a save, a retry or a migration callback.
 * - **[halt].** A tab that lost the writer lock drops everything pending at once.
 */
@Suppress("TooManyFunctions") // one flush pipeline: queue, flush, fill, abort, finish, settle, halt
internal class IndexedDbProfileStore private constructor(
    private val db: dynamic,
    private val ownerToken: String,
    initial: Map<String, String>,
) {
    val cache = LinkedHashMap(initial)

    /** key -> value to put, or null to delete; insertion order is write order. */
    private val dirty = LinkedHashMap<String, String?>()
    private var scheduled = false
    private var inFlight = false
    private var newWritesDuringFlight = false
    private var failedSinceIdle = false
    private var halted = false
    private val waiters = mutableListOf<(Boolean) -> Unit>()

    /** Called once if a write finds that another tab now owns the database. */
    var onFenced: (() -> Unit)? = null

    /** The most recent transaction failure, for callers that need its kind (quota vs. other). */
    var lastError: dynamic = null
        private set

    fun get(key: String): String? = cache[key]

    fun put(
        key: String,
        value: String,
    ) {
        cache[key] = value
        queue(key, value)
    }

    fun remove(key: String) {
        cache.remove(key)
        queue(key, null)
    }

    private fun queue(
        key: String,
        value: String?,
    ) {
        if (halted) return
        dirty[key] = value
        if (inFlight) newWritesDuringFlight = true
        schedule()
    }

    /** [onDone] receives true once every write made before this call completed on disk. */
    fun whenDurable(onDone: (Boolean) -> Unit) {
        when {
            halted -> Promise.resolve(Unit).then { onDone(false) }
            !scheduled && !inFlight && dirty.isEmpty() -> Promise.resolve(Unit).then { onDone(true) }
            else -> {
                waiters += onDone
                // Idle with writes retained from a failed transaction: nothing would flush them
                // again until the next write, so the question itself starts the retry.
                if (!scheduled && !inFlight) schedule()
            }
        }
    }

    /** Stop for good: drop pending writes, refuse new ones, answer every waiter "not durable". */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun halt() {
        if (halted) return
        halted = true
        dirty.clear()
        val pending = waiters.toList()
        waiters.clear()
        pending.forEach { it(false) }
        // A transaction already in flight still finishes; close() only waits for it.
        try {
            db.close()
        } catch (ignored: Throwable) {
            // Nothing left to release.
        }
    }

    private fun schedule() {
        if (scheduled || halted) return
        scheduled = true
        Promise.resolve(Unit).then { flush() }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun flush() {
        scheduled = false
        // Serialized: finished() reschedules whatever accumulated while this one was in flight.
        if (halted || inFlight) return
        if (dirty.isEmpty()) return settle()
        val batch = LinkedHashMap(dirty)
        dirty.clear()
        inFlight = true
        newWritesDuringFlight = false
        var fenced = false
        var tx: dynamic = null
        try {
            tx = db.transaction(arrayOf(STORE, META), "readwrite", js("({ durability: 'strict' })"))
            val opened = tx
            opened.oncomplete = { _: dynamic -> finished(batch, ok = true, fenced = false) }
            opened.onabort = { _: dynamic -> finished(batch, ok = false, fenced = fenced, error = opened.error) }
            val owner = opened.objectStore(META).get(OWNER_KEY)
            owner.onsuccess = { _: dynamic ->
                if (owner.result != ownerToken) {
                    fenced = true
                    opened.abort()
                } else {
                    fill(opened, batch)
                }
            }
        } catch (error: Throwable) {
            abortOrFinish(tx, batch, error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fill(
        tx: dynamic,
        batch: Map<String, String?>,
    ) {
        try {
            val store = tx.objectStore(STORE)
            batch.forEach { (key, value) -> if (value == null) store.delete(key) else store.put(value, key) }
            // No more requests: let the browser commit now rather than when the task ends, which
            // narrows the window in which closing the tab could still lose this save.
            if (tx.commit != null) tx.commit()
        } catch (error: Throwable) {
            // A request that threw while the batch was being written must not let the earlier
            // ones commit on their own: abort, so the batch stays all-or-nothing.
            abortOrFinish(tx, batch, error)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun abortOrFinish(
        tx: dynamic,
        batch: Map<String, String?>,
        error: Throwable,
    ) {
        lastError = error
        if (tx == null) {
            finished(batch, ok = false, fenced = false, error = error)
            return
        }
        try {
            tx.abort()
        } catch (ignored: Throwable) {
            // Already finishing; its oncomplete/onabort reports the outcome.
        }
    }

    private fun finished(
        batch: Map<String, String?>,
        ok: Boolean,
        fenced: Boolean,
        error: dynamic = null,
    ) {
        inFlight = false
        if (fenced) {
            console.warn("[osada] another tab owns the profile now; this tab's write was refused")
            halt()
            onFenced?.invoke()
            return
        }
        if (!ok) {
            console.error("[osada] profile write to IndexedDB failed; kept for retry", error)
            failedSinceIdle = true
            // Re-queue only keys with no newer write waiting: a newer value must win the retry.
            batch.forEach { (key, value) -> if (key !in dirty) dirty[key] = value }
            // A manual abort reports no error of its own; keep the request's (e.g. QuotaExceededError).
            if (error != null) lastError = error
            // Retry at once only when something new arrived meanwhile; otherwise wait for the next
            // write or question, so a full disk does not spin.
            if (newWritesDuringFlight) schedule()
        } else if (dirty.isNotEmpty()) {
            schedule()
        } else {
            // Every retained write was in this successful batch (or superseded by it): what was
            // written so far is on disk, so an earlier failure no longer stands.
            failedSinceIdle = false
        }
        settle()
    }

    private fun settle() {
        if (scheduled || inFlight || halted) return
        val ok = !failedSinceIdle && dirty.isEmpty()
        if (dirty.isEmpty() || failedSinceIdle) {
            failedSinceIdle = false
            val pending = waiters.toList()
            waiters.clear()
            pending.forEach { it(ok) }
        }
    }

    companion object {
        const val DB_NAME = "osada-profile"

        /** Tests open their own database so they never touch a real profile. */
        internal var databaseName = DB_NAME
        const val STORE = "kv"
        const val META = "meta"
        const val OWNER_KEY = "owner"
        private const val VERSION = 2
        private const val OPEN_TIMEOUT_MS = 5000

        /**
         * Opens (creating if needed), claims ownership for [ownerToken], then loads every key.
         * [onResult] gets null when IndexedDB is unavailable.
         *
         * The claim is the one write that is not fenced, so it must never come late: an open that
         * already answered (the timeout gave up on it) or whose tab has since lost the profile
         * ([cancelled], e.g. "Play here" in another tab) closes its connection instead of claiming.
         * Claiming then would take the database back from the tab that now owns it and fence out
         * that tab's saves.
         */
        @Suppress("TooGenericExceptionCaught")
        fun open(
            ownerToken: String,
            cancelled: () -> Boolean = { false },
            onResult: (IndexedDbProfileStore?) -> Unit,
        ) {
            var answered = false
            val answer: (IndexedDbProfileStore?) -> Unit = { store ->
                if (!answered) {
                    answered = true
                    onResult(store)
                }
            }
            val factory: dynamic = js("typeof indexedDB === 'undefined' ? null : indexedDB")
            if (factory == null) return answer(null)
            // A hung open (some privacy modes) must not hang startup.
            kotlinx.browser.window.setTimeout({ answer(null) }, OPEN_TIMEOUT_MS)
            try {
                val request = factory.open(databaseName, VERSION)
                request.onupgradeneeded = { _: dynamic ->
                    val db = request.result
                    val names = db.objectStoreNames
                    if (!(names.contains(STORE) as Boolean)) db.createObjectStore(STORE)
                    if (!(names.contains(META) as Boolean)) db.createObjectStore(META)
                }
                request.onerror = { _: dynamic -> answer(null) }
                request.onblocked = { _: dynamic -> answer(null) }
                request.onsuccess = { _: dynamic ->
                    val db = request.result
                    if (answered || cancelled()) {
                        db.close()
                        answer(null)
                    } else {
                        claim(db, ownerToken, {
                            readAll(
                                db,
                                { entries ->
                                    if (answered || cancelled()) {
                                        db.close()
                                        answer(null)
                                    } else {
                                        answer(IndexedDbProfileStore(db, ownerToken, entries))
                                    }
                                },
                                { answer(null) },
                            )
                        }, { answer(null) })
                    }
                }
            } catch (error: Throwable) {
                console.warn("[osada] IndexedDB unavailable", error)
                answer(null)
            }
        }

        /** Writes the owner token; every earlier transaction of another tab is ordered before it. */
        private fun claim(
            db: dynamic,
            ownerToken: String,
            onClaimed: () -> Unit,
            onError: () -> Unit,
        ) {
            val tx = db.transaction(arrayOf(STORE, META), "readwrite", js("({ durability: 'strict' })"))
            tx.objectStore(META).put(ownerToken, OWNER_KEY)
            tx.oncomplete = { _: dynamic -> onClaimed() }
            tx.onabort = { _: dynamic -> onError() }
        }

        private fun readAll(
            db: dynamic,
            onEntries: (Map<String, String>) -> Unit,
            onError: () -> Unit,
        ) {
            val tx = db.transaction(STORE, "readonly")
            val store = tx.objectStore(STORE)
            val keysRequest = store.getAllKeys()
            val valuesRequest = store.getAll()
            tx.oncomplete = { _: dynamic ->
                val keys = keysRequest.result as Array<dynamic>
                val values = valuesRequest.result as Array<dynamic>
                val entries = LinkedHashMap<String, String>()
                keys.forEachIndexed { index, key -> entries[key as String] = values[index] as String }
                onEntries(entries)
            }
            tx.onerror = { _: dynamic -> onError() }
            tx.onabort = { _: dynamic -> onError() }
        }
    }
}
