package com.a4a.g8api.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HealthcheckUrlValidatorTest {

    // ---- Accept ----------------------------------------------------------------

    @Test
    fun `accepts canonical hc-ping URL`() {
        assertNotNull(validateHealthcheckUrl("https://hc-ping.com/abc-123"))
    }

    @Test
    fun `accepts hc-ping URL with fail suffix (cleanup_failed path)`() {
        assertNotNull(validateHealthcheckUrl("https://hc-ping.com/abc-123/fail"))
    }

    @Test
    fun `accepts hc-ping with an explicit https port (rare but legal)`() {
        assertNotNull(validateHealthcheckUrl("https://hc-ping.com:443/abc-123"))
    }

    // ---- Reject: scheme --------------------------------------------------------

    @Test
    fun `rejects http (downgrade attack on outbound)`() {
        assertNull(validateHealthcheckUrl("http://hc-ping.com/abc-123"))
    }

    @Test
    fun `rejects file scheme`() {
        assertNull(validateHealthcheckUrl("file:///etc/passwd"))
    }

    @Test
    fun `rejects jar scheme`() {
        assertNull(validateHealthcheckUrl("jar:file:/tmp/x.jar!/META-INF/MANIFEST.MF"))
    }

    @Test
    fun `rejects ftp scheme`() {
        assertNull(validateHealthcheckUrl("ftp://hc-ping.com/abc-123"))
    }

    // ---- Reject: host ----------------------------------------------------------

    @Test
    fun `rejects evil host`() {
        assertNull(validateHealthcheckUrl("https://evil.example/abc-123"))
    }

    @Test
    fun `rejects lookalike host with hc-ping prefix in a malicious domain`() {
        // hc-ping.com.evil.example: the host is the LAST label set, so URI.host
        // returns the full string. Equality check against "hc-ping.com" fails.
        assertNull(validateHealthcheckUrl("https://hc-ping.com.evil.example/abc-123"))
    }

    @Test
    fun `rejects subdomain even if it ends in hc-ping-com`() {
        assertNull(validateHealthcheckUrl("https://evil.hc-ping.com.attacker.example/abc-123"))
    }

    @Test
    fun `rejects an IPv4 literal targeting cloud metadata`() {
        // AWS / DigitalOcean metadata endpoint. Host != hc-ping.com → reject.
        assertNull(validateHealthcheckUrl("https://169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun `rejects a private network IPv4 literal`() {
        assertNull(validateHealthcheckUrl("https://10.0.0.1/abc-123"))
    }

    @Test
    fun `rejects loopback`() {
        assertNull(validateHealthcheckUrl("https://127.0.0.1/abc-123"))
    }

    // ---- Reject: userinfo ------------------------------------------------------

    @Test
    fun `rejects URL with userinfo even on the right host`() {
        // https://attacker@hc-ping.com — userinfo could be abused in some
        // request-libraries that send the credentials in a Proxy-Authorization
        // header or similar. We never expect userinfo, refuse the whole URL.
        assertNull(validateHealthcheckUrl("https://attacker@hc-ping.com/abc-123"))
    }

    // ---- Reject: malformed / empty --------------------------------------------

    @Test
    fun `rejects null`() {
        assertNull(validateHealthcheckUrl(null))
    }

    @Test
    fun `rejects empty string`() {
        assertNull(validateHealthcheckUrl(""))
    }

    @Test
    fun `rejects whitespace-only`() {
        assertNull(validateHealthcheckUrl("   "))
    }

    @Test
    fun `rejects a syntactically invalid URI`() {
        assertNull(validateHealthcheckUrl("ht!tp://[::not a uri"))
    }

    @Test
    fun `rejects a non-absolute URI`() {
        assertNull(validateHealthcheckUrl("/abc-123"))
    }

    @Test
    fun `rejects a typo on the TLD`() {
        // hc-ping.con — classic operator typo. Host is exactly "hc-ping.con",
        // not the trusted host. Validator catches it.
        assertNull(validateHealthcheckUrl("https://hc-ping.con/abc-123"))
    }

    // ---- Echo ------------------------------------------------------------------

    @Test
    fun `returns the URL unchanged on success`() {
        val url = "https://hc-ping.com/abc-123"
        assertEquals(url, validateHealthcheckUrl(url))
    }
}
