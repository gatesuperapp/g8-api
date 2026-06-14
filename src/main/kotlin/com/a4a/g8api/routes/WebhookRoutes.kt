package com.a4a.g8api.routes

import com.a4a.g8api.database.IMagicLinkService
import com.a4a.g8api.database.ISubscriptionService
import com.a4a.g8api.database.IUsersService
import com.a4a.g8api.services.AuthLogger
import com.a4a.g8api.services.EmailService
import com.stripe.Stripe
import com.stripe.model.Event
import com.stripe.model.Subscription as StripeSubscription
import com.stripe.net.Webhook
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.slf4j.Logger
import java.util.UUID

/**
 * POST /v1/webhooks/stripe — receive Stripe webhook events
 * No JWT auth — validated via Stripe HMAC signature
 */
fun Route.stripeWebhook(
    subscriptionService: ISubscriptionService,
    usersService: IUsersService,
    magicLinkService: IMagicLinkService,
    emailService: EmailService,
    authLogger: AuthLogger
) {
    post("/v1/webhooks/stripe") {
        val stripeKey = System.getenv("STRIPE_SECRET_KEY")
        val webhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET")
        if (stripeKey.isNullOrBlank() || webhookSecret.isNullOrBlank()) {
            return@post call.respond(HttpStatusCode.InternalServerError)
        }
        Stripe.apiKey = stripeKey

        val payload = call.receiveText()
        val sigHeader = call.request.headers["Stripe-Signature"]
            ?: return@post call.respond(HttpStatusCode.BadRequest)

        // Verify HMAC signature. Log via SLF4J 2-arg form so the trace is formatted
        // by Logback under our control — never interpolate `e.message` (the secret
        // is not in there today, but the principle is to never trust SDK strings).
        val event: Event = try {
            Webhook.constructEvent(payload, sigHeader, webhookSecret)
        } catch (e: Exception) {
            call.application.environment.log.warn("Stripe webhook signature verification failed", e)
            return@post call.respond(HttpStatusCode.BadRequest)
        }

        // Idempotence check
        if (subscriptionService.isWebhookProcessed(event.id)) {
            return@post call.respond(HttpStatusCode.OK)
        }

        call.application.environment.log.info("Stripe webhook: ${event.type} (${event.id})")

        when (event.type) {
            "checkout.session.completed" -> handleCheckoutCompleted(
                event, subscriptionService, usersService, magicLinkService, emailService, authLogger,
                call.application.environment.log
            )
            "customer.subscription.updated" -> handleSubscriptionUpdated(event, subscriptionService)
            "customer.subscription.deleted" -> handleSubscriptionDeleted(event, subscriptionService)
            "invoice.payment_failed" -> handlePaymentFailed(event, subscriptionService)
        }

        // Mark as processed
        subscriptionService.markWebhookProcessed(event.id, event.type)

        call.respond(HttpStatusCode.OK)
    }
}

private suspend fun handleCheckoutCompleted(
    event: Event,
    subscriptionService: ISubscriptionService,
    usersService: IUsersService,
    magicLinkService: IMagicLinkService,
    emailService: EmailService,
    authLogger: AuthLogger,
    log: Logger
) {
    // `dataObjectDeserializer.object` returns Optional.empty() when the webhook event's
    // API version differs from the SDK's pinned version — silent failure mode.
    // `deserializeUnsafe()` forces the parse regardless. Safe here: we only access
    // documented fields (customer, subscription, metadata, customerDetails.email)
    // that haven't changed shape across recent Stripe API versions.
    val session = (event.dataObjectDeserializer.`object`.orElse(null)
        ?: event.dataObjectDeserializer.deserializeUnsafe())
        as? com.stripe.model.checkout.Session ?: run {
            log.warn("Stripe webhook ${event.id}: failed to deserialize Session object — skipping")
            return
        }

    val stripeCustomerId = session.customer ?: run {
        log.warn("Stripe webhook ${event.id}: session.customer is null — skipping (session=${session.id})")
        return
    }
    val stripeSubscriptionId = session.subscription ?: run {
        log.warn("Stripe webhook ${event.id}: session.subscription is null — skipping (session=${session.id})")
        return
    }
    // "fly" or "fab" — set in BillingRoutes when an authenticated user upgrades.
    // Falls back to a priceId-based lookup if absent (e.g. checkout from the public
    // website where we can't put user_id metadata client-side).
    val productFromMetadata = session.metadata["product"]
    val userIdFromMetadata = session.metadata["user_id"]

    // Resolve the user. Two flows:
    //   (a) Logged-in upgrade: user_id is in metadata, set by BillingRoutes
    //   (b) Stripe-first signup (paid before having an app account): we look up the
    //       user by the email Stripe captured during checkout, create them if needed,
    //       and send a magic link at the end so they can sign in to the app.
    val checkoutEmail = (session.customerDetails?.email ?: session.customerEmail)
        ?.lowercase()?.trim()
    val userId: UUID
    val isNewUser: Boolean
    if (userIdFromMetadata != null) {
        userId = UUID.fromString(userIdFromMetadata)
        isNewUser = false
    } else {
        if (checkoutEmail.isNullOrBlank()) {
            log.warn("Stripe checkout completed without user_id metadata or customer email — cannot link sub ${session.id}")
            return
        }
        val existing = usersService.userByEmail(checkoutEmail)
        if (existing != null) {
            userId = existing.id
            isNewUser = false
        } else {
            userId = usersService.createUser(checkoutEmail)
            isNewUser = true
        }
    }

    // Update user with Stripe customer ID
    usersService.updateStripeCustomerId(userId, stripeCustomerId)

    // Fetch full subscription from Stripe to get details
    val stripeSub = StripeSubscription.retrieve(stripeSubscriptionId)

    val periodEnd = Instant.fromEpochSeconds(stripeSub.currentPeriodEnd)
        .toLocalDateTime(TimeZone.UTC)

    // Determine plan from price interval
    val plan = stripeSub.items.data.firstOrNull()?.let { item ->
        when (item.price.recurring.interval) {
            "month" -> "monthly"
            "year" -> "yearly"
            else -> "monthly"
        }
    } ?: "monthly"

    val priceId = stripeSub.items.data.firstOrNull()?.price?.id
    val product = productFromMetadata ?: priceIdToProduct(priceId)

    // Create or update subscription in our DB
    val existingSub = subscriptionService.findByStripeSubscriptionId(stripeSubscriptionId)
    if (existingSub == null) {
        subscriptionService.createSubscription(
            userId = userId,
            stripeSubscriptionId = stripeSubscriptionId,
            status = stripeSub.status,
            currentPeriodEnd = periodEnd,
            plan = plan,
            product = product
        )
    } else {
        subscriptionService.updateSubscription(
            stripeSubscriptionId = stripeSubscriptionId,
            status = stripeSub.status,
            currentPeriodEnd = periodEnd,
            cancelAtPeriodEnd = stripeSub.cancelAtPeriodEnd ?: false,
            product = product,
        )
    }

    // Only send a post-checkout magic link for *brand-new* users. The previous
    // "userIdFromMetadata == null" check was too broad: an existing app user who paid
    // through the website (where the checkout has no user_id metadata) would get
    // re-emailed a "log into g8" link even though they're already signed in. Existing
    // users — whether matched via metadata or via the checkout email — already have a
    // way into the app, so we stay quiet. Only the genuine "Stripe-first" signup
    // (no prior account) gets the email.
    if (isNewUser && !checkoutEmail.isNullOrBlank()) {
        // premium_signup gets a different email body that confirms the premium
        // subscription instead of pitching one — the user just paid. Stripe stores the
        // language used at checkout in session.locale (e.g. "fr" / "en" / "auto") —
        // pass it through so the email matches what they saw on the payment page.
        val purpose = "premium_signup"
        try {
            val token = magicLinkService.createToken(checkoutEmail, purpose)
            emailService.sendMagicLinkEmail(checkoutEmail, token, purpose, session.locale)
            authLogger.magicLinkRequested(
                email = checkoutEmail,
                ip = "stripe_webhook",
                purpose = purpose,
                suppressed = false
            )
        } catch (e: Exception) {
            // Don't fail the webhook on email-send failure — Stripe would retry the
            // whole thing and we'd risk creating duplicate subscriptions on the
            // re-attempt's race with the idempotence check. The user can still
            // request a fresh magic link from the app's login screen.
            log.error("Failed to send post-checkout magic link to $checkoutEmail", e)
        }
    }
}

/** Resolve a Stripe price ID to our product name by matching against the 4 STRIPE_PRICE_* env vars. */
private fun priceIdToProduct(priceId: String?): String? {
    if (priceId == null) return null
    return when (priceId) {
        System.getenv("STRIPE_PRICE_FLY_MONTHLY"), System.getenv("STRIPE_PRICE_FLY_YEARLY") -> "fly"
        System.getenv("STRIPE_PRICE_FAB_MONTHLY"), System.getenv("STRIPE_PRICE_FAB_YEARLY") -> "fab"
        else -> null
    }
}

private suspend fun handleSubscriptionUpdated(
    event: Event,
    subscriptionService: ISubscriptionService
) {
    val stripeSub = (event.dataObjectDeserializer.`object`.orElse(null)
        ?: event.dataObjectDeserializer.deserializeUnsafe())
        as? StripeSubscription ?: return

    val periodEnd = Instant.fromEpochSeconds(stripeSub.currentPeriodEnd)
        .toLocalDateTime(TimeZone.UTC)

    // Resolve product from the (possibly new) price ID. Important when the user
    // switches plan (Fly ↔ Fab) via the Customer Portal — Stripe keeps the same
    // subscription_id but swaps the price.
    val priceId = stripeSub.items.data.firstOrNull()?.price?.id
    val product = priceIdToProduct(priceId)

    subscriptionService.updateSubscription(
        stripeSubscriptionId = stripeSub.id,
        status = stripeSub.status,
        currentPeriodEnd = periodEnd,
        cancelAtPeriodEnd = stripeSub.cancelAtPeriodEnd ?: false,
        product = product,
    )
}

private suspend fun handleSubscriptionDeleted(
    event: Event,
    subscriptionService: ISubscriptionService
) {
    val stripeSub = (event.dataObjectDeserializer.`object`.orElse(null)
        ?: event.dataObjectDeserializer.deserializeUnsafe())
        as? StripeSubscription ?: return

    val periodEnd = Instant.fromEpochSeconds(stripeSub.currentPeriodEnd)
        .toLocalDateTime(TimeZone.UTC)

    // Sub fully ended — cancelAtPeriodEnd no longer meaningful, reset to false.
    subscriptionService.updateSubscription(
        stripeSubscriptionId = stripeSub.id,
        status = "canceled",
        currentPeriodEnd = periodEnd,
        cancelAtPeriodEnd = false,
    )
}

private suspend fun handlePaymentFailed(
    event: Event,
    subscriptionService: ISubscriptionService
) {
    val invoice = (event.dataObjectDeserializer.`object`.orElse(null)
        ?: event.dataObjectDeserializer.deserializeUnsafe())
        as? com.stripe.model.Invoice ?: return

    val stripeSubscriptionId = invoice.subscription ?: return
    val existing = subscriptionService.findByStripeSubscriptionId(stripeSubscriptionId) ?: return

    subscriptionService.updateSubscription(
        stripeSubscriptionId = stripeSubscriptionId,
        status = "past_due",
        currentPeriodEnd = existing.currentPeriodEnd,
        cancelAtPeriodEnd = existing.cancelAtPeriodEnd,
    )
}
