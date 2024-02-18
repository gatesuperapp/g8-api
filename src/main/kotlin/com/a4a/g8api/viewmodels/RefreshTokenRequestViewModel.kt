package com.a4a.g8api.viewmodels

import kotlinx.serialization.Serializable


@Serializable
data class RefreshTokenRequestViewModel(val refreshToken: String)