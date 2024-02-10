package com.a4a.g8api.routes

import com.a4a.g8api.database.FarmerService
import com.a4a.g8api.models.Farmer
import com.a4a.g8api.models.ResponseBase
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.getFarmerById(){

    get("/api/farmer/{id}"){
        val id = call.parameters["id"]?.toInt() ?: return@get call.respondText(
            "Missing id",
            status = HttpStatusCode.BadRequest
        )

        val farmerService = FarmerService()
        val farmer = farmerService.farmerById(id) ?: return@get call.respond(
                status = HttpStatusCode.NotFound,
                ResponseBase("No farmer with id $id")
            )

        call.respond(HttpStatusCode.OK, farmer)
    }
}

fun Route.getFarmers(){
    get("/api/farmer"){
        val farmerService = FarmerService()
        call.respond(mapOf("farmers" to farmerService.allFarmers()))
    }
}

fun Route.createFarmer(){
    post("/api/farmer"){
        val farmer = call.receive<Farmer>()

        val farmerService = FarmerService()
        val id = farmerService.createFarmer(farmer)

        call.application.log.info("Farmer - Created a new farmer, welcome to ${farmer.firstName} ${farmer.lastName} with user id $id!")

        call.respondText("Account created", status = HttpStatusCode.Created)
    }
}