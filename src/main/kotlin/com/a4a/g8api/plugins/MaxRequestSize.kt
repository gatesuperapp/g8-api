package com.a4a.g8api.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

/**
 * Hard cap on request body size — rejects with 413 if Content-Length exceeds
 * the limit.
 *
 * Defense-in-depth on top of nginx's `client_max_body_size` upstream: nginx
 * already drops oversized bodies before they hit the JVM. This plugin is the
 * second line in case the reverse proxy is ever misconfigured, removed, or
 * the JVM gets exposed directly through a firewall hole.
 *
 * Notes:
 *  - Only inspects `Content-Length`. Chunked requests (no header) are not
 *    capped at this layer — nginx upstream handles those. In practice no
 *    legitimate client of this API uses chunked.
 *  - 64 KB is generous: every endpoint here ships < 1 KB on the happy path.
 *    Stripe webhooks are the largest payload and rarely exceed 16 KB.
 *  - Wired in [Application.module] *after* `configureErrorHandling` so any
 *    throw from the intercept goes through the global UUID-correlated
 *    error handler instead of bubbling raw to the client.
 */
private const val MAX_BODY_BYTES: Long = 64L * 1024

fun Application.configureMaxRequestSize() {
    intercept(ApplicationCallPipeline.Plugins) {
        val contentLength = call.request.contentLength()
        if (contentLength != null && contentLength > MAX_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            finish()
        }
    }
}
