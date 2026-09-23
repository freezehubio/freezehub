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

## Releasing, on the single box

**Read this first: a release is one dispatch — `Release`** (`FZ-203`). It chains the three
workflows below and passes the image tag itself, so the copy-paste between two dispatches —
where a release went out against the wrong tree — is gone. It is still a button somebody
presses, deliberately.

**The hand procedure below is the fallback, not the path** (`FZ-205`). CI could not
authenticate to AWS at all until 2026-09-23, when advanced features were activated and the
OIDC provider, the deploy role and its inline policy were created — `OI-43`, closed. What is
kept below is how a release is diagnosed when a workflow fails, and how one is taken if CI
cannot run. Reach for it only then: four hand deploys produced four divergences from what the
workflow would have done (`FZ-178`, `FZ-182`, and the two recorded under § *Verifying a
deploy actually landed*).

Three workflows underneath, because two of the three steps are shared with the ECS posture
and one is not (`FZ-166`):

| Order | Workflow | Does |
|---|---|---|
| 1 | **Build image** | Builds `linux/arm64`, pushes to ECR, prints the tag |
| 2 | **Deploy single-box** | Sends the tag to the box by SSM and waits for the real outcome |
| 3 | **Deploy frontend** | Builds the SPA, syncs to S3, invalidates CloudFront |

Step 3 is independent of the other two — a frontend-only release is step 3 alone, and it
touches no compute.

**Do not run `Deploy`.** That workflow is the ECS posture: it builds, rolls out a task
definition, and publishes the frontend in one dispatch. On this posture there is no cluster
for it to roll out to, and it would fail after pushing the image.

### The guards, and why each one is here

A hand deploy is the workflow with its assertions removed. Every one of these exists because
its absence already caused something:

1. **You are in the right account.** `FZ-175` applied Terraform into the wrong one and
   nothing stopped it. A laptop's ambient credentials have been observed pointing at a
   different account than the deployment's, as **root**.
2. **Your checkout is current.** A deploy on 2026-09-22 shipped a tree that was 24 commits
   behind, so a merged story reached the site and a later one did not. The site then held a
   combination no commit describes.
3. **The build variables are the ones the code reads.** A deploy shipped without
   `VITE_COGNITO_DOMAIN` and the site fell back to the development sign-in form, whose
   endpoint does not exist outside the `local` profile. **Nobody could sign in at all.** The
   workflow has a `test -n` for exactly this; the hand path skipped it.
4. **You looked at the result.** A deploy that returns `200` proves the bucket accepted
   bytes, not that the right bytes are being served.

```bash
# 1 — the account guard. Not optional.
aws sts get-caller-identity --query Account --output text   # must be 668471252983

# 2 — the staleness guard
git -C ~/dev/freezehub fetch --prune origin
git -C ~/dev/freezehub status -sb | head -1                 # must be up to date with origin/master
```

### Frontend, by hand

Every value below is a repository variable; `gh variable list` prints them.

```bash
cd frontend && npm ci

VITE_API_BASE_URL=https://api.freezehub.io \
VITE_COGNITO_DOMAIN=freezehub-beta.auth.us-east-2.amazoncognito.com \
VITE_COGNITO_CLIENT_ID=1baicl02uro0in2mv6gsr88mfk \
  npm run build

# 3 — guard: the workflow's test -n, after the fact
grep -q "freezehub-beta.auth" dist/assets/*.js \
  || { echo "STOP: this build ships the development sign-in"; exit 1; }

aws s3 sync dist "s3://freezehub-beta-frontend-668471252983" --delete --region us-east-2
aws cloudfront create-invalidation --distribution-id E1UE997VT1TXCT --paths '/*'
```

**`VITE_COGNITO_DOMAIN`, not `VITE_COGNITO_HOSTED_UI_DOMAIN`.** The workflow reads
`vars.COGNITO_HOSTED_UI_DOMAIN` and assigns it to `VITE_COGNITO_DOMAIN`; copying the name
from the wrong side of that colon is what broke sign-in. The code reads
`import.meta.env.VITE_COGNITO_DOMAIN` in `frontend/src/features/auth/cognito.ts`.

**There is no runtime override.** All three are baked in at build time — a browser cannot be
told the API address afterwards. A wrong value deploys successfully and fails silently.

### Backend, by hand

```bash
TAG=$(git rev-parse --short HEAD)
aws ecr get-login-password --region us-east-2 \
  | docker login --username AWS --password-stdin 668471252983.dkr.ecr.us-east-2.amazonaws.com
docker buildx build --platform linux/arm64 --push \
  -t "668471252983.dkr.ecr.us-east-2.amazonaws.com/freezehub-beta:$TAG" backend
```

`linux/arm64` matters: the box is Graviton and an `amd64` image will pull and refuse to run.

**For the rollout, copy the command list out of `.github/workflows/deploy-singlebox.yml`
rather than out of this document.** It is roughly thirty quoted shell fragments that write
`compose.yaml`, `Caddyfile`, `.env` at `0600`, the backup unit and timer, then
`docker compose up -d --wait`. Transcribing it into prose is how a step goes missing —
`OI-46` found the backup had never been installed for exactly that reason. The workflow file
is the source of truth; this runbook deliberately does not restate it.

Then wait for the real outcome, which is what the workflow does and what a hand deploy most
often forgets:

```bash
aws ssm get-command-invocation --command-id "$COMMAND_ID" \
  --instance-id "$INSTANCE_ID" --query Status --output text
```

`send-command` returning only proves the API accepted it.

### Verifying a deploy actually landed

The check that has caught every divergence so far. **The asset hash must change**; if it did
not, nothing shipped:

```bash
H=$(curl -s https://app.freezehub.io | grep -oE '/assets/[^"]+\.js'); echo "$H"
curl -s "https://app.freezehub.io$H" > /tmp/live.js

grep -c "freezehub-beta.auth" /tmp/live.js     # sign-in reaches Cognito; 0 means it does not
grep -c "Is the freeze on"    /tmp/live.js     # a marker from the story you just shipped
```

For the backend, pick an endpoint the release changed. `POST /api/signup` answering `401`
means the running build predates `FZ-082`, because that endpoint is unauthenticated by
design.

### Turning CI on, and retiring the hand path

This is the procedure that makes everything above unnecessary. **Four hand deploys have
produced four divergences** from what the workflow would have done (`FZ-178`, `FZ-182`, and
the two recorded above), so the argument for doing it is not tidiness.

**Done for beta on 2026-09-23** (`FZ-205`). Kept, because it is the procedure for the next
environment and because steps 4–6 are how a variable is re-published when one changes. What it
cost is recorded with the resolved `OI-43`: activation cannot be undone, and the enforced
spend limit it removed is not coming back — `FZ-204`'s budget only alerts.

**Step 1 is irreversible and only you can take it.**

1. **Set the budget up first, then activate.** Activation lifts the service control policy
   that denies `iam:*Provider*` — the whole of `OI-43` — and in the same moment **removes the
   enforced spend limit**, which has been the only thing between a mistake and an unbounded
   bill. `D-37` requires the replacement the same day; doing it first means there is no day.

   ```bash
   # in infra/shared/terraform.tfvars
   budget_alert_email = "you@example.com"      # required, no default
   # budget_limit_usd = "40"                    # optional; D-35 projects about 18 USD/month

   terraform -chdir=infra/shared apply          # creates the budget; the OIDC role still off
   ```

   Then activate advanced features in the console. **It cannot be undone**, and the budget
   only *alerts* — it does not refuse spend the way the limit did. That downgrade is the
   price of CI.

   Confirm it worked, with the probe `FZ-171` used:

   ```bash
   aws iam list-open-id-connect-providers
   # AccessDenied "explicit deny in a service control policy" -> not active yet
   # a list, even empty                                       -> active, continue
   ```

2. **Confirm the repository the role will trust.** `infra/shared/terraform.tfvars` must set
   `github_repository = "freezehubio/freezehub"`. The trust policy is scoped to
   `repo:<that>:ref:refs/heads/master`, and getting it wrong is the difference between only
   this repository being able to deploy and anyone's being able to.

3. **Enable and apply**, from a shell in the deployment's account:

   ```bash
   aws sts get-caller-identity --query Account --output text   # must be the deployment's
   ```

   Then set `github_oidc_enabled = true` in `infra/shared/terraform.tfvars`. **Open the file
   and add the line**, or use `printf` — not `echo … >>`. `terraform.tfvars` is hand-edited
   and need not end in a newline, and appending to a file that does not produces
   `budget_alert_email = "…"github_oidc_enabled = true`: one line Terraform cannot parse, from
   a command that reported success. That happened here.

   ```bash
   printf '\ngithub_oidc_enabled = true\n' >> infra/shared/terraform.tfvars
   grep -c '^github_oidc_enabled' infra/shared/terraform.tfvars   # must print 1, not 0 or 2
   terraform fmt infra/shared/terraform.tfvars   # re-aligns; FAILS if the file does not parse

   terraform -chdir=infra/shared apply
   ```

   **Read the plan before approving it.** It should say `3 to add, 0 to change, 0 to destroy`.
   Anything under *change* or *destroy* touches the estate that is already serving — in
   particular the Cognito user pool, whose replacement would invalidate every existing
   identity. Stop and find out why rather than approving it.

4. **Publish the two variables CI is missing.**

   **Check each value before setting it.** `gh variable set` with an empty `--body` does not
   fail — it falls through to an interactive prompt, so a missed step 3 quietly sets the
   variable to whatever gets pasted next. That is the same silent-empty-value shape that
   shipped a frontend without `VITE_COGNITO_DOMAIN` and left nobody able to sign in.

   ```bash
   ROLE=$(terraform -chdir=infra/shared output -raw github_deploy_role_arn 2>/dev/null)
   if [ -n "$ROLE" ]; then
     gh variable set AWS_DEPLOY_ROLE_ARN --body "$ROLE"
   else
     echo "STOP: no deploy role in state — step 3 has not run"
   fi

   # `Release` passes this to the box so nobody pastes an instance id (`FZ-203`)
   BOX=$(terraform -chdir=infra/singlebox output -raw instance_id 2>/dev/null)
   if [ -n "$BOX" ]; then
     gh variable set SINGLEBOX_INSTANCE_ID --body "$BOX"
   else
     echo "STOP: no instance in state — infra/singlebox has not been applied"
   fi
   ```

5. **Dispatch `Release` from `master`.** One workflow, which chains the three and passes the
   image tag itself (`FZ-203`). The trust policy names `refs/heads/master`, so a dispatch from
   any other branch fails at the credentials step — correct, and confusing the first time it
   happens.

   Tick `backend`, `frontend` or both. A frontend-only release skips the box and its ~15
   second gap; a backend-only one skips the CloudFront invalidation.

   The three underlying workflows remain individually dispatchable, which is how a rollback
   is done: `Deploy single-box` with an earlier `image_tag`, no rebuild.

6. **Verify the deploy the way § *Verifying a deploy actually landed* says**, not by the
   workflow going green. A green workflow proves the steps ran.

Nothing else needs changing: every other variable the three workflows read is already set,
and the deploy role already carries ECR push, SSM `SendCommand`, S3 and CloudFront
invalidation — checked against what each workflow actually does (`FZ-201`).

## "A deploy failed"

The workflow polls the command and fails on the real outcome rather than on the API having
accepted it (`FZ-154`), so the job output already names the failure. For more:

```bash
aws ssm get-command-invocation --command-id "$COMMAND_ID" \
  --instance-id "$INSTANCE_ID" --query StandardErrorContent --output text
```

**Rollback is `Deploy single-box` again with an earlier `image_tag`** — no rebuild, because the image for that tag is still in ECR. There is nothing else to undo:
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

### The drill, performed 2026-09-21

Against `freezehub-2026-09-21T16-58-30Z.sql.gz`, the first backup the service ever produced.
Downloaded with operator credentials — the box cannot read its own backups — and restored
into a scratch database, never the live one.

```text
dump                  55 715 bytes uncompressed, 7 735 gzipped
restore               0 errors
tables                21
liquibase changesets  28
constraints           185
organization          0 rows
```

**Each number answers a different question**, which is why the check is not simply "did
psql exit zero":

- **21 tables** — the schema is whole, not the prefix of a truncated dump.
- **28 changesets** — `databasechangelog` came through, so a restored box continues from
  where the original was rather than re-running every migration.
- **185 constraints** — foreign keys and checks survived. A dump that restores tables and
  loses constraints looks healthy until the first bad write.
- **0 organizations** — correct, the database was empty. This is the number to watch when
  it should no longer be zero.

The scratch database was dropped afterwards. **Run this again once there is real data**: a
drill against an empty database proves the mechanism and nothing about the contents.

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
