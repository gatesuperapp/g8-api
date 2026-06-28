package com.a4a.g8api.plugins

import com.a4a.g8api.integrationTest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Verifies the [configureMaxRequestSize] plugin caps Content-Length and that
 * the cap kicks in before the route handler / serializer ever sees the body.
 *
 * Uses POST /v1/auth/magic-link/request as a target — any route with a body
 * would do, this one is the smallest legitimate use case.
 */
class MaxRequestSizeTest {

    private val target = "/v1/auth/magic-link/request"

    @Test
    fun `request just under 64 KB is accepted`() = integrationTest {
        // 60 KB body — small enough to pass the cap. Field name is bogus so the
        // route returns 204 (anti-enumeration) without doing anything useful;
        // we only care about the status NOT being 413.
        val body = """{"junk":"${"a".repeat(60 * 1024)}"}"""
        val response = client.post(target) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertNotEquals(
            HttpStatusCode.PayloadTooLarge,
            response.status,
            "body under cap must not trigger 413",
        )
    }

    @Test
    fun `request over 64 KB returns 413`() = integrationTest {
        // 100 KB JSON body — well over the 64 KB cap.
        val body = """{"junk":"${"a".repeat(100 * 1024)}"}"""
        val response = client.post(target) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

}
