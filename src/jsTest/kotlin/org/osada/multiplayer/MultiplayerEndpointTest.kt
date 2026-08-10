package org.osada.multiplayer

import kotlinx.browser.window
import org.osada.multiplayer.model.MultiplayerEndpoint
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The room address is never compiled in: it follows the page. That is what lets the same build move
 * from a plain-HTTP host to `https://<domain>/` without a client change — and a browser refuses a
 * `ws://` socket from an https page, so the rule has to hold.
 */
class MultiplayerEndpointTest {
    private var savedOverride: String? = null

    @BeforeTest
    fun clearOverride() {
        savedOverride = window.localStorage.getItem(MultiplayerEndpoint.OVERRIDE_STORAGE_KEY)
        window.localStorage.removeItem(MultiplayerEndpoint.OVERRIDE_STORAGE_KEY)
    }

    @AfterTest
    fun restoreOverride() {
        savedOverride?.let { window.localStorage.setItem(MultiplayerEndpoint.OVERRIDE_STORAGE_KEY, it) }
    }

    @Test
    fun socketSchemeFollowsPageScheme() {
        val endpoint = MultiplayerEndpoint.resolve()
        val expectedScheme = if (window.location.protocol == "https:") "wss://" else "ws://"
        assertTrue(
            endpoint.webSocketBaseUrl.startsWith(expectedScheme),
            "expected ${endpoint.webSocketBaseUrl} to start with $expectedScheme",
        )
        assertTrue(endpoint.webSocketBaseUrl.endsWith(MultiplayerEndpoint.SOCKET_PATH))
    }

    @Test
    fun socketSharesTheOriginOfThePage() {
        val endpoint = MultiplayerEndpoint.resolve()
        assertTrue(
            endpoint.webSocketBaseUrl.contains(window.location.host),
            "expected ${endpoint.webSocketBaseUrl} to point at ${window.location.host}",
        )
        assertEquals("${window.location.protocol}//${window.location.host}", endpoint.httpBaseUrl)
        assertTrue(MultiplayerEndpoint.isOnlineAvailable())
    }

    @Test
    fun storedOverrideWins() {
        window.localStorage.setItem(MultiplayerEndpoint.OVERRIDE_STORAGE_KEY, "ws://198.51.100.7:8090/mp")
        val endpoint = MultiplayerEndpoint.resolve()
        assertEquals("ws://198.51.100.7:8090/mp", endpoint.webSocketBaseUrl)
        assertEquals("custom", endpoint.environment)
        assertEquals("http://198.51.100.7:8090", endpoint.httpBaseUrl)
    }

    @Test
    fun aNonSocketOverrideIsIgnored() {
        // A stray value must not silently redirect the game somewhere unusable.
        window.localStorage.setItem(MultiplayerEndpoint.OVERRIDE_STORAGE_KEY, "http://198.51.100.7/mp")
        val endpoint = MultiplayerEndpoint.resolve()
        assertFalse(endpoint.environment == "custom")
        assertTrue(endpoint.webSocketBaseUrl.contains(window.location.host))
    }
}
