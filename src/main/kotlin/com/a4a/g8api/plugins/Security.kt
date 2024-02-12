package com.a4a.g8api.plugins

import com.a4a.g8api.models.ErrorResponse
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

fun Application.configureSecurity() {
    //Using 'property' gets the property or fails
    val jwtAudience = environment.config.property("jwt.audience").getString()
    val jwtIssuer = environment.config.property("jwt.issuer").getString()
    val jwtRealm = environment.config.property("jwt.realm").getString()
    val jwtSecret = environment.config.property("jwt.secret").getString()

    authentication {
        jwt {
            realm = jwtRealm
            // The verifier checks if the token is well-formed and not expired
            // https://www.javadoc.io/doc/com.auth0/java-jwt/latest/com/auth0/jwt/JWTVerifier.html
            verifier(
                JWT
                    .require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build()
            )
            //Validates allows  to perform additional validations on the JWT payload
            validate { credential ->
                if (credential.payload.getClaim("id").asInt() > 0) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            // Challenge configure a response to be sent if authentication fails
            challenge { defaultScheme, realm ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Token is not valid or has expired"))
            }
        }
    }
}