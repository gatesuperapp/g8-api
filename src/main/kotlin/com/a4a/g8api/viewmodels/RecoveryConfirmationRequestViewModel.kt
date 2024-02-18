package com.a4a.g8api.viewmodels

import kotlinx.serialization.Serializable


@Serializable
data class RecoveryConfirmationRequestViewModel(val recoveryToken: String, val password: String)