package com.a4a.g8api.routes

import com.a4a.g8api.database.ISessionService
import com.a4a.g8api.database.ISubscriptionService
import com.a4a.g8api.database.IUsersService
import com.a4a.g8api.models.ErrorResponse
import com.stripe.Stripe
import com.stripe.exception.StripeException
import com.stripe.model.Customer
import com.stripe.model.Subscription as StripeSubscription
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.UUID

private val accountLog = LoggerFactory.getLogger("account")

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
 * GET /v1/account — current user info + subscription status
 */
fun Route.getAccount(usersService: IUsersService, subscriptionService: ISubscriptionService) {
    authenticate {
        get("/v1/account") {
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

            call.respond(AccountResponse(
                email = user.email,
                subscription = subscriptionResponse
            ))
        }
    }
}

/**
 * DELETE /v1/account — RGPD account deletion.
 *
 * Order matters: Stripe first, local state second. If Stripe is unreachable we still
 * cancel the local account (the user wants out, blocking on a Stripe outage would be
 * worse than a lingering Stripe customer record we can sweep later). Stripe errors are
 * logged for manual reconciliation.
 *
 * What we do, end to end:
 *  1. Cancel the active Stripe Subscription immediately (`Subscription.cancel`, not
 *     `cancel_at_period_end` — the user has paid, but they want gone now).
 *  2. Delete the Stripe Customer (anonymises their PII on Stripe's side, satisfies RGPD).
 *  3. Revoke all refresh sessions for the user.
 *  4. Soft-delete the user record (sets `deleted_at` — kept for audit, made invisible
 *     to all lookup queries by the partial unique index on `email`).
 */
fun Route.deleteAccount(
    usersService: IUsersService,
    sessionService: ISessionService,
    subscriptionService: ISubscriptionService,
) {
    authenticate {
        delete("/v1/account") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))

            val userId = UUID.fromString(principal.getClaim("id", String::class))
            // Need the stripe customer ID before the soft-delete makes the row disappear
            // from `userById` (the query filters out `deleted_at IS NOT NULL`).
            val user = usersService.userById(userId)
                ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))

            val stripeKey = System.getenv("STRIPE_SECRET_KEY")
            if (!stripeKey.isNullOrBlank()) {
                Stripe.apiKey = stripeKey

                // Cancel the active subscription, if any. Already-cancelled subs throw
                // a StripeException we just log and move on — idempotent for the caller.
                val sub = subscriptionService.findByUserId(userId)
                if (sub != null) {
                    try {
                        StripeSubscription.retrieve(sub.stripeSubscriptionId).cancel()
                        accountLog.info("Cancelled Stripe subscription ${sub.stripeSubscriptionId} for user $userId")
                    } catch (e: StripeException) {
                        accountLog.error(
                            "Failed to cancel Stripe subscription ${sub.stripeSubscriptionId} for user $userId — local deletion proceeds",
                            e
                        )
                    }
                }

                // Delete the Stripe Customer so their PII (name, email, payment methods)
                // is purged on Stripe's side too. Stripe keeps a deleted-Customer stub
                // for invoice history; that's compatible with French accounting law.
                if (!user.stripeCustomerId.isNullOrBlank()) {
                    try {
                        Customer.retrieve(user.stripeCustomerId).delete()
                        accountLog.info("Deleted Stripe customer ${user.stripeCustomerId} for user $userId")
                    } catch (e: StripeException) {
                        accountLog.error(
                            "Failed to delete Stripe customer ${user.stripeCustomerId} for user $userId — local deletion proceeds",
                            e
                        )
                    }
                }
            } else {
                accountLog.warn("STRIPE_SECRET_KEY not set — skipping Stripe cleanup for user $userId")
            }

            // Local cleanup. The session revoke makes any existing access token reject
            // on next call (interceptor will see 401 and clear local tokens), the
            // soft-delete sets `deleted_at` so the user is invisible to lookup queries.
            sessionService.revokeAllUserSessions(userId)
            usersService.softDeleteUser(userId)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
data class AccountResponse(
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
