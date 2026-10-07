package org.osada.mpserver.account

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.Executors

/**
 * The account database: one SQLite file on a persistent volume outside the deployment releases.
 *
 * Every statement runs on one dedicated thread holding one connection. SQLite has a single writer
 * anyway; confining the connection to a thread bounds database I/O concurrency, keeps blocking JDBC
 * off the Ktor event loop and makes each [transaction] trivially serial.
 *
 * The schema is never created or migrated implicitly on startup (§8: no automatic production schema
 * migration). `osada-server migrate` applies [AccountSchema]; a server that finds an older schema
 * keeps accounts switched off and says so.
 */
class AccountDatabase private constructor(
    private val connection: Connection,
) : Closeable {
    private val executor =
        Executors.newSingleThreadExecutor {
            Thread(
                it,
                "osada-account-db",
            ).apply { isDaemon = true }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = executor.asCoroutineDispatcher()

    /** Runs [block] inside one transaction on the database thread; any exception rolls it back. */
    suspend fun <T> transaction(block: Tx.() -> T): T = withContext(dispatcher) { transactionBlocking(block) }

    @Suppress("TooGenericExceptionCaught") // Any failure rolls the transaction back, then propagates.
    internal fun <T> transactionBlocking(block: Tx.() -> T): T {
        connection.autoCommit = false
        return try {
            Tx(connection).block().also { connection.commit() }
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    fun schemaVersion(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { if (it.next()) it.getInt(1) else 0 }
        }

    override fun close() {
        executor.shutdown()
        connection.close()
    }

    companion object {
        fun open(file: File): AccountDatabase {
            file.absoluteFile.parentFile?.mkdirs()
            return open("jdbc:sqlite:${file.absolutePath}")
        }

        /** Test seam: `jdbc:sqlite::memory:` keeps a private database for the life of the connection. */
        fun open(url: String): AccountDatabase {
            val connection = DriverManager.getConnection(url)
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = 5000")
                // WAL lets the backup job read while the server writes (§9). Ignored for :memory:.
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA synchronous = FULL")
            }
            return AccountDatabase(connection)
        }
    }
}

/** A thin, explicit statement helper: bound parameters only, never string-built SQL values. */
class Tx internal constructor(
    val connection: Connection,
) {
    fun update(
        sql: String,
        vararg params: Any?,
    ): Int = prepare(sql, params).use { it.executeUpdate() }

    fun <T> query(
        sql: String,
        vararg params: Any?,
        map: (ResultSet) -> T,
    ): List<T> =
        prepare(sql, params).use { statement ->
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(map(rows)) }
            }
        }

    fun <T> single(
        sql: String,
        vararg params: Any?,
        map: (ResultSet) -> T,
    ): T? = query(sql, *params, map = map).firstOrNull()

    fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    private fun prepare(
        sql: String,
        params: Array<out Any?>,
    ): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        return statement
    }
}

fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
