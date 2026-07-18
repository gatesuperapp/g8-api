# g8-api

Backend for The Gate app. Passwordless authentication (magic-link), user
accounts, Stripe-based subscriptions.

## Stack

- **Kotlin 2.3.21** / **Ktor 3.5.1** (Netty), JVM 17
- **PostgreSQL** in production via **Exposed** + **HikariCP** (Postgres via **Testcontainers** for tests — matches prod dialect)
- **Koin** for DI
- **Stripe** SDK for Checkout, Portal and webhooks
- Gradle Kotlin DSL build → fat JAR via the Ktor plugin

## Authentication

Magic-link → JWT HS256 (24h) + rotating refresh token (180d, hashed at rest).
Replay detection: if a refresh token that has already been rotated is
presented again, every session for that user is revoked.

`/v1` endpoints:

| Method  | Path                              | Auth | Description                              |
|---------|-----------------------------------|------|------------------------------------------|
| POST    | `/v1/auth/magic-link/request`     | —    | Always 204 (anti-enumeration)            |
| POST    | `/v1/auth/magic-link/consume`     | —    | Creates user if missing, returns tokens  |
| POST    | `/v1/auth/refresh`                | —    | Rotates the refresh token                |
| POST    | `/v1/auth/logout`                 | JWT  | Revokes the current session              |
| GET     | `/v1/account`                     | JWT  | Profile + subscription state             |
| DELETE  | `/v1/account`                     | JWT  | Soft-delete + cancel Stripe              |
| POST    | `/v1/billing/checkout-session`    | JWT  | Stripe Checkout                          |
| POST    | `/v1/billing/portal-session`      | JWT  | Stripe Customer Portal                   |
| POST    | `/v1/webhooks/stripe`             | HMAC | Stripe events (idempotent)               |
| GET     | `/v1/health`                      | —    | Health check                             |

## Build & run locally

```bash
./gradlew test                    # tests (Postgres via Testcontainers, no real SMTP — needs Docker)
./gradlew buildFatJar             # JAR → build/libs/g8-api.jar
JWT_SECRET=dev-secret-only ./gradlew run
```

Environment variables:

| Variable                  | Required | Default / note                                  |
|---------------------------|----------|-------------------------------------------------|
| `JWT_SECRET`              | yes      | Fail-fast at boot if missing                    |
| `DATABASE_URL`            | no       | JDBC URL, defaults to Postgres on localhost     |
| `DATABASE_USER`           | no       |                                                 |
| `DATABASE_PASSWORD`       | no       |                                                 |
| `DATABASE_DRIVER`         | no       | Override the JDBC driver                        |
| `STRIPE_SECRET_KEY`       | no       | Stripe routes are disabled if absent            |
| `STRIPE_WEBHOOK_SECRET`   | no       | Required to verify webhook signatures           |
| `STRIPE_PRICE_*`          | no       | Price IDs (monthly / yearly plans)              |
| `SMTP_HOST` / `SMTP_PORT` | no       | Defaults to postfix on localhost:25             |
| `EMAIL_NOOP`              | no       | `true` in tests to skip outgoing mail           |
| `HC_PING_URL_HEARTBEAT`   | no       | Healthchecks.io ping URL (5-min liveness)       |
| `HC_PING_URL_CLEANUP`     | no       | Healthchecks.io ping URL (24h cleanup job)      |
| `HC_PING_URL_ABUSE`       | no       | Healthchecks.io ping URL (5-min abuse scan — pings `/fail` when a user_id shows up from ≥5 distinct IPs in 15 min) |

## Security

- `JWT_SECRET` fail-fast at boot, never committed in clear text
- Magic-link tokens: 256 bits of entropy, 15-minute expiry, single-use,
  SHA-256-hashed at rest
- Anti-enumeration on `/magic-link/request` (always 204)
- Ktor per-IP rate-limit on public endpoints
- Security headers (HSTS, X-Frame, CSP)
- Stripe webhook HMAC signature verified
- Structured JSON logs with hashed emails (GDPR)

## Tests

JUnit 4 + kotlin-test. The `AuthIntegrationTest` integration suite covers
the full auth flow, rate limiting, anti-enumeration, replay detection and
security headers.

```bash
./gradlew test
```
