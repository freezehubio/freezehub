# FreezeHub — Security Specification

## Purpose

Finalizes the MVP human and machine authentication approach, per `FZ-010`, before any authentication/tenant-sensitive implementation begins (`FZ-011`+).

This document specifies the *approach*: mechanisms, tokens, and tenant-resolution rules. It does not specify implementation code.

## Governing Principles

Restated from `CLAUDE.md` and `02-architecture.md` — this document must not contradict them:

1. Human authentication and machine authentication are separate mechanisms.
2. A client-supplied `organizationId` (header, query, path, or body) is never treated as authorization. The organization is always resolved server-side from the authenticated principal.
3. Raw API key secrets are never stored after creation (`01-domain.md`).

## Human Authentication

**Provider: Amazon Cognito** (user pool). This confirms `02-architecture.md`'s infrastructure target, which named Cognito "subject to security-step confirmation" — this is that confirmation.

**Pricing tier: Lite.** `00-product.md` does not require passwordless login, passkeys, or advanced threat protection (Essentials/Plus features) — basic password authentication covers MVP scope. Lite is the cheaper option (~$0.0055/MAU beyond the 10,000 free MAU/month, vs. $0.015/MAU on Essentials) with no functional gap for what's specified. Revisit if a documented requirement later needs an Essentials/Plus-only feature.

- Flow: Cognito authenticates the user and issues an **access token** (JWT). The frontend attaches it as `Authorization: Bearer <token>` on API requests.
- Backend: Spring Security configured as an **OAuth2 Resource Server**, validating the JWT signature and claims against the Cognito user pool's JWKS endpoint (issuer URI supplied per environment via configuration, not hardcoded).
- Identity mapping: Cognito's `sub` claim is the external identity identifier. FreezeHub resolves it to a `users` row scoped to one `organization_id`.
  - This resolves `03-data-model.md`'s open item on the `users` table: add `external_subject VARCHAR NOT NULL UNIQUE`. (Updated in that document as part of this change.) **Named `cognito_subject` until `FZ-132`** (`OI-16`), which is the same column: it holds the issuer's `sub` claim, and naming the issuer in the schema claimed a coupling the backend does not have.
- Organization resolution: on every authenticated request, `organization_id` comes from the resolved `users` row — never from client input.

**Resolved by `FZ-012`:** user provisioning is **admin-provisioned bootstrap + in-product invite**:

- An organization's first user (its Administrator) is provisioned out-of-band — no self-service signup. Concretely: create the Cognito identity via `AdminCreateUser` (Cognito emails a temporary password), then insert the matching `users` row with `role = ADMINISTRATOR`. This is a manual/ops step for each new pilot organization, not a product feature.
- Every subsequent user is added via an **in-product invite**, restricted to Administrators. This is a separate backlog item (invite endpoint), not part of `FZ-012` itself — `FZ-012` delivers the authentication mechanism (JWT validation, identity resolution) that the invite feature and everything else builds on.
- Rationale: `00-product.md` names three actors per organization (Administrator, Manager, Engineer), so multi-user orgs are required — but no backlog item anywhere describes a self-service signup/onboarding flow, so building one isn't MVP scope. See that item for exact invite mechanics.

**Superseded in part by `FZ-080`:** a self-service signup flow is now specified — `docs/11-commercial.md` §4, built by `FZ-082`. The statements above remain an accurate description of what exists today, and of why nothing was built when they were written. What changes is only that "no backlog item describes one" is no longer true.

Three security rules govern it, and none of them are negotiable by the implementation:

- **`POST /api/signup` returns the same `202 Accepted` whether the organization was created or the email was already in use.** Varying the response makes signup a customer-enumeration oracle: anyone could learn which companies use FreezeHub by trying their domains. This is the same reasoning that makes a cross-tenant resource return `404` rather than `403`.
- **It cannot ship before rate limiting exists** (`OI-11`, `FZ-087`). It is an unauthenticated endpoint that creates a Cognito identity and sends an email.
- **It cannot ship before a real `IdentityProvider`** (`OI-2`, `FZ-046`). The only implementation today is a `@Profile("local")` fake.

Nothing about it weakens tenant isolation: signup creates a *new* organization and resolves nothing from client input. The organization identifier still never appears in a request.

**Built by `FZ-082`.** All three preconditions were met first: `FZ-087` had already named `/api/signup` in its default rate-limit paths, and `FZ-046` replaced the fake `IdentityProvider` with Cognito. Three things the implementation added are security properties rather than features:

- **Cognito is the global uniqueness check.** `users.email` is unique only within an organization (`uq_users_organization_email`); the pool is shared across all of them. So "has this address been seen before" is answered by `AdminCreateUser` refusing, not by a second index that could disagree with it. `LocalIdentityProvider` reproduces that refusal, so the duplicate path — the one thing keeping the endpoint from being an enumeration oracle — behaves the same in tests as in production, rather than being the single path a convenient fake quietly hides.
- **An unverified organization is purged after seven days**, with its user, subscription, audit trail and Cognito identity. The identity is the part that matters: left behind, it holds the address against anyone ever signing up with it again. Only `PENDING_VERIFICATION` is ever touched — one sign-in puts an organization permanently out of the purge's reach, whatever later happens to its subscription.
- **The foreign keys are the purge's safety net.** Eleven tables carry an `organization_id` and the purge deletes rows from three. That is sound only because everything else is created through an authenticated endpoint, and authenticating is what ends `PENDING_VERIFICATION`. If that reasoning is ever wrong the foreign key refuses the delete, the transaction rolls back whole, and the organization survives — so an unattended job that erases tenants fails closed without anyone having had to predict which table would be the surprise.

**Implemented by `FZ-016`:** `POST /api/invites`, Administrator-only. The Cognito `AdminCreateUser` call is behind an `IdentityProvider` port — same local/real split as JWT validation below, since no real Cognito user pool exists yet. Only the local fake ships now; a real Cognito-backed implementation is required before this endpoint runs against a deployed environment (see `FZ-016`'s known gap in `08-backlog.md`).

**Resolved by `FZ-012`:** minimal role model — `users.role` is one of `ADMINISTRATOR` or `MEMBER`. This is not "advanced RBAC" (`00-product.md`'s exclusion): it gates a short, enumerated list of organization-level actions (see Authorization, below), not general resource permissions. `Team`/`Application`/`Environment`/restriction management remain open to any authenticated org member unless a future requirement says otherwise.

### Local development and automated tests

Provisioning a real Cognito user pool is infrastructure work (`FZ-063`), which is sequenced far after `FZ-012` needs to test authenticated endpoints. To avoid blocking on that:

- **Local/test profile:** the backend issues and validates JWTs signed with a locally-generated key, matching the claim shape Cognito would produce (at minimum `sub`). No AWS dependency.
- **Any deployed environment** (including a shared dev/staging AWS environment) validates against the real Cognito JWKS endpoint. There is no environment where the local signing key is trusted outside a developer's own machine or CI test run.

### Token validation rules for a deployed environment

**Specified by `FZ-125`, before `FZ-046` implements any of it.** Nothing below is built: there is no `issuer-uri` and no `JwtDecoder` outside the `local` profile today (`OI-2`), which is precisely why the rules are written now rather than discovered later. Every one of them is a check that a signature-valid token still fails.

**Cognito issues ID tokens and access tokens from the same issuer, signed by the same JWKS.** Signature validation alone therefore accepts both, and an ID token presented where an access token is meant is a valid token being used outside its purpose. Three consequences, none optional:

- **`token_use` must equal `access`.** This is the check that distinguishes the two, and it is the one a default resource-server configuration does not make.
- **Audience is validated against `client_id`, not `aud`.** A Cognito *access* token carries the app client in `client_id`; `aud` is populated on the ID token. A validator configured on `aud` in the ordinary way silently validates nothing, which is worse than not validating, because it reports success.
- **The issuer is validated against the configured pool**, supplied per environment as configuration and never defaulted — the same fail-fast as the missing `JwtDecoder` and the missing encryption key.

Expiry and signature are assumed rather than stated; they are what the library already does. These three are the ones it does not.

**A test must present each rejected shape and assert a `401`** — an ID token, a token from another pool, and a token for another app client. A validator with no negative test is a validator nobody has seen refuse anything, which `FZ-114` and `FZ-121` have each already demonstrated the cost of in a different corner of this codebase.

## Machine Authentication (API Keys)

CI/CD and other machine clients authenticate to the Policy Evaluation API (and other machine-facing endpoints) using an **API key**, sent as a dedicated header:

```text
X-API-Key: <key>
```

A separate header (rather than reusing `Authorization`) keeps human (JWT) and machine (API key) authentication mechanically distinct, per Governing Principle 1.

- **Key format:** `fzh_` followed by 256 bits of `SecureRandom` entropy in URL-safe Base64. The prefix aids leak-scanning and quick identification in logs; it does not reduce the secret portion's entropy.
- **Storage:** only the SHA-256 hash of the key is persisted. The raw key is shown to the caller exactly once, at creation time, and is not recoverable afterwards.
- **Lookup:** the backend hashes the incoming key and looks up the matching `ApiKey` record. `organization_id` is resolved from that record — never from any client-supplied identifier.
- **Revocation:** `api_key.revoked_at` is set once and never cleared. Revocation is not a toggle: a key is withdrawn because it may already be in someone else's hands, and restoring it would revive that copy. Issue a new key instead.
- **Reach:** a key authenticates only against `/api/policy/**`. It cannot read or change an organization's data and cannot mint another key, so a credential leaked from CI is not an account takeover. Managing keys is part of the human API and requires an Administrator JWT.

**Resolved by `FZ-052` — the hash is not salted.** This document originally said "salted hash (e.g. SHA-256)". A salt defeats rainbow tables and offline brute force against *low-entropy* secrets; neither attack applies to a 256-bit random value, because there is nothing to guess. A per-key salt would also mean the hash of an incoming key no longer identifies its row, forcing either a second lookup handle inside the token or hashing every stored row on every call — and the Policy API is asked on every deployment. What this section actually requires is unchanged and holds exactly: the raw key is never stored, and lookup is by hash.

## Outbound Destinations (Where FreezeHub Will Call)

**Implemented by `FZ-126`, resolving `OI-23`.** A webhook or Slack URL is chosen by a customer's administrator and then called *from inside the deployment's network*. Until this existed the only check was `startsWith("https://")`, which says nothing about which host.

Three rules, enforced on every delivery rather than when the integration is saved — a name validated at save time can be repointed the minute after:

- **Redirects are not followed.** A `302` from an attacker's own HTTPS endpoint to `http://169.254.169.254/` is the whole attack, and checking the address of a request that is then redirected elsewhere checks nothing. A `3xx` is a delivery *failure*, not a success: a response nobody followed is not a delivery.
- **Every resolved address must be public.** Loopback, link-local (where a container runtime serves the task's own credentials), private, carrier-grade NAT, unique-local, any-local and multicast are refused — and *every* address a name resolves to must pass, because a name with several records only needs one of them to be useful.
- **A userinfo authority is refused outright.** `https://hooks.slack.com@10.0.0.5/` starts with `https://`, reads like Slack, and addresses an internal host.

The refusal is visible to the customer on the notifications screen and names the *class* of address, never the address itself — telling somebody their name resolved to `10.0.0.5` confirms the internal range to whoever pointed it there.

**What this does not close, stated because it matters:** the window between FreezeHub's resolution and the HTTP client's own, which is a DNS rebind. Closing that means connecting to a pinned address with the `Host` header set by hand. The durable answer is egress control in the network — a security group, or egress through a proxy — which belongs to `FZ-123` and which this does not replace. These rules apply to customer-supplied destinations only; the demo-request notifier calls a webhook this organization configures for itself.

## Outbound Authentication (Webhook Signing)

Everything above is about authenticating what reaches FreezeHub. This is the other direction: letting a customer's receiver verify that a webhook delivery actually came from FreezeHub (`FZ-048`, decision `D-2` in `07-decisions.md`).

Without it, anyone who learns a customer's endpoint URL can post a forged event to it — and a forged `CANCELLED`, telling an automated consumer that a freeze has been lifted, is the one worth forging.

- **Secret:** `whsec_` followed by 256 bits of `SecureRandom` entropy, generated per webhook integration and returned **once** at creation. Stored recoverable rather than hashed, because signing requires the key itself — see the note under Secrets Management.
- **Headers on every delivery:**

  ```text
  X-FreezeHub-Timestamp: 1700000000
  X-FreezeHub-Signature: sha256=<hex>
  ```

- **Signed string:** `"<timestamp>.<body>"`, HMAC-SHA256 with the shared secret, hex-encoded. The timestamp is signed *with* the body deliberately: it is what stops a captured delivery being replayed with a different body, and it lets a receiver reject deliveries that are too old.
- **Verification, receiver side:** recompute the HMAC over `timestamp + "." + raw body` and compare in constant time. Reject if the timestamp is outside an acceptable window (300 seconds is a reasonable default). Compare against the **raw** body, before any JSON reformatting — re-serialising changes the bytes and the signature will not match.
- **Rotation:** `POST /api/integrations/{id}/signing-secret` issues a new secret and invalidates the previous one immediately. There is no overlap window, so rotation is coordinated with the receiver — an overlap would keep a leaked secret working for exactly as long as it lasted.

Integrations created before `FZ-048` have no secret and are delivered unsigned, with a warning logged, until rotated.

## Authorization

MVP does not implement advanced RBAC (`00-product.md`, Out of Scope). Role checks are deliberately few, and each one guards something that decides what the organization itself can do rather than a resource within it. `role = ADMINISTRATOR` is required to:

- invite a user (`FZ-016`);
- configure a notification destination (`FZ-045`) — it decides who hears about a freeze, and its configuration can hold a credential;
- issue or revoke an API key (`FZ-052`) — a key authenticates as the whole organization.

- read anything under `/actuator` except health (`FZ-065`) — the counters there are aggregate across every tenant, so they are not an organization's data at all. An administrator is not the right bar either, merely a cheaper one than the fix: see `OI-21`.

Every other authenticated action is available to any user within their own organization.

`/actuator/health` and its probes stay unauthenticated, because the load balancer reads them.

## Tenant Isolation Enforcement

1. Authentication (human JWT or machine API key) resolves exactly one `organization_id`.
2. That `organization_id` is attached to the request's security context and is the only source of truth for scoping queries and writes.
3. Application/service code must filter every tenant-owned read and write by this resolved `organization_id`. A client-supplied organization identifier appearing anywhere in a request is not authorization and must not be used as one (Governing Principle 2, `01-domain.md` invariant 4).

## Rate Limiting

**Extended by `FZ-130`, resolving `OI-27`.** `FZ-087` limited the endpoints that exist without a credential; everything else was unmetered, including the one endpoint the product can least afford to lose.

Four limits, each keyed by whoever is actually responsible for the traffic:

| What | Counted per | Default | Why that key |
|---|---|---|---|
| `/api/signup`, `/api/demo-requests`, `/api/dev/token` | source address | 10 / minute | there is no credential to count against |
| `/api/webhooks/stripe/**` | source address | 120 / minute | same, and Stripe bursts — a new subscription arrives as several events |
| `/api/policy/**`, no usable API key | source address | 30 / minute | the attempt costs a hash and a lookup, and nothing else stops it repeating |
| `/api/policy/**`, authenticated | **API key** | 60 / 10 seconds | the key is the pipeline; an address is a shared office |

Set under `freezehub.rate-limit.*` — `unauthenticated`, `stripe-webhook`, `policy.failures`, `policy.per-key`, each taking `requests` and `window`. Numbers that can only be changed by a release are numbers nobody changes.

**The key matters more than the number on the Policy API.** `freeze-check.sh` fails closed by default (`FREEZEHUB_ON_ERROR=block`, `FZ-053`), so a `429` does not slow a deployment down, it stops it. Counting authenticated evaluations per *address* would mean one stale credential behind a corporate NAT could fill the bucket for every pipeline sharing that egress IP — the defence causing the outage the product exists to schedule. Per key, a pipeline can only refuse itself. `PolicyRateLimitTest` exercises exactly that: the failures are exhausted from an address, and a valid key from the same address still deploys.

**The window is short rather than the limit large.** 60 in 10 seconds and 360 in a minute allow the same rate, but a ten-second window can never answer `Retry-After: 54`. The worst a refused pipeline is asked to wait is ten seconds — which `freeze-check.sh` now sits out and retries (`curl --retry`, ceilinged at 30 seconds) instead of failing the build.

**Enforcement lives in two places, because the endpoints are reached differently.** The path-based limits are an interceptor. The Policy API's are a filter inside its security chain: a request whose key does not resolve is refused by the chain's entry point and never reaches the DispatcherServlet, so an interceptor would see only the calls that succeeded and the half with no credential behind it would stay unmetered. Both refuse with `429`, `Retry-After`, and the same Problem Details body as every other refusal (`FZ-061`).

**What this is not.** The counters are in memory and per instance, so two tasks mean twice the effective limit — stated rather than hidden (`CLAUDE.md` §4 keeps distributed caching out of the MVP). It stops one source, or one credential, hammering one endpoint. Abuse spread across many addresses needs a WAF or the load balancer, above the application.

## Response Headers on the Distribution

**Implemented by `FZ-129`, resolving `OI-26`.** CloudFront serves every response with a Content-Security-Policy, HSTS (one year, subdomains, **not** preloaded — preload is a one-way door), `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, and a `Permissions-Policy` switching off the browser features the product has no use for.

The CSP is the one that carries weight, because **the frontend holds its bearer token in `sessionStorage`** — a deliberate choice documented in `AuthProvider.tsx` — which makes a script injection the way that token leaves. It is written from what the application actually loads rather than from a template, and is strict where it can be: `script-src 'self'` with no third-party script anywhere in the product, `connect-src` naming only the API, and `frame-ancestors 'none'`.

One allowance is deliberate and worth stating: `style-src-attr 'unsafe-inline'`. React sets four inline style attributes and two are load-bearing — the usage bar's width and the checks chart's bar heights are computed from data. Scoping the allowance to the *attribute* keeps `<style>` elements and stylesheets strict, and an injected style attribute is a far weaker primitive than an injected script.

**A policy is only worth having if the application still works under it**, so it was rehearsed against the real production bundle before any apply: fonts and API calls succeeded, the chart's bars measured 149px rather than collapsing, and a third-party script and a cross-origin `fetch` were both refused.

## Secrets Management

- Cognito app client configuration: environment-specific configuration values (issuer URI, client ID), not secrets by themselves. Any actual secret material uses AWS Secrets Manager in deployed environments (`02-architecture.md`), and environment variables locally.
- API key raw secrets: never stored, anywhere, after creation.
- **Webhook signing secrets are the exception, and a deliberate one**: HMAC requires the key itself, so there is nothing to compare a hash against. They are stored recoverable in `integration.signing_secret`.
- **Recoverable secret material is encrypted at rest** (`FZ-049`, decision `D-3`). `integration.config` and `integration.signing_secret` are AES-256-GCM encrypted in the application before they reach the database, so a database connection, a dump or a backup yields ciphertext. It does **not** protect against a compromised application, which holds the key.
- **The encryption key** comes from `freezehub.secrets.encryption-key` — 32 bytes, Base64. A deployed environment sources it from AWS Secrets Manager and **must** supply it: there is no default outside the `local` profile, and the application refuses to start without one, the same fail-fast as the missing `JwtDecoder`. The committed local key protects a developer's own database and is worth nothing.
- Encryption sits behind a `SecretProtector` port, so storing secrets *in* a provider and keeping only a reference is a second implementation rather than a rewrite. See `D-3`.

## Threat Model and OWASP Coverage

**Written by `FZ-125`**, a review of the whole application rather than of one change. It records what an attacker can reach and what is missing, and it is deliberately narrow: six findings that can be acted on now, not a coverage matrix for categories nothing yet exercises. Several OWASP categories cannot be answered honestly until something is deployed and `FZ-046` exists, and a matrix that answers them anyway is worth less than no matrix.

### What the review confirmed rather than assumed

`FZ-065` had already checked tenant isolation, API-key handling, date/time and the restriction lifecycle **by running things**, and this review did not repeat that work. What it re-read and found sound: the development sign-in is fenced three independent ways — `@Profile("local")` on the controller, on its security chain and on the token issuer — and outside that profile the application refuses to start at all for want of a `JwtDecoder`. API keys are 256 bits, never stored raw, checked for revocation before use, and reach `/api/policy/**` and nothing else. Recoverable secret material is AES-256-GCM encrypted before it reaches the database (`D-3`). Outbound deliveries are HMAC-signed over timestamp and body (`D-2`). CORS has no permissive default. The Stripe webhook has its own chain and verifies signatures. The bearer token is held in `sessionStorage` rather than `localStorage`, deliberately.

### The findings

| | Finding | OWASP | Owner |
|---|---|---|---|
| 1 | Outbound webhooks reach any host the network can reach | A10 Server-Side Request Forgery | `OI-23` · `FZ-126` |
| 2 | Nothing scans dependencies or images | A06 Vulnerable and Outdated Components | `OI-24` · `FZ-127` |
| 3 | Deployed token validation is unspecified | A07 Identification and Authentication Failures | `OI-25` · `FZ-128` |
| 4 | No response-headers policy on the distribution | A05 Security Misconfiguration | `OI-26` · `FZ-129` |
| 5 | Rate limiting covers only the unauthenticated endpoints | A07 · A04 | `OI-27` · `FZ-130` |
| 6 | Actuator is on the application's own port | A05 Security Misconfiguration | `OI-21` · `FZ-123` |

### The requirements these produce

**Egress is a boundary, not a validator.** A webhook URL is attacker-chosen by definition — that is the feature. The requirement is therefore not "validate the URL better" but **the application must not be able to reach anything it has no business reaching**: redirects are not followed on outbound deliveries, the resolved address is rejected at connect time rather than at save time, and link-local, loopback, private and unique-local ranges are refused. Validating at save and resolving at send is a gap a name can be moved through, so the check belongs where the connection is made.

**A `https://` prefix is not a destination check.** `startsWith("https://")` is satisfied by a userinfo-bearing authority whose host is an internal address, and it is bypassed entirely by a redirect to `http://`. It stays, because these carry credentials and announcements — but it is a transport requirement and must never again be read as a host requirement.

**The credential a delivery could steal is the one worth bounding.** The blast radius of this class is whatever the runtime's own metadata endpoint will hand out, so the deployment's egress posture (`FZ-122`, `FZ-123`) and this finding are the same decision seen from two sides, and should be decided together.

**A dependency nobody scans is a dependency nobody has approved.** The product's whole claim is to be the control that gates a customer's deployments; shipping unreviewed transitive dependencies is the answer that reads worst on the questionnaire that claim invites. The requirement is that a build fails on a known-exploitable dependency, and that the container image is scanned as well as the dependency tree, because the base image is not in `pom.xml`. **Answered by `FZ-127` and finished by `FZ-139`**: the scan runs on every build, and all three `FROM` lines are pinned by digest, so what it reports on is the artifact that ships rather than whatever a tag resolved to that morning — which was not a hypothetical, `OI-30` began as a tag changing Ubuntu release under the project.

**Every authentication check needs a test that watches it refuse.** Stated in full under Token validation rules above, and it generalises: this codebase has twice shipped a guard whose test exercised the path production does not use (`FZ-114`, `FZ-121`).

**Rate limiting is an availability control here, not only a credential one.** A 256-bit key is not brute-forcible, so the exposure on `/api/policy/**` is cost and availability rather than compromise — but that endpoint is the one whose unavailability blocks every customer's deployments (`D-21`, `D-24`), which makes it the endpoint least able to afford being hammered. **Answered by `FZ-130`** — see Rate Limiting above: the authenticated limit is counted per API key rather than per address, precisely so that this control cannot become that outage.

## Out of Scope for MVP

- Enterprise SSO / SAML / external OIDC federation beyond Cognito's own hosted authentication (`00-product.md`).
- Advanced/granular RBAC (`00-product.md`).
- Cookie/session-based human authentication — token-based only.
- Mandating specific Cognito MFA policy — configurable later as a user-pool setting without application changes.
