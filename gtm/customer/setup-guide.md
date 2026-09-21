# FreezeHub — Setting It Up

From an empty account to a pipeline that refuses to deploy during a freeze. Half an hour,
most of it deciding names.

Everything here is what the product does today. Where something is not built, it says so.

---

## Before you start

**We create your account.** There is no self-serve signup. You tell us the company name and
the first administrator's email address, and we provision the organization and that
person's identity. They receive a temporary password and are asked to change it on first
sign-in. Everybody after the first is invited from inside the product.

That is a deliberate choice rather than a missing feature — an in-product way to create
organizations would mean a privileged account able to act across tenants, which is the one
thing the security model does not have.

**You will need**, before the last step:

- somewhere in your pipeline that runs a container or a shell script;
- outbound HTTPS from that job to FreezeHub. Nothing inbound, and no credentials into your
  systems.

---

## 1. Name what you deploy

This is the only part that takes thinking, and the part everyone gets wrong first.

**Applications, environments and teams are names you choose.** FreezeHub does not discover
them, validate them against anything, or care whether they exist in your infrastructure. An
environment called `production` can exist here before you have one.

They have to match what your pipeline sends. That is the whole contract.

### Applications

One per thing you deploy independently.

```text
payments-api
checkout-web
identity-service
```

If two components always ship together, they are one application. If either can ship
without the other, they are two.

### Environments

One per destination you would freeze separately.

```text
production
staging
```

Most organizations need two or three. Naming every ephemeral preview environment costs more
than it returns — you are unlikely to freeze them.

### Teams

A team owns applications. With one team, create it anyway and put everything in it; the
dimension becomes useful the moment a second team exists and you want to freeze one group's
deploys without touching another's.

---

## 2. How scope actually works

A restriction names any combination of teams, applications and environments. **Each is a
dimension.** Within a dimension the entries are OR'd; the dimensions are AND'd:

```text
matches =  (teams empty        OR the app belongs to one of them)
       AND (applications empty OR the app is one of them)
       AND (environments empty OR the target is one of them)
```

**An empty dimension is a wildcard, not an empty set.** That is what lets one line say
"freeze everything going to production".

Worked through:

| Scope | Effect |
|---|---|
| `environments = [production]` | Every application, production only |
| `applications = [payments-api]` | That application, every environment |
| `applications = [payments-api]`, `environments = [production]` | That application, and **only** to production. Its staging deploys continue |
| `teams = [platform]`, `applications = [payments-api]` | Narrower, not wider: that application, and only while it belongs to that team |

The third row is the one worth pausing on. Naming more things makes a restriction **match
less**, because dimensions are AND'd. If you want "payments-api everywhere *and* everything
in production", that is two restrictions.

**At least one target is required.** A restriction with all three dimensions empty is
refused, so there is no way to type "freeze absolutely everything" by leaving fields blank.

---

## 3. Choose a level

| | |
|---|---|
| **`HARD_FREEZE`** | A matching deployment is **blocked**. Your pipeline job fails. |
| **`ADVISORY`** | A matching deployment is **allowed**, and the reason is printed in the job log. |

`ADVISORY` is worth more than it sounds for a first week: your pipelines run unchanged,
everybody sees the announcements in their build output, and you find out whether your names
are right before anything is blocked.

**On the Free plan every restriction is `ADVISORY`.** Announcing is free; blocking is not.

---

## 4. Create an API key

Settings → API keys. One key per pipeline, named for it — `github-actions`, `gitlab-ci`.

**The key is shown once.** Put it straight into your CI secret store. If you lose it,
revoke it and make another; revocation is immediate and permanent.

**A key reaches exactly one thing: policy evaluation.** It cannot read your catalog, list
your restrictions, or create or cancel one. Announcing a freeze is a human act, and a key
that leaks should not be able to lift one — nor to tell an attacker what you deploy.

---

## 5. Wire up your pipeline

The check is one container, published and public:

```text
ghcr.io/freezehubio/freeze-check:v1
```

Four inputs:

```text
FREEZEHUB_URL          https://api.freezehub.io
FREEZEHUB_API_KEY      from step 4, out of your secret store
FREEZEHUB_APPLICATION  a name from step 1
FREEZEHUB_ENVIRONMENT  a name from step 1
```

Put it **immediately before the step that deploys**, not at the start of the pipeline. Build
and test whatever you like during a freeze; the freeze is about what reaches the
environment.

`connectors/README.md` in the repository has working configuration for GitHub Actions,
GitLab CI, Jenkins and Argo CD, and instructions for anything else.

### What the exit codes mean

| Code | Meaning |
|---|---|
| `0` | Allowed. Advisories, if any, are printed. |
| `1` | **Blocked.** A `HARD_FREEZE` matched. |
| `2` | Setup problem — a bad key, an unknown name, FreezeHub unreachable. |

`1` and `2` are kept apart on purpose: "a freeze stopped this" and "this check is
misconfigured" are different conversations, and a pipeline that treats them alike will
eventually treat a broken integration as a freeze and wait for it to end.

**It fails closed.** If FreezeHub cannot be reached, the deployment is blocked rather than
allowed. A freeze tool that waves deployments through when it is having a bad day is worse
than no freeze tool, because you would not know. Plan for that: the people who can announce
a freeze should also know how to bypass the check in a genuine emergency, and your pipeline
should make that possible without editing it under pressure.

---

## 6. Announce a freeze

Restrictions → New. A window, a scope, a level, and a reason people will read in their build
output.

Windows are stored and compared in **UTC**, and a restriction moves `SCHEDULED → ACTIVE →
COMPLETED` on its own — you do not start or stop it by hand. Cancelling is available at any
point and is permanent.

**Before announcing anything wide**, use the preview: it answers what a given
application-and-environment would get right now, without a pipeline having to run. It is the
cheapest way to find out that your scope matches nothing because a name is spelled
differently in CI.

---

## 7. What your engineers see

In the build log, from the check itself — no new tool to learn, no dashboard anyone has to
visit.

Announcements also reach **Slack**, through an incoming webhook you configure in Settings →
Integrations.

**Email notification is not available yet.** Slack is the notification channel today. If
email is the one your organization would actually read, tell us — it is built and not
switched on, and knowing it matters to you changes when it does.

---

## What this does not do

Stated here so it is not discovered later:

- **The check is voluntary.** A pipeline that does not call FreezeHub is not stopped by
  FreezeHub — nothing here reaches into your repositories or your cloud. A required GitHub
  status check that cannot be skipped is a plausible future and is not built.
- **No self-serve signup**, as above.
- **No SSO beyond the built-in directory** — no SAML, no Okta, no Entra.
- **No email notifications yet.**
- **One region, United States.** No EU data residency.
- **No SOC 2 report and no published DPA.** `security-brief.md` is direct about what does
  and does not exist.

---

## If something does not work

The commonest three, in order:

1. **The check says a name is unknown.** The application or environment your pipeline sends
   does not match the catalog exactly — case and hyphens included.
2. **A freeze matches nothing.** Usually a scope naming both a team and an application that
   does not belong to it. Dimensions are AND'd; see step 2.
3. **Everything is blocked, including things you did not name.** An empty dimension is a
   wildcard. A restriction naming only `production` covers every application deploying
   there, which is usually what was meant and occasionally a surprise.

Anything else, send us the request id. Every response carries one, and it is enough for us
to find the exact request in our logs.
