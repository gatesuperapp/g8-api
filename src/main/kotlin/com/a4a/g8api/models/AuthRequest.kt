package com.a4a.g8api.models

import kotlinx.serialization.Serializable

@Serializable
data class AuthRequest(val email: String, val password: String)
