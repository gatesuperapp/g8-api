package com.a4a.g8api.routes

import com.a4a.g8api.models.farmerStorage
import com.a4a.g8api.models.productStorage
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.getProductsOfFarmer(){
    get("api/farmer/{id}/product"){
        call.respond(productStorage)
    }
}