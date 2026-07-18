package com.a4a.g8api

import com.a4a.g8api.database.IMagicLinkService
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Integration tests covering the v1 auth surface.
 *
 * We hit the real HTTP endpoints (via [testApplication]) so that rate limiting,
 * security headers, JWT validation, and the routing layer are all exercised.
 * Email sending is no-op'd via the EMAIL_NOOP env var so the tests don't touch SMTP.
 *
 * To get a usable magic-link token (which is sent by email in production), tests
 * call MagicLinkService.createToken directly via Koin — that's the same call the
 * route handler makes internally, so we're testing the consume path with a valid
 * token without parsing emails.
 */
class AuthIntegrationTest {

    @Serializable
    private data class TokenPair(
        val authToken: String,
        val refreshToken: String,
        val userId: String? = null,
        val email: String? = null,
    )

    @Serializable private data class MagicLinkRequest(val email: String)
    @Serializable private data class ConsumeBody(val token: String)
    @Serializable private data class RefreshBody(val refreshToken: String)
    @Serializable private data class AccountResponse(val email: String, val subscription: SubscriptionDto? = null)
    @Serializable private data class SubscriptionDto(val status: String, val currentPeriodEnd: String, val plan: String)

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private suspend fun ApplicationTestBuilder.issueRawToken(email: String, purpose: String): String {
        // Touch the test client so the application is started and Koin is initialized
        startApplication()
        val service: IMagicLinkService = koinGet()
        return service.createToken(email, purpose)
    }

    private suspend fun ApplicationTestBuilder.signupAndConsume(email: String): TokenPair {
        val token = issueRawToken(email, "signup")
        val client = jsonClient()
        val response = client.post("/v1/auth/magic-link/consume") {
            contentType(ContentType.Application.Json)
            setBody(ConsumeBody(token))
        }
        assertEquals(HttpStatusCode.OK, response.status, "consume should succeed for fresh token")
        return response.body()
    }

    // -------------------------------------------------------------------------
    // Magic link request — anti-enumeration
    // -------------------------------------------------------------------------

    @Test
    fun `magic-link request returns 204 for an unknown email`() = integrationTest {
        val client = jsonClient()
        val response = client.post("/v1/auth/magic-link/request") {
            contentType(ContentType.Application.Json)
            setBody(MagicLinkRequest("nobody-${System.nanoTime()}@example.com"))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `magic-link request returns 204 for a malformed email`() = integrationTest {
        val client = jsonClient()
        val response = client.post("/v1/auth/magic-link/request") {
            contentType(ContentType.Application.Json)
            setBody(MagicLinkRequest("not-an-email"))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `magic-link request returns 204 for an existing email too`() = integrationTest {
        // First create a user via signup
        signupAndConsume("existing@example.com")
        // Now requesting again must still respond 204 (no enumeration leak)
        val client = jsonClient()
        val response = client.post("/v1/auth/magic-link/request") {
            contentType(ContentType.Application.Json)
            setBody(MagicLinkRequest("existing@example.com"))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    // -------------------------------------------------------------------------
    // Magic link consume — happy path + edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `consume with a fresh signup token creates a user and returns tokens`() = integrationTest {
        val tokens = signupAndConsume("new-user@example.com")
        assertTrue(tokens.authToken.isNotBlank(), "JWT must be returned")
        assertTrue(tokens.refreshToken.isNotBlank(), "refresh token must be returned")
        assertEquals("new-user@example.com", tokens.email)
    }

    @Test
    fun `consume with an unknown token returns 401`() = integrationTest {
        val client = jsonClient()
        val response = client.post("/v1/auth/magic-link/consume") {
            contentType(ContentType.Application.Json)
            setBody(ConsumeBody("totally-fake-token"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `consume rejects a token that was already consumed`() = integrationTest {
        val token = issueRawToken("once@example.com", "signup")
        val client = jsonClient()

        val first = client.post("/v1/auth/magic-link/consume") {
            contentType(ContentType.Application.Json)
            setBody(ConsumeBody(token))
        }
        assertEquals(HttpStatusCode.OK, first.status)

        val second = client.post("/v1/auth/magic-link/consume") {
            contentType(ContentType.Application.Json)
            setBody(ConsumeBody(token))
        }
        assertEquals(HttpStatusCode.Unauthorized, second.status, "single-use token must be rejected on replay")
    }

    // -------------------------------------------------------------------------
    // Refresh token — rotation + replay detection
    // -------------------------------------------------------------------------

    @Test
    fun `refresh rotates tokens and returns a fresh JWT`() = integrationTest {
        val tokens = signupAndConsume("refresher@example.com")
        val client = jsonClient()

        val refreshed: TokenPair = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshBody(tokens.refreshToken))
        }.body()

        assertNotEquals(tokens.refreshToken, refreshed.refreshToken, "refresh token must rotate")
        assertTrue(refreshed.authToken.isNotBlank())
    }

    @Test
    fun `refresh with an unknown token returns 401`() = integrationTest {
        val client = jsonClient()
        val response = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshBody("nonexistent-refresh"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `replaying a revoked refresh token returns 401 and revokes all sessions`() = integrationTest {
        val tokens = signupAndConsume("replay-victim@example.com")
        val client = jsonClient()

        // Rotate once — now the original refresh token is revoked
        val rotated: TokenPair = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshBody(tokens.refreshToken))
        }.body()

        // Reuse the OLD refresh token — replay detection kicks in
        val replay = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshBody(tokens.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)

        // The "rotated" token from the legitimate refresh should now ALSO be dead
        // (revoke-all-sessions kicked in).
        val afterRevocation = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshBody(rotated.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, afterRevocation.status,
            "after replay detection, even the freshly-rotated token must be unusable")
    }

    // -------------------------------------------------------------------------
    // /v1/account — JWT-protected endpoint
    // -------------------------------------------------------------------------

    @Test
    fun `account without bearer returns 401`() = integrationTest {
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/account").status)
    }

    @Test
    fun `account with garbage bearer returns 401`() = integrationTest {
        val client = jsonClient()
        val response = client.get("/v1/account") {
            header(HttpHeaders.Authorization, "Bearer not-a-jwt")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `account with malformed Authorization header returns 401 not 500`() = integrationTest {
        // Ktor 3's parseAuthorizationHeader throws BadRequestException on values
        // that don't match RFC 7235 (e.g. a stray comma from a shell copy-paste).
        // StatusPages must convert that to 401, not let it propagate as 500.
        val client = jsonClient()
        val response = client.get("/v1/account") {
            header(HttpHeaders.Authorization, "Bearer eyJ.malformed,extra=stuff")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `account with valid JWT returns the user email`() = integrationTest {
        val tokens = signupAndConsume("accountuser@example.com")
        val client = jsonClient()

        val response = client.get("/v1/account") {
            header(HttpHeaders.Authorization, "Bearer ${tokens.authToken}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val account: AccountResponse = response.body()
        assertEquals("accountuser@example.com", account.email)
        assertEquals(null, account.subscription, "no subscription yet")
    }

    // -------------------------------------------------------------------------
    // Security headers
    // -------------------------------------------------------------------------

    @Test
    fun `security headers are present on every response`() = integrationTest {
        val client = jsonClient()
        val response = client.get("/v1/health")
        assertNotNull(response.headers["Strict-Transport-Security"], "HSTS header missing")
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertNotNull(response.headers["Content-Security-Policy"])
        assertEquals("g8-api", response.headers["Server"], "Server header must not leak Ktor version")
    }

    // -------------------------------------------------------------------------
    // Rate limit per email — request endpoint stays well-behaved under burst
    // (the actual cap behavior is unit-tested in EmailRateLimiterTest)
    // -------------------------------------------------------------------------

    @Test
    fun `magic-link request endpoint stays 204 even under burst (silent drop)`() = integrationTest {
        val client = jsonClient()
        val email = "burst@example.com"
        repeat(20) {
            val response = client.post("/v1/auth/magic-link/request") {
                contentType(ContentType.Application.Json)
                setBody(MagicLinkRequest(email))
            }
            assertEquals(
                HttpStatusCode.NoContent,
                response.status,
                "request endpoint must always 204 — anti-enumeration even when rate-limited"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Rate limit on /consume — anti brute-force / DoS / detection signal
    // -------------------------------------------------------------------------

    @Test
    fun `consume endpoint returns 429 after 20 attempts in the same minute`() = integrationTest {
        startApplication()
        val client = jsonClient()

        // Burn the bucket with 20 bogus tokens — each must come back as 401
        repeat(20) {
            val response = client.post("/v1/auth/magic-link/consume") {
                contentType(ContentType.Application.Json)
                setBody(ConsumeBody("bogus-token-$it"))
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "bogus token attempt #$it should be 401 before the bucket runs out"
            )
        }

        // 21st attempt must be rate-limited (429), even with a token that would otherwise be valid
        val service: IMagicLinkService = koinGet()
        val realToken = service.createToken("victim@example.com", "signup")
        val response = client.post("/v1/auth/magic-link/consume") {
            contentType(ContentType.Application.Json)
            setBody(ConsumeBody(realToken))
        }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            response.status,
            "consume must be rate-limited once 20/min/IP is exceeded — even a valid token must not pass"
        )
    }

    // -------------------------------------------------------------------------
    // StatusPages — unhandled exceptions must NOT leak class names, messages,
    // or stack traces into the response body. The client only sees a sanitized
    // 500 with a correlation id; the full trace stays in the server logs.
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Rate limit anti-spoofing — rotating the first X-Forwarded-For entry must
    // NOT bypass the per-IP limit. Bucketing is anchored on the LAST entry, which
    // in prod is appended by our reverse proxy.
    // -------------------------------------------------------------------------

    @Test
    fun `refresh rate limit cannot be bypassed by rotating the first X-Forwarded-For entry`() = integrationTest {
        val client = jsonClient()

        // Burn the bucket with 10 attempts whose LAST X-F-F entry is constant.
        // Each request has a different FIRST entry — that would have given a fresh
        // bucket under the old "trust first entry" behavior.
        repeat(10) { i ->
            val response = client.post("/v1/auth/refresh") {
                contentType(ContentType.Application.Json)
                header("X-Forwarded-For", "10.0.0.$i, 1.2.3.4")
                setBody(RefreshBody("never-existed-$i"))
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "attempt #$i with rotating first X-F-F must still hit the same bucket and return 401, not bypass"
            )
        }

        // 11th attempt with another rotated first entry, but SAME real last entry
        // (1.2.3.4) → must be rate-limited, not 401.
        val blocked = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            header("X-Forwarded-For", "10.0.0.99, 1.2.3.4")
            setBody(RefreshBody("never-existed-blocked"))
        }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            blocked.status,
            "spoofing the first X-F-F entry must not yield a fresh rate-limit bucket"
        )

        // Sanity check: a genuinely different LAST entry IS its own bucket.
        // This is the legitimate scenario where two real clients sit behind the
        // same proxy — they must each have their own rate-limit allowance.
        val differentClient = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            header("X-Forwarded-For", "9.9.9.9")
            setBody(RefreshBody("never-existed-different-client"))
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            differentClient.status,
            "a different real client (different last X-F-F entry) must have its own bucket"
        )
    }

    @Test
    fun `unhandled exception returns sanitized 500 with no secret leakage in body`() = integrationTest(
        extraRouting = {
            get("/_test/boom") {
                throw IllegalStateException("sk_test_super_secret_leak_canary_AbCd1234")
            }
        }
    ) {
        val client = jsonClient()
        val response = client.get("/_test/boom")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()

        assertFalse(
            body.contains("sk_test_super_secret_leak_canary"),
            "the exception message must never leak into the response body — found in: $body"
        )
        assertFalse(
            body.contains("IllegalStateException"),
            "the exception class name must never leak — found in: $body"
        )
        assertFalse(
            body.contains("\tat ") || body.contains("    at "),
            "no stack trace lines must leak — found in: $body"
        )
        assertTrue(
            body.contains("id="),
            "response must include an error correlation id so logs can be traced — got: $body"
        )
    }
}
