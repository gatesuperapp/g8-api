package com.a4a.g8api.plugins

import com.a4a.g8api.services.AbuseDetector
import com.a4a.g8api.services.AuthLogger
import com.a4a.g8api.services.CleanupService
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.ktor.ext.get
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Healthchecks.io exposes its ping API on exactly one host. We refuse to make
 * outbound HTTP from the JVM to anywhere else from the maintenance path — this
 * is a defense-in-depth measure against SSRF: today the only way to feed us a
 * hostile URL is to control the env vars (which requires root, at which point
 * the game is already lost), but a future regression (URL coming from DB,
 * admin panel, runtime config) would put this surface back in play. Cheap to
 * harden today.
 */
private const val HEALTHCHECKS_HOST = "hc-ping.com"

/**
 * Returns the URL string unchanged if it points at our healthcheck provider over
 * https, or null otherwise. Pure function for unit testing.
 *
 * Rules:
 * - Must parse as a syntactically valid URI
 * - Scheme must be https (no http downgrade, no file://, no jar:, etc.)
 * - Host must be exactly `hc-ping.com` (rejects lookalikes like `hc-ping.com.evil.example`)
 * - No userinfo (rejects `https://attacker@hc-ping.com/...`)
 */
fun validateHealthcheckUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val parsed = try {
        URI(url)
    } catch (e: URISyntaxException) {
        return null
    }
    if (!parsed.isAbsolute) return null
    if (!"https".equals(parsed.scheme, ignoreCase = true)) return null
    if (parsed.host != HEALTHCHECKS_HOST) return null
    if (parsed.userInfo != null) return null
    return url
}

/**
 * Background maintenance tasks running inside the Ktor application coroutine scope.
 *
 * Three coroutines are launched at boot:
 * - **Cleanup** — calls [CleanupService.runCleanup] every 24h, then pings the
 *   `HC_PING_URL_CLEANUP` URL on success (or `/fail` on exception). The first run
 *   happens after a short delay so app boot stays snappy.
 * - **Heartbeat** — pings `HC_PING_URL_HEARTBEAT` every 5 minutes so Healthchecks.io
 *   knows the API is alive. Only launched if the env var is set.
 * - **Abuse scan** — polls [AbuseDetector] every 5 minutes for user_ids that have
 *   been hit from too many distinct IPs (residual gap left by the Ktor 3 rate-limit
 *   fallback in [RateLimit]). Clean scan pings `HC_PING_URL_ABUSE`, suspect scan
 *   pings `HC_PING_URL_ABUSE/fail` so Healthchecks.io fans out to the existing
 *   notification channel (email / Slack / …). Only launched if the env var is set.
 *
 * All env vars are optional. If unset, the scheduler still runs the corresponding
 * work locally (cleanup, abuse detection); we just lose the external monitoring signal.
 */
fun Application.configureMaintenance() {
    val cleanupService: CleanupService = get()
    val abuseDetector: AbuseDetector = get()
    val authLogger: AuthLogger = get()
    val log = LoggerFactory.getLogger("maintenance")

    val hcCleanupUrl = resolveHealthcheckEnv("HC_PING_URL_CLEANUP", log)
    val hcHeartbeatUrl = resolveHealthcheckEnv("HC_PING_URL_HEARTBEAT", log)
    val hcAbuseUrl = resolveHealthcheckEnv("HC_PING_URL_ABUSE", log)
    val pinger = HealthcheckPinger()

    launch {
        // Wait a couple of minutes so app boot finishes cleanly before the first cleanup
        delay(2 * 60 * 1000L)
        while (true) {
            try {
                val report = cleanupService.runCleanup()
                hcCleanupUrl?.let { pinger.ping(it, "cleanup ok: $report") }
            } catch (e: Exception) {
                log.error("maintenance.cleanup_failed", e)
                hcCleanupUrl?.let { pinger.ping("$it/fail", "cleanup failed: ${e.javaClass.simpleName}") }
            }
            delay(24 * 60 * 60 * 1000L) // 24h
        }
    }

    if (hcHeartbeatUrl != null) {
        launch {
            while (true) {
                pinger.ping(hcHeartbeatUrl)
                delay(5 * 60 * 1000L) // 5 min
            }
        }
    }

    launch {
        // Small lead-in so we don't scan an empty detector immediately at boot
        delay(60 * 1000L)
        while (true) {
            val suspects = abuseDetector.suspiciousUsers()
            suspects.forEach { authLogger.abuseSuspected(it.userId, it.ipCount) }
            if (hcAbuseUrl != null) {
                if (suspects.isEmpty()) {
                    pinger.ping(hcAbuseUrl)
                } else {
                    val summary = suspects.joinToString(", ") { "${it.userId}=${it.ipCount}ip" }
                    pinger.ping("$hcAbuseUrl/fail", "abuse suspects: $summary")
                }
            }
            delay(5 * 60 * 1000L) // 5 min
        }
    }
}

/**
 * Read a `HC_PING_URL_*` env var and run it through [validateHealthcheckUrl].
 * Invalid URLs are logged loud and treated as if the env var was unset — same
 * effect on the running app (no ping for that channel), but with a clear signal
 * in journald so a fat-fingered URL is noticed.
 */
private fun resolveHealthcheckEnv(varName: String, log: org.slf4j.Logger): String? {
    val raw = System.getenv(varName)?.takeIf { it.isNotBlank() }
    if (raw == null) {
        log.info("maintenance.healthcheck: $varName not set — ping disabled for this channel")
        return null
    }
    val valid = validateHealthcheckUrl(raw)
    if (valid == null) {
        log.warn("maintenance.healthcheck: $varName rejected (not a https://hc-ping.com/... URL) — ping disabled for this channel")
        return null
    }
    return valid
}

/**
 * Fire-and-forget HTTP GET/POST to a Healthchecks.io ping URL.
 * Failures are logged but never rethrown — a missed ping is far less critical than
 * blocking the maintenance loop on a flaky outbound network.
 */
class HealthcheckPinger {
    private val log = LoggerFactory.getLogger("healthcheck")
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun ping(url: String, body: String? = null) {
        // Belt-and-suspenders. The boot-time validation already filtered the env
        // vars, but we re-check here so any future caller (DB-sourced URL, admin
        // panel, etc.) inherits the same outbound restriction.
        if (validateHealthcheckUrl(url) == null) {
            log.warn("healthcheck.ping_rejected: url not allowed by whitelist")
            return
        }
        try {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
            val request = if (body == null) {
                builder.GET().build()
            } else {
                builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
            }
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() !in 200..299) {
                log.warn("healthcheck.ping_unexpected_status: $url -> ${response.statusCode()}")
            }
        } catch (e: Exception) {
            log.warn("healthcheck.ping_failed: $url", e)
        }
    }
}
