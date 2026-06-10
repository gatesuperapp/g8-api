package com.a4a.g8api.database

import com.a4a.g8api.models.Session
import com.a4a.g8api.plugins.dbQuery
import kotlinx.datetime.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.security.MessageDigest
import java.util.UUID

class SessionService : ISessionService {

    object Sessions : Table("sessions") {
        val id = uuid("id").autoGenerate()
        val userId = uuid("user_id").references(UsersService.Users.id)
        val refreshTokenHash = varchar("refresh_token_hash", 256).uniqueIndex()
        val expiresAt = datetime("expires_at")
        val createdAt = datetime("created_at")
        val lastUsedAt = datetime("last_used_at")
        val revokedAt = datetime("revoked_at").nullable()
        val deviceInfo = varchar("device_info", 1024).nullable()

        override val primaryKey = PrimaryKey(id)
    }

    private fun resultRowToSession(row: ResultRow) = Session(
        id = row[Sessions.id],
        userId = row[Sessions.userId],
        refreshTokenHash = row[Sessions.refreshTokenHash],
        expiresAt = row[Sessions.expiresAt],
        createdAt = row[Sessions.createdAt],
        lastUsedAt = row[Sessions.lastUsedAt],
        revokedAt = row[Sessions.revokedAt],
        deviceInfo = row[Sessions.deviceInfo]
    )

    override suspend fun createSession(
        userId: UUID,
        refreshToken: String,
        deviceInfo: String?
    ): Session = dbQuery {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val expiresAt = Clock.System.now()
            .plus(180, DateTimeUnit.DAY, TimeZone.UTC)
            .toLocalDateTime(TimeZone.UTC)

        val hash = hashToken(refreshToken)

        val id = Sessions.insert {
            it[Sessions.userId] = userId
            it[refreshTokenHash] = hash
            it[Sessions.expiresAt] = expiresAt
            it[createdAt] = now
            it[lastUsedAt] = now
            it[Sessions.deviceInfo] = deviceInfo
        }[Sessions.id]

        Session(id, userId, hash, expiresAt, now, now, null, deviceInfo)
    }

    /**
     * Returns any session matching this hash, including revoked or expired ones.
     *
     * The caller is expected to inspect [Session.revokedAt] and [Session.expiresAt]:
     * a revoked match means the caller is replaying a rotated token, which the
     * `/auth/refresh` route treats as a probable theft and triggers a full
     * session purge for the user. If we filtered revoked rows out here, replay
     * detection would never fire — the caller would just see a "not found"
     * 401 and the attacker's freshly-rotated token would stay valid.
     */
    override suspend fun findByRefreshToken(refreshToken: String): Session? = dbQuery {
        val hash = hashToken(refreshToken)
        Sessions.selectAll()
            .where { Sessions.refreshTokenHash eq hash }
            .map(::resultRowToSession)
            .singleOrNull()
    }

    override suspend fun rotateRefreshToken(
        oldRefreshToken: String,
        newRefreshToken: String
    ): Session? = dbQuery {
        val oldHash = hashToken(oldRefreshToken)
        val newHash = hashToken(newRefreshToken)
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val newExpiresAt = Clock.System.now()
            .plus(180, DateTimeUnit.DAY, TimeZone.UTC)
            .toLocalDateTime(TimeZone.UTC)

        // Revoke old token
        Sessions.update({ Sessions.refreshTokenHash eq oldHash }) {
            it[revokedAt] = now
        }

        // Find the old session to get userId and deviceInfo
        val oldSession = Sessions.selectAll()
            .where { Sessions.refreshTokenHash eq oldHash }
            .map(::resultRowToSession)
            .singleOrNull() ?: return@dbQuery null

        // Create new session
        val id = Sessions.insert {
            it[userId] = oldSession.userId
            it[refreshTokenHash] = newHash
            it[expiresAt] = newExpiresAt
            it[createdAt] = now
            it[lastUsedAt] = now
            it[deviceInfo] = oldSession.deviceInfo
        }[Sessions.id]

        Session(id, oldSession.userId, newHash, newExpiresAt, now, now, null, oldSession.deviceInfo)
    }

    override suspend fun revokeSession(refreshToken: String) = dbQuery {
        val hash = hashToken(refreshToken)
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        Sessions.update({ Sessions.refreshTokenHash eq hash }) {
            it[revokedAt] = now
        }
        Unit
    }

    override suspend fun revokeAllUserSessions(userId: UUID) = dbQuery {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        Sessions.update({ (Sessions.userId eq userId) and Sessions.revokedAt.isNull() }) {
            it[revokedAt] = now
        }
        Unit
    }

    override suspend fun isTokenRevoked(refreshToken: String): Boolean = dbQuery {
        val hash = hashToken(refreshToken)
        val session = Sessions.selectAll()
            .where { Sessions.refreshTokenHash eq hash }
            .map(::resultRowToSession)
            .singleOrNull()
        session?.revokedAt != null
    }

    companion object {
        fun hashToken(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(token.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
