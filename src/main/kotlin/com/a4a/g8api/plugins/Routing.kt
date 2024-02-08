package com.a4a.g8api.plugins

import com.a4a.g8api.routes.authenticate
import com.a4a.g8api.routes.createFarmer
import com.a4a.g8api.routes.getFarmerById
import com.a4a.g8api.routes.getFarmers
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Hello World!")
        }

        getFarmerById()
        createFarmer()
        getFarmers()
        authenticate()
    }

}
