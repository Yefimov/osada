package org.osada.mpserver

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.osada.mpserver.account.AccountBootstrap
import org.osada.mpserver.account.AccountConfig
import org.osada.mpserver.account.AccountSchema
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    if (args.firstOrNull() == "migrate") {
        // The only way the account schema is created or upgraded: an explicit operator step.
        val applied = AccountBootstrap.migrate(AccountConfig.fromEnvironment())
        println("Account schema is at version ${AccountSchema.CURRENT_VERSION} ($applied step(s) applied)")
        return
    }
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
