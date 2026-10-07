package org.osada.mpserver.saves

import io.ktor.server.routing.get
import kotlinx.coroutines.sync.Semaphore
import org.osada.mpserver.account.AccountModule
import org.osada.mpserver.account.RateLimiter

/** Online-save endpoints (§4); present only when accounts are on. */
class SaveModule(
    val accounts: AccountModule,
    val repository: SaveRepository,
) {
    // §5.2: 12 writes per minute per account, after the client has already coalesced dirty state.
    internal val uploadLimiter = RateLimiter(limit = UPLOADS_PER_MINUTE, windowMillis = MINUTE_MILLIS)

    // Bounded upload concurrency: each in-flight upload holds up to one decoded snapshot in the heap.
    internal val uploadPermits = Semaphore(CONCURRENT_UPLOADS)

    internal companion object {
        const val UPLOADS_PER_MINUTE = 12
        const val MINUTE_MILLIS = 60_000L
        const val CONCURRENT_UPLOADS = 2
    }
}
