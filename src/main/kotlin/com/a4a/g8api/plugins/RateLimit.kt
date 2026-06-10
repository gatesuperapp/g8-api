package com.a4a.g8api.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Rate-limit names used in routing to wrap individual routes.
 * - PUBLIC_AUTH: magic-link request (anti-spam)
 * - MAGIC_LINK_CONSUME: magic-link consume (defense-in-depth + DoS + detection signal)
 * - REFRESH: token refresh endpoint (anti brute-force)
 */
object RateLimitNames {
    val PUBLIC_AUTH = RateLimitName("public-auth")
    val MAGIC_LINK_CONSUME = RateLimitName("magic-link-consume")
    val REFRESH = RateLimitName("refresh")
}

fun Application.configureRateLimit() {
    // Client IP is resolved via `ApplicationCall.clientIp()` (see ClientIp.kt) which
    // only honors X-Forwarded-For when the TCP peer is the loopback reverse proxy,
    // and always takes the LAST entry — immune to client-supplied X-F-F spoofing.
    install(RateLimit) {
        // 60 magic link requests per hour per IP — generous to accommodate carrier-grade
        // NAT (mobile users behind the same egress IP). The real anti-spam defense is the
        // per-email limit, not the per-IP one.
        register(RateLimitNames.PUBLIC_AUTH) {
            rateLimiter(limit = 60, refillPeriod = 1.hours)
            requestKey { call -> call.clientIp() }
        }

        // 20 consume attempts per minute per IP. The token has 256 bits of entropy
        // so brute force is mathematically impossible — this is defense-in-depth:
        // caps DoS on the DB-hitting consume path, gives us a detection signal,
        // and protects us if the token entropy is ever reduced. Legit users only
        // need 1 successful consume, so this never locks them out in practice.
        register(RateLimitNames.MAGIC_LINK_CONSUME) {
            rateLimiter(limit = 20, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIp() }
        }

        // 10 refresh attempts per minute per IP (brute-force protection)
        register(RateLimitNames.REFRESH) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIp() }
        }
    }
}
