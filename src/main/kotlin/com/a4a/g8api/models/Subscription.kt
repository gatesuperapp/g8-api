package com.a4a.g8api.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable


@Serializable
data class Subscription(val id: Int, val productId: String, val activeUntil: Instant)

