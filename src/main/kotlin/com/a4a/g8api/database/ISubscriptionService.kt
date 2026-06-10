package com.a4a.g8api.database

import com.a4a.g8api.models.Subscription
import kotlinx.datetime.LocalDateTime
import java.util.UUID

interface ISubscriptionService {
    suspend fun findByUserId(userId: UUID): Subscription?
    suspend fun findByStripeSubscriptionId(stripeSubscriptionId: String): Subscription?
    suspend fun createSubscription(
        userId: UUID,
        stripeSubscriptionId: String,
        status: String,
        currentPeriodEnd: LocalDateTime,
        plan: String,
        product: String?
    ): Subscription
    suspend fun updateSubscription(
        stripeSubscriptionId: String,
        status: String,
        currentPeriodEnd: LocalDateTime,
        cancelAtPeriodEnd: Boolean = false,
        // When the user switches plan via the Customer Portal (Fly → Fab or vice
        // versa), Stripe keeps the same subscription_id and only swaps the price.
        // Pass the resolved product (fly / fab) so the column tracks the active plan.
        // null = no change (keep whatever's stored).
        product: String? = null,
    )
    suspend fun isWebhookProcessed(eventId: String): Boolean
    suspend fun markWebhookProcessed(eventId: String, eventType: String)
}
