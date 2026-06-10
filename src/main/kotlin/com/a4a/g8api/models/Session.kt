package com.a4a.g8api.models

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Session(
    @Contextual val id: UUID,
    @Contextual val userId: UUID,
    val refreshTokenHash: String,
    val expiresAt: @Contextual LocalDateTime,
    val createdAt: @Contextual LocalDateTime,
    val lastUsedAt: @Contextual LocalDateTime,
    val revokedAt: @Contextual LocalDateTime? = null,
    val deviceInfo: String? = null
)
