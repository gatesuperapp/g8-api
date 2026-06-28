package com.a4a.g8api.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Rate-limit names used in routing to wrap individual routes.
 * Public routes are keyed by client IP; JWT-protected routes are keyed by
 * user_id (a stolen token can rotate IPs but stays the same user).
 *
 *  - PUBLIC_AUTH          : magic-link request (anti-spam)
 *  - MAGIC_LINK_CONSUME   : magic-link consume (defense-in-depth + DoS + detection signal)
 *  - REFRESH              : token refresh endpoint (anti brute-force)
 *  - ACCOUNT              : GET/DELETE /v1/account — 60/min/user (called on every screen resume)
 *  - BILLING              : POST /v1/billing/... — 5/h/user (legit users create ~1 checkout/portal session)
 */
object RateLimitNames {
    val PUBLIC_AUTH = RateLimitName("public-auth")
    val MAGIC_LINK_CONSUME = RateLimitName("magic-link-consume")
    val REFRESH = RateLimitName("refresh")
    val ACCOUNT = RateLimitName("account")
    val BILLING = RateLimitName("billing")
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

        // 60 /v1/account hits per minute per user_id — generous, the mobile
        // client calls it on every screen resume (Account, gStore, after a
        // Stripe redirect). Keyed by user_id so a stolen JWT can't sidestep
        // by rotating IPs.
        register(RateLimitNames.ACCOUNT) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call -> requireUserIdKey(call, jail = "account") }
        }

        // 5 billing actions per hour per user_id. A legit user creates one
        // checkout session at signup and zero-or-one portal session per plan
        // change. 5/h kills the abuse vector "spam the Stripe API on the
        // server's dime" without ever clipping real usage.
        register(RateLimitNames.BILLING) {
            rateLimiter(limit = 5, refillPeriod = 1.hours)
            requestKey { call -> requireUserIdKey(call, jail = "billing") }
        }
    }
}

/**
 * Pulls the JWT user_id out of the principal to key a rate-limit bucket on it.
 *
 * The Ktor rate-limit plugin runs its `requestKey` after `authenticate` has
 * populated the principal — so on a properly wired route there's always a
 * JWTPrincipal. If we somehow end up here without one it's a wiring bug
 * (registered the RL on an unauthenticated route), and we'd rather fail loud
 * than silently bucket everyone into the same global slot.
 */
private fun requireUserIdKey(call: ApplicationCall, jail: String): String {
    val principal = call.principal<JWTPrincipal>()
        ?: error("RateLimit '$jail' applied to a route without JWT auth — fix the routing wiring")
    return principal.getClaim("id", String::class)
        ?: error("RateLimit '$jail' got a JWT with no 'id' claim — token issuer is broken")
}
