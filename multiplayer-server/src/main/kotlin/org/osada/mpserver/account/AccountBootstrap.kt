package org.osada.mpserver.account

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.osada.mpserver.account")

/** Builds the account service, or explains in the log why accounts stay off. Never fails startup. */
object AccountBootstrap {
    @Suppress("ReturnCount") // Each early exit is a distinct, logged reason for leaving accounts off.
    fun open(
        config: AccountConfig,
        allowedOrigins: Set<String>,
        http: ProviderHttp = JdkProviderHttp(),
    ): AccountModule? {
        val databaseFile = config.databaseFile
        if (!config.active || databaseFile == null) {
            if (config.enabled) {
                logger.warn(
                    "Accounts requested but not configured (database path, public origin and a provider are required)",
                )
            }
            return null
        }
        val database =
            runCatching { AccountDatabase.open(databaseFile) }
                .onFailure { logger.error("Account database could not be opened; accounts stay off", it) }
                .getOrNull() ?: return null
        val version = database.schemaVersion()
        if (version != AccountSchema.CURRENT_VERSION) {
            logger.error(
                "Account database schema is {} but this server needs {}; run `osada-server migrate`. " +
                    "Accounts stay off.",
                version,
                AccountSchema.CURRENT_VERSION,
            )
            database.close()
            return null
        }
        logger.info("Accounts on: providers {}, sign-up {}", config.providers.map { it.provider }, config.signupEnabled)
        return AccountModule(
            config = config,
            service = AccountService(database, config),
            sessions = SessionService(database, config.sessionLifetimeMillis),
            providers = providersFor(config, http),
            allowedOrigins = allowedOrigins,
        )
    }

    fun providersFor(
        config: AccountConfig,
        http: ProviderHttp,
    ): Map<String, IdentityProvider> =
        config.providers.associate { credentials ->
            credentials.provider to
                when (credentials.provider) {
                    AccountConfig.GOOGLE -> GoogleProvider(credentials, http)
                    else -> GitHubProvider(credentials, http)
                }
        }

    /** `osada-server migrate`: the only path that creates or upgrades the schema. */
    fun migrate(config: AccountConfig): Int {
        val file = requireNotNull(config.databaseFile) { "OSADA_DB_PATH is not set" }
        return AccountDatabase.open(file).use { AccountSchema.migrate(it) }
    }
}
