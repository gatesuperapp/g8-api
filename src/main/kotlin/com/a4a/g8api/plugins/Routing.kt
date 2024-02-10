package com.a4a.g8api.plugins

import com.a4a.g8api.routes.*
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
        farmersProducts()
        authenticate()
    }

}
