package com.a4a.g8api.services

import kotlin.test.Test
import kotlin.test.assertEquals

class TruncateIpTest {

    // ---- IPv4 → /24 ---------------------------------------------------------

    @Test
    fun `IPv4 keeps the first 3 octets and zeroes the last`() {
        assertEquals("82.65.123.0", truncateIp("82.65.123.45"))
    }

    @Test
    fun `IPv4 with last octet already 0 is idempotent`() {
        assertEquals("82.65.123.0", truncateIp("82.65.123.0"))
    }

    @Test
    fun `IPv4 edge values stay in range`() {
        assertEquals("0.0.0.0", truncateIp("0.0.0.0"))
        assertEquals("255.255.255.0", truncateIp("255.255.255.255"))
    }

    // ---- IPv6 → /48 ---------------------------------------------------------

    @Test
    fun `IPv6 keeps the first 3 hextets and zeroes the rest`() {
        // 2001:db8:1234:5678::1 → 6 bytes preserved → "2001:db8:1234::"
        assertEquals("2001:db8:1234:0:0:0:0:0", truncateIp("2001:db8:1234:5678::1"))
    }

    @Test
    fun `IPv6 abbreviated leading zeros (loopback) collapses to unspecified`() {
        // ::1 has no significant prefix in /48 — anonymized down to the unspecified address.
        assertEquals("0:0:0:0:0:0:0:0", truncateIp("::1"))
    }

    @Test
    fun `IPv6 fully expanded form is handled the same as abbreviated`() {
        assertEquals(
            truncateIp("2001:0db8:1234:5678:0000:0000:0000:0001"),
            truncateIp("2001:db8:1234:5678::1")
        )
    }

    // ---- Garbage in → "unknown" --------------------------------------------

    @Test
    fun `blank input returns unknown`() {
        assertEquals("unknown", truncateIp(""))
        assertEquals("unknown", truncateIp("   "))
    }

    @Test
    fun `IPv4 with too few or too many octets returns unknown`() {
        assertEquals("unknown", truncateIp("82.65.123"))
        assertEquals("unknown", truncateIp("82.65.123.45.99"))
    }

    @Test
    fun `IPv4 with out-of-range octet returns unknown`() {
        assertEquals("unknown", truncateIp("82.65.123.999"))
        assertEquals("unknown", truncateIp("82.65.123.-1"))
    }

    @Test
    fun `IPv4 with non-numeric octet returns unknown`() {
        assertEquals("unknown", truncateIp("82.65.abc.45"))
    }

    @Test
    fun `malformed IPv6 returns unknown`() {
        assertEquals("unknown", truncateIp("::g"))
        assertEquals("unknown", truncateIp("2001:db8::xyz"))
    }
}
