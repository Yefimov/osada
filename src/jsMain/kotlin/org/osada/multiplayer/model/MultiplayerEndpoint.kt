package org.osada.multiplayer.model

import kotlinx.browser.window

/**
 * Where the room server lives.
 *
 * The address is derived from the page the game was served from instead of being compiled in, so
 * the same build works on the server's bare IP over plain HTTP today and on `https://<domain>/`
 * after a certificate is issued: an https page automatically produces a `wss://` socket, which is
 * the only scheme a browser accepts from a secure origin.
 *
 * Overrides, in priority order:
 *  1. `?mpEndpoint=ws://host:port/mp` in the URL — also stored, so a reload keeps it;
 *  2. `osada-mp-endpoint-v1` in localStorage — set once, used for local Worker/dev runs;
 *  3. the page origin.
 */
object MultiplayerEndpoint {
    const val OVERRIDE_STORAGE_KEY = "osada-mp-endpoint-v1"
    const val SOCKET_PATH = "/mp"

    fun resolve(): MultiplayerEndpointConfig {
        val override = readOverride()
        val protocol = window.location.protocol
        val host = window.location.host
        return when {
            override != null ->
                MultiplayerEndpointConfig(
                    environment = "custom",
                    httpBaseUrl = override.replace(Regex("^ws"), "http").removeSuffix(SOCKET_PATH),
                    webSocketBaseUrl = override,
                )

            // Opened straight from disk (file://): there is no server to reach.
            host.isBlank() || (protocol != "http:" && protocol != "https:") ->
                MultiplayerEndpointConfig("offline", "", "")

            else ->
                MultiplayerEndpointConfig(
                    environment = if (isLoopback(window.location.hostname)) "development" else "online",
                    httpBaseUrl = "$protocol//$host",
                    webSocketBaseUrl = "${if (protocol == "https:") "wss" else "ws"}://$host$SOCKET_PATH",
                )
        }
    }

    /** False when the build is not served over HTTP, in which case only two-tab mode can work. */
    fun isOnlineAvailable(): Boolean = resolve().webSocketBaseUrl.isNotEmpty()

    private fun readOverride(): String? {
        val fromQuery =
            window.location.search
                .removePrefix("?")
                .split('&')
                .firstOrNull { it.startsWith("mpEndpoint=") }
                ?.substringAfter('=')
                ?.let { decodeURIComponent(it) }
                ?.takeIf { it.startsWith("ws://") || it.startsWith("wss://") }
        if (fromQuery != null) {
            runCatching { window.localStorage.setItem(OVERRIDE_STORAGE_KEY, fromQuery) }
            return fromQuery
        }
        return runCatching { window.localStorage.getItem(OVERRIDE_STORAGE_KEY) }
            .getOrNull()
            ?.takeIf { it.startsWith("ws://") || it.startsWith("wss://") }
    }

    private fun isLoopback(hostname: String): Boolean =
        hostname == "localhost" || hostname == "127.0.0.1" || hostname == "[::1]"
}

@Suppress("UnusedParameter")
private fun decodeURIComponent(value: String): String = js("decodeURIComponent(value)") as String
