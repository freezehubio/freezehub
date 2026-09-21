# FreezeHub — Security Brief

Written to be handed to a security reviewer. Every answer describes what is true today; where
something does not exist, it says so rather than describing an intention.

## What FreezeHub holds about you

Narrower than most tools you are reviewing, and worth establishing first:

- the names your team typed — teams, applications, environments, and the text of a freeze;
- who made each administrative change, by email;
- for each deployment check, the application and environment asked about, the decision, and
  optionally the deploying engineer's identity, the commit reference and the pipeline URL;
- the destination configuration for your notifications.

**Not held:** your source code, your build artifacts, any credential into your systems, and
any access to your repositories, cloud accounts or pipelines. The integration is one outbound
HTTPS request from your CI job. There is nothing for FreezeHub to reach into.

## Authentication

**People** authenticate against Amazon Cognito and present a JWT. The token is validated
against the pool's JWKS on every request.

**Machines** present an API key in `X-API-Key`. A separate header from a separate filter
chain, so the two mechanisms cannot be confused for one another.

API keys are 256 bits of `SecureRandom`, shown **once** at creation and stored **only as a
SHA-256 hash** — the raw value is not recoverable from FreezeHub by anyone, including us.
A key reaches the policy endpoint and nothing else: it cannot read your data, change a
freeze, or mint another key. **A key leaked from CI is not an account takeover.**

Revocation is one-way. A withdrawn key is never reinstated, because a key is withdrawn when
a copy may exist elsewhere and restoring it would revive that copy.

## Tenant isolation

Your organization is resolved **server-side from the authenticated credential, on every
request**. An organization identifier supplied by a client — in a header, path, query or
body — is never treated as authorization, and every tenant-owned read and write is filtered
by the resolved value.

A request for another tenant's resource returns `404`, not `403`, so the API cannot be used
to discover what exists.

## Encryption

**In transit:** TLS everywhere, with certificates issued and renewed automatically.

**At rest:** the database volume is encrypted. Credentials that must be recoverable —
a Slack webhook URL is itself a credential, and an HMAC signing secret cannot be hashed
because signing needs the key — are additionally **encrypted in the application with
AES-256-GCM before reaching the database**, so a database dump or backup yields ciphertext.

This does not protect against a compromised application, which holds the key. We would
rather state that than imply otherwise.

## Outbound webhooks

Every delivery is signed: `X-FreezeHub-Signature`, HMAC-SHA256 over the timestamp and the
raw body, with the timestamp signed alongside it so a captured delivery cannot be replayed.
Verify in constant time and reject anything outside a five-minute window.

Signing secrets are rotatable, and rotation is immediate with no overlap window — an overlap
would keep a leaked secret working for exactly as long as it lasted.

## Where your data lives

**AWS, `us-east-2`.** PostgreSQL runs with its volume on encrypted storage and is not
reachable from outside the application. Runtime secrets are held in AWS Systems Manager
Parameter Store, never in the repository or an image.

EU residency is not offered today. The application has no AWS coupling of its own, so a
second region is a deployment change rather than a redesign — but we are not going to give
you a date for something that is not built.

## Retention and deletion

| | Kept |
|---|---|
| Deployment checks | your configured window, 7 days to 10 years by plan; enforced by a daily job |
| Audit trail | unlimited, on every plan |
| Accounts | for the life of the organization |
| Backups | nightly database dumps with lifecycle expiry |

**Audit retention is unlimited on every plan, deliberately.** Selling back the record of who
lifted a freeze would be selling you your own compliance evidence at the moment you most
need it.

## Data protection

FreezeHub is a **processor** for everything your organization puts into the product, and a
**controller** only for what we collect ourselves — a demo request, the signup record,
billing details. A subject request about your users is forwarded to you, because the decision
is yours to make, not ours.

The operating company is Colombian, so Ley 1581 de 2012 applies to our own processing. The
full internal analysis — every store classified, retention per class, and the procedures for
access, erasure, cancellation and breach — is `docs/17-data-protection.md` in the product
repository and we are happy to walk through it.

**Subprocessors:** Amazon Web Services (hosting), Paddle (payments, as merchant of record),
a transactional email provider, and Slack where you configure that channel yourself.

## What does not exist yet

Stated plainly, because you will find out anyway and it is better from us.

- **There is no SOC 2 report and no ISO 27001 certificate.** Nothing has run in production
  long enough to produce evidence for either. If your process hard-requires a report, we are
  not a fit yet, and we would rather say that in the first meeting than the fourth.
- **No signed DPA, subprocessor page or privacy notice is published yet.** The substance
  exists; the documents are being drafted with counsel.
- **Deleting a user or an organization is done by us, on request**, rather than by you in the
  product. It is a known gap with a fix planned, and it means an erasure request is handled
  by hand today.
- **Backups are taken nightly and a restore has not yet been drilled end to end.** An
  untested restore is not a backup, and we are not going to claim one until somebody has run it.
- **No uptime SLA below the Enterprise plan**, and no availability figure worth quoting —
  there is not enough production history to compute an honest one.

## Questions we expect, answered

**"What happens if FreezeHub is down?"** Your pipeline decides. The connector fails closed by
default, so a freeze is not silently lifted by our outage; set `FREEZEHUB_ON_ERROR=allow` if
you would rather deploy. We would rather you make that choice knowingly than inherit ours.

**"Can FreezeHub stop our deployments?"** No. It answers a question your pipeline asks. We
hold no credential capable of acting on your systems.

**"Who is the seller of record?"** Paddle, which registers for and remits sales tax and VAT
itself. Early customers are invoiced directly while that is being set up.

**"Can we self-host?"** Not today, and it is not on the near roadmap.
