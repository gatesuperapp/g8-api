package com.a4a.g8api.routes

import com.a4a.g8api.database.ISessionService
import com.a4a.g8api.database.IUsersService
import com.a4a.g8api.models.ErrorResponse
import com.a4a.g8api.plugins.clientIp
import com.a4a.g8api.services.AuthLogger
import com.a4a.g8api.viewmodels.RefreshTokenRequestViewModel
import com.a4a.g8api.viewmodels.SignInResponseViewModel
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaInstant
import java.security.SecureRandom
import java.util.Base64

import java.time.Instant.now

fun Route.refreshAuthenticationToken(
    sessionService: ISessionService,
    usersService: IUsersService,
    authLogger: AuthLogger
) {
    post("/v1/auth/refresh") {
        val request = call.receive<RefreshTokenRequestViewModel>()
        val ipAddress = call.clientIp()

        // Find session by refresh token hash (returns revoked/expired matches too —
        // we need them to detect replay)
        val session = sessionService.findByRefreshToken(request.refreshToken)
        if (session == null) {
            authLogger.refreshFailed(ipAddress, "token_not_found")
            return@post call.respond(
                status = HttpStatusCode.Unauthorized,
                ErrorResponse("Token is invalid or expired")
            )
        }

        // Replay detection: this token was already rotated. Probable theft → nuke
        // every active session for the user, forcing a full re-login.
        if (session.revokedAt != null) {
            sessionService.revokeAllUserSessions(session.userId)
            authLogger.refreshReuseDetected(session.userId, ipAddress)
            return@post call.respond(
                status = HttpStatusCode.Unauthorized,
                ErrorResponse("Token has been revoked. All sessions invalidated.")
            )
        }

        // Plain expiry — no security incident, just stale.
        val now = java.time.Instant.now()
        if (session.expiresAt.toInstant(kotlinx.datetime.TimeZone.UTC).toJavaInstant().isBefore(now)) {
            authLogger.refreshFailed(ipAddress, "token_expired")
            return@post call.respond(
                status = HttpStatusCode.Unauthorized,
                ErrorResponse("Token is invalid or expired")
            )
        }

        // Generate new refresh token (rotation)
        val newRefreshToken = generateSecureToken()
        sessionService.rotateRefreshToken(request.refreshToken, newRefreshToken)
            ?: run {
                authLogger.refreshFailed(ipAddress, "rotate_failed")
                return@post call.respond(
                    status = HttpStatusCode.InternalServerError,
                    ErrorResponse("Failed to rotate token")
                )
            }

        // Generate new JWT (24h)
        val jwtAudience = this@refreshAuthenticationToken.environment!!.config.property("jwt.audience").getString()
        val jwtIssuer = this@refreshAuthenticationToken.environment!!.config.property("jwt.issuer").getString()
        val jwtSecret = this@refreshAuthenticationToken.environment!!.config.property("jwt.secret").getString()

        val user = usersService.userById(session.userId)
            ?: return@post call.respond(
                status = HttpStatusCode.InternalServerError,
                ErrorResponse("User not found")
            )

        val accessToken = JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("id", session.userId.toString())
            .withClaim("email", user.email)
            .withExpiresAt(now().plusSeconds(24 * 3600)) // 24h
            .sign(Algorithm.HMAC256(jwtSecret))

        authLogger.refreshSuccess(session.userId, ipAddress)
        call.respond(SignInResponseViewModel(accessToken, newRefreshToken, user.id.toString(), user.email))
    }
}

fun generateSecureToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
