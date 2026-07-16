package com.a4a.g8api

import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.koin.core.context.GlobalContext
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/**
 * Shared scaffolding for integration tests.
 *
 * Each test gets:
 * - A fresh `public` schema on a shared Postgres 16 container (Testcontainers starts
 *   the container once per JVM; [resetPublicSchema] drops+recreates the schema
 *   between tests so state is isolated). Same dialect as production, so schema code
 *   like `ensureUsersEmailUniquenessIndex()` is exercised for real under test.
 * - A throwaway JWT secret
 * - The full production [Application.module] pipeline (real routes, real rate limiters,
 *   real auth, real maintenance scheduler)
 * - Email sending stubbed by `EMAIL_NOOP=true` from build.gradle (no SMTP traffic)
 *
 * Koin is shut down between tests because koin-ktor's `install(Koin)` calls
 * `startKoin()` and that fails if a previous test left a context behind.
 */
private val postgres: PostgreSQLContainer<*> =
    PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("g8_test")
        .withUsername("g8_test")
        .withPassword("g8_test")
        .also { it.start() }

private fun resetPublicSchema() {
    DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute("DROP SCHEMA IF EXISTS public CASCADE")
            stmt.execute("CREATE SCHEMA public")
        }
    }
}

fun integrationTest(
    extraRouting: (Routing.() -> Unit)? = null,
    block: suspend ApplicationTestBuilder.() -> Unit,
) {
    resetPublicSchema()
    testApplication {
        environment {
            config = MapApplicationConfig(
                "ktor.deployment.port" to "8080",
                "ktor.environment" to "test",
                "jwt.secret" to "test-secret-only-for-junit-runs",
                "jwt.audience" to "g8",
                "jwt.issuer" to "g8-api",
                "jwt.realm" to "G8 Tools",
                "storage.driverClassName" to "org.postgresql.Driver",
                "storage.jdbcURL" to postgres.jdbcUrl,
                "storage.username" to postgres.username,
                "storage.password" to postgres.password,
            )
        }
        application {
            runCatching { GlobalContext.stopKoin() }
            module()
            if (extraRouting != null) {
                routing { extraRouting() }
            }
        }
        try {
            block()
        } finally {
            runCatching { GlobalContext.stopKoin() }
        }
    }
}

/** Pull a Koin singleton from the running test application. */
inline fun <reified T : Any> koinGet(): T = GlobalContext.get().get(T::class)
