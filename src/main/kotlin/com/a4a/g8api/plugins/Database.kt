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
    }
}

suspend fun <T> dbQuery(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }
