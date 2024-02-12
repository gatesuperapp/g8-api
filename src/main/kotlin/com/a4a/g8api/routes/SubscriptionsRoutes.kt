package com.a4a.g8api.routes

import com.a4a.g8api.models.ErrorResponse
import com.a4a.g8api.models.Subscription
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock.System.now
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

fun Route.usersSubscriptions(){
    //Nesting the endpoint into the authenticate function enables the auth protection
    authenticate() {
        get("api/user/{id}/subscription") {

            //Get Authenticated user
            val principal = call.principal<JWTPrincipal>()
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("Please authenticate then retry.")
                )

            //TODO : Call Database or Stripe service
            val timeZone = TimeZone.of("Europe/Berlin")
            val fakeSubscription = Subscription(1,"free_plan", now().plus(1, DateTimeUnit.DAY, timeZone))

            //log.info only works from the Application function
            //in the modules we have to access the logger through the call object
            call.application.environment.log.info("Subscriptions - Returning subscription for User Id : ${principal.getClaim("id", Int::class)}")

            call.respond(fakeSubscription)
        }
    }
}