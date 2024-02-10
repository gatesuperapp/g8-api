package com.a4a.g8api

import com.a4a.g8api.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.openapi.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*

//EngineMain provides more flexibility to configure a server. You can specify server parameters in a file and change a configuration without recompiling your application.
//The other option is by using the `embeddedServer` function. It's a simple way to configure server parameters in code and quickly run an application.
// More details https://ktor.io/docs/create-server.html#embedded
fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)


fun Application.module() {


    val runEnv = environment.config.property("ktor.environment").getString()
    log.info("Running G8-api in $runEnv!!")

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
