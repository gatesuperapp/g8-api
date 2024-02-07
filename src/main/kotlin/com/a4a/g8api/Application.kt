package com.a4a.g8api

import com.a4a.g8api.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.openapi.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureSecurity()
    configureSerialization()
    configureHTTP()
    configureRouting()

    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")

        //This is needed to explicitly regenerate the open api documentation
        openAPI(path="openapi", swaggerFile = "openapi/documentation.yaml") {
            //codegen = StaticHtmlCodegen()
        }
    }


}
