package com.a4a.g8api.routes

import com.a4a.g8api.database.IUsersService
import com.a4a.g8api.database.UsersService
import com.a4a.g8api.models.User
import com.a4a.g8api.models.ErrorResponse
import com.a4a.g8api.viewmodels.GetUserResponseViewModel
import com.a4a.g8api.viewmodels.SignupRequestViewModel
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.mindrot.jbcrypt.BCrypt

fun Route.getUserById(usersService: IUsersService){

    get("/api/user/{id}"){
        val id = call.parameters["id"]?.toInt() ?: return@get call.respondText(
            "Missing id",
            status = HttpStatusCode.BadRequest
        )

        val user = usersService.userById(id) ?: return@get call.respond(
                status = HttpStatusCode.NotFound,
                ErrorResponse("No user with id $id")
            )

        call.respond(HttpStatusCode.OK, GetUserResponseViewModel(user))
    }
}

fun Route.getUsers(usersService: IUsersService){
    get("/api/user"){
        call.respond(mapOf("users" to usersService.allUsers().map { GetUserResponseViewModel(it) }))
    }
}

fun Route.createUser(usersService: IUsersService){
    post("/api/signup"){
        val user = call.receive<SignupRequestViewModel>()

        //TODO : Hash the password - PBKDF2, BCrypt, and SCrypt are three recommended algorithms.
        //There seems to be no native kotlin implementation : https://slack-chats.kotlinlang.org/t/461016/which-is-the-recommended-library-for-hashing-passwords-in-ko
        //use jBcrypt : https://github.com/jeremyh/jBCrypt
        //or bcrypt-mpp : https://github.com/ToxicBakery/bcrypt-mpp (which is based on JBcrypt)


        val hash = BCrypt.hashpw(user.password, BCrypt.gensalt());
        user.password = hash
        val id = usersService.createUser(user.toModel())

        call.application.log.info("User - Created a new user, welcome to ${user.firstName} ${user.lastName} with user id $id!")

        call.respondText("Account created", status = HttpStatusCode.Created)
    }
}