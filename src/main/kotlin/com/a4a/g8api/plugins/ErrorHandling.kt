package com.a4a.g8api.plugins

import com.a4a.g8api.models.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Global exception handler.
 *
 * Without this, any uncaught exception in a route handler falls through to Ktor's
 * default handler, which writes a generic 500 to the client AND logs the full
 * stack trace via SLF4J. Stack traces shouldn't leak secrets today (the Stripe
 * SDK and our own code are careful), but relying on that is fragile — a future
 * library upgrade or a careless `log.error("...$payload")` would be enough to
 * burn `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` into journald.
 *
 * What we do here:
 * - Tag every failure with an error_id (UUID) so logs can be correlated with the
 *   client-facing 500 without revealing internals.
 * - Log the exception via SLF4J's 2-arg form (`log.error(msg, throwable)`) so
 *   Logback formats the stack trace; never interpolate `e.message` into the log
 *   string (that's where user input / library payloads sneak in).
 * - Return a minimal JSON body to the client: { error: "Internal server error",
 *   error_id }. No class name, no message, no stack.
 */
fun Application.configureErrorHandling() {
    val log = LoggerFactory.getLogger("error")

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val errorId = UUID.randomUUID().toString()
            log.error("unhandled exception [error_id=$errorId] ${call.request.httpMethod.value} ${call.request.path()}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal server error (id=$errorId)")
            )
        }
    }
}
