package com.a4a.g8api.services

/**
 * Lightweight syntactic validation of an email address.
 *
 * We deliberately do NOT try to match RFC 5322 — that regex is a nightmare and
 * still cannot prove deliverability. The real validation is the magic-link
 * round-trip: if the address resolves to a mailbox and the user clicks the
 * link, the address is good enough. This function just blocks the obviously
 * malformed and the security-relevant patterns.
 *
 * Checks (in order):
 *  - Length 3..254 (254 = RFC 5321 SMTP max).
 *  - No control characters. `\r` and `\n` are the load-bearing reject — without
 *    that, an attacker could smuggle extra mail headers by submitting an
 *    address like `victim@x.com\nBcc: attacker@evil.com`. JakartaMail does its
 *    own scrubbing today, but defense-in-depth before we hand it off.
 *  - Exactly one `@`, neither at the start nor at the end.
 *  - At least one `.` in the domain part (forces a TLD-shaped structure).
 *
 * Callers should trim + lowercase the address BEFORE calling this (already done
 * by the magic-link route via `email.lowercase().trim()`).
 */
internal fun isValidEmail(email: String): Boolean {
    if (email.length !in 3..254) return false
    if (email.any { it.isISOControl() }) return false

    val atIndex = email.indexOf('@')
    if (atIndex <= 0 || atIndex != email.lastIndexOf('@') || atIndex == email.lastIndex) return false

    val domain = email.substring(atIndex + 1)
    if (!domain.contains('.')) return false

    return true
}
