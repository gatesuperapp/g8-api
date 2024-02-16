package com.a4a.g8api.models

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class RefreshToken(val id: Int, val token: String, val expiresAt: LocalDateTime, val userId: Int)

