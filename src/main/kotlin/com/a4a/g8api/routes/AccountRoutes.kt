package com.a4a.g8api.routes

import com.a4a.g8api.database.ISessionService
import com.a4a.g8api.database.ISubscriptionService
import com.a4a.g8api.database.IUsersService
import com.a4a.g8api.models.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * POST /v1/auth/logout — revoke current session
 */
fun Route.logout(sessionService: ISessionService) {
    authenticate {
        post("/v1/auth/logout") {
            @Serializable
            data class LogoutRequest(val refreshToken: String)

            val request = call.receive<LogoutRequest>()
            sessionService.revokeSession(request.refreshToken)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * POST /v1/auth/logout-all — revoke all sessions for current user
 */
fun Route.logoutAll(sessionService: ISessionService) {
    authenticate {
        post("/v1/auth/logout-all") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))

            val userId = UUID.fromString(principal.getClaim("id", String::class))
            sessionService.revokeAllUserSessions(userId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * GET /v1/me — current user info + subscription status
 */
fun Route.getMe(usersService: IUsersService, subscriptionService: ISubscriptionService) {
    authenticate {
        get("/v1/me") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))

            val userId = UUID.fromString(principal.getClaim("id", String::class))
            val user = usersService.userById(userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))

            val subscription = subscriptionService.findByUserId(userId)

            val subscriptionResponse = subscription?.let {
                SubscriptionResponse(
                    status = it.status,
                    currentPeriodEnd = it.currentPeriodEnd.toString(),
                    plan = it.plan,
                    product = it.product,
                    cancelAtPeriodEnd = it.cancelAtPeriodEnd,
                )
            }

            call.respond(MeResponse(
                email = user.email,
                subscription = subscriptionResponse
            ))
        }
    }
}

/**
 * DELETE /v1/me — soft delete account (RGPD)
 */
fun Route.deleteMe(usersService: IUsersService, sessionService: ISessionService) {
    authenticate {
        delete("/v1/me") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))

            val userId = UUID.fromString(principal.getClaim("id", String::class))

            // Revoke all sessions
            sessionService.revokeAllUserSessions(userId)

            // Soft delete user
            usersService.softDeleteUser(userId)

            // TODO: Cancel Stripe subscription if active

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
data class MeResponse(
    val email: String,
    val subscription: SubscriptionResponse? = null
)

@Serializable
data class SubscriptionResponse(
    val status: String,
    val currentPeriodEnd: String,
    val plan: String,
    val product: String? = null,
    val cancelAtPeriodEnd: Boolean = false,
)
