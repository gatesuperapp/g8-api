package com.a4a.g8api.models

import kotlinx.serialization.Serializable

//Use to return specific errors to the client
@Serializable
data class ErrorResponse(val message: String)
