package com.a4a.g8api

import com.a4a.g8api.models.Farmer
import com.a4a.g8api.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlin.test.*


class   ApplicationTest {
    @Test
    fun testRoot() = testApplication {
        application {
            configureRouting()
        }
        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("Hello World!", bodyAsText())
        }
    }

    @Test
    fun testSignup() = testApplication {
        //Be cautious, we need the CLIENT version of contentnegotiation, not the server one 11
        val client = createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
        }
        val response = client.post("/api/farmer") {
            contentType(ContentType.Application.Json)
            setBody(Farmer(1, "Alice", "Fox", "afox@g8.org", "pwd"))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("Account created", response.bodyAsText())
    }
}
