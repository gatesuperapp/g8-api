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

        val jwtAudience = this@authenticate.environment!!.config.property("jwt.audience").getString()
        val jwtIssuer = this@authenticate.environment!!.config.property("jwt.issuer").getString()
        val jwtSecret = this@authenticate.environment!!.config.property("jwt.secret").getString()

        val token = JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("username", farmer.email)
            .withClaim("firstname", farmer.firstName)
            .withClaim("lastname", farmer.lastName)
            .withClaim("id", farmer.id)
            //Subscription expires in 1 day
            .withClaim("subexpiration", now().plusSeconds(3600*24))
            //Token expires in 1 hour
            .withExpiresAt(now().plusSeconds(3600))
            .sign(Algorithm.HMAC256(jwtSecret))
        call.respond(hashMapOf("token" to token))

    }
}