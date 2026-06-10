package com.a4a.g8api.routes

import com.a4a.g8api.database.ISubscriptionService
import com.a4a.g8api.database.IUsersService
import com.a4a.g8api.models.ErrorResponse
import com.stripe.Stripe
import com.stripe.exception.StripeException
import com.stripe.model.checkout.Session as StripeSession
import com.stripe.model.billingportal.Session as PortalSession
import com.stripe.param.checkout.SessionCreateParams
import com.stripe.param.billingportal.SessionCreateParams as PortalSessionCreateParams
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

private val billingLog = LoggerFactory.getLogger("billing")

/**
 * POST /v1/billing/checkout-session — create Stripe Checkout Session
 */
fun Route.createCheckoutSession(
    usersService: IUsersService,
    subscriptionService: ISubscriptionService
) {
    authenticate {
        post("/v1/billing/checkout-session") {
            val stripeKey = System.getenv("STRIPE_SECRET_KEY")
            if (stripeKey.isNullOrBlank()) {
                return@post call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Stripe not configured"))
            }
            Stripe.apiKey = stripeKey

            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))

            val userId = UUID.fromString(principal.getClaim("id", String::class))
            val user = usersService.userById(userId)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))

            @Serializable
            data class CheckoutRequest(
                val product: String,   // "fly" or "fab"
                val interval: String,  // "monthly" or "yearly"
            )

            val request = call.receive<CheckoutRequest>()

            // Map (product, interval) to Stripe Price ID (configured via env vars)
            val priceEnvKey = when (request.product to request.interval) {
                "fly" to "monthly" -> "STRIPE_PRICE_FLY_MONTHLY"
                "fly" to "yearly"  -> "STRIPE_PRICE_FLY_YEARLY"
                "fab" to "monthly" -> "STRIPE_PRICE_FAB_MONTHLY"
                "fab" to "yearly"  -> "STRIPE_PRICE_FAB_YEARLY"
                else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid product or interval"))
            }
            val priceId = System.getenv(priceEnvKey)

            if (priceId.isNullOrBlank()) {
                return@post call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Price not configured"))
            }

            val successUrl = System.getenv("STRIPE_SUCCESS_URL") ?: "https://the-gate.fr/payment-success"
            val cancelUrl = System.getenv("STRIPE_CANCEL_URL") ?: "https://the-gate.fr/payment-cancel"

            val paramsBuilder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .setCustomerEmail(user.email)
                .addLineItem(
                    SessionCreateParams.LineItem.builder()
                        .setPrice(priceId)
                        .setQuantity(1L)
                        .build()
                )
                .putMetadata("user_id", userId.toString())
                .putMetadata("product", request.product)

            // If user already has a Stripe customer ID, use it
            if (user.stripeCustomerId != null) {
                paramsBuilder.setCustomer(user.stripeCustomerId)
                // Remove customerEmail when customer is set
                paramsBuilder.setCustomerEmail(null)
            }

            val session = try {
                StripeSession.create(paramsBuilder.build())
            } catch (e: StripeException) {
                // Don't interpolate e.message — log the throwable via SLF4J's
                // 2-arg form so Logback formats the trace under our control.
                // The Stripe SDK does not put the API key in its exceptions, but
                // we treat every external SDK as untrusted on that front.
                billingLog.error("stripe checkout-session create failed for user $userId", e)
                return@post call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse("Stripe error — please retry shortly")
                )
            }

            call.respond(CheckoutResponse(url = session.url))
        }
    }
}

/**
 * POST /v1/billing/portal-session — create Stripe Customer Portal session
 */
fun Route.createPortalSession(usersService: IUsersService) {
    authenticate {
        post("/v1/billing/portal-session") {
            val stripeKey = System.getenv("STRIPE_SECRET_KEY")
            if (stripeKey.isNullOrBlank()) {
                return@post call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Stripe not configured"))
            }
            Stripe.apiKey = stripeKey

            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))

            val userId = UUID.fromString(principal.getClaim("id", String::class))
            val user = usersService.userById(userId)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))

            if (user.stripeCustomerId == null) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("No active subscription"))
            }

            val returnUrl = System.getenv("STRIPE_PORTAL_RETURN_URL") ?: "https://the-gate.fr"

            val params = PortalSessionCreateParams.builder()
                .setCustomer(user.stripeCustomerId)
                .setReturnUrl(returnUrl)
                .build()

            val session = try {
                PortalSession.create(params)
            } catch (e: StripeException) {
                billingLog.error("stripe portal-session create failed for user $userId", e)
                return@post call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse("Stripe error — please retry shortly")
                )
            }

            call.respond(CheckoutResponse(url = session.url))
        }
    }
}

@Serializable
data class CheckoutResponse(val url: String)
