package com.a4a.g8api.plugins

import com.a4a.g8api.database.IUsersService
import com.a4a.g8api.routes.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

//The get() function allows Koin to provide the dependencies at runtime
fun Application.configureRouting(usersService: IUsersService=get()) {
    routing {
        get("/") {
            call.respondText("Hello World!")
        }

        getUserById(usersService)
        createUser(usersService)
        getUsers(usersService)
        usersSubscriptions()
        authenticate(usersService)
    }

}
