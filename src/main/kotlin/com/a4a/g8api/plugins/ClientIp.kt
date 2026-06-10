package com.a4a.g8api.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*

/**
 * Resolve the real client IP, immune to `X-Forwarded-For` spoofing.
 *
 * The JVM listens behind YunoHost's nginx, which connects from the loopback. Any
 * other peer (someone hitting the JVM port directly through a hole in the
 * firewall, or via a misconfigured tunnel) is treated as untrusted — we ignore
 * whatever X-Forwarded-For they send and use the actual TCP peer instead.
 *
 * When the peer IS the loopback proxy, we trust X-Forwarded-For but take the
 * LAST entry, not the first. Rationale: nginx with
 * `$proxy_add_x_forwarded_for` appends the real client IP at the end of any
 * pre-existing header the client may have sent. The last entry is therefore the
 * authoritative one our proxy contributed; entries before it are attacker-supplied.
 *
 * This also stays correct under the safer nginx config
 * `proxy_set_header X-Forwarded-For $remote_addr` (overwrite) — a single entry
 * IS the last entry.
 *
 * We do NOT use Ktor's `XForwardedHeaders` plugin: it trusts the first entry
 * blindly regardless of the peer, which is the original bug we're fixing.
 */
private val TRUSTED_PROXY_PEERS = setOf(
    "127.0.0.1",
    "::1",
    "0:0:0:0:0:0:0:1",
    "localhost",
)

/**
 * Pure resolver, exposed for unit testing. Production callers use [clientIp]
 * which feeds it the actual TCP peer and `X-Forwarded-For` header.
 */
fun resolveClientIp(peer: String, xForwardedFor: String?): String {
    if (peer !in TRUSTED_PROXY_PEERS) return peer
    val forwarded = xForwardedFor
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
    return forwarded?.lastOrNull() ?: peer
}

fun ApplicationCall.clientIp(): String =
    resolveClientIp(request.origin.remoteHost, request.headers["X-Forwarded-For"])
