package com.a4a.g8api.viewmodels

import com.a4a.g8api.models.User
import kotlinx.serialization.Serializable

@Serializable
class GetUserResponseViewModel {
    constructor(user: User){
        id = user.id
        firstName = user.firstName
        lastName = user.lastName
        email = user.email
    }

    var id: Int = 0
    var firstName: String = ""
    var lastName: String = ""
    var email: String = ""
}