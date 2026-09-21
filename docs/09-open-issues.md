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

### OI-36 — Nothing can be forgotten
**Severity:** Gap · **Owner:** needs a story · **Found in:** `FZ-161`

There is no way to delete a user, no way to delete an organization, no export, and no
pseudonymization on erasure. Not in the API, not in a script. **Every account ever created is
permanent**, along with its Cognito identity and every `deployment_check` row naming a
deploying engineer.

Four capabilities, one issue, because they are the same request arriving from different
directions: a departing employee, a cancelled customer, an access request, an erasure demand.
Building any one of them alone leaves the others answered by hand against the database.

**Deletion closes SOC 2 CC6.3 and ISO A.5.18**, and pseudonymization is what lets erasure
coexist with the unlimited audit retention `11-commercial.md` sells — one random token per
erasure, applied to every row naming the person, mapping stored nowhere. The event survives,
the attribution does not.

**Urgent before `FZ-082`, not after.** While accounts are hand-provisioned this is a gap.
Self-serve signup turns it into a promise that cannot be kept, made to people nobody has met.

### OI-37 — `demo_request` has no retention and no provable autorización
**Severity:** Gap · **Owner:** needs a story · **Found in:** `FZ-161`

Two problems in the one table FreezeHub is *responsable* for.

**No retention.** Rows are kept forever, holding name, email, company and free text about
people who never became customers. Empty today, which is why it reads as a design note rather
than a defect — `FZ-111` is what fills it.

**No provable autorización.** Ley 1581 Art. 9 makes *autorización previa, expresa e informada*
the general basis, and Art. 8(b) gives the titular the right to demand **proof** of it. Proof
means the timestamp and the exact text shown at the moment it was given. `demo_request` has a
column for neither, so **a checkbox on the form would not close this** — it is a schema
change.

### OI-38 — Encryption key rotation cannot be staged
**Severity:** Gap · **Owner:** needs a story · **Found in:** `FZ-161`

`AesGcmSecretProtector` stores values as `fzenc1:` plus Base64. That prefix is a **format**
version, not a **key** identifier, so nothing records which key encrypted a given value and
two keys cannot be live at once.

Rotation is therefore an all-at-once re-encryption with downtime, and a partially completed
one is unrecoverable — there is no way to tell a value encrypted under the old key from one
encrypted under the new.

**A key identifier in the prefix is a small change now and a live-data migration later.**
Nothing is deployed, so today it costs almost nothing.

### OI-39 — Provisioning writes tenant rows and records nothing
**Severity:** Gap · **Owner:** needs a story · **Found in:** `FZ-161`

`scripts/provision-organization.sh` inserts directly into `organization`, `users` and
`subscription`, and writes no `audit_event`.

Provisioning is a script rather than an admin console on purpose (`D-23`), which means an
operator with production database credentials is a standing part of the design. A privileged
path that creates identities and leaves no trace is the first finding an access review
produces, and there is no way to answer "who created this organization, and when" without it.

### OI-40 — `notification.last_error` is unbounded
**Severity:** Gap · **Owner:** needs a story · **Found in:** `FZ-161`

An unbounded `TEXT` column holding a failed delivery's error, with no retention rule and no
classification.

If that error ever carries a response body from a customer's webhook endpoint, arbitrary
third-party content lands in a column nobody classified and nothing purges. Needs a length cap
and a stated rule about what may go in it.

### OI-41 — No contract or notice documents exist
**Severity:** Gap · **Owner:** needs a story · **Found in:** `FZ-161`

No DPA, no published subprocessor list, no privacy notice, and no Política de Tratamiento de
Datos Personales — the last of which is a **statutory** instrument under Decreto 1377 Art. 13
for a Colombian *responsable*, not an optional courtesy.

`17-data-protection.md` supplies the structure and the substance for all four. What it cannot
supply is the wording, the company's identifying details, or the confirmation of whether the
databases must be registered in the RNBD — that threshold turns on total assets in UVT and
wants checking against the current figure.

**This blocks selling, not just compliance.** *"Send us your DPA"* and *"who are we actually
buying from"* are two of the objections in `gtm/02-objections.md` most likely to end a real
deal.

### OI-42 — No breach register
**Severity:** Gap · **Owner:** needs a story · **Found in:** `FZ-161`

There is no register of security incidents and no runbook for one.

As *encargado* every affected *responsable* must be told without undue delay; as *responsable*
the SIC must be informed. Neither has a procedure, and **every incident should be recorded
whether or not it was notifiable** — the register is what an auditor asks to see, and "we have
had none" is not evidence of anything.

### OI-43 — GitHub Actions cannot authenticate to AWS on the managed experience
**Severity:** Blocker (for CI only) · **Owner:** `FZ-138` · **Found in:** `FZ-170`

`infra/shared/github-oidc.tf:7` creates an `aws_iam_openid_connect_provider`. AWS's new
sign-up experience denies `iam:*Provider*` through a service control policy that applies on
the Free Tier *and* the Paid Plan and that "cannot be modified", so **`terraform apply` on
`infra/shared/` fails** and `build-image.yml`, `deploy-frontend.yml` and
`deploy-singlebox.yml` lose the only credential path they have (`FZ-064`).

**Confirmed against the live account** (`FZ-171`), not just the published policy:
`iam:ListOpenIDConnectProviders` returns `AccessDenied` with *"an explicit deny in a service
control policy"*, naming one of the AWS-managed policies. The same check found `D-32` wrong — see below.

**Nothing else is blocked.** `iam:CreateRole` is permitted and every service the one-box
posture needs works in `us-east-2`, verified by calling each one. `D-35` is unaffected.
`D-32` was **not** — `us-east-1` turned out to permit only global services, so the region
moved to `us-east-2` (`FZ-171`). This issue is CI alone.

**The fix is activating advanced features** (`D-37`), which is irreversible and removes the
project's enforced spend limit. Until then deploys are by hand, which means no record of
what shipped — the thing `FZ-152` built the workflows to provide. The stopgap is acceptable
only while nothing is live.

**This is not what blocks the first deploy, and it is worth being clear about the order.**
`infra/shared/` has two required variables with no default — `domain_name` and
`hosted_zone_id` — and creates an `aws_acm_certificate_validation` that blocks until DNS
resolves. So `shared/` cannot be applied without a domain whatever happens to the OIDC
provider, and the chain is:

```
domain → hosted zone → shared/ → Cognito pool → FZ-046 → singlebox/ → deploy → CI
```

This issue bites at the last step. Anything done about it before the domain exists is
speculative.

**When it does bite, the split is small.** Scoped while investigating, so it is not
re-derived: `github-oidc.tf` holds three resources and nothing outside the file references
them except two outputs in `outputs.tf`. It depends on exactly three things from the rest
of `shared/` — `aws_ecr_repository.backend.arn`, `aws_s3_bucket.frontend.arn` and
`aws_cloudfront_distribution.frontend.arn`.

**Resolved as a `count` toggle** (`FZ-174`): `var.github_oidc_enabled`, default `false`,
on the provider, the assume-role policy document, the role and its inline policy, with
`one()` on the two outputs. The alternative — a fourth root module, matching `FZ-159`'s
precedent of lifecycle boundaries as module boundaries — was rejected because these
resources *share* the shared estate's lifecycle. They are absent only because of an
account capability we intend to remove, and a module boundary would assert a difference
that is not there.

So `shared/` applies today with the toggle off. **Turning it on is the last step of
activating advanced features**, not a separate migration: set `github_oidc_enabled = true`
and apply again. Until then `github_deploy_role_arn` and `github_deploy_role_name` are
`null`, which is what `../ecs` and the workflows would read.

**The registrar does not matter, and AWS refuses to be one.** `route53domains:*` is
permitted by the SCP and the API answers — but an actual registration fails with *"We can't
finish registering your domain. Contact AWS Support"*. Amazon Registrar applies
fraud-prevention checks to new accounts, and the restriction is account-level and removable
only through a support case (Basic support covers it, free).

Nothing depends on it. `shared/` asks for `domain_name` and `hosted_zone_id` and never asks
who the registrar is, so **register anywhere and delegate the nameservers to a Route 53
hosted zone** — which costs about $0.50 a month and is not blocked. The support case is
worth opening only if AWS as registrar is wanted for its own sake.

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

**Free tier does not apply — and the tier itself has since changed.** The personal account dates from 2022-10-23, so the twelve-month window covering 750 hrs of both ALB and `db.t4g.micro` expired years ago. AWS has since replaced that model with credits — $100 on signup and up to $100 more — which is one reason `FZ-168` starts the estate on a fresh account rather than this one. Neither model applies to the account these figures were measured in.

**Other providers were assessed and AWS is retained.** Worth recording what the assessment found rather than re-deriving it: the backend has **no AWS coupling at all** — no SDK, nothing in `pom.xml`, nothing in `application.yml`. It needs Postgres over JDBC, an OIDC issuer, SMTP and outbound HTTPS. Every "Cognito" reference is a comment, the `cognito_subject` column name, or the `IdentityProvider` port. Cloud Run, Fly, Render and Railway all bundle TLS and egress, so the $49 of ALB-plus-NAT does not exist as a line item there; the saving against a reduced AWS posture is roughly $15–25 a month. Not decisive, and the portability means this stays cheap to revisit.

**Two things block closing this**, and both are the operator's:

1. Which AWS services are genuinely needed — the open question, deliberately not answered by default.
2. Whether the beta posture becomes real: `backend_desired_count`, subnet placement, and relaxable deletion protection would all have to become variables. As it stands `terraform destroy` cannot run at all, because `deletion_protection` on RDS and Cognito, `skip_final_snapshot = false`, and `prevent_destroy` on both secrets deliberately block it — correct for production, wrong for a pre-customer beta.

Nothing is urgent while nothing is deployed. It becomes urgent the day someone outside the team needs a URL.

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

### OI-32 — Production would run in a personal AWS account
**Severity:** Gap · **Owner:** `FZ-138` · **Raised:** 2026-09-15

The target account is the operator's personal one, dating from 2022-10-23 (`OI-15`). `infra/README.md` already says Terraform must use an IAM role and not account root — advice a personal account cannot take, because there the operator *is* root.

**The infrastructure itself is account-portable and costs nothing to redirect.** There is no account id anywhere in `infra/`; `locals.tf` reads `aws_caller_identity` and the only use is making the frontend bucket name unique. Pointing the whole stack at another account is a credentials change plus re-running `bootstrap/`.

**What is not portable is identity, and it is the same argument `FZ-135` makes about region.** A Cognito user pool cannot be moved between accounts, and `users.external_subject` stores the `sub` it issues. Before `FZ-046` creates that pool this move is free; after the first real user it is a new pool, new subjects, and a forced password reset for every customer.

What it costs to leave alone: a security questionnaire asks whether production is isolated, who holds root, and whether MFA is enforced, and the honest answers are no, the operator, and maybe. An unrelated suspension of the personal account takes production with it. And `D-23` already requires "an operator with production access", which here can only ever be one person.

**The fix is a fresh account, and it is no longer the Organization this issue proposed.** `FZ-168` established that a personal identity in permanent control of production is the whole of the objection, and that an account owned by `freezehubio@gmail.com` answers it. The Organization on top was refused by AWS's own terms: joining one expires a new account's free credits immediately — about $200, or eleven months at `D-35`'s $18 a month — so it is deferred to a named trigger (`D-36`, `16-accounts.md` §7).

**What stays open until `FZ-138` runs.** The account does not exist yet, and one consequence of the single-account shape is new: with no Organization there is no Identity Center path into the account, so a long-lived IAM access key exists on one laptop. It is scoped to `sts:AssumeRole` on one role and refused without MFA, and no key reaches CI (`FZ-064` uses OIDC) — but it is a real residual, and removing it is step 5 of the migration.

### OI-34 — Free organizations never expire, and nothing bounds them
**Severity:** Gap · **Owner:** needs a story · **Found in:** `FZ-142`

Every organization today either converts or is purged: a trial ends, and `11-commercial.md` §4 already deletes anything still `PENDING_VERIFICATION` after seven days. The `FREE` plan introduced by `FZ-142` has no such bound — a free organization is `ACTIVE` indefinitely, and they accumulate.

The infrastructure cost is genuinely small, and that is not the point. Seven-day deployment-check retention bounds a free organization's storage at a fraction of a paying one's, so a thousand of them cost close to nothing. What is unbounded is the number of Cognito identities, notification destinations holding encrypted credentials, and API keys reaching the Policy API — none of which any signed-in human is watching.

**The shape of the answer is probably an inactivity policy** — no sign-in and no policy evaluation for twelve months triggers an email, then deactivation after thirty days — following the purge and the lifecycle reconciler that already exist. It is deliberately not decided here: the right window is a product judgement nobody can make before seeing how a real free organization behaves.

Recorded now because the repository's own rule is that "no owner" is not a status, and because a tier with no exit is the kind of thing that is invisible until somebody runs a query.

## Resolved

| Issue | Found in | Resolved by |
| **Whether `FZ-123` was superseded by the one box** — two readings of the same story were live at once, and it still claimed `OI-15` and `OI-21` | `FZ-160` | `FZ-162` — it is the beta apply, now pointing at `shared/` then `singlebox/`. `FZ-159` had already rewritten it to say so; a conflict resolution had split the entry, leaving the old body under the heading and the new one orphaned inside Milestone 14 |
| **The connector image was not published, so nothing was installable** — every guideline in `connectors/README.md` named an image that did not exist, and the entry was wrong twice before settling on publishing one artifact rather than extracting a second repository | `FZ-090` | `FZ-099` — `ghcr.io/freezehubio/freeze-check:v1` is public, `linux/amd64` and `linux/arm64`, and verified by pulling it anonymously and watching it exit 2 when FreezeHub is unreachable |
| **Stripe cannot be used from Colombia, and `FZ-084` assumes it can** — Stripe supports Brazil and Mexico in Latin America and not Colombia, so a built, tested and correct billing integration had no account to point at | `FZ-137` | `FZ-148` verified it against Stripe's own availability page, and `D-34` chose Paddle as merchant of record. Implementation is `FZ-149` |
| **EU data residency was deferred by letting a default choose the region** — `us-east-1` was never decided, it was the `variables.tf` default, and a region cannot be changed after the first apply without moving the database *and* re-creating every identity | `FZ-080` | `FZ-135` removed the default and priced the choice; `FZ-141` records the decision (`D-32`): `us-east-1`, knowingly, with the EU answer accepted as a cost |
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
