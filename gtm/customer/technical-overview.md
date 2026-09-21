# FreezeHub — Technical Overview

For the engineer evaluating whether this fits, and how much work it is. Everything below is
what the product does today.

## The shape of it

One deployable backend, one database, one API. **Java and Spring Boot, PostgreSQL, a React
front end, REST over JSON.** No message broker, no Kubernetes, no agents in your
infrastructure, no access into your systems.

FreezeHub holds names your team typed, the identity of whoever deployed, and the record of
what was asked and answered. **It has no credentials into your repositories, your cloud, or
your pipelines** — the integration runs one outbound HTTPS request from your CI job.

## The model

A **restriction** is one announcement with a window and a scope.

- **Level** — `HARD_FREEZE` blocks; `ADVISORY` announces and permits.
- **Scope** — any combination of teams, applications and environments. Scoping to nothing
  means the whole organization.
- **Window** — start and end, stored and compared in UTC. A restriction moves through
  scheduled → active → completed on its own.

Applications and environments are named, not numbered: your pipeline sends
`payments-api` / `production`, not an internal id it would have to look up.

## Asking the question

```http
POST /api/policy/evaluate
X-API-Key: <key>
Content-Type: application/json

{
  "action": "DEPLOY",
  "application": "payments-api",
  "environment": "production",

  "actor": "alice@example.com",
  "reference": "a1b2c3d4e5f6",
  "source": "https://gitlab.example.com/acme/payments-api/-/pipelines/9182"
}
```

The last three are optional and make the record worth reading — who, what commit, which run.
FreezeHub cannot discover them; the API key identifies the pipeline, never the person. The
connector fills them from whatever your CI system already exposes.

The response carries the decision, the reason, and a line worth printing in a build log.

**It is a `POST` even though it reads**, deliberately: a `GET` is cacheable, and a cached
`ALLOW` served during a freeze is exactly the wrong answer. No proxy can answer this.

**An unregistered application or environment is refused.** Misspelling `prodution` would
otherwise match no scope and tend toward `ALLOW`, which makes a typo a route through a
freeze. The response names what was not recognised.

## Integrating

One container image, `ghcr.io/freezehubio/freeze-check:v1`, public, `amd64` and `arm64`.

| CI system | How |
|---|---|
| GitHub Actions | an action step |
| GitLab CI | a component, or the image directly |
| Jenkins | the image in a pipeline stage |
| Anything else | run the container, or call the endpoint with `curl` |

The same binary in every case. A team on GitLab and a team on Jenkins get the same answer
from the same freeze — per-ecosystem plugins drift in exactly the places that matter, like
what a timeout means or whether a `401` fails open, and that drift shows up as one team
deploying during a freeze that stopped another.

**Failure behaviour is yours to choose, and safe by default.** `FREEZEHUB_ON_ERROR` defaults
to `block`: if the API cannot be reached, the pipeline does not get an `ALLOW`. Set it to
`allow` if availability matters more to you than the gate. Either way the script says which
mode it ran in, in the build log.

**A setup problem is never subject to that switch.** A missing API key or a wrong URL always
fails, so enforcement cannot be disabled by breaking it.

**Rate limits will not fail your build.** Evaluations are counted per API key, so one noisy
pipeline can only ever refuse itself — not every other pipeline behind the same egress
address. On a `429` the connector waits out the `Retry-After` and asks again.

## Notifications

Slack, email, or a webhook to your own endpoint. Deliveries are queued **per destination**,
so Slack succeeding while a webhook fails is a normal outcome rather than a lost message.

Retry is bounded and observable: 30 seconds doubling to a 15-minute cap, six attempts, then
a terminal failed state. The cap exists because unbounded doubling eventually means never,
and the limit exists so an undeliverable announcement is visibly given up on — nobody learns
an announcement never arrived if it retries forever.

**Webhooks are signed.** `X-FreezeHub-Signature` is an HMAC-SHA256 over the timestamp and
the raw body; verify it in constant time and reject anything outside a five-minute window.
Without a signature, anyone who learns your endpoint URL could post a forged *cancelled*
event — and a forged cancellation is the one worth forging.

## What you will actually have to do

1. Register your applications, environments and teams — the catalog is what scope is built on.
2. Issue an API key. It is shown once and stored only as a hash; it reaches the policy
   endpoint and nothing else, so a key leaked from CI is not an account takeover.
3. Add one step to each pipeline.

Step 3 is the real timeline. A platform team with a shared template is a day. Forty
repositories owned by forty teams is longer, and worth scoping before you start.
