package com.a4a.g8api.routes

import com.a4a.g8api.models.Farmer
import com.a4a.g8api.models.farmerStorage
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
fun Route.getFarmerById(){
    get("/api/farmer/{id}"){
        val id = call.parameters["id"] ?: return@get call.respondText(
            "Missing id",
            status = HttpStatusCode.BadRequest
        )

        val farmer =
            farmerStorage.find { it.id == id.toInt() } ?: return@get call.respondText(
                "No farmer with id $id",
                status = HttpStatusCode.NotFound
            )

        call.respond(farmer)
    }
}

fun Route.getFarmers(){
    get("/api/farmer"){
        if (farmerStorage.isNotEmpty()) {
            call.respond(farmerStorage)
        } else {
            call.respondText("No farmers found", status = HttpStatusCode.NotFound)
        }
    }
}

fun Route.createFarmer(){
    post("/api/farmer"){
        val farmer = call.receive<Farmer>()
        farmerStorage.add(farmer)

        call.application.log.info("Farmer - Created a new farmer, welcome to ${farmer.firstName} ${farmer.lastName} !")

        call.respondText("Account created", status = HttpStatusCode.Created)
    }
}