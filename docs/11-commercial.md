# FreezeHub — Commercial Model

## Purpose

How a company becomes a FreezeHub customer, what it pays for, and what the product does when it stops paying.

Everything before this document assumed the customer already existed. `06-security.md` says an organization's first Administrator is "provisioned out-of-band — no self-service signup", and `scripts/seed-demo.sh` has to insert that row with raw SQL because the API deliberately refuses to. That is the correct description of what exists today, and it is why there is no way to acquire a customer without a developer.

This document specifies the missing half. It is a specification, not a record of what is built: **nothing here is implemented.** Milestone 8 in `08-backlog.md` is the work.

## 1. What is being sold

Not software that stops deployments. FreezeHub cannot stop a deployment and does not try to — enforcement lives in the customer's pipeline, which calls `POST /api/policy/evaluate` and decides what to do with the answer. `10-demo.md` carries the same warning for the same reason: a technical buyer will test the claim.

What is sold is **the authoritative answer, and the record that it was given**:

| The customer's question | What they use today | What FreezeHub replaces it with |
|---|---|---|
| Is there a freeze on right now? | Slack scrollback, a calendar invite, asking someone | One place, one answer |
| Does it apply to *my* service? | Reading a paragraph and guessing | Scope evaluated by the product |
| Did anyone deploy during it? | Nobody can answer | The deployment console |
| Who lifted the freeze, and when? | Nobody can answer | The audit trail |

The last two are what make this a purchase rather than a Slack channel. The first two make it used daily; the last two make it renewed.

## 2. Go-to-market motion

**Hybrid.** Two entry points, one product.

```text
                    Public site
                    /         \
        "Start free"           "Book a demo"
              │                      │
      self-serve signup        demo request
              │                      │
      14-day trial, no card    FreezeHub runs the demo
              │                      │
      Stripe Checkout          provisioned on a plan,
      (monthly or annual)      annual contract, invoiced
              │                      │
              └──────────┬───────────┘
                    same product
```

Self-serve exists so a team can adopt FreezeHub without anyone selling to them, and so the demo video has a working "try it" button rather than a calendar link. Sales-assisted exists because the buyer for a compliance-adjacent tool is usually a manager who wants a conversation, and because annual contracts do not close themselves.

**The two paths converge immediately.** A sales-provisioned organization is an ordinary organization with a different subscription row. There is no enterprise build, no second code path, and no feature that exists only for one motion.

## 3. Pricing

### The metric: registered applications

Price scales with the number of applications in the customer's catalog.

**Why not per seat.** Most engineers never sign in. The value arrives as a Slack message and as an API answer inside a pipeline. Seats therefore undercount usage badly, and — worse — they charge the customer for the exact thing the product needs them to do: tell *everybody*. A freeze that half the organization has not been told about is not a freeze. Users are unlimited on every plan, deliberately, and that is a selling point rather than generosity.

**Why not per evaluation.** Metering the check taxes the behaviour the product depends on. A customer optimising the bill would call the Policy API less, which is precisely how a deployment slips through a freeze. Policy evaluations are unlimited on every plan, and this is a stated commitment, not a current-generosity-subject-to-change.

**Why applications.** It is what scope is built on (`01-domain.md`), it grows as adoption spreads, it is already on screen so the customer can predict their own bill, and it cannot be gamed: `D-14` blocks deployments for applications that are not in the catalog, so leaving an application out to save money breaks that application's pipeline. The incentive points the right way — registering everything is both what the customer wants and what FreezeHub is paid for.

### Plans

| | **Free** | **Starter** | **Growth** | **Scale** | **Enterprise** |
|---|---|---|---|---|---|
| **Freezes actually block** | **advisory only** | yes | yes | yes | yes |
| Applications | 5 | 10 | 50 | 200 | unlimited |
| Monthly | $0 | $99 | $349 | $899 | from $2,000 |
| Annual (2 months free) | — | $990 | $3,490 | $8,990 | contract |
| Users | unlimited | unlimited | unlimited | unlimited | unlimited |
| Teams, environments, restrictions | unlimited | unlimited | unlimited | unlimited | unlimited |
| Policy evaluations | unlimited | unlimited | unlimited | unlimited | unlimited |
| API keys | 1 | 5 | 25 | unlimited | unlimited |
| Notification destinations | 1, any type | 3 | unlimited | unlimited | unlimited |
| Message templates | — | yes | yes | yes | yes |
| Deployment-check retention | 7 days | 90 days | 1 year | 1 year | up to 10 years |
| Audit trail retention | unlimited | unlimited | unlimited | unlimited | unlimited |
| Support | docs | email | business hours | business hours | SLA |
| Purchase | self-serve | self-serve | self-serve | self-serve | sales |

Every price is a starting position, not a finding. They are anchored on what an engineering organization already spends per month on a single mid-tier developer tool, and on the observation that a freeze tool is bought out of a manager's budget rather than a platform-team line item. Expect to move them once real deals happen; the point is to have a number to test, since a pricing page saying "contact us" converts nobody self-serve.

**Two levers are already built and cost nothing to sell:** `deployment_check_retention_days` and `starting_soon_lead_time_minutes` are per-organization settings that exist today (`FZ-047`, `FZ-070`). Retention is the natural paid lever because it is the one a regulated customer will ask about unprompted.

**Audit retention is unlimited on every plan.** Selling back the record of who lifted a freeze would be selling a customer their own compliance evidence at the moment they most need it. Deployment checks are bounded because they are high-volume and contain the deploying engineer's identity (`D-19`); administrative audit events are neither.

### The free plan announces; it does not enforce

The line between Free and paid is **one capability**: a free organization cannot create a `HARD_FREEZE`. Its restrictions are `ADVISORY`, which the Policy API already answers with `ALLOW` plus the matched restriction (`01-domain.md`, Policy rule 2).

**The row never exists, so nothing downstream changes.** Domain rule 3 — *any matching active `HARD_FREEZE` means `BLOCK`* — stays unconditionally true, because a free organization has none to match. `PolicyService` consults no subscription, evaluation stays deterministic (rule 5), and `D-21` holds verbatim: for identical persisted state a billing state still never changes a policy answer. The refusal happens at `POST /api/restrictions`, in the UI, on an administrator — never mid-flight in a pipeline, where `freeze-check.sh` fails closed and a refusal would be an organization-wide outage. See `D-33`.

**The paywall then advertises itself for the cost of one string.** `PolicyEvaluationResponse.message` is documented as "the line worth printing in a build log", and `freeze-check.sh` prints it verbatim on `ALLOW`. So every deploy through a free organization's active freeze tells the engineer, in their own build log, that this deployment would have been refused on a paid plan. That fires on the person who feels the pain and cannot sign, which is how it reaches the person who can.

**Three deliberate choices inside the free plan:**

- **Freezes announced stay unlimited.** Capping them caps the activity that generates the pain that sells the product. A team that stops announcing at five a month stops using FreezeHub, and a lapsed free user converts at zero. Cap breadth — applications, channels, retention — never the core verb.
- **One destination, any type, Slack included.** Email-only would send announcements where announcements go to die, so the free tier would underperform and never produce the moment that converts. The paywall is the *second* audience, which is exactly when a second team needs telling.
- **Five applications, not three.** A squad running a frontend, an API, a worker, a migrator and a cron has to be able to succeed. The limit is self-enforcing anyway: `D-14` blocks unregistered applications, so under-registering to dodge it breaks the customer's own pipeline.

### Annual and the sales path

Annual is 10 months for 12 on the self-serve plans. Enterprise is annual-only, invoiced, and priced per deal — it exists because unlimited applications, longer retention, and an SLA are the three things that come up in every conversation with a company large enough to have a compliance function.

## 4. The self-serve signup flow

```text
  Public site
      │  "Start free trial"
      ▼
  name · work email · company name
      │  POST /api/signup      (unauthenticated, rate limited)
      ▼
  organization  status = PENDING_VERIFICATION
  users         role   = ADMINISTRATOR
  subscription  status = TRIALING, trial_ends_at = now + 14 days
  Cognito       AdminCreateUser → temporary password emailed
      │
      ▼
  202 Accepted — "check your email"        (always, see below)
      │
      ▼
  first successful sign-in → organization status = ACTIVE
      │
      ▼
  onboarding checklist: environments → applications → freeze-check → first restriction
```

### Rules

**The response never varies.** `POST /api/signup` returns `202 Accepted` with the same body whether the organization was created, the email was already used, or the company name is already taken. Otherwise signup is a customer-enumeration oracle: anyone could learn which companies use FreezeHub by trying their domains. This is the same reasoning that makes a cross-tenant resource return `404` and not `403` (`04-api.md`) — existence is never revealed to someone not entitled to know it.

**It is the first unauthenticated write endpoint in the product.** Today the only endpoint reachable without a credential is `/actuator/health`. Signup and demo requests change that, and neither can ship without rate limiting, which does not exist anywhere in the codebase (`OI-11`).

**Unverified organizations are purged.** An organization still `PENDING_VERIFICATION` after 7 days is deleted, along with its user and its Cognito identity. Without this, every abandoned signup and every abusive one is permanent. The purge follows the pattern of the lifecycle reconciler and the deployment-check retention job.

**Free-mail addresses are accepted.** Blocking `gmail.com` is standard B2B practice and it would be wrong here: a two-person startup evaluating a freeze tool is exactly the customer self-serve exists for, and they have not set up a domain. The cost is more junk signups, which the purge bounds.

**Nothing about the trial is a different product.** Every feature is available for 14 days. A trial that hides the audit trail or the deployment console hides the two things that make the product worth buying (§1).

### Trial expiry

**Day 14 with no subscription moves the organization to the `FREE` plan, status `ACTIVE`** — not to `SUSPENDED`. A trial that ends in a read-only account loses the customer entirely; one that ends in a working free tier keeps a live account at the top of the funnel, still announcing freezes, still telling the engineer on every deploy what a paid plan would have done.

Per `D-22` the drop is never retroactive. Hard freezes created during the trial keep their level and keep blocking; API keys issued during it keep working. Both are bounded — restrictions are time-bounded and complete on their own, so the leak self-heals, and a pipeline that keeps checking is adoption rather than abuse.

**`SUSPENDED` still exists and is unchanged. It is where paid churn goes, and nowhere else.**

| | Behaviour when suspended |
|---|---|
| **Policy API** | **Keeps answering, unchanged.** |
| Human UI | Read-only. Existing data visible; nothing can be created or edited. |
| Notifications | Stop. |
| Restrictions | Keep being enforced exactly as before. |

**A billing state never changes a policy answer.** If suspension made `/api/policy/evaluate` start returning `401`, every pipeline at that customer would break at once, on a schedule the customer did not set — FreezeHub would have caused an outage over an unpaid invoice. And if suspension made it return `ALLOW`, an active freeze would silently lift, which is worse: the product would have failed at its only job, quietly, at the moment of a commercial dispute.

Suspension is therefore a **grace state, not a kill switch**, and it lasts 30 days. Deactivation after that is a deliberate human action at FreezeHub, communicated first — never a scheduled job. See `D-21`.

**A paying customer must never fall to `FREE`.** Their hard freezes are in force, and a plan that can only advise would silently stop enforcing them — the exact failure `D-21` calls worse than an outage, arriving as a side effect of a billing event. Only a trial expires into `FREE`; non-payment goes to `SUSPENDED`, where restrictions keep being enforced. That separation is load-bearing and wants its own test.

## 5. The sales-assisted flow

```text
  Public site "Book a demo"
      │  POST /api/demo-requests   (unauthenticated, rate limited)
      ▼
  demo_request row  →  Slack notification to FreezeHub's own workspace
      │
      ▼
  demo call
      │
      ▼
  scripts/provision-organization.sh  — run by a FreezeHub operator
      │
      ▼
  organization ACTIVE · subscription ACTIVE on the agreed plan · admin invited
```

The demo request captures name, work email, company, approximate team size, and a free-text message. It is stored rather than only emailed, so the pipeline is inspectable and so a request is never lost to a mail filter.

**Built by `FZ-083`.** The request is stored first and announced second, which is the important ordering: the lead is the row, so a Slack outage or a webhook nobody configured cannot lose it.

It does *not* reuse the notification module, though this document said it would. That module is restriction-shaped — a notification needs an organization and a restriction, and a demo request has neither. It reuses `RetryPolicy` and keeps its own four-column outbox, which is smaller than the change reuse would have required.

### There is no platform super-administrator

Provisioning is a **script run by a FreezeHub operator with production access**, not an in-product admin console.

The security model of this product is one sentence: *the organization is always resolved from the credential, never from the request* (`04-api.md`, `CLAUDE.md` §5). A principal that can act across tenants is the exact negation of that sentence, and it would have to be reasoned about at every endpoint, forever, to protect against one bug that exposes every customer to another.

At fewer than roughly twenty customers, provisioning happens rarely enough that a script is both safer and cheaper. Revisit when provisioning becomes weekly, or when somebody who should not have production access needs to do it — and when that happens, build it as a separate deployable with its own credential, not as a role inside the tenant application. See `D-23`.

## 6. Billing

### The rail is not settled — Stripe is unavailable to the operator

**Checked in `FZ-148`, and it changes who can be paid rather than how.** Stripe does not support businesses in Colombia: its availability page lists Brazil and Mexico for Latin America and omits Colombia entirely, and its support material states that payments are not supported there. Everything below describes an integration that is built, tested and correct — and that currently has no account to point at.

Nothing in it needs redesigning. **The webhook stays the only thing that may change entitlement** whatever rail is chosen; that rule is about trusting a browser redirect, not about Stripe. A merchant of record would replace the integration; a US entity would keep it exactly as written.

It does not block validation: `13-validation.md` §3 invoices the first customers by hand, deliberately, so that no payment rail sits on the critical path. The decision is due before the first *self-serve* payment. See `OI-31`.

### Stripe, with entitlement staying in FreezeHub

- **Stripe Checkout** hosts the payment page. Card details never reach FreezeHub, so FreezeHub is never in PCI scope.
- **Stripe Customer Portal** handles card changes, plan changes and cancellation. No billing UI to build beyond the two buttons that open these.
- **Stripe is the source of truth for payment. FreezeHub is the source of truth for entitlement.** The `subscription` row is what the product reads to decide what a customer may do, and it is written *only* from verified webhook events — never from a client claiming it just paid, and never from a Checkout redirect, which is a browser navigation anyone can forge.

### The webhook

`POST /api/webhooks/stripe` is authenticated by Stripe's `Stripe-Signature` header, verified against the endpoint signing secret — HMAC over timestamp and body, which is the same construction FreezeHub already uses for its own outbound webhooks (`D-2`), pointed inward.

It gets **its own security chain**, matching `/api/webhooks/stripe/**`, alongside the human chain and the `/api/policy/**` machine chain. Same reason as `FZ-052`: a credential that works on one boundary must not work on another. No CORS — a browser has no business calling it.

Events handled:

| Event | Effect |
|---|---|
| `checkout.session.completed` | `TRIALING` → `ACTIVE`, plan set, Stripe ids stored |
| `customer.subscription.updated` | plan and period end updated |
| `invoice.payment_failed` | → `PAST_DUE`; notify the administrator |
| `customer.subscription.deleted` | → `SUSPENDED` at period end |

**Deliveries are idempotent.** Stripe redelivers on any non-2xx and sometimes on a 2xx it did not see. Every processed `event_id` is stored and duplicates are ignored, or a redelivered `checkout.session.completed` double-counts a conversion and a redelivered `subscription.deleted` suspends a customer who has already re-subscribed.

**Every subscription change is audited**, with `stripe` as the actor, using the existing audit trail. "Why did we get downgraded on the 3rd?" must be answerable, and the answer must not require reading Stripe's dashboard.

### Limit enforcement

**Built by `FZ-081`.** Applications, API keys and notification destinations are enforced on creation; a refusal is `402` with the plan, the limit and the current count as extensions, so a UI can say "10 of 10 applications used" rather than "something went wrong".

Two things the specification did not anticipate, both decided during implementation:

- **Revoked API keys do not count.** The limit is on live credentials. Counting a revoked one would charge a customer for rotating a key, discouraging exactly the habit that limits the damage of a leaked CI variable. Disabled notification destinations *do* count — they are still configured, still hold an encrypted credential, and are one toggle from sending.
- **An organization with no subscription row gets everything, not nothing.** That state is a defect and is logged as one, but refusing on it would make a customer's API read-only because of a bug in our billing data — the failure `D-21` exists to prevent. It fails towards not billing, which is a conversation, rather than towards not working.

**Retention is not yet enforced** (`OI-14`): `Plan` carries the number, but nothing can set `deployment_check_retention_days`, so every organization sits on the 365-day default whatever they pay. That row of the table above is currently aspirational.



**Refused on creation, never applied retroactively.** Creating application number 11 on Starter is refused. A Growth customer with 30 applications who downgrades to Starter keeps all 30 — nothing is deleted, disabled, or hidden — and simply cannot create number 31 until the count is under the limit.

Deleting applications on a downgrade would silently narrow every restriction scoped to them, which un-freezes deployments as a side effect of a billing event. That is the same failure `FZ-020` refused to allow the database to cause, and the reasoning has not changed. See `D-22`.

The refusal is **`402 Payment Required`**, as `application/problem+json`, carrying the plan, the limit, and the current count so the UI can say "10 of 10 applications used" rather than "something went wrong". `402` because the request was well-formed and the caller is permitted — it is the *plan* that refuses, which no other status code says.

### In-product

A **Billing** section in Settings, Administrator-only, showing the plan, usage against each limit, days left in trial, and one button that opens Stripe. A trial banner appears organization-wide in the last 5 days — to everyone, not only administrators, because the person who notices the trial ending is rarely the person who signs.

## 7. What this deliberately does not do

| Not doing | Why |
|---|---|
| Usage-based billing on evaluations | Taxes the behaviour the product needs (§3) |
| Per-seat pricing | Charges for telling people, which is the product (§3) |
| Metered overage | Unpredictable bills lose renewals; a hard limit with an upgrade prompt is honest |
| In-product super-admin | Negates the tenant-isolation invariant (§5) |
| Self-serve Enterprise | Unlimited applications and 10-year retention are a conversation, not a checkbox |
| Domain-based organization joining | Two colleagues signing up create two organizations (`OI-12`) |
| Dunning emails, invoices, tax | Stripe does all four; building them is duplicated work |

## 8. Open questions

Neither blocks Milestone 8, and both want a real customer before being answered.

- **What happens to a suspended organization's data at deactivation.** Export first, presumably, but the format and the window are unknown until somebody asks for them.
- **Whether Starter's 90-day check retention is too short to be useful.** It is the one limit that could make the cheapest plan fail at the job it was bought for. Watch the first ten customers.
