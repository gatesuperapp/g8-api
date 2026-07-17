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


private fun provideDataSource(
    url: String,
    driverClass: String,
    user: String?,
    pwd: String?,
): HikariDataSource {
    val hikariConfig = HikariConfig().apply {
        driverClassName = driverClass
        jdbcUrl = url
        // Only set credentials when actually provided. Passing an empty string to
        // Hikari makes Postgres refuse the connection.
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
 * Replace any non-partial uniqueness on `users.email` with a partial unique index that
 * excludes soft-deleted rows (`… WHERE deleted_at IS NULL`). Without the partial form,
 * a legacy plain-unique constraint blocks new signups from re-using a soft-deleted
 * account's email.
 *
 * Two-step teardown before the CREATE:
 *  1. Drop any unique CONSTRAINT on `users.email` via `ALTER TABLE … DROP CONSTRAINT`
 *     — dropping the underlying index directly is forbidden when a constraint owns it
 *     (Postgres errors with "constraint X requires it").
 *  2. Sweep any leftover non-partial unique INDEX not backed by a constraint.
 *
 * Both loops inspect pg_catalog rather than hard-coding names, so future Exposed-version
 * renames don't break us.
 */
private fun ensureUsersEmailUniquenessIndex() {
    exec("""
        DO ${'$'}${'$'}
        DECLARE name text;
        BEGIN
            -- Step 1: drop any non-partial unique CONSTRAINT on users.email.
            -- Dropping the constraint auto-drops its underlying index.
            FOR name IN
                SELECT con.conname
                FROM pg_constraint con
                JOIN pg_class t ON t.oid = con.conrelid
                JOIN pg_attribute a
                  ON a.attrelid = t.oid AND a.attnum = ANY(con.conkey)
                WHERE t.relname = 'users'
                  AND a.attname = 'email'
                  AND con.contype = 'u'
            LOOP
                EXECUTE 'ALTER TABLE users DROP CONSTRAINT ' || quote_ident(name);
            END LOOP;

            -- Step 2: drop any remaining non-partial unique INDEX (not backed by
            -- a constraint — those were already removed in step 1).
            FOR name IN
                SELECT i.relname
                FROM pg_index x
                JOIN pg_class c ON c.oid = x.indrelid
                JOIN pg_class i ON i.oid = x.indexrelid
                JOIN pg_attribute a
                  ON a.attrelid = c.oid AND a.attnum = ANY(x.indkey)
                WHERE c.relname = 'users'
                  AND a.attname = 'email'
                  AND x.indisunique
                  AND x.indpred IS NULL
            LOOP
                EXECUTE 'DROP INDEX ' || quote_ident(name);
            END LOOP;
        END
        ${'$'}${'$'};
    """.trimIndent())
    exec(
        "CREATE UNIQUE INDEX IF NOT EXISTS users_email_active_unique " +
            "ON users (email) WHERE deleted_at IS NULL"
    )
}

private fun exec(sql: String) {
    org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(sql)
}

suspend fun <T> dbQuery(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }
