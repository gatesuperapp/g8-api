# g8-api

Backend de l'app The Gate. Authentification passwordless (magic-link), gestion
de comptes utilisateurs, intégration Stripe pour les abonnements.

## Stack

- **Kotlin 1.9.22** / **Ktor 2.3.7** (Netty), JVM 17
- **PostgreSQL** en prod via **Exposed** + **HikariCP** (H2 in-memory pour les tests)
- **Koin** pour la DI
- **Stripe** SDK pour Checkout, Portal et webhooks
- Build Gradle Kotlin DSL → fatjar via le plugin Ktor

## Authentification

Magic-link → JWT HS256 (24 h) + refresh token rotaté (180 j, stocké hashé).
Détection de réutilisation : si un refresh token déjà tourné est rejoué, toutes
les sessions de l'utilisateur sont révoquées.

Endpoints `/v1` :

| Méthode | Path                              | Auth | Description                          |
|---------|-----------------------------------|------|--------------------------------------|
| POST    | `/v1/auth/magic-link/request`     | —    | Toujours 204 (anti-énumération)      |
| POST    | `/v1/auth/magic-link/consume`     | —    | Crée user si absent, renvoie tokens  |
| POST    | `/v1/auth/refresh`                | —    | Rotation du refresh token            |
| POST    | `/v1/auth/logout`                 | JWT  | Révoque la session courante          |
| GET     | `/v1/me`                          | JWT  | Profil + état d'abonnement           |
| DELETE  | `/v1/me`                          | JWT  | Soft-delete + cancel Stripe          |
| POST    | `/v1/billing/checkout-session`    | JWT  | Stripe Checkout                      |
| POST    | `/v1/billing/portal-session`      | JWT  | Stripe Customer Portal               |
| POST    | `/v1/webhooks/stripe`             | HMAC | Évènements Stripe (idempotents)      |
| GET     | `/v1/health`                      | —    | Health check                         |

## Build & run en local

```bash
./gradlew test                    # tests (H2 in-memory, aucun SMTP réel)
./gradlew buildFatJar             # JAR → build/libs/g8-api.jar
JWT_SECRET=dev-secret-only ./gradlew run
```

Variables d'environnement reconnues :

| Variable               | Obligatoire | Défaut / Note                                |
|------------------------|-------------|----------------------------------------------|
| `JWT_SECRET`           | ✅          | Fail-fast au boot si absente                 |
| `DATABASE_URL`         | non         | Format JDBC, par défaut Postgres localhost   |
| `DATABASE_USER`        | non         |                                              |
| `DATABASE_PASSWORD`    | non         |                                              |
| `DATABASE_DRIVER`      | non         | Override JDBC driver (ex. H2 pour dev local) |
| `STRIPE_SECRET_KEY`    | non         | Désactive Stripe si absent                   |
| `STRIPE_WEBHOOK_SECRET`| non         | Requis pour valider la signature webhook     |
| `STRIPE_PRICE_*`       | non         | IDs des prix g8 Fly / g8 Fab (mensuel/annuel)|
| `SMTP_HOST` / `SMTP_PORT` | non      | Par défaut postfix localhost:25              |
| `EMAIL_NOOP`           | non         | `true` en test pour ne pas envoyer d'email   |

## Sécurité

- `JWT_SECRET` fail-fast au boot, jamais en clair dans le repo
- Magic-link 256 bits d'entropie, expiration 15 min, usage unique, stocké hashé SHA-256
- Anti-énumération sur `/magic-link/request` (toujours 204)
- Rate-limit Ktor par IP sur les endpoints publics
- Headers de sécurité (HSTS, X-Frame, CSP)
- HMAC Stripe-Signature vérifiée sur les webhooks
- Logs structurés JSON avec hash des emails (RGPD)

## Tests

JUnit 4 + kotlin-test. La suite d'intégration `AuthIntegrationTest` couvre
le flow auth complet, le rate-limit, l'anti-énumération, la détection de
replay et les headers sécu.

```bash
./gradlew test
```
