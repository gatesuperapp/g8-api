package com.a4a.g8api.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Hello World!")
        }

        get("/{name}") {
            val p = call.parameters["name"]
            call.respondText("Hello $p!")
        }
    }
}
