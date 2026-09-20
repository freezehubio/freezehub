# FreezeHub — Operations

## Purpose

How to find out what is wrong, on the single-box beta (`D-35`).

Organised by **what you observed**, not by component, because whoever opens this is already
having a bad morning and does not yet know which component is at fault. If you want the
architecture, that is `infra/singlebox/` and `D-35`.

**There is no SSH.** Every route onto the box is SSM (`FZ-152`), which means access is IAM-
controlled and recorded in CloudTrail rather than guarded by a key somebody holds.

```bash
aws ssm start-session --target "$INSTANCE_ID" --region "$AWS_REGION"
sudo -i && cd /opt/freezehub
```

`INSTANCE_ID` comes from `terraform -chdir=infra/singlebox output -raw instance_id`.

## The single most useful thing in this document

**Every log line carries a request id, and so does every error body.**

`FZ-062` puts `X-Request-Id` in the MDC, on the response header, and in the Problem Details
of every failure. The log pattern is `%5p [%X{requestId:-no-request}]`.

So when a customer says *"it failed around three"*, ask for the `requestId` from the error
they saw. That turns an anecdote into an exact lookup:

```bash
docker compose logs backend | grep '9f2c1a4e-...'
```

It is also the only way to separate two concurrent requests in one stream — timestamps
cannot do it once more than one thing is in flight. Lines tagged `[no-request]` are
background work: the schedulers, the dispatcher, startup.

---

## "The site is down"

```bash
cd /opt/freezehub
docker compose ps          # what is up, and what is restarting
docker compose logs --tail=100 caddy
docker compose logs --tail=200 backend
```

**A container in a restart loop** shows a rising restart count in `ps`. The reason is
almost always in the last 50 lines before the last restart:

```bash
docker compose logs --tail=300 backend | grep -B5 -iE "APPLICATION FAILED|Caused by"
```

Three failures worth recognising immediately:

| What you see | What it means |
|---|---|
| `No qualifying bean of type 'IdentityProvider'` | The application is running without `FZ-046`. It cannot start outside `local` at all (`OI-2`) — this is deliberate fail-fast, not a regression |
| `Could not resolve placeholder 'freezehub.secrets.encryption-key'` | SSM parameters did not reach the container. Check the deploy exported them; the application refuses to start rather than run unencrypted (`D-3`) |
| `Connection to postgres:5432 refused` | PostgreSQL is not healthy yet, or its volume is gone. `docker compose logs postgres` |

**If Caddy is up and the backend is not**, callers get a 502 and the TLS certificate is
fine. **If Caddy itself is down**, TLS fails and everything looks like DNS. Check
certificates before assuming a network problem:

```bash
docker compose exec caddy ls -R /data/caddy/certificates
docker compose logs caddy | grep -i "certificate\|acme\|challenge"
```

Let's Encrypt rate-limits failed issuance. If the ACME challenge is failing, confirm port
80 is reachable — Caddy needs it for HTTP-01 even though everything else redirects.

---

## "A customer's pipeline is failing"

**Read this section before reassuring anyone.** `freeze-check.sh` fails closed: if it cannot
get an answer it blocks the deployment. So *"FreezeHub refused my deploy"* and *"FreezeHub is
down"* look identical from the customer's side and are opposite problems.

Ask for two things: the **request id** from the failing step, and whether the message said
`BLOCK` or reported an error.

```bash
# It answered, and the answer was no. The product working.
docker compose logs backend | grep 'POST /api/policy/evaluate' | tail -20
```

| Symptom | Cause |
|---|---|
| `BLOCK` naming a restriction | Working as intended. Point them at the restriction |
| `BLOCK` with `unregistered` | The application or environment is not in their catalog (`D-14`). A rename is the usual cause — check the audit trail |
| `401` | Their API key is wrong or revoked. Keys reach `/api/policy/**` and nothing else |
| `429` | Rate limited (`FZ-087`). Real, and `D-31` says a `429` is a wait rather than an answer |
| Timeout, no status | **Ours.** This is the outage case |

The audit trail answers the rename case without a database query — `CATALOG_RENAMED` against
the application, with the old and new names.

---

## "Notifications are not arriving"

The outbox is the first place to look, not Slack.

```bash
docker compose exec -T postgres psql -U freezehub -d freezehub -c \
  "select status, count(*) from notification group by status;"
```

`PENDING`, `SENT`, `FAILED` are the only states.

- **Everything `PENDING` and not moving** — the dispatcher is not running. It sweeps every
  30 seconds and takes a `scheduler_lock` row first (`FZ-121`). A stale lock held by a dead
  container blocks it until the lease expires:
  ```bash
  docker compose exec -T postgres psql -U freezehub -d freezehub -c "select * from scheduler_lock;"
  ```
- **`FAILED`** — delivery was attempted and gave up after its retries (`FZ-044`). The
  notifications screen shows which channel and why, and `FZ-119` can retry it from the
  product.
- **Nothing enqueued at all** — the restriction never activated. That is the lifecycle
  reconciler, not the notification module: `grep RestrictionLifecycle` in the backend log.

**Email that silently does nothing is usually configuration, not failure.** The email sender
is `@ConditionalOnProperty` on `freezehub.notifications.email.from`. If it is unset the
sender is not registered at all and EMAIL notifications **defer rather than fail** — so
nothing errors and nothing arrives.

```bash
docker compose exec backend env | grep -i "FREEZEHUB_NOTIFICATIONS_EMAIL\|SPRING_MAIL"
```

---

## Verifying Slack and email end to end

Do this **once after the first deploy**, and again after changing anything about mail. It
exercises the real path — outbox, dispatcher, sender — rather than asserting configuration
looks right.

### Slack

1. In Slack: **Apps → Incoming Webhooks → Add to Slack**, pick a channel, copy the URL. It
   looks like `https://hooks.slack.com/services/T000/B000/xxxx`.
2. In FreezeHub: **Settings → Integrations → Add destination**, type `SLACK`, configuration:
   ```json
   {"webhookUrl": "https://hooks.slack.com/services/T000/B000/xxxx"}
   ```
   Administrator only (`06-security.md`). The URL is stored AES-256-GCM encrypted (`D-3`),
   and the UI will only ever show you the host back — **the path segment of a Slack webhook
   URL is the secret**, so it is not displayed and cannot be copied out again.
3. Create a restriction scheduled to start within the advance-warning window (Settings →
   Organization sets it; the default is 24 hours). That produces a `STARTING_SOON`.
4. Watch it move:
   ```bash
   docker compose exec -T postgres psql -U freezehub -d freezehub -c \
     "select id, event, status, attempts, next_attempt_at from notification order by id desc limit 5;"
   ```
   `PENDING` → `SENT` within about 30 seconds. The message should appear in the channel.

**If it stays `PENDING`:** the dispatcher is not sweeping — see the previous section.
**If it goes `FAILED`:** Slack rejected it. `docker compose logs backend | grep -i slack`.
A `404` from Slack means the webhook was revoked or the URL is wrong; because the path is the
secret, a typo and a revocation look the same.

### Email

Email needs two things that Slack does not: a from-address, and an SMTP server.

1. **Lift SES out of the sandbox**, or nothing reaches anyone but verified identities. This
   is an AWS support request and is not instant — do it before you need it.
2. Verify the from-address as an SES identity, and create SES SMTP credentials.
3. Set on the box, through the deploy rather than by hand:
   ```
   FREEZEHUB_NOTIFICATIONS_EMAIL_FROM=freezes@<your-domain>
   SPRING_MAIL_HOST=email-smtp.<region>.amazonaws.com
   SPRING_MAIL_PORT=587
   SPRING_MAIL_USERNAME=<ses-smtp-username>
   SPRING_MAIL_PASSWORD=<ses-smtp-password>
   SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
   ```
   SES exposes plain SMTP, so **the same configuration works locally** — point it at
   Mailpit or MailHog on your laptop to test the flow without AWS involved at all.
4. Add an `EMAIL` destination:
   ```json
   {"recipients": ["you@example.com"]}
   ```
5. Trigger the same way as Slack, and watch the same query.

**The failure that wastes the most time:** if `FREEZEHUB_NOTIFICATIONS_EMAIL_FROM` is unset,
there is no error anywhere. The sender is never registered, the notification defers, and the
outbox looks healthy. Check the environment first, not the logs.

### Webhooks

Same shape, `{"url": "https://..."}`, and worth testing because deliveries are signed
(`D-2`): `X-FreezeHub-Timestamp` and `X-FreezeHub-Signature`, HMAC-SHA256 over
`"<timestamp>.<body>"`. A receiver verifying against a re-serialised body will never match —
it must use the raw bytes. `https://webhook.site` is enough to see the headers arrive.

---

## "A deploy failed"

The workflow polls the command and fails on the real outcome rather than on the API having
accepted it (`FZ-154`), so the job output already names the failure. For more:

```bash
aws ssm get-command-invocation --command-id "$COMMAND_ID" \
  --instance-id "$INSTANCE_ID" --query StandardErrorContent --output text
```

**Rollback is the same workflow with an earlier `image_tag`.** There is nothing else to undo:
the stack files travel with each deploy, so redeploying a previous tag restores both the
image and the configuration that shipped with it.

A deploy that hangs at `docker compose up -d --wait` is waiting on the readiness health
check. That is usually Liquibase still migrating — legitimate on a release with a large
changeset, and `FZ-062` made readiness report false during it precisely so traffic does not
arrive early.

---

## Logs, and where they are

| What | Where |
|---|---|
| Backend, one line per request plus its own | `docker compose logs backend`, JSON-file driver, 10 MB × 5 |
| Caddy access log | `docker compose exec caddy cat /data/access.log` — JSON, rolled at 10 MiB |
| PostgreSQL | `docker compose logs postgres` |
| Backup runs | `journalctl -u freezehub-backup` |
| Deploys | CloudWatch, via the command's output configuration |
| Who ran what on the box | CloudTrail — every SSM session and command |

**To raise the log level without a redeploy**, `/actuator/loggers` is on the management
surface and is administrator-only (`FZ-065`):

```bash
curl -X POST http://localhost:8080/actuator/loggers/com.freezhub \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
```

Run it from inside the container or over the SSM session — it is not exposed publicly. **Put
it back afterwards**: `DEBUG` on a busy dispatcher fills the log ring quickly, and the thing
you were trying to find scrolls out of it.

---

## Restoring the database

This is `FZ-155`'s acceptance criterion, and **the story is not done until it has actually
been run.** An untested backup is the classic way to discover there was none.

```bash
aws s3 ls s3://<backup-bucket>/postgres/ | tail -5
aws s3 cp s3://<backup-bucket>/postgres/<file>.sql.gz /tmp/ && gunzip /tmp/<file>.sql.gz

# Into a scratch database first. Never straight over the live one.
docker compose exec -T postgres createdb -U freezehub restore_check
docker compose exec -T postgres psql -U freezehub -d restore_check < /tmp/<file>.sql

docker compose exec -T postgres psql -U freezehub -d restore_check -c \
  "select (select count(*) from organization) as orgs,
          (select count(*) from change_restriction) as restrictions,
          (select count(*) from deployment_check) as checks;"
```

Compare those counts against the live database. **Record the output of a real run in this
section** — a drill nobody wrote down is a drill nobody can prove happened.

The instance can write backups and not read them (`FZ-152`), deliberately: a backup the box
can read back is one an attacker on the box can read back. Restores use your credentials,
not the instance's.

---

## When to stop running this way

`D-35` names three triggers, and `FZ-157` is the route off:

1. **The first paying customer** — move PostgreSQL to RDS for managed backups and the
   isolation boundary.
2. **An availability commitment** — go to ALB and ECS, which already exists in `infra/`.
3. **A security review asking about isolation** — same answer as 2.

The one to take seriously is the first. This box is a single point of failure for a product
that fails closed, so an hour of downtime is an hour in which **every customer's deployments
are blocked**. That is free at zero customers, which is the entire window this posture is
for.
