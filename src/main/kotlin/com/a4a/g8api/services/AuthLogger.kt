package com.a4a.g8api.services

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.InetAddress
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
 * RGPD posture:
 *  - Emails are hashed (SHA-256, 16 hex chars) — enough to correlate a single
 *    mailbox across events, without storing the address itself.
 *  - Client IPs are truncated to /24 (IPv4) or /48 (IPv6) by default. We keep
 *    the full IP only on events that signal an attack in progress (token reuse,
 *    brute-force suspicion, abuse-rate-limit trigger) — there the legitimate
 *    interest in identifying the attacker outweighs minimisation.
 */
class AuthLogger {

    private val log = LoggerFactory.getLogger("auth")

    fun magicLinkRequested(email: String, ip: String, purpose: String, suppressed: Boolean) =
        emit("magic_link.requested") {
            put("email_hash", hashEmail(email))
            // Full IP only when the per-email rate limit kicked in — that's an abuse signal.
            put("ip", if (suppressed) ip else truncateIp(ip))
            put("purpose", purpose)
            put("suppressed", suppressed)
        }

    fun magicLinkConsumed(userId: UUID, ip: String) =
        emit("magic_link.consumed") {
            put("user_id", userId.toString())
            put("ip", truncateIp(ip))
        }

    fun magicLinkConsumeFailed(reason: String, ip: String) =
        emit("magic_link.consume_failed") {
            put("reason", reason)
            put("ip", ip) // full IP — possible brute-force, need to investigate
        }

    fun refreshSuccess(userId: UUID, ip: String) =
        emit("auth.refresh") {
            put("user_id", userId.toString())
            put("ip", truncateIp(ip))
            put("success", true)
        }

    fun refreshFailed(ip: String, reason: String) =
        emit("auth.refresh") {
            put("ip", ip) // full IP — possible brute-force / replay
            put("success", false)
            put("reason", reason)
        }

    /** Suspected token theft — token reuse after rotation. Highest priority alert. */
    fun refreshReuseDetected(userId: UUID, ip: String) {
        val record = jsonRecord("auth.refresh_reuse_detected") {
            put("user_id", userId.toString())
            put("ip", ip) // full IP — attacker fingerprint
        }
        log.warn(record)
    }

    fun logout(userId: UUID, ip: String) =
        emit("auth.logout") {
            put("user_id", userId.toString())
            put("ip", truncateIp(ip))
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

/**
 * Anonymize an IP for long-term log storage (CNIL guidance).
 *
 *  - IPv4 → /24 (keep first 3 octets, zero the last) :  82.65.123.45 → 82.65.123.0
 *  - IPv6 → /48 (keep first 3 hextets / 6 bytes)     :  2001:db8:1234:5678::1 → 2001:db8:1234::
 *  - Unparseable input                                →  "unknown"
 *
 * Kept top-level so it can be unit-tested without instantiating AuthLogger.
 */
internal fun truncateIp(ip: String): String {
    if (ip.isBlank()) return "unknown"
    return when {
        ":" in ip -> truncateIpv6(ip)
        else -> truncateIpv4(ip)
    }
}

private fun truncateIpv4(ip: String): String {
    val parts = ip.split(".")
    if (parts.size != 4) return "unknown"
    if (parts.any { p -> p.toIntOrNull()?.let { it in 0..255 } != true }) return "unknown"
    return "${parts[0]}.${parts[1]}.${parts[2]}.0"
}

private fun truncateIpv6(ip: String): String {
    // ':' is not a valid char in DNS hostnames so getByName won't do a lookup here —
    // it'll either parse the literal or throw, both fine.
    return try {
        val bytes = InetAddress.getByName(ip).address
        if (bytes.size != 16) return "unknown"
        val truncated = ByteArray(16)
        System.arraycopy(bytes, 0, truncated, 0, 6)
        InetAddress.getByAddress(truncated).hostAddress
    } catch (_: Exception) {
        "unknown"
    }
}
