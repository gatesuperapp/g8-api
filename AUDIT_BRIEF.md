# g8-api — Fiche de revue de code

Document destiné à un reviewer externe. Donne le fonctionnement de l'API et la liste hiérarchisée des points à auditer.

---

## 1. Vue d'ensemble

### Stack
- **Kotlin 1.9.22** / **Ktor 2.3.7** (Netty), JVM 17
- Build Gradle Kotlin DSL, fatjar via Ktor plugin (`./gradlew buildFatJar` → `build/libs/g8-api.jar`), port 8080. Pas de Docker en prod : déploiement direct par scp du JAR + systemd.
- DI **Koin 3.5.3** (un seul module)
- Entrypoint : `src/main/kotlin/com/a4a/g8api/Application.kt`

### Architecture
Modules classiques :
- `plugins/` — configuration Ktor (Security, Routing, Database, SecurityHeaders, ErrorHandling, RateLimit, ClientIp, Maintenance)
- `routes/` — handlers HTTP par ressource
- `services/` — Email, AuthLogger, CleanupService, EmailRateLimiter
- `models/` — DTO
- `database/` — Exposed tables + interfaces
- `viewmodels/` — request/response

### Persistance
- **Exposed 0.47** + **HikariCP** (pool=3, isolation `TRANSACTION_REPEATABLE_READ`, autoCommit=false)
- Schéma créé au boot via `SchemaUtils.create()` + `createMissingTablesAndColumns()` — **aucune migration versionnée**
- Default H2 file (`./db/g8-db`) ; PostgreSQL via env `DB_URL`

### Modèle de données
| Table | Colonnes clés |
|-------|---------------|
| `users` | id (UUID), email (unique), stripe_customer_id, created_at, updated_at, **deleted_at** (soft-delete) |
| `sessions` | id, user_id (FK), refresh_token_hash (unique), expires_at, revoked_at, last_used_at, device_info |
| `magic_links` | id, email, token_hash (unique), purpose, expires_at, consumed_at |
| `subscriptions` | id, user_id, stripe_subscription_id (unique), status, current_period_end, plan, product, cancel_at_period_end |
| `webhook_events` | stripe_event_id (PK = idempotence), event_type, processed_at |

### Authentification
**Passwordless magic-link → JWT HS256 (24 h)** + **refresh token rotaté** (180 j, stocké hashé).
Détection de replay : si un refresh token déjà tourné est réutilisé, **toutes les sessions du user sont révoquées**.
Pas de rôles, autorisation binaire (authentifié / non).

### Endpoints (tous sous `/v1`)
| Méthode | Path | Auth | Rate limit | Description |
|---------|------|------|-----------|-------------|
| GET | `/` | public | — | Health check |
| GET | `/v1/health` | public | — | Health check |
| POST | `/v1/auth/magic-link/request` | public | 60/h/IP + 3/24h/email | **Renvoie toujours 204** (anti-énumération) |
| POST | `/v1/auth/magic-link/consume` | public | 20/min/IP | Crée user si absent, renvoie JWT + refresh |
| POST | `/v1/auth/refresh` | public | 10/min/IP | Rotation du refresh token |
| POST | `/v1/auth/logout` | JWT | — | Révoque la session courante |
| POST | `/v1/auth/logout-all` | JWT | — | Révoque toutes les sessions |
| GET | `/v1/me` | JWT | — | User + subscription |
| DELETE | `/v1/me` | JWT | — | Soft-delete (⚠️ ne cancelle pas la sub Stripe) |
| POST | `/v1/billing/checkout-session` | JWT | — | Stripe Checkout |
| POST | `/v1/billing/portal-session` | JWT | — | Stripe Customer Portal |
| POST | `/v1/webhooks/stripe` | HMAC Stripe-Signature | — | `checkout.session.completed`, `customer.subscription.updated/deleted`, `invoice.payment_failed` |

### Sécurité périmétrique
- **SecurityHeaders** : HSTS, X-Content-Type-Options, X-Frame-Options, CSP `default-src 'none'`, header `Server` masqué
- **ErrorHandling** global : chaque exception corrélée par UUID, **jamais de stack au client** (`{"message":"Internal server error (id=...)"}`)
- **ClientIp** : résout `X-Forwarded-For` en prenant la **dernière entrée non-loopback** (anti-spoof)
- **Pas de CORS** (volontaire — pas de front browser en phase 1)

### Secrets
Tous en env vars, **aucun en clair dans le repo**.
- Obligatoires : `JWT_SECRET` (fail-fast au boot)
- Optionnels : `DB_URL`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_*`, `STRIPE_*_URL`, `SMTP_HOST/PORT`, `EMAIL_NOOP`, `HC_PING_URL_*`

### Logging
- Logback + SLF4J, loggers nommés par domaine (`auth`, `billing`, `error`, `cleanup`, `maintenance`, `healthcheck`)
- `AuthLogger` émet du **JSON structuré** avec `email_hash` SHA-256 (RGPD) et **IP tronquée** par défaut (`/24` IPv4, `/48` IPv6). L'IP complète n'est conservée que sur les événements signalant une attaque (`magic_link.consume_failed`, `auth.refresh` en échec, `auth.refresh_reuse_detected`, et `magic_link.requested` quand `suppressed=true`) — base légale : intérêt légitime sécurité.
- Healthchecks.io ping avec **whitelist URL stricte (anti-SSRF, regex sur `hc-ping.com`)**
- ⚠️ `logback.xml` root = `TRACE`

### Tests
- kotlin-test / JUnit 4, env H2 in-memory par test, `EMAIL_NOOP=true`
- `AuthIntegrationTest` (~500 lignes) : flow auth complet, rate-limit, anti-énumération, replay, headers
- `ClientIpTest`, `EmailRateLimiterTest`, `HealthcheckUrlValidatorTest`
- ⚠️ **Aucun test sur Stripe / webhooks / billing / cleanup**

---

## 2. Points à challenger en priorité

### 🔴 Bloquants production
1. **`routes/AccountRoutes.kt:99` — TODO non implémenté** : `DELETE /v1/me` ne **cancelle pas l'abonnement Stripe**. Facturation continue après suppression de compte.
2. **Aucune migration DB versionnée** (`SchemaUtils.create*`) : pas de rollback, drift garanti en multi-instance. Introduire Flyway/Liquibase avant scale-out.
3. **Rate-limiter `EmailRateLimiter` en mémoire** (`ConcurrentHashMap`) : bypass dès la 2ᵉ instance. À externaliser (Redis) si déploiement multi-replica.

### 🟠 Sécurité / robustesse à vérifier
4. **Idempotence webhooks** : la PK `stripe_event_id` empêche le doublon de traitement, mais les `handleCheckout*` font 4-5 writes **non transactionnels**. Rejouer un webhook (`stripe trigger`) et vérifier absence de race / d'orphelin.
5. **Pas de transaction explicite englobante** sur les handlers webhook : chaque `dbQuery {}` ouvre sa propre tx. Vérifier que `REPEATABLE_READ` suffit pour deux webhooks Stripe simultanés sur la même sub.
6. **Refresh token 180 jours** (`services/SessionService.kt:45`) : surface d'attaque longue. Discuter 30-90 j + politique d'inactivité.
7. **Pas de rate-limit sur les routes authentifiées** (`/me`, `/billing/*`). Un JWT volé permet de spammer Stripe Checkout. Ajouter au moins sur les routes appelant Stripe.
8. **Validation email naïve** (`routes/MagicLinkRoutes.kt:35` : `contains("@") && contains(".")`). OK pour MVP, à durcir.
9. **Fallback `priceIdToProduct(priceId)`** dans le webhook : si Stripe envoie un price ID inconnu, on log un warn mais on continue. Vérifier que le sub n'est pas créé avec un `product` exploitable.

### 🟡 Hygiène
10. **`logback.xml` root = TRACE** → passer en `INFO` pour prod (volume de logs Netty/Jetty).
11. **Statuts en `String`** (`subscription.status`, `magic_link.purpose`) → migrer en `enum class` Kotlin.
12. **Couverture Stripe nulle** : ajouter tests sur `BillingRoutes` + `WebhookRoutes` avec mock SDK Stripe et fixtures signées (c'est le cœur revenu de l'app).
13. **Versions dépendances** : Exposed 0.47 a plusieurs releases de retard (≥ 0.50). Ktor 2.3.7 vs branche 3.x. Pas critique, à arbitrer.
14. **Soft-delete user / RGPD** : vérifier que `CleanupService` purge bien les `sessions` et `magic_links` rattachés à un user soft-deleted — sinon données personnelles persistent.

### 🟢 RGPD — minimisation des données (fait)
- **`magic_links.ip_address` + `user_agent` supprimés** : colonnes mortes (jamais relues côté app). Migration prod à passer à la main : `ALTER TABLE magic_links DROP COLUMN ip_address; ALTER TABLE magic_links DROP COLUMN user_agent;`
- **IP tronquée dans les logs `AuthLogger`** : `/24` IPv4 / `/48` IPv6 par défaut, IP complète uniquement sur les événements de sécurité (cf. section *Logging*). Helper `truncateIp` testé unitairement (`TruncateIpTest`).
- **Décisions explicites (non faites)** :
  - `sessions.device_info` (User-Agent) conservé : utile pour une UI future "Vos appareils connectés". À mentionner dans les CGU.
  - Pas de hash salé d'IP pour le rate-limiting : inutile ici, le plugin Ktor `RateLimit` garde les clés en RAM volatile avec TTL court (pas de persistance disque).

### 🟢 Détection d'intrusion — alertes auth (configurées, 🔜 à déployer)
- 4 jails fail2ban versionnés sous `ops/fail2ban/` (lecture fichier `/var/log/g8-api/auth.log` via pyinotify, ban iptables + email `contact@the-gate.fr`) :
  - `g8-refresh-reuse` — 1 event = ban 30j (vol de token confirmé via `auth.refresh_reuse_detected`)
  - `g8-magic-link-bf` — 10 `magic_link.consume_failed` / 10 min = ban 24h
  - `g8-refresh-bf` — 20 `auth.refresh` `success:false` / 10 min = ban 24h
  - `g8-magic-link-spam` — 5 `magic_link.requested` `suppressed:true` / 1h = ban 24h (mass-mailing / enumeration)
- Logback (`src/main/resources/logback.xml`) écrit le logger `auth` dans un fichier dédié, daily rolling, 30 jours, 100 MB cap. Le STDOUT est conservé en parallèle (journald reste utilisable pour grep ad-hoc).
- **Pourquoi pas le backend systemd** : tenté en premier (cleanest, pas de fichier à gérer), mais sur ce serveur Debian 12 + fail2ban 1.0.2 le reader systemd ne remontait aucun event en live, même avec le bon `journalmatch` et `python3-systemd` installé. `fail2ban-regex --journalmatch` confirmait `0 lines processed` malgré des events bien présents dans `journalctl`. Pas isolé, switch au pattern éprouvé file + pyinotify.
- Procédure d'install + test : `ops/fail2ban/README.md`.

---

## 3. Points positifs (à mentionner pour qu'ils ne soient pas re-challengés)

- `JWT_SECRET` fail-fast au boot, rotation de refresh token, **replay detection**, anti-énumération sur magic-link (toujours 204), hash des tokens en base
- HMAC Stripe-Signature correctement vérifiée sur les webhooks
- Anti-SSRF sur Healthchecks.io (regex whitelist)
- `ClientIp` correctement implémenté (dernière IP non-trusted dans `X-Forwarded-For`)
- Error handler global : pas de leak de stack trace, corrélation par UUID
- Logs structurés JSON avec hash email
- Webhook idempotence via PK `stripe_event_id`
- Tests d'intégration auth bout-en-bout solides
