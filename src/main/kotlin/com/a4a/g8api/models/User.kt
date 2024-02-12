package com.a4a.g8api.models

import kotlinx.serialization.Serializable

@Serializable
data class User(val id: Int, val firstName: String, val lastName: String, val email: String, val password: String)

