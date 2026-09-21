# FreezeHub — Questions People Ask

## Product

**Can FreezeHub actually stop a deployment?**
No, and it does not try to. It answers the question your pipeline asks, and your pipeline
decides. A tool that could stop your deploys is a tool that can take your production down,
and it would be a hard dependency on a third party in every release.

**So what stops the deployment?**
Your pipeline, using the answer. The shipped connector exits non-zero on `BLOCK`, which
fails the job — one step, in the CI system you already use.

**What if someone just removes the step?**
Then they have deliberately removed a gate from a pipeline definition that is in version
control and reviewed like any other change. FreezeHub makes the removal visible; it cannot
make it impossible, and nothing that lives outside your pipeline could.

**Can we announce a freeze without blocking anything?**
Yes — that is an advisory restriction. It is announced, it appears everywhere a blocking
freeze would, and the policy answer is `ALLOW` with the restriction named. Useful for a
heads-up period before a hard freeze starts.

**How specific can a freeze be?**
Any combination of teams, applications and environments. "Payments and checkout, production
only, Friday 18:00 to Monday 09:00" is one restriction. Scoping to nothing covers the whole
organization.

**What happens if someone deploys an application we never registered?**
It is refused, and the response says which name was not recognised. Otherwise a typo like
`prodution` would match no scope and tend toward `ALLOW`, which turns a misspelling into a
route through a freeze.

**Who can lift a freeze?**
Any member of your organization, and it is recorded — who, when, and what changed. Approval
workflows are not in the product; if you need one, the record is what you would build it on.

## Integration

**Which CI systems are supported?**
GitHub Actions, GitLab CI and Jenkins have documented integrations, and all three run the
**same container image**. Anything that can run a container or make an HTTPS request works.

**How long does integration take?**
Registering your catalog and issuing a key is minutes. Adding the step to pipelines is the
real timeline — a day with a shared template, longer across many independently owned
repositories.

**Does FreezeHub need access to our repositories?**
No. No repository access, no cloud credentials, no agents. One outbound HTTPS request from
your CI job.

**What if the API is unreachable mid-pipeline?**
The connector fails closed by default. Set `FREEZEHUB_ON_ERROR=allow` to invert that. The
build log always says which mode it ran in, and a *setup* error — a missing key, a wrong URL
— always fails regardless, so enforcement cannot be turned off by breaking it.

## Commercial

**Why per application and not per user?**
Because most of your engineers will never sign in — the value arrives as a Slack message and
an API answer — and charging per seat would charge you for telling everybody. A freeze half
the organization has not heard about is not a freeze. Users are unlimited on every plan.

**Why not per policy check?**
Metering the check taxes the behaviour the product depends on. A customer optimising that
bill calls the API less, which is precisely how a deployment slips through a freeze.
Evaluations are unlimited, and that is a commitment rather than current generosity.

**Can we under-register applications to pay less?**
It would break your own pipelines: an unregistered application is refused. The number you
pay on is the number you actually run.

**What does the free plan not do?**
It cannot create a blocking freeze — its restrictions are advisory. Everything else is
there: unlimited users, unlimited freezes announced, one notification destination of any
type including Slack, five applications, seven days of check history.

**Who are we buying from?**
Paddle acts as merchant of record and is the seller on the receipt, handling sales tax and
VAT. Early customers are invoiced directly while that is set up.

## Security and data

**Do you have SOC 2?**
No. Nothing has run in production long enough to produce the evidence. The security brief
sets out what is actually in place, and we will answer your questionnaire directly.

**Where is our data?**
AWS, `us-east-2`. EU residency is not available today.

**What do you store about our engineers?**
Email addresses for people with accounts, and — if your pipeline sends them — the deploying
engineer's identity, commit reference and pipeline URL on each check. Those three are
optional; a pipeline that omits them still works.

**How long do you keep it?**
Deployment checks for the window your plan allows and you configure, from 7 days to 10
years. The audit trail is kept indefinitely on every plan, because selling you back your own
compliance evidence would be a bad way to behave.

**Can you delete our data?**
Yes, on request. Self-service deletion is a known gap today; a request is handled by hand.

**Is our notification configuration safe?**
A Slack webhook URL is itself a credential, so it is encrypted in the application before it
reaches the database. Outbound webhooks to your own endpoints are signed with HMAC-SHA256 so
you can verify a delivery came from us.

## Getting started

**Can we sign up and try it?**
Not yet — self-serve signup is not built. Today it is a demo and, if it fits, an organization
we provision for you.

**What does a trial look like?**
We set you up, you register a handful of applications, add the step to one or two pipelines,
and run a real freeze. The useful test is the one after it ends: ask the product what
deployed while it was on.

**Are there other customers we can talk to?**
Not yet — you would be among the first. That is worth something in both directions, and we
would rather say it than imply a customer base that does not exist.
