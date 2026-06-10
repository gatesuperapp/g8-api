package com.a4a.g8api.services

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Emits structured single-line JSON log records for security-relevant auth events.
 *
 * Records are written to the standard "auth" SLF4J logger so existing log routing
 * (journald via systemd) picks them up without extra config. Use jq / journalctl -u
 * g8-api to grep:
 *
 *     journalctl -u g8-api -f | grep '"event":' | jq .
 *
 * Emails are hashed (SHA-256) before logging — RGPD-friendly: enough to correlate
 * activity for a single mailbox across events, without storing the address itself.
 */
class AuthLogger {

    private val log = LoggerFactory.getLogger("auth")

    fun magicLinkRequested(email: String, ip: String, purpose: String, suppressed: Boolean) =
        emit("magic_link.requested") {
            put("email_hash", hashEmail(email))
            put("ip", ip)
            put("purpose", purpose)
            put("suppressed", suppressed) // true if dropped by per-email rate limit
        }

    fun magicLinkConsumed(userId: UUID, ip: String) =
        emit("magic_link.consumed") {
            put("user_id", userId.toString())
            put("ip", ip)
        }

    fun magicLinkConsumeFailed(reason: String, ip: String) =
        emit("magic_link.consume_failed") {
            put("reason", reason)
            put("ip", ip)
        }

    fun refreshSuccess(userId: UUID, ip: String) =
        emit("auth.refresh") {
            put("user_id", userId.toString())
            put("ip", ip)
            put("success", true)
        }

    fun refreshFailed(ip: String, reason: String) =
        emit("auth.refresh") {
            put("ip", ip)
            put("success", false)
            put("reason", reason)
        }

    /** Suspected token theft — token reuse after rotation. Highest priority alert. */
    fun refreshReuseDetected(userId: UUID, ip: String) {
        val record = jsonRecord("auth.refresh_reuse_detected") {
            put("user_id", userId.toString())
            put("ip", ip)
        }
        log.warn(record)
    }

    fun logout(userId: UUID, ip: String) =
        emit("auth.logout") {
            put("user_id", userId.toString())
            put("ip", ip)
        }

    private inline fun emit(event: String, crossinline fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        log.info(jsonRecord(event, fields))
    }

    private inline fun jsonRecord(event: String, crossinline fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("ts", Instant.now().toString())
            put("event", event)
            fields()
        }.toString()

    private fun hashEmail(email: String): String {
        val normalized = email.lowercase().trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
