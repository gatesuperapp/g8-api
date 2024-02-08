package com.a4a.g8api.models

import kotlinx.serialization.Serializable

@Serializable
data class Farmer(val id: String, val firstName: String, val lastName: String, val email: String, val password: String)

val farmerStorage = mutableListOf<Farmer>()