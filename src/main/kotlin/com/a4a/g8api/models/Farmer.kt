package com.a4a.g8api.models

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table

@Serializable
data class Farmer(val id: Int, val firstName: String, val lastName: String, val email: String, val password: String)

val farmerStorage = mutableListOf<Farmer>()

