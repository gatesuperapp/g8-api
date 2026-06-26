package com.a4a.g8api.database

import com.a4a.g8api.plugins.dbQuery
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class MagicLinkService : IMagicLinkService {

    object MagicLinks : Table("magic_links") {
        val id = uuid("id").autoGenerate()
        val email = varchar("email", 1024)
        val tokenHash = varchar("token_hash", 256).uniqueIndex()
        val purpose = varchar("purpose", 16) // 'signup' or 'login'
        val expiresAt = datetime("expires_at")
        val consumedAt = datetime("consumed_at").nullable()
        val createdAt = datetime("created_at")

        override val primaryKey = PrimaryKey(id)
    }

    override suspend fun createToken(
        email: String,
        purpose: String
    ): String = dbQuery {
        // Generate 32 bytes secure random token, base64url encoded
        val secureRandom = SecureRandom()
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val hash = hashToken(token)

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val expiresAt = Clock.System.now()
            .plus(15, DateTimeUnit.MINUTE, TimeZone.UTC)
            .toLocalDateTime(TimeZone.UTC)

        // Invalidate any existing unconsumed tokens for this email
        MagicLinks.update({
            (MagicLinks.email eq email.lowercase().trim()) and MagicLinks.consumedAt.isNull()
        }) {
            it[consumedAt] = now // mark as consumed so they can't be used
        }

        // Create new token
        MagicLinks.insert {
            it[MagicLinks.email] = email.lowercase().trim()
            it[tokenHash] = hash
            it[MagicLinks.purpose] = purpose
            it[MagicLinks.expiresAt] = expiresAt
            it[createdAt] = now
        }

        token
    }

    override suspend fun consumeToken(token: String): ConsumeResult? = dbQuery {
        val hash = hashToken(token)
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        val result = MagicLinks.selectAll()
            .where {
                (MagicLinks.tokenHash eq hash) and
                MagicLinks.consumedAt.isNull() and
                (MagicLinks.expiresAt greater now)
            }
            .singleOrNull()

        if (result != null) {
            // Mark as consumed
            MagicLinks.update({ MagicLinks.tokenHash eq hash }) {
                it[consumedAt] = now
            }
            ConsumeResult(
                email = result[MagicLinks.email],
                purpose = result[MagicLinks.purpose]
            )
        } else {
            null
        }
    }

    override suspend fun cleanupExpiredTokens(): Int = dbQuery {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        MagicLinks.deleteWhere { Op.build { MagicLinks.expiresAt less now } }
    }

    companion object {
        fun hashToken(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(token.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}

data class ConsumeResult(val email: String, val purpose: String)
