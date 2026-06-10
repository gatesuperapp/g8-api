package com.a4a.g8api.models

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Subscription(
    @Contextual val id: UUID,
    @Contextual val userId: UUID,
    val stripeSubscriptionId: String,
    val status: String,       // active, past_due, canceled, trialing, incomplete, unpaid
    @Contextual val currentPeriodEnd: LocalDateTime,
    val plan: String,         // monthly, yearly
    val product: String?,     // fly, fab — null on legacy rows
    val cancelAtPeriodEnd: Boolean = false,
    @Contextual val createdAt: LocalDateTime,
    @Contextual val updatedAt: LocalDateTime
)
