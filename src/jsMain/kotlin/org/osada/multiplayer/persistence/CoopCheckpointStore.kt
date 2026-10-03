package org.osada.multiplayer.persistence

import kotlinx.browser.window
import org.osada.multiplayer.coop.CoopCheckpoint
import org.osada.multiplayer.sync.MultiplayerSnapshot

/** Bounded to one latest acknowledged checkpoint per browser; never touches single-player saves. */
internal object CoopCheckpointStore {
    const val KEY = "osada-coop-checkpoint-v1"
    private const val MAX_UNACKNOWLEDGED = 8
    private var memory: CoopCheckpoint? = null
    private val published = linkedMapOf<String, MultiplayerSnapshot>()
    var durable: Boolean = true
        private set

    fun save(snapshot: MultiplayerSnapshot) {
        val checkpoint = CoopCheckpoint.fromSnapshot(snapshot)
        memory = checkpoint
        durable = runCatching { window.localStorage.setItem(KEY, checkpoint.encode()) }.isSuccess
    }

    fun latest(): CoopCheckpoint? =
        memory ?: runCatching {
            window.localStorage.getItem(KEY)?.let(CoopCheckpoint::decode)
        }.getOrNull()?.also { memory = it }

    fun stage(snapshot: MultiplayerSnapshot) {
        published[snapshot.stateHash] = snapshot
        // A browser may resolve several AI orders before receipts arrive. Bound unacknowledged memory.
        while (published.size > MAX_UNACKNOWLEDGED ||
            (
                published.size > 1 &&
                    published.values.sumOf { it.gameStateJson.length + (it.multiplayerState.coopJson?.length ?: 0) } >
                    CoopCheckpoint.MAX_CHARS
            )
        ) {
            published.remove(published.keys.first())
        }
    }

    fun acknowledge(hash: String) {
        published.remove(hash)?.let(::save)
    }

    fun clearPending() {
        published.clear()
    }
}
