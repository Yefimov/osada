package org.osada.mpserver

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory

fun main() {
    val config = ServerConfig.fromEnvironment()
    val logger = LoggerFactory.getLogger("org.osada.mpserver.Main")
    logger.info(
        "Starting OSADA room server on {}:{} (web root: {}, origins: {})",
        config.host,
        config.port,
        config.webRoot?.absolutePath ?: "<none>",
        config.allowedOrigins.ifEmpty { setOf("<any>") },
    )
    embeddedServer(CIO, host = config.host, port = config.port) {
        roomServerModule(config)
    }.start(wait = true)
}
