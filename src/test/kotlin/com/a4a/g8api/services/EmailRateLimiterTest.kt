package com.a4a.g8api.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests for [EmailRateLimiter]. We pass a synthetic `now` so the test
 * doesn't depend on wall-clock time — moves much faster, deterministic.
 */
class EmailRateLimiterTest {

    @Test
    fun `first call is always allowed`() {
        val limiter = EmailRateLimiter()
        assertTrue(limiter.tryAcquire("a@b.com", now = 0))
    }

    @Test
    fun `the second call within one minute is rejected`() {
        val limiter = EmailRateLimiter()
        assertTrue(limiter.tryAcquire("a@b.com", now = 0))
        assertFalse(
            limiter.tryAcquire("a@b.com", now = 30_000),
            "1/min rule must reject a second request 30s after the first"
        )
    }

    @Test
    fun `a request more than one minute later passes`() {
        val limiter = EmailRateLimiter()
        assertTrue(limiter.tryAcquire("a@b.com", now = 0))
        assertTrue(
            limiter.tryAcquire("a@b.com", now = 61_000),
            "after 61s the 1/min slot must free up"
        )
    }

    @Test
    fun `at most 8 requests can succeed in one hour`() {
        val limiter = EmailRateLimiter()
        // Space requests just past the 1-min burst limit so the per-minute rule
        // doesn't fire. We expect exactly 8 successes, then rejection.
        var successes = 0
        repeat(20) { i ->
            val now = i.toLong() * 65_000 // 65s spacing
            if (limiter.tryAcquire("a@b.com", now = now)) successes++
        }
        // The first 8 within 8*65s = 520s pass; #9 onwards would still pass under the
        // 1-min rule but we're capped at 8/h → rejected. Around index 55+ (3600s+) the
        // first one rolls off the hour window and we can pass again — but that's beyond
        // our 20 iterations, so we expect exactly 8.
        assertTrue(successes <= 8, "must cap at 8/h — got $successes")
        assertTrue(successes >= 8, "must allow at least 8 within the first hour — got $successes")
    }

    @Test
    fun `rate limit is per-email, not global`() {
        val limiter = EmailRateLimiter()
        assertTrue(limiter.tryAcquire("alice@x.com", now = 0))
        assertTrue(
            limiter.tryAcquire("bob@x.com", now = 0),
            "different recipient must not share the bucket with another"
        )
    }

    @Test
    fun `email is normalized when keying the limiter`() {
        val limiter = EmailRateLimiter()
        assertTrue(limiter.tryAcquire("Alice@X.COM", now = 0))
        assertFalse(
            limiter.tryAcquire("alice@x.com", now = 30_000),
            "uppercase variant must be treated as the same recipient"
        )
    }
}
