package org.osada.mpserver.account

/**
 * Fixed-window request counter per key (an IP address or an account id).
 *
 * In memory on purpose: it throttles bursts, it is not an audit record, and a restart forgetting it
 * is harmless. The map is bounded so a spray of addresses cannot grow the heap without limit.
 */
class RateLimiter(
    private val limit: Int,
    private val windowMillis: Long,
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
) {
    private class Window(
        var startedAt: Long,
        var count: Int,
    )

    private val windows = LinkedHashMap<String, Window>()

    @Synchronized
    fun allow(
        key: String,
        now: Long,
    ): Boolean {
        val window = windows[key]
        if (window == null && windows.size >= maxKeys) evictExpired(now)
        return when {
            window != null && now - window.startedAt < windowMillis -> ++window.count <= limit
            // Full of live windows: refuse a new key rather than forget somebody else's count.
            window == null && windows.size >= maxKeys -> false
            else -> {
                windows[key] = Window(now, 1)
                true
            }
        }
    }

    private fun evictExpired(now: Long) {
        windows.entries.removeIf { now - it.value.startedAt >= windowMillis }
    }

    private companion object {
        const val DEFAULT_MAX_KEYS = 10_000
    }
}
