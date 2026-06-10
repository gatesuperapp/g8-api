package com.a4a.g8api.database

interface IMagicLinkService {
    suspend fun createToken(
        email: String,
        purpose: String,
        ipAddress: String? = null,
        userAgent: String? = null
    ): String

    suspend fun consumeToken(token: String): ConsumeResult?

    suspend fun cleanupExpiredTokens(): Int
}
