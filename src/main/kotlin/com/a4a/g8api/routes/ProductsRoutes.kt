package com.a4a.g8api.routes

import com.a4a.g8api.models.Product
import com.a4a.g8api.models.ResponseBase
import com.a4a.g8api.models.productStorage
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.util.*

fun Route.farmersProducts(){
    //Nesting the endpoint into the authenticate function enables the auth protection
    authenticate() {
        get("api/farmer/{id}/product") {

            //Get Authenticated user
            val principal = call.principal<JWTPrincipal>()
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    ResponseBase("Please authenticate then retry.")
                )

            //Deny access if subscription has expired
            //The expiration date should be fetched from the database instead
            if (principal.getClaim("subexpiration", Date::class)!! > Date.from(Instant.now())) {
                return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    ResponseBase("Your subscription has expired.")
                )
            }

            //log.info only works from the Application function
            //in the modules we have to access the logger through the call object
            call.application.environment.log.info("Products - Returning products for Farmer Id : ${principal.getClaim("id", Int::class)}")

            productStorage.add(Product(1,"Carrot", 999))

            call.respond(productStorage)
        }
    }
}