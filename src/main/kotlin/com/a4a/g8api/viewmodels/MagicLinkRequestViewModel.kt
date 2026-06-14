package com.a4a.g8api.viewmodels

import kotlinx.serialization.Serializable

@Serializable
data class MagicLinkRequestViewModel(
    val email: String,
    // BCP-47 tag from the app (e.g. "fr", "fr-FR", "en", "en-US"). Optional — older
    // clients won't send it, and the email service defaults to French in that case.
    val locale: String? = null,
)
