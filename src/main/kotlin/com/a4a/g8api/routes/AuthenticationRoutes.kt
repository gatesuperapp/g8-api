package com.a4a.g8api.routes

import com.a4a.g8api.database.IUsersService
import com.a4a.g8api.database.UsersService
import com.a4a.g8api.models.AuthRequest
import com.a4a.g8api.models.ErrorResponse
import com.a4a.g8api.models.RefreshToken
import com.a4a.g8api.viewmodels.RefreshTokenRequestViewModel
import com.a4a.g8api.viewmodels.SignInResponseViewModel
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.*
import org.mindrot.jbcrypt.BCrypt

import java.time.Instant.now

fun Route.authenticate(usersService: IUsersService){
    post("api/authentication/signin"){
        val request = call.receive<AuthRequest>()

        if(request.email.isEmpty() || request.password.isEmpty()){
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Email and password are required!"))
        }

        val user = usersService.userByEmail (request.email) ?: return@post call.respond(
            status = HttpStatusCode.Forbidden,
            ErrorResponse("Wrong credentials")
        )

        if(!BCrypt.checkpw(request.password, user.password)){
            return@post call.respond(
                status = HttpStatusCode.Forbidden,
                ErrorResponse("Wrong credentials")
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
        val refreshToken = (1..60)
            .map { allowedChars.random() }
            .joinToString("")

        Clock.System.now()
        //Save refresh token
        val expiresAt: Instant = Clock.System.now().plus(1, DateTimeUnit.DAY, TimeZone.UTC)
        val dateTimeExpiresAt: LocalDateTime = expiresAt.toLocalDateTime(TimeZone.UTC)

        //TODO : Hash the refresh token to store it securely : https://stackoverflow.com/a/73993329
        usersService.saveRefreshToken(RefreshToken(0, refreshToken, dateTimeExpiresAt, user.id))

        call.respond(SignInResponseViewModel(token, refreshToken))
    }

}

fun Route.refreshAuthenticationToken(usersService: IUsersService){
    post("api/authentication/refresh"){
        val request = call.receive<RefreshTokenRequestViewModel>()

        val token = usersService.refreshTokenByToken(request.refreshToken)?: return@post call.respond(
            status = HttpStatusCode.Forbidden,
            ErrorResponse("Token is invalid")
        )

        if(token.expiresAt < Clock.System.now().toLocalDateTime(TimeZone.UTC)){
            return@post call.respond(
                status = HttpStatusCode.Forbidden,
                ErrorResponse("Token is invalid")
            )
        }

        //Generate and store new token
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        val refreshToken = (1..60)
            .map { allowedChars.random() }
            .joinToString("")

        Clock.System.now()
        //Save refresh token
        val expiresAt: Instant = Clock.System.now().plus(1, DateTimeUnit.DAY, TimeZone.UTC)
        val dateTimeExpiresAt: LocalDateTime = expiresAt.toLocalDateTime(TimeZone.UTC)

        //TODO : Hash the refresh token to store it securely : https://stackoverflow.com/a/73993329
        usersService.saveRefreshToken(RefreshToken(0, refreshToken, dateTimeExpiresAt, token.userId))


        //Generate new auth token
        val jwtAudience = this@refreshAuthenticationToken.environment!!.config.property("jwt.audience").getString()
        val jwtIssuer = this@refreshAuthenticationToken.environment!!.config.property("jwt.issuer").getString()
        val jwtSecret = this@refreshAuthenticationToken.environment!!.config.property("jwt.secret").getString()

        val authToken = JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("id", token.userId)
            //Token expires in 1 hour
            .withExpiresAt(now().plusSeconds(3600))
            .sign(Algorithm.HMAC256(jwtSecret))


        usersService.deleteRefreshToken(token.id)

        call.respond(SignInResponseViewModel(authToken, refreshToken))

    }

    post("api/authentication/recovery"){
        call.respond(HttpStatusCode.OK)
    }


    post("api/authentication/recovery-confirmation"){
        call.respond(HttpStatusCode.OK)
    }
}