package com.a4a.g8api.database

import com.a4a.g8api.models.Session
import java.util.UUID

interface ISessionService {
    suspend fun createSession(userId: UUID, refreshToken: String, deviceInfo: String? = null): Session
    suspend fun findByRefreshToken(refreshToken: String): Session?
    suspend fun rotateRefreshToken(oldRefreshToken: String, newRefreshToken: String): Session?
    suspend fun revokeSession(refreshToken: String)
    suspend fun revokeAllUserSessions(userId: UUID)
    suspend fun isTokenRevoked(refreshToken: String): Boolean
}
