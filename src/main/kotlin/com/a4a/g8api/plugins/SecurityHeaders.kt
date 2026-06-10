package com.a4a.g8api.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.defaultheaders.*

/**
 * Sets HTTP security headers on every response.
 *
 * No CORS configured: this API has no browser front-end in Phase 1, so we do NOT emit
 * Access-Control-Allow-Origin. Any browser cross-origin call is blocked by default.
 *
 * HSTS is also emitted by the YunoHost reverse proxy — emitting it here too is harmless
 * (a duplicate Strict-Transport-Security header is collapsed by intermediaries) and
 * protects in case the proxy config drifts.
 */
fun Application.configureSecurityHeaders() {
    install(DefaultHeaders) {
        header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        // CSP: API responses are JSON, no scripts. Lock everything down.
        header(
            "Content-Security-Policy",
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"
        )
        // Remove the default Server header that leaks Ktor version
        header("Server", "g8-api")
    }
}
