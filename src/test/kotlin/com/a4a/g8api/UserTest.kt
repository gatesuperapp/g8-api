package com.a4a.g8api

import com.a4a.g8api.models.User
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class UserTest {
    @Test
    fun farmerSignupSuccess() = testApplication {
        //Be cautious, we need the CLIENT version of contentnegotiation, not the server one 11
        val client = createClient {
            install(ContentNegotiation) {
                json()
            }
        }
        val response = client.post("/api/farmer") {
            contentType(ContentType.Application.Json)
            setBody(User(1, "Alice", "Fox", "afox@g8.org", "pwd"))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("Account created", response.bodyAsText())
    }
}