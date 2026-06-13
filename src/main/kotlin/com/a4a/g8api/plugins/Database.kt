package com.a4a.g8api.plugins

import com.a4a.g8api.database.MagicLinkService
import com.a4a.g8api.database.SessionService
import com.a4a.g8api.database.SubscriptionService
import com.a4a.g8api.database.UsersService
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.jetbrains.exposed.sql.vendors.currentDialect


private fun provideDataSource(
    url: String,
    driverClass: String,
    user: String?,
    pwd: String?,
): HikariDataSource {
    val hikariConfig = HikariConfig().apply {
        driverClassName = driverClass
        jdbcUrl = url
        // Only set credentials when actually provided. Test config + local H2 file
        // both run with empty creds — passing "" to Hikari makes Postgres refuse.
        if (!user.isNullOrEmpty()) username = user
        if (!pwd.isNullOrEmpty()) password = pwd
        maximumPoolSize = 3
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    }
    return HikariDataSource(hikariConfig)
}

fun Application.configureDatabase() {
    val driverClass = environment.config.property("storage.driverClassName").getString()
    val jdbcUrl = environment.config.property("storage.jdbcURL").getString()
    val user = environment.config.propertyOrNull("storage.username")?.getString()
    val pwd = environment.config.propertyOrNull("storage.password")?.getString()
    val db = Database.connect(provideDataSource(jdbcUrl, driverClass, user, pwd))
    transaction(db) {
        SchemaUtils.create(UsersService.Users)
        SchemaUtils.create(SessionService.Sessions)
        SchemaUtils.create(MagicLinkService.MagicLinks)
        SchemaUtils.create(SubscriptionService.Subscriptions)
        SchemaUtils.create(SubscriptionService.WebhookEvents)
        // Idempotent: ALTER TABLE for new nullable columns added after initial deploy.
        SchemaUtils.createMissingTablesAndColumns(SubscriptionService.Subscriptions)
        ensureUsersEmailUniquenessIndex()
    }
}

/**
 * Replace any non-partial unique index on `users.email` with a partial one that excludes
 * soft-deleted rows. This lets a soft-deleted account's email be re-used by a brand-new
 * signup — without the partial form, the legacy unique constraint blocks the INSERT.
 *
 * Dispatched per dialect:
 *  - **Postgres** (production): `… WHERE deleted_at IS NULL`. The DO-block discovers the
 *    legacy unique index by inspecting `pg_index` instead of hard-coding the Exposed
 *    name, so it's robust to future Exposed-version renames.
 *  - **H2** (tests run with `MODE=PostgreSQL`): keeps a plain unique index, since H2
 *    doesn't support partial indexes. Tests still get duplicate-email protection.
 */
private fun ensureUsersEmailUniquenessIndex() {
    if (currentDialect is PostgreSQLDialect) {
        exec("""
            DO ${'$'}${'$'}
            DECLARE idx text;
            BEGIN
                FOR idx IN
                    SELECT i.relname
                    FROM pg_index x
                    JOIN pg_class c ON c.oid = x.indrelid
                    JOIN pg_class i ON i.oid = x.indexrelid
                    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(x.indkey)
                    WHERE c.relname = 'users'
                      AND a.attname = 'email'
                      AND x.indisunique
                      AND x.indpred IS NULL
                LOOP
                    EXECUTE 'DROP INDEX ' || quote_ident(idx);
                END LOOP;
            END
            ${'$'}${'$'};
        """.trimIndent())
        exec(
            "CREATE UNIQUE INDEX IF NOT EXISTS users_email_active_unique " +
                "ON users (email) WHERE deleted_at IS NULL"
        )
    } else {
        exec(
            "CREATE UNIQUE INDEX IF NOT EXISTS users_email_unique ON users (email)"
        )
    }
}

private fun exec(sql: String) {
    org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(sql)
}

suspend fun <T> dbQuery(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }
