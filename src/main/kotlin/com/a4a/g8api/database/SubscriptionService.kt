package com.a4a.g8api.database

import com.a4a.g8api.models.Subscription
import com.a4a.g8api.plugins.dbQuery
import kotlinx.datetime.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

class SubscriptionService : ISubscriptionService {

    object Subscriptions : Table("subscriptions") {
        val id = uuid("id").autoGenerate()
        val userId = uuid("user_id").references(UsersService.Users.id)
        val stripeSubscriptionId = varchar("stripe_subscription_id", 256).uniqueIndex()
        val status = varchar("status", 32)
        val currentPeriodEnd = datetime("current_period_end")
        val plan = varchar("plan", 16)
        val product = varchar("product", 16).nullable()
        // Mirrors Stripe's `cancel_at_period_end` flag. true = user has clicked "Cancel"
        // in the Customer Portal; sub is still active until [currentPeriodEnd], then
        // Stripe sends `customer.subscription.deleted` and we flip status to "canceled".
        val cancelAtPeriodEnd = bool("cancel_at_period_end").default(false)
        val createdAt = datetime("created_at")
        val updatedAt = datetime("updated_at")

        override val primaryKey = PrimaryKey(id)
    }

    object WebhookEvents : Table("webhook_events") {
        val stripeEventId = varchar("stripe_event_id", 256)
        val eventType = varchar("event_type", 128)
        val processedAt = datetime("processed_at")

        override val primaryKey = PrimaryKey(stripeEventId)
    }

    private fun resultRowToSubscription(row: ResultRow) = Subscription(
        id = row[Subscriptions.id],
        userId = row[Subscriptions.userId],
        stripeSubscriptionId = row[Subscriptions.stripeSubscriptionId],
        status = row[Subscriptions.status],
        currentPeriodEnd = row[Subscriptions.currentPeriodEnd],
        plan = row[Subscriptions.plan],
        product = row[Subscriptions.product],
        cancelAtPeriodEnd = row[Subscriptions.cancelAtPeriodEnd],
        createdAt = row[Subscriptions.createdAt],
        updatedAt = row[Subscriptions.updatedAt]
    )

    override suspend fun findByUserId(userId: UUID): Subscription? = dbQuery {
        Subscriptions.selectAll()
            .where { Subscriptions.userId eq userId }
            .orderBy(Subscriptions.createdAt, SortOrder.DESC)
            .map(::resultRowToSubscription)
            .firstOrNull()
    }

    override suspend fun findByStripeSubscriptionId(stripeSubscriptionId: String): Subscription? = dbQuery {
        Subscriptions.selectAll()
            .where { Subscriptions.stripeSubscriptionId eq stripeSubscriptionId }
            .map(::resultRowToSubscription)
            .singleOrNull()
    }

    override suspend fun createSubscription(
        userId: UUID,
        stripeSubscriptionId: String,
        status: String,
        currentPeriodEnd: LocalDateTime,
        plan: String,
        product: String?
    ): Subscription = dbQuery {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val id = Subscriptions.insert {
            it[Subscriptions.userId] = userId
            it[Subscriptions.stripeSubscriptionId] = stripeSubscriptionId
            it[Subscriptions.status] = status
            it[Subscriptions.currentPeriodEnd] = currentPeriodEnd
            it[Subscriptions.plan] = plan
            it[Subscriptions.product] = product
            it[createdAt] = now
            it[updatedAt] = now
        }[Subscriptions.id]

        Subscription(
            id = id,
            userId = userId,
            stripeSubscriptionId = stripeSubscriptionId,
            status = status,
            currentPeriodEnd = currentPeriodEnd,
            plan = plan,
            product = product,
            cancelAtPeriodEnd = false,
            createdAt = now,
            updatedAt = now,
        )
    }

    override suspend fun updateSubscription(
        stripeSubscriptionId: String,
        status: String,
        currentPeriodEnd: LocalDateTime,
        cancelAtPeriodEnd: Boolean,
        product: String?,
    ) = dbQuery {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        Subscriptions.update({ Subscriptions.stripeSubscriptionId eq stripeSubscriptionId }) {
            it[Subscriptions.status] = status
            it[Subscriptions.currentPeriodEnd] = currentPeriodEnd
            it[Subscriptions.cancelAtPeriodEnd] = cancelAtPeriodEnd
            // Only overwrite product when the caller resolved one. null = unknown
            // (e.g. payment_failed event); keep the existing column value untouched.
            if (product != null) {
                it[Subscriptions.product] = product
            }
            it[updatedAt] = now
        }
        Unit
    }

    override suspend fun isWebhookProcessed(eventId: String): Boolean = dbQuery {
        WebhookEvents.selectAll()
            .where { WebhookEvents.stripeEventId eq eventId }
            .count() > 0
    }

    override suspend fun markWebhookProcessed(eventId: String, eventType: String) = dbQuery {
        // Duplicate-insert protection: WebhookRoutes calls isWebhookProcessed() first,
        // and we swallow the rare race-condition PK collision here as a belt-and-braces.
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        try {
            WebhookEvents.insert {
                it[stripeEventId] = eventId
                it[WebhookEvents.eventType] = eventType
                it[processedAt] = now
            }
        } catch (_: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            // PK already exists — another concurrent webhook delivery beat us to it.
        }
        Unit
    }
}
