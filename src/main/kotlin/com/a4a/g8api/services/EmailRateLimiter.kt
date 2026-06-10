package com.a4a.g8api.services

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-recipient sliding-window rate limiter for magic-link emails.
 *
 * Enforces TWO limits on the same email address:
 * - [maxPerMinute] requests per [oneMinuteMillis] (anti-burst: stops accidental
 *   double-clicks and rapid retries from generating multiple emails)
 * - [maxPerHour] requests per [oneHourMillis] (overall ceiling against email
 *   bombing through rotated source IPs)
 *
 * Both limits must be satisfied for a request to be allowed. On overflow the
 * caller silently drops the email but still responds 204 — anti-enumeration.
 *
 * In-memory only — adequate while the API runs as a single instance.
 */
class EmailRateLimiter(
    private val maxPerMinute: Int = 1,
    private val maxPerHour: Int = 8,
    private val oneMinuteMillis: Long = 60_000L,
    private val oneHourMillis: Long = 3_600_000L,
) {
    private val timestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun tryAcquire(email: String, now: Long = System.currentTimeMillis()): Boolean {
        val key = email.lowercase().trim()
        val deque = timestamps.computeIfAbsent(key) { ArrayDeque() }
        synchronized(deque) {
            // Drop entries older than the longest window we care about
            val hourCutoff = now - oneHourMillis
            while (deque.isNotEmpty() && deque.peekFirst() < hourCutoff) {
                deque.pollFirst()
            }

            // Check 1-hour ceiling
            if (deque.size >= maxPerHour) return false

            // Check 1-minute burst limit
            val minuteCutoff = now - oneMinuteMillis
            val recent = deque.count { it >= minuteCutoff }
            if (recent >= maxPerMinute) return false

            deque.addLast(now)
            return true
        }
    }
}
