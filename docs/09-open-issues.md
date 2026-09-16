# FreezeHub — Open Issues

## Purpose

Every known defect, gap and deferred decision in one place, each with an owner.

They were previously recorded only inside the entry of whichever story surfaced them, which meant a real bug could sit unnoticed in the middle of a paragraph about something else. This is the index; `08-backlog.md` remains where the work is specified.

**Rules for this file**

- Anything found and not fixed in the same story gets an entry here, immediately.
- Every entry names the story that will resolve it. If none exists, that is itself the next action — "no owner" is not a status.
- An entry is removed only when the work is done and verified, with the resolving story noted in the Resolved table below.

Severity is about consequence if it reaches beta, not effort:

| | |
|---|---|
| **Defect** | behaves incorrectly today |
| **Gap** | required behaviour is missing |
| **Decision** | a choice nobody has made; blocks or shapes later work |

## Open

### OI-2 — No real Cognito identity provider
**Severity:** Gap · **Owner:** needs a story · **Found in:** `FZ-016`

`IdentityProvider` has only `LocalIdentityProvider`, a `@Profile("local")` fake that invents a subject. Inviting a user in any deployed environment requires a real `AdminCreateUser` implementation.

Not silently broken: without the `local` profile the application refuses to start, because no `IdentityProvider` bean exists. That is deliberate fail-fast, but it does mean **the backend cannot run outside `local` at all today**, which `FZ-063` will hit the moment infrastructure work begins.

**Decided (`D-4`): sequenced with `FZ-063`, not built ahead of it.** Writing the adapter now means writing it against a service nothing can reach — it could not be run once, and its first real execution would happen during infrastructure work anyway. An adapter verified only against a mock and left unexercised is a liability rather than a head start.

The "cannot start outside `local`" consequence bites exactly when the first deployed environment appears, which is `FZ-063` itself. Stays open until the pair ships.

### OI-12 — Colleagues signing up separately create unrelated organizations
**Severity:** Gap · **Owner:** `FZ-088` (deferred) · **Found in:** `FZ-080`

Self-serve signup keys on nothing but the email address, so two people at the same company create two organizations that share a domain and know nothing about each other. Each has its own catalog, its own freezes, and its own bill. Nothing merges them and nothing warns either person.

The MVP answer is that support fixes it by hand, which is honest at this volume and unacceptable later.

`FZ-088` is deliberately deferred rather than scheduled: the right fix depends on whether the common case is *join the existing organization automatically* — fast, and wrong for a contractor signing up under a client's domain — or *request access from an administrator*, which is correct and more machinery. One real occurrence answers that. Guessing first does not.

### OI-13 — The connector image is not published, so nothing is installable
**Severity:** Gap · **Owner:** `FZ-099` · **Found in:** `FZ-090`, corrected twice

**This entry has been wrong twice, and both errors are worth keeping visible.**

It first described publication as *discoverability* — "usable by direct reference now, listable later". That was wrong: a connector in a private repository is not usable at all, because `uses:` and `component:` resolve against a repository the customer can read.

It then described the fix as extracting the connectors into a second public repository. That was over-built. The image is the connector (`D-26`), so publishing **one artifact** makes every guideline in `connectors/README.md` work at once — no second repository, no sync, no cross-repo token.

Verified rather than assumed, at the time of writing:

- `docker manifest inspect ghcr.io/freezehubio/freeze-check:v1` → `manifest unknown`
- `git tag` → empty
- the repository is private, and under a personal account

So every guideline currently names an image that does not exist, and the README says so.

**Two of those three facts have since changed** (2026-09-15). The `freezehubio` organization exists, this repository was moved into it, and it is now public — so "private, and under a personal account" no longer holds. That move also removed `FZ-099`'s second blocker rather than satisfying it: `GITHUB_TOKEN` could not write to *another* owner's package namespace, and `freezehubio` is no longer another owner, so no `CONNECTOR_PUBLISH_TOKEN` is needed or configured.

**The fact that matters is unchanged.** `ghcr.io/freezehubio/freeze-check:v1` still does not exist, so every guideline still names an image customers cannot pull. This entry stays open until a `workflow_dispatch` publishes one and its package visibility is set to public.

Discoverability — a Marketplace or Catalog listing — is a separate and lesser problem, deferred to `FZ-096`.

### OI-15 — The deployed cost posture, and which AWS services are actually needed
**Severity:** Decision · **Owner:** `FZ-123` · **Raised:** 2026-09-05 · **Platform decided:** `D-28`

**Update, 2026-09-08 (`FZ-122`).** The platform half of this is closed. **AWS has closed App Runner to new customers**, so the comparison this issue framed cannot be made: the existing ECS Fargate Terraform stays. Sizing is now measured rather than assumed — `0.5 vCPU` and `1 GB`, which is Fargate's smallest legal pairing and not a guess — and the NAT gateway is removable by putting tasks in public subnets. What remains open is the money: every figure below is list-price arithmetic, and `FZ-123` records the first real invoice against it.

`FZ-063` designed a production-shaped AWS environment and it has never been applied. Nothing is deployed and the account spends **$0.007 a month, all S3** (AWS Cost Explorer, four months). Applying it as written costs about **$96 a month with no customers.**

**Where that goes, and why it is worth revisiting:**

| | $/month | |
|---|---|---|
| NAT Gateway | 32.85 | so two idle containers can reach ECR and CloudWatch |
| Fargate, 2 tasks | 28.84 | `backend_desired_count = 2` |
| ALB | 16.43 | TLS and a stable hostname |
| RDS `db.t4g.micro` + 20 GB | 13.98 | |
| Secrets, logs, Route 53, CloudFront, S3, ECR | ~4.40 | |

**64% is redundancy and network plumbing for zero customers.** One task in a public subnet behind the same strict security group removes about $47 and is reversible before any customer's security review. VPC interface endpoints — the "proper" replacement for the NAT — run about $7.20 each for ECR, ECR-DKR, CloudWatch and Secrets Manager, which is *worse* than the NAT at this scale.

**Free tier does not apply.** The account dates from 2022-10-23, so the twelve-month window covering 750 hrs of both ALB and `db.t4g.micro` expired years ago.

**Other providers were assessed and AWS is retained.** Worth recording what the assessment found rather than re-deriving it: the backend has **no AWS coupling at all** — no SDK, nothing in `pom.xml`, nothing in `application.yml`. It needs Postgres over JDBC, an OIDC issuer, SMTP and outbound HTTPS. Every "Cognito" reference is a comment, the `cognito_subject` column name, or the `IdentityProvider` port. Cloud Run, Fly, Render and Railway all bundle TLS and egress, so the $49 of ALB-plus-NAT does not exist as a line item there; the saving against a reduced AWS posture is roughly $15–25 a month. Not decisive, and the portability means this stays cheap to revisit.

**Two things block closing this**, and both are the operator's:

1. Which AWS services are genuinely needed — the open question, deliberately not answered by default.
2. Whether the beta posture becomes real: `backend_desired_count`, subnet placement, and relaxable deletion protection would all have to become variables. As it stands `terraform destroy` cannot run at all, because `deletion_protection` on RDS and Cognito, `skip_final_snapshot = false`, and `prevent_destroy` on both secrets deliberately block it — correct for production, wrong for a pre-customer beta.

Nothing is urgent while nothing is deployed. It becomes urgent the day someone outside the team needs a URL.

### OI-20 — EU data residency is deferred by choosing us-east-1
**Severity:** Decision · **Owner:** `FZ-135` · **Raised:** 2026-09-08

The deployment region was decided as `us-east-1` (the `variables.tf` default) with the residency question knowingly deferred. Latency is not the issue — the Policy API is one HTTPS POST per deploy, and 250 ms from Sydney is nothing against a pipeline step measured in minutes. Residency is.

FreezeHub is sold to companies with a compliance function, and an EU buyer's security review routinely asks where customer data lives. The asymmetry is what makes this worth recording: EU buyers frequently require EU residency, US buyers rarely require US residency, so a single region in the EU would have answered both and cost the same. Changing it before the first apply is a variable; changing it after is a migration of live data.

The seam is in good shape, which is why this is a decision rather than a gap: the backend has no AWS coupling at all — no SDK, nothing in `pom.xml`, nothing in `application.yml` — so a second region is a Terraform workspace rather than a redesign.

**Corrected by `FZ-135`: it becomes urgent at the first `apply`, not at the first EU deal.**
Two things were understated above.

"A migration of live data" is the database, and it is not the expensive half. **A Cognito
user pool is region-bound and cannot be moved**, and the `sub` it issues is what
`users.external_subject` stores — so moving region after `FZ-046` creates the pool means a
new pool, a new subject for every user, and a re-mapping of that column. It is a migration
of who people are, not only of what they own. The pool does not exist yet, which is the
whole of the window.

And the cost of choosing now is smaller than "a Terraform workspace": the configuration is
already parameterised end to end — availability zones are read and sliced rather than
named, and the only pinned `us-east-1` is the CloudFront certificate, which AWS accepts
from nowhere else and which holds no customer data. It is one variable, until it is applied.


### OI-21 — Actuator is on the application's own port, reachable by any administrator of any tenant
**Severity:** Gap · **Owner:** `FZ-123` · **Raised:** 2026-09-09

`/actuator/metrics` and `/actuator/info` are aggregate across every organization — `freezehub.policy.evaluations` counts every customer's deployment checks, and `jvm.*` describes the process. `FZ-065` narrowed them from "any authenticated member" (verified live: a member of one tenant could read them) to ADMINISTRATOR, which shrinks the audience but does not change what they are: figures no customer should see at all.

The real fix is `management.server.port` on a port the load balancer does not publish, so nothing outside the VPC can reach anything but `/actuator/health`. That is a Terraform change — a second container port, a security-group rule, and the health check pointed at it — which is why it belongs to the story that applies the deployment rather than to the review that found it.

### OI-23 — Outbound webhooks reach any host the network can reach
**Severity:** Defect · **Owner:** `FZ-126` · **Found in:** `FZ-125`

`WebhookNotificationSender` posts to a customer-supplied URL, and the only validation is `IntegrationConfigs.requireHttpsUrl` — `startsWith("https://")` plus a `URI.create`. There is no host check anywhere in the codebase: no `InetAddress` resolution, no loopback or link-local test, no allowlist.

Three things make the scheme check weaker than it reads:

- **Redirects are followed.** An attacker-controlled HTTPS endpoint answering with a redirect to an `http://` link-local address reaches it, and on a container runtime that address is where the task's own IAM credentials are served. That is the path from a tenant's administrator to the deployment's cloud account.
- **A userinfo authority satisfies the prefix.** `https://something.example.com@<internal-address>/` starts with `https://` and resolves to the internal host.
- **Validating at save and resolving at send is a gap a DNS name can be moved through.** The check has to be at connect time.

It is blind — `toBodilessEntity()` discards the response — so there is no direct read-back, but status and timing still distinguish an open internal port from a closed one, and the request carries a body to whatever it reaches.

It requires an `ADMINISTRATOR`, so this is escalation rather than anonymous compromise. That does not lower it much: the premise of a multi-tenant product is that a customer's administrator cannot reach its provider's infrastructure.

**Decided in `FZ-125`: the fix is egress, not validation.** A webhook URL is attacker-chosen by design, so the boundary belongs where the connection is made. That makes this the same decision as the deployment's egress posture (`FZ-122`, `FZ-123`) seen from the other side, and the two should be settled together.

**The application half is closed by `FZ-126`** — redirects are not followed, every resolved
address is checked on the way out, and a userinfo authority is refused. Verified by removing
the fix: with redirects followed, the delivery reaches the second address and raises nothing.

**This entry stays open for the network half**, which `FZ-126` always said was the more
durable one and left to `FZ-123`: a security group or an egress proxy, so that the boundary
does not depend on the application resolving a name correctly. The residual gap in the
meantime is a DNS rebind between FreezeHub's resolution and the client's own.

### OI-25 — Token validation for a deployed environment is unspecified
**Severity:** Decision · **Owner:** `FZ-128` · **Found in:** `FZ-125`

There is no `issuer-uri` and no `JwtDecoder` outside the `local` profile, consistent with `OI-2`. So the rules a deployed environment will validate against have never been written, and `FZ-046` would otherwise choose them while implementing them.

The specific hazard is that **Cognito issues ID tokens and access tokens from the same issuer, signed by the same keys**, so signature validation accepts both — and its access token carries the app client in `client_id` rather than `aud`, so an audience validator configured the ordinary way passes everything while appearing to check something.

`FZ-125` wrote the rules into `06-security.md` § Token validation rules. This entry stays open until something enforces them, with a test that watches each rejected shape fail.

### OI-31 — Whether Stripe can be used from Colombia at all is unverified
**Severity:** Decision · **Owner:** needs a story · **Raised:** 2026-09-15

`FZ-084` is DONE and the whole commercial model rests on it: Checkout, the Customer Portal, and a signature-verified webhook that is the only thing allowed to change entitlement. Every one of those assumes a Stripe account that can accept payments.

**Stripe's merchant support is country-bound, and the operator is in Colombia.** Whether a Colombian business can hold a Stripe account that takes payments has never been checked. If it cannot, `FZ-084` is not wrong — it is unreachable, and the options are a merchant-of-record (Paddle, Lemon Squeezy, which also absorb US sales tax and EU VAT) or a US entity holding a US Stripe account.

**It does not block validation**, which invoices by hand (`docs/13-validation.md` §3), and that is the only reason this is a Decision rather than a Defect. It blocks the first self-serve payment, and it shapes the entity decision, so the answer is worth an hour long before either is needed.

Recorded rather than assumed because the cost of being wrong is discovering it at the moment a customer is trying to pay.

### OI-32 — Production would run in a personal AWS account
**Severity:** Gap · **Owner:** `FZ-138` · **Raised:** 2026-09-15

The target account is the operator's personal one, dating from 2022-10-23 (`OI-15`). `infra/README.md` already says Terraform must use an IAM role and not account root — advice a personal account cannot take, because there the operator *is* root.

**The infrastructure itself is account-portable and costs nothing to redirect.** There is no account id anywhere in `infra/`; `locals.tf` reads `aws_caller_identity` and the only use is making the frontend bucket name unique. Pointing the whole stack at another account is a credentials change plus re-running `bootstrap/`.

**What is not portable is identity, and it is the same argument `FZ-135` makes about region.** A Cognito user pool cannot be moved between accounts, and `users.external_subject` stores the `sub` it issues. Before `FZ-046` creates that pool this move is free; after the first real user it is a new pool, new subjects, and a forced password reset for every customer.

What it costs to leave alone: a security questionnaire asks whether production is isolated, who holds root, and whether MFA is enforced, and the honest answers are no, the operator, and maybe. An unrelated suspension of the personal account takes production with it. And `D-23` already requires "an operator with production access", which here can only ever be one person.

The fix is an AWS Organization with the existing account as management and a new member account for production — free, and worth checking for free-tier eligibility, since `OI-15` records that the current account's expired in 2023.

## Resolved

| Issue | Found in | Resolved by |
|---|---|---|
| **`deploy.yml` and `verify.yml` still ran actions targeting Node 20** — GitHub had deprecated that runtime and was force-running those actions on a newer one, so the workflows were relying on a compatibility shim with an end date | `FZ-099` | `FZ-140` — every `uses:` resolved to the runtime its own `action.yml` declares, which found two the issue had missed; the eight on node20 bumped, the ones already on node24 left alone, and the pinning question answered: actions stay on major tags, base images do not (`FZ-139`) |
| **The Dockerfiles followed floating tags, so what shipped changed without a commit** — `eclipse-temurin:21-jre` moved from Ubuntu 24.04 to 26.04 under the project, which is how eight HIGH findings appeared in CI while a local scan of the same tag was clean | `FZ-127` | `FZ-139` — all three `FROM` lines pinned by digest, both backend stages moved to the variant that does not ship `/usr/bin/pebble`, and the scanner now reads the refs out of the Dockerfiles. The baseline is empty |
| **Rate limiting covered only the unauthenticated endpoints** — nothing limited failed API-key attempts, the Stripe webhook, or authenticated traffic, leaving `/api/policy/**` unmetered: the endpoint whose unavailability blocks every customer's deployments, because the connector fails closed | `FZ-125` | `FZ-130` — four limits, the authenticated one counted per API key so that the defence cannot become the outage, and a client that sits out a `429` rather than failing the build (`D-31`) |
| **The dependency tree carried 39 known HIGH/CRITICAL vulnerabilities** — Spring Boot 3.3.4, with seven CRITICALs in the HTTP connector alone. Found the day a scanner was first pointed at it | `FZ-127` | `FZ-136` — Spring Boot 4.1.1 and Tomcat pinned to 11.0.25: 39 to 0, with 468 tests unchanged |
| **Nothing scanned dependencies or images** — three workflows and no scanner of any kind, so nothing in the repository knew whether a dependency had a published vulnerability | `FZ-125` | `FZ-127` — Trivy over the Maven tree, the npm tree and both base images, gated at HIGH, with a dated baseline. It immediately found `OI-29` |
| **No response-headers policy on the distribution** — no CSP, HSTS, `X-Content-Type-Options`, `Referrer-Policy` or `Permissions-Policy`, while the frontend holds its bearer token in `sessionStorage`, which makes a script injection how that token leaves | `FZ-125` | `FZ-129` — derived from what the application loads, and rehearsed against the real bundle before any apply |
| **Restrictions still wore the Industry furniture** — a bordered filter `fieldset` with a legend and the table inside a boxed panel, while Broadsheet takes its structure from the type scale and negative space. The last screen left like it | `FZ-133` | `FZ-134` — chips as the deck draws a multi-select, the system's own unboxed table, and the narrow-screen scroll its comment had always claimed |
| **Layout spacing did not use the design system's scale**, so the system's density was unreachable by changing tokens — and it turned out to be two screens that were never re-pitched rather than the whole application | `FZ-101` | `FZ-133` — 159 token uses, 0 rem literals, px furniture deliberately untouched |
| **`cognito_subject` named a vendor in the schema** — the column holds whatever subject an OIDC issuer put in the `sub` claim, and the backend has no coupling to that provider, so the name asserted one that does not exist | `OI-15` assessment | `FZ-132` — renamed to `external_subject`, constraint and index with it, rehearsed against a clone of the live database |
| **Dark mode was removed with the Industry theme** — `FZ-100` pinned `color-scheme: light` because Industry shipped no dark ramp, and Broadsheet shipped none either, so a reader on a dark system got a light application with no warning | `FZ-100` | `FZ-131` — derived from the ramps' own shared lightness scale, so no module changed |
| **The frontend had no request timeout** — a request accepted and never answered left every screen in its loading state indefinitely, with no error and no retry: the defect `FZ-065` had just fixed on the backend's outbound calls, on the side a customer looks at | `FZ-065` | `FZ-124` |
| **Five scheduled jobs had no distributed locking** — every one ran on every instance, so at the default desired count of two the notification dispatcher delivered each pending row twice and the lifecycle reconciler recorded two activations of one restriction. Latent only because nothing had been applied yet | Milestone 13 planning | `FZ-121` — a `scheduler_lock` row per job, taken in one atomic statement against the database's clock |
| **The deployment-check retention purge never ran** — the scheduled method self-invoked the transactional one, so Spring's proxy was bypassed and the `@Modifying` delete threw `TransactionRequiredException` on every pass. Its test called the inner method on the injected bean, which does go through the proxy, so the suite passed and the only path that runs in production was the one nothing exercised | a running backend, `FZ-113` | `FZ-114` |
| **No rate limiting anywhere** — defensible while `/actuator/health` was the only endpoint reachable without a credential, and a prerequisite for signup, which creates a Cognito identity and sends an email | `FZ-080` | `FZ-087` — per-IP fixed window, in application; it also found that forwarded headers were unconfigured, so a limiter would have bucketed every customer together behind the load balancer |
| **The deployment-check retention lever could not be used** — priced per plan in `11-commercial.md` and carried by `Plan`, but nothing could set it, so every organization sat on the 365-day default whatever they paid | `FZ-081` | `FZ-085` — settable on the settings endpoint and capped by plan, refused with the same `402` as any other limit |
| **The audit trail recorded changes that never happened** — a no-op update compared the client's nanosecond timestamps against the microsecond values PostgreSQL had already truncated them to, and wrote an entry whose `to` value was never persisted | first CI run, `FZ-092` | `FZ-098` — decided (`D-25`): instants are normalised to storable precision at the boundary |
| Deleting a catalog entry referenced by a restriction returned `500` instead of `409` | `FZ-020` | `FZ-036` |
| Deliberate rejection reasons never reached clients — Spring omits `message` from its error body, so every `ResponseStatusException` reason in the API arrived as a bare status code | `FZ-036` | `FZ-036` |
| No API or UI configured notification destinations | `FZ-040` | `FZ-045` |
| No UI created catalog data, so the create-restriction form had nothing to scope to | `FZ-030` | `FZ-036` |
| No way to obtain a token in development before a Cognito pool exists | `FZ-030` | `FZ-035` |
| **Notification retry was unbounded** — a failed delivery was retried on every dispatch pass for ever, with no attempt limit, no backoff and no terminal state | `FZ-041` | `FZ-044` |
| CORS was absent, so every browser request failed preflight with `401` | `FZ-031` | `FZ-031` |
| **The Policy API had no machine credential** — `FZ-051` was ordered before API keys, so the endpoint deciding whether deployments are blocked would have shipped with nothing able to authenticate to it | `FZ-050` | `FZ-052`, taken out of order |
| Any error behind the machine chain came back as `401` — the forward to `/error` is re-filtered and does not match `/api/policy/**`, so it fell through to the human chain and reported a credential failure instead of the real one | `FZ-052` | `FZ-052` |
| Testing Library's DOM cleanup never registered, leaking rendered DOM between tests | `FZ-031` | `FZ-031` |
| **Administrator features existed only in the API** — API keys, the advance-warning setting, and the audit trail could each be reached only with `curl` | `FZ-047`, beta-readiness audit | `FZ-038` and `FZ-039` |
| **Catalog changes were not audited** — renaming an application turned every pipeline using the old name into a refusal, with nothing in the trail explaining when it started or who caused it | `FZ-060` | `FZ-072` |
| **`docs/07-decisions.md` did not exist**, so every architectural decision was recorded only in the backlog entry of the story that made it | ongoing | `FZ-048` created it; `FZ-066` backfilled the rest |
| **The "restriction starting soon" notification was never implemented**, though `00-product.md` lists it — it needed a lead-time decision nobody had made | `FZ-040` | `FZ-047` — per organization, defaulting to 24 hours |
| **Destination credentials and webhook signing secrets were stored in plain text**, readable to anyone with a database connection or a backup | `FZ-040`, `FZ-045`, `FZ-048` | `FZ-049` — decided (`D-3`): AES-256-GCM in the application, behind a port that keeps the Secrets Manager lane open |
| Frontend tests finished with three unhandled rejections — the test router had no route for where the page navigates on success, so React Router threw while unmounting | `FZ-052` | `FZ-037` |
| **Mutable restrictions had no change history** — editing overwrote the previous state with no record of what changed or who changed it | `FZ-023` | decided (`D-1`): audit events with before/after, implemented by `FZ-060` |
| **Webhook deliveries were unauthenticated** — a receiver could not tell a FreezeHub delivery from a forged one, and a forged `CANCELLED` announces that a freeze has been lifted | `FZ-043` | `FZ-048` |
| **An unrecognised application or environment name could bypass a freeze** — it matches no scope list, so evaluating it normally tended toward `ALLOW`; misspelling the environment was a deliberate route to deploying during a freeze | `FZ-050` | `FZ-051` — decided: it blocks, and the response names what was not recognised |
