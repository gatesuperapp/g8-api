package com.a4a.g8api.routes


import com.a4a.g8api.models.AuthRequest
import com.a4a.g8api.models.ResponseBase
import com.a4a.g8api.models.farmerStorage
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.http.HttpHeaders.Date
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant.now

fun Route.authenticate(){
    post("api/auth"){
        val request = call.receive<AuthRequest>()

        if(request.email.isEmpty() || request.password.isEmpty()){
            call.respond(HttpStatusCode.BadRequest, ResponseBase("Email and password are required!"))
        }

        val farmer =
            farmerStorage.find { it.email == request.email && it.password == request.password } ?: return@post call.respondText(
                "Wrong email or password",
                status = HttpStatusCode.BadRequest
            )

        val token = JWT.create()
            .withAudience("g8")
            .withIssuer("g8-api")
            .withClaim("username", farmer.email)
            .withClaim("firstname", farmer.firstName)
            .withClaim("lastname", farmer.lastName)
            .withClaim("id", farmer.id)
            .withClaim("subexpiration", now().plusSeconds(3600*24))
            .withExpiresAt(now().plusSeconds(3600))
            .sign(Algorithm.HMAC256("soon-to-be-secret"))
        call.respond(hashMapOf("token" to token))

    }
}