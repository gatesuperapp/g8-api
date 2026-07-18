package com.a4a.g8api.plugins

import com.a4a.g8api.database.IMagicLinkService
import com.a4a.g8api.database.ISessionService
import com.a4a.g8api.database.ISubscriptionService
import com.a4a.g8api.database.IUsersService
import com.a4a.g8api.routes.*
import com.a4a.g8api.services.AbuseDetector
import com.a4a.g8api.services.AuthLogger
import com.a4a.g8api.services.EmailRateLimiter
import com.a4a.g8api.services.EmailService
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

fun Application.configureRouting(
    usersService: IUsersService = get(),
    sessionService: ISessionService = get(),
    magicLinkService: IMagicLinkService = get(),
    subscriptionService: ISubscriptionService = get(),
    emailService: EmailService = get(),
    emailRateLimiter: EmailRateLimiter = get(),
    authLogger: AuthLogger = get(),
    abuseDetector: AbuseDetector = get(),
) {
    routing {
        // Health check
        get("/") {
            call.respondText("g8-api OK")
        }
        get("/v1/health") {
            call.respondText("OK")
        }

        // Auth — magic link request (rate-limited 5/h/IP + 3/24h/email, anti-spam)
        rateLimit(RateLimitNames.PUBLIC_AUTH) {
            requestMagicLink(magicLinkService, usersService, emailService, emailRateLimiter, authLogger)
        }
        // Auth — magic link consume (rate-limited 20/min/IP, defense-in-depth +
        // anti-DoS on the DB-hitting path; entropy alone makes brute force impossible)
        rateLimit(RateLimitNames.MAGIC_LINK_CONSUME) {
            consumeMagicLink(magicLinkService, usersService, sessionService, authLogger)
        }

        // Auth — refresh token (rate-limited 10/min/IP, anti brute-force)
        rateLimit(RateLimitNames.REFRESH) {
            refreshAuthenticationToken(sessionService, usersService, authLogger)
        }

        // Auth — session management (authenticated)
        logout(sessionService)
        logoutAll(sessionService)

        // Account (authenticated)
        getAccount(usersService, subscriptionService, abuseDetector)
        deleteAccount(usersService, sessionService, subscriptionService, abuseDetector)

        // Billing (authenticated)
        createCheckoutSession(usersService, subscriptionService, abuseDetector)
        createPortalSession(usersService, abuseDetector)

        // Stripe webhook (public, HMAC verified). Wires in magic-link + email so
        // post-checkout signups (paid on the website without an app account) get a
        // login email automatically.
        stripeWebhook(subscriptionService, usersService, magicLinkService, emailService, authLogger)
    }
}
