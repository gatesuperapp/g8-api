package com.a4a.g8api.viewmodels

import com.a4a.g8api.models.User
import kotlinx.serialization.Serializable

@Serializable
class SignupRequestViewModel {
    var firstName: String = ""
    var lastName: String = ""
    var email: String = ""
    var password: String = ""

    fun toModel(): User {
        return User(0, firstName, lastName, email, password)
    }

}