package com.a4a.g8api

import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared scaffolding for integration tests.
 *
 * Each test gets:
 * - A fresh H2 in-memory database (unique URL via [dbCounter] so sequential tests
 *   don't pollute each other's state)
 * - A throwaway JWT secret
 * - The full production [Application.module] pipeline (so we exercise real routes,
 *   real rate limiters, real auth, real maintenance scheduler)
 * - Email sending stubbed by `EMAIL_NOOP=true` from build.gradle (no SMTP traffic)
 *
 * Koin is shut down between tests because koin-ktor's `install(Koin)` calls
 * `startKoin()` and that fails if a previous test left a context behind.
 */
private val dbCounter = AtomicInteger(0)

fun integrationTest(
    extraRouting: (Routing.() -> Unit)? = null,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    environment {
        config = MapApplicationConfig(
            "ktor.deployment.port" to "8080",
            "ktor.environment" to "test",
            "jwt.secret" to "test-secret-only-for-junit-runs",
            "jwt.audience" to "g8",
            "jwt.issuer" to "g8-api",
            "jwt.realm" to "G8 Tools",
            "storage.driverClassName" to "org.h2.Driver",
            "storage.jdbcURL" to "jdbc:h2:mem:test-${dbCounter.incrementAndGet()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
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

/** Pull a Koin singleton from the running test application. */
inline fun <reified T : Any> koinGet(): T = GlobalContext.get().get(T::class)
