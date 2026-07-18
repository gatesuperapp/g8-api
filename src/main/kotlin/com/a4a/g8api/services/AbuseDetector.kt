package com.a4a.g8api.services

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects "same JWT hitting from many distinct IPs" — the residual gap left by
 * the Ktor 3 rate-limit fallback. When [RateLimit] can no longer key on user_id
 * (Ktor 3 fires the interceptor before Authentication populates the principal),
 * an attacker with a stolen JWT + a proxy pool could evade a per-IP throttle.
 *
 * We track (user_id, ip) hits in a bounded in-memory sliding window and expose
 * [suspiciousUsers] for the maintenance loop to poll. Purely in-memory: fine
 * for detection (attacks are ongoing, not archival), and it resets at every
 * deploy without operational fuss.
 *
 * Thread-safe: called from every authenticated route handler concurrently.
 */
class AbuseDetector(
    private val windowMs: Long = 15 * 60 * 1000L,
    private val ipThreshold: Int = 5,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // userId -> (ip -> lastSeenMs)
    private val hits = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>>()

    fun recordHit(userId: UUID, ip: String) {
        val now = clock()
        val ipMap = hits.computeIfAbsent(userId) { ConcurrentHashMap() }
        ipMap[ip] = now
        // Opportunistic prune of the *just-touched* user's stale IPs. A full sweep
        // is cheap enough at [suspiciousUsers] time; per-hit we only touch this user.
        ipMap.entries.removeIf { now - it.value > windowMs }
        if (ipMap.isEmpty()) hits.remove(userId, ipMap)
    }

    /**
     * Snapshot of users currently over the IP-diversity threshold. Also prunes
     * empty entries — cheap way to keep [hits] from growing forever with
     * long-idle user_ids.
     */
    fun suspiciousUsers(): List<Suspect> {
        val now = clock()
        val out = mutableListOf<Suspect>()
        hits.entries.removeIf { (uid, ipMap) ->
            ipMap.entries.removeIf { now - it.value > windowMs }
            if (ipMap.size >= ipThreshold) {
                out += Suspect(uid, ipMap.size)
            }
            ipMap.isEmpty()
        }
        return out
    }

    data class Suspect(val userId: UUID, val ipCount: Int)
}
