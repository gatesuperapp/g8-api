package com.a4a.g8api.routes

import com.a4a.g8api.database.IMagicLinkService
import com.a4a.g8api.database.ISessionService
import com.a4a.g8api.database.IUsersService
import com.a4a.g8api.models.ErrorResponse
import com.a4a.g8api.plugins.clientIp
import com.a4a.g8api.services.AuthLogger
import com.a4a.g8api.services.EmailRateLimiter
import com.a4a.g8api.services.EmailService
import com.a4a.g8api.services.isValidEmail
import com.a4a.g8api.viewmodels.MagicLinkRequestViewModel
import com.a4a.g8api.viewmodels.SignInResponseViewModel
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.time.Instant.now

private val log = LoggerFactory.getLogger("MagicLinkRoutes")

fun Route.requestMagicLink(
    magicLinkService: IMagicLinkService,
    usersService: IUsersService,
    emailService: EmailService,
    emailRateLimiter: EmailRateLimiter,
    authLogger: AuthLogger
) {
    post("/v1/auth/magic-link/request") {
        val request = call.receive<MagicLinkRequestViewModel>()
        val email = request.email.lowercase().trim()
        val ipAddress = call.clientIp()

        // Always return 204 — never reveal if email exists (anti-enumeration).
        // isValidEmail caps length (RFC 5321), forbids CR/LF (header-injection
        // guard for SMTP), and enforces a sane shape. See EmailValidator.kt.
        if (!isValidEmail(email)) {
            call.respond(HttpStatusCode.NoContent)
            return@post
        }

        // Per-email rate limit: 3 magic links per 24h max for the same recipient.
        // On overflow we still respond 204 but silently drop the email.
        if (!emailRateLimiter.tryAcquire(email)) {
            authLogger.magicLinkRequested(email, ipAddress, purpose = "unknown", suppressed = true)
            call.respond(HttpStatusCode.NoContent)
            return@post
        }

        val existingUser = usersService.userByEmail(email)
        val purpose = if (existingUser != null) "login" else "signup"

        val token = magicLinkService.createToken(email, purpose)
        // Prefer the explicit body locale (set by newer clients), fall back to the
        // Accept-Language header (set globally by our HttpClient default request) so
        // users on locale-aware clients still get their language even when the body
        // field is null (e.g. legacy client before we started sending it).
        val locale = request.locale ?: call.request.headers[HttpHeaders.AcceptLanguage]
        val sent = emailService.sendMagicLinkEmail(email, token, purpose, locale)
        if (!sent) {
            log.warn("magic-link send failed for email=$email purpose=$purpose")
        }
        authLogger.magicLinkRequested(email, ipAddress, purpose, suppressed = false)

        call.respond(HttpStatusCode.NoContent)
    }
}

fun Route.consumeMagicLink(
    magicLinkService: IMagicLinkService,
    usersService: IUsersService,
    sessionService: ISessionService,
    authLogger: AuthLogger
) {
    post("/v1/auth/magic-link/consume") {
        @kotlinx.serialization.Serializable
        data class ConsumeRequest(val token: String)

        val request = call.receive<ConsumeRequest>()
        val ipAddress = call.clientIp()

        // Consume token (validates + marks as consumed)
        val result = magicLinkService.consumeToken(request.token)
        if (result == null) {
            authLogger.magicLinkConsumeFailed("invalid_or_expired", ipAddress)
            return@post call.respond(
                status = HttpStatusCode.Unauthorized,
                ErrorResponse("Lien invalide ou expiré")
            )
        }

        // Get or create user
        var user = usersService.userByEmail(result.email)
        if (user == null) {
            val userId = usersService.createUser(result.email)
            user = usersService.userById(userId)!!
        }

        // Generate refresh token
        val refreshToken = generateSecureToken()
        val deviceInfo = call.request.headers["User-Agent"]
        sessionService.createSession(user.id, refreshToken, deviceInfo)

        // Generate JWT (24h)
        val jwtAudience = this@consumeMagicLink.environment!!.config.property("jwt.audience").getString()
        val jwtIssuer = this@consumeMagicLink.environment!!.config.property("jwt.issuer").getString()
        val jwtSecret = this@consumeMagicLink.environment!!.config.property("jwt.secret").getString()

        val accessToken = JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("id", user.id.toString())
            .withClaim("email", user.email)
            .withExpiresAt(now().plusSeconds(24 * 3600)) // 24h
            .sign(Algorithm.HMAC256(jwtSecret))

        authLogger.magicLinkConsumed(user.id, ipAddress)
        call.respond(
            HttpStatusCode.OK,
            SignInResponseViewModel(accessToken, refreshToken, user.id.toString(), user.email)
        )
    }
}
