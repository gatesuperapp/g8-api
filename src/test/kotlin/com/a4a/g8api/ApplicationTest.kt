package com.a4a.g8api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.test.*

class ApplicationTest {
    @Test
    fun testRoot() = integrationTest(
        extraRouting = {
            get("/") {
                call.respondText("g8-api OK")
            }
        }
    ) {
        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("g8-api OK", bodyAsText())
        }
    }
}
