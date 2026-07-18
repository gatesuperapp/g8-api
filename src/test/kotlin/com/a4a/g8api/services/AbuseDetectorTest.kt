package com.a4a.g8api.services

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AbuseDetectorTest {

    private class MutableClock(var nowMs: Long = 0L) {
        fun advance(ms: Long) { nowMs += ms }
        fun get(): Long = nowMs
    }

    private fun newDetector(
        clock: MutableClock = MutableClock(),
        windowMs: Long = 15 * 60 * 1000L,
        ipThreshold: Int = 5,
    ) = AbuseDetector(windowMs = windowMs, ipThreshold = ipThreshold, clock = clock::get)

    @Test
    fun `single user single ip is never suspect`() {
        val detector = newDetector()
        val uid = UUID.randomUUID()
        repeat(50) { detector.recordHit(uid, "1.2.3.4") }
        assertTrue(detector.suspiciousUsers().isEmpty())
    }

    @Test
    fun `user hitting from few ips is not suspect below threshold`() {
        val detector = newDetector(ipThreshold = 5)
        val uid = UUID.randomUUID()
        listOf("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4").forEach {
            detector.recordHit(uid, it)
        }
        assertTrue(detector.suspiciousUsers().isEmpty(), "4 IPs must not trip a threshold=5 detector")
    }

    @Test
    fun `user at or over threshold is flagged with the correct ip count`() {
        val detector = newDetector(ipThreshold = 5)
        val uid = UUID.randomUUID()
        (1..5).forEach { detector.recordHit(uid, "10.0.0.$it") }
        val suspects = detector.suspiciousUsers()
        assertEquals(1, suspects.size)
        assertEquals(uid, suspects[0].userId)
        assertEquals(5, suspects[0].ipCount)
    }

    @Test
    fun `ips older than the window are pruned and no longer count`() {
        val clock = MutableClock()
        val detector = newDetector(clock = clock, windowMs = 60_000L, ipThreshold = 3)
        val uid = UUID.randomUUID()
        // Fire 3 hits, then let them all age past the 60s window before the 4th.
        detector.recordHit(uid, "1.1.1.1")
        detector.recordHit(uid, "2.2.2.2")
        detector.recordHit(uid, "3.3.3.3")
        clock.advance(120_000) // all three IPs now 120s old — past the window
        detector.recordHit(uid, "4.4.4.4")
        // Only 4.4.4.4 remains within the window → below threshold=3.
        val suspects = detector.suspiciousUsers()
        assertTrue(suspects.isEmpty(), "expected pruning to drop us below threshold=3 — got $suspects")
    }

    @Test
    fun `distinct users are tracked independently`() {
        val detector = newDetector(ipThreshold = 5)
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        (1..6).forEach { detector.recordHit(alice, "10.0.0.$it") }
        detector.recordHit(bob, "192.168.1.1")
        val suspects = detector.suspiciousUsers().associate { it.userId to it.ipCount }
        assertEquals(mapOf(alice to 6), suspects)
    }

    @Test
    fun `re-hitting the same ip refreshes its timestamp instead of double-counting`() {
        val clock = MutableClock()
        val detector = newDetector(clock = clock, windowMs = 60_000L, ipThreshold = 3)
        val uid = UUID.randomUUID()
        detector.recordHit(uid, "1.1.1.1"); clock.advance(30_000)
        detector.recordHit(uid, "2.2.2.2"); clock.advance(30_000)
        // Refresh 1.1.1.1 just before it would age out — it must not be double-counted.
        detector.recordHit(uid, "1.1.1.1"); clock.advance(30_000)
        // Now 2.2.2.2 is 60s old (out), 1.1.1.1 is 30s old (in). Only 1 unique IP left.
        assertTrue(detector.suspiciousUsers().isEmpty())
    }
}
