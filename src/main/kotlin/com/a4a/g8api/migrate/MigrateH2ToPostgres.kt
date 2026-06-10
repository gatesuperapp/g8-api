package com.a4a.g8api.migrate

import com.a4a.g8api.database.MagicLinkService
import com.a4a.g8api.database.SessionService
import com.a4a.g8api.database.SubscriptionService
import com.a4a.g8api.database.UsersService
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * One-shot data migration from the production H2 file to Postgres.
 *
 * Usage on server (with g8-api stopped — H2 file mode locks the .mv.db):
 *
 *     export H2_URL='jdbc:h2:file:./db/g8-db'
 *     # DATABASE_URL / DATABASE_USER / DATABASE_PASSWORD already in g8-api.service env
 *     java -cp g8-api.jar com.a4a.g8api.migrate.MigrateH2ToPostgresKt
 *
 * Strategy:
 *  1. Connect to both H2 (source) and Postgres (target).
 *  2. Create Postgres schema via SchemaUtils.create — idempotent, no-op if rerun.
 *  3. For each table, read all rows from H2 into in-memory snapshots, then
 *     batchInsert into Postgres. Order respects FKs: users → sessions → subscriptions.
 *  4. Print a per-table count so you can sanity-check against the H2 source.
 *
 * Idempotency: re-running the script on top of an already-populated Postgres will
 * fail on primary-key conflicts. Wipe Postgres tables (or drop/recreate) before retry.
 */
fun main() {
    val h2Url = System.getenv("H2_URL") ?: "jdbc:h2:file:./db/g8-db"
    val pgUrl = System.getenv("DATABASE_URL")
        ?: error("DATABASE_URL is required (e.g. jdbc:postgresql://localhost:5432/g8db)")
    val pgUser = System.getenv("DATABASE_USER")
        ?: error("DATABASE_USER is required")
    val pgPwd = System.getenv("DATABASE_PASSWORD")
        ?: error("DATABASE_PASSWORD is required")

    println("Source (H2):     $h2Url")
    println("Target (Postgres): $pgUrl as $pgUser")
    println()

    val h2 = Database.connect(url = h2Url, driver = "org.h2.Driver")
    val pg = Database.connect(
        url = pgUrl,
        driver = "org.postgresql.Driver",
        user = pgUser,
        password = pgPwd,
    )

    println("Creating Postgres schema (idempotent)…")
    transaction(pg) {
        SchemaUtils.create(
            UsersService.Users,
            SessionService.Sessions,
            MagicLinkService.MagicLinks,
            SubscriptionService.Subscriptions,
            SubscriptionService.WebhookEvents,
        )
    }
    println("  done.")
    println()

    // Order matters because of FKs: users first, then anything referencing them.
    migrate(h2, pg, UsersService.Users)
    migrate(h2, pg, SessionService.Sessions)
    migrate(h2, pg, SubscriptionService.Subscriptions)
    migrate(h2, pg, MagicLinkService.MagicLinks)
    migrate(h2, pg, SubscriptionService.WebhookEvents)

    println()
    println("Migration complete.")
}

/**
 * Copy every row from [table] from [source] to [target] via in-memory snapshots.
 *
 * batchInsert with `body = { row -> columns.forEach { c -> this[c] = row[c] } }` works
 * for any Table whose columns we can iterate over generically — Exposed lets us copy
 * column-by-column without naming each one, so this single helper covers all 5 tables.
 */
private fun migrate(source: Database, target: Database, table: Table) {
    val tableName = table.tableName
    print("  $tableName: reading from H2… ")

    // Snapshot the rows. Storing per-column Any? values keeps us decoupled from
    // the column types, so we don't need a per-table copy block.
    val snapshots: List<Map<String, Any?>> = transaction(source) {
        table.selectAll().map { row ->
            table.columns.associate { col -> col.name to row[col] }
        }
    }
    print("${snapshots.size} rows. Writing to Postgres… ")

    if (snapshots.isNotEmpty()) {
        transaction(target) {
            table.batchInsert(snapshots) { snap ->
                @Suppress("UNCHECKED_CAST")
                table.columns.forEach { col ->
                    val value = snap[col.name]
                    // Cast through Any? — Exposed's set operator needs the typed column.
                    this[col as org.jetbrains.exposed.sql.Column<Any?>] = value
                }
            }
        }
    }
    println("done.")
}
