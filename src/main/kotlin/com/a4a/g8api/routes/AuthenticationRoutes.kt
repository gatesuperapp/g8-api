package com.a4a.g8api.routes

import com.a4a.g8api.database.IUsersService
import com.a4a.g8api.database.UsersService
import com.a4a.g8api.models.AuthRequest
import com.a4a.g8api.models.ErrorResponse
import com.a4a.g8api.viewmodels.SignInResponseViewModel
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.mindrot.jbcrypt.BCrypt

import java.time.Instant.now

fun Route.authenticate(usersService: IUsersService){
    post("api/login"){
        val request = call.receive<AuthRequest>()

        if(request.email.isEmpty() || request.password.isEmpty()){
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Email and password are required!"))
        }

        val user = usersService.userByEmail (request.email) ?: return@post call.respondText(
            "Wrong email or password",
            status = HttpStatusCode.BadRequest
        )

        if(!BCrypt.checkpw(request.password, user.password)){
            return@post call.respondText(
                "Wrong email or password",
                status = HttpStatusCode.BadRequest
            )
        }

        val jwtAudience = this@authenticate.environment!!.config.property("jwt.audience").getString()
        val jwtIssuer = this@authenticate.environment!!.config.property("jwt.issuer").getString()
        val jwtSecret = this@authenticate.environment!!.config.property("jwt.secret").getString()

        val token = JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("id", user.id)
            //Token expires in 1 hour
            .withExpiresAt(now().plusSeconds(3600))
            .sign(Algorithm.HMAC256(jwtSecret))

        //Generate refresh token
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        val refreshToken = (1..30)
            .map { allowedChars.random() }
            .joinToString("")

        call.respond(SignInResponseViewModel(token, refreshToken))
    }

}