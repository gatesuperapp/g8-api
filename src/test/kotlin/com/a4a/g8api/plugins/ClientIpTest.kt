package com.a4a.g8api.plugins

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientIpTest {

    // ---- Untrusted peer: X-Forwarded-For is always ignored. -----------------

    @Test
    fun `untrusted peer with no X-F-F returns the peer`() {
        assertEquals("203.0.113.7", resolveClientIp("203.0.113.7", null))
    }

    @Test
    fun `untrusted peer ignores a spoofed X-F-F`() {
        // This is THE attack: client sends a fake X-F-F to spoof its IP.
        // If the immediate TCP peer is not our loopback proxy, we never honor it.
        assertEquals("203.0.113.7", resolveClientIp("203.0.113.7", "10.0.0.1"))
    }

    @Test
    fun `untrusted peer ignores even a well-formed multi-entry X-F-F`() {
        assertEquals("203.0.113.7", resolveClientIp("203.0.113.7", "1.1.1.1, 2.2.2.2"))
    }

    // ---- Trusted peer (loopback): X-F-F is honored, LAST entry wins. --------

    @Test
    fun `trusted IPv4 loopback peer with no X-F-F returns the peer itself`() {
        assertEquals("127.0.0.1", resolveClientIp("127.0.0.1", null))
    }

    @Test
    fun `trusted peer with single X-F-F entry returns that entry`() {
        // Matches nginx `proxy_set_header X-Forwarded-For $remote_addr` (overwrite).
        assertEquals("5.5.5.5", resolveClientIp("127.0.0.1", "5.5.5.5"))
    }

    @Test
    fun `trusted peer with multi-entry X-F-F returns the LAST entry`() {
        // Matches nginx `$proxy_add_x_forwarded_for` (append). The proxy appends
        // the real client IP at the end of whatever the client supplied.
        // Spoofed entries the client controls (1.1.1.1) are ignored.
        assertEquals("9.9.9.9", resolveClientIp("127.0.0.1", "1.1.1.1, 9.9.9.9"))
    }

    @Test
    fun `trusted peer with attacker spoofing many fake entries still returns the proxy's appended IP`() {
        // The original bypass attack: rotate the first entry, hope to hit a fresh
        // rate-limit bucket each time. With LAST-entry semantics, every request
        // buckets to the same real attacker IP — bypass dead.
        assertEquals(
            "203.0.113.42",
            resolveClientIp("127.0.0.1", "10.0.0.1, 10.0.0.2, 10.0.0.3, 203.0.113.42")
        )
    }

    @Test
    fun `trusted peer trims whitespace around X-F-F entries`() {
        assertEquals("9.9.9.9", resolveClientIp("127.0.0.1", "  1.1.1.1  ,   9.9.9.9  "))
    }

    @Test
    fun `trusted peer with empty X-F-F falls back to the peer`() {
        assertEquals("127.0.0.1", resolveClientIp("127.0.0.1", ""))
    }

    @Test
    fun `trusted peer with X-F-F containing only commas and whitespace falls back to the peer`() {
        assertEquals("127.0.0.1", resolveClientIp("127.0.0.1", ", , ,"))
    }

    @Test
    fun `IPv6 loopback shorthand is recognized as a trusted peer`() {
        assertEquals("5.5.5.5", resolveClientIp("::1", "5.5.5.5"))
    }

    @Test
    fun `IPv6 loopback expanded form is recognized as a trusted peer`() {
        assertEquals("5.5.5.5", resolveClientIp("0:0:0:0:0:0:0:1", "5.5.5.5"))
    }

    @Test
    fun `localhost hostname is recognized as a trusted peer`() {
        // Some Ktor request-origin paths can surface "localhost" as the peer string.
        assertEquals("5.5.5.5", resolveClientIp("localhost", "5.5.5.5"))
    }
}
