package com.a4a.g8api.viewmodels

import kotlinx.serialization.Serializable


@Serializable
data class RefreshTokenResponseViewModel(val authToken: String, val refreshToken: String)