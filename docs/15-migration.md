# FreezeHub — Graduating to ECS

## Purpose

How to move off the single box (`D-35`) onto the ALB-and-ECS estate in `infra/`, written
while the reasons are fresh rather than discovered under pressure.

**Nothing here has been executed.** It is a plan, and the first real migration will correct
it.

## When

`D-35` names three triggers. Any one of them ends the single-box posture:

1. **The first paying customer.** The narrow version of this is moving PostgreSQL to RDS for
   managed backups and point-in-time recovery, which can be done without the rest.
2. **An availability commitment.** A box that reboots is a box on which every customer's
   deployments are blocked, because `freeze-check.sh` fails closed.
3. **A security review asking about isolation** — the database shares a host with the
   internet-facing process, and it holds AES-encrypted Slack tokens and webhook signing
   secrets (`D-3`).

The first is the one that arrives soonest and is the cheapest to act on.

## Why this is small

Most of the estate never moves. `infra/singlebox/` deliberately creates **none** of these,
and `infra/` owns them in both postures:

| Shared, untouched by the migration |
|---|
| The SPA bucket and its CloudFront distribution |
| The Cognito user pool, client and domain |
| The ECR repository |
| The Route 53 hosted zone |
| The GitHub OIDC deploy role |

So what changes is the compute and database tier, and one DNS record. That is the whole
migration, and it is the reason `FZ-152` was written as a separate root module rather than a
flag inside `infra/`.

## The application needs no changes at all

`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` are
already overrides the backend honours — the same ones `compose.yaml` sets and the same ones
`backend.tf`'s task definition sets, from different sources. Pointing the application at RDS
is configuration.

The image is identical. CI already builds `linux/arm64` and pushes to the same ECR
repository, and `backend.tf` runs ARM64 Fargate.

## The order, and why it is this order

**The box keeps accepting writes until DNS moves.** So a dump taken while it is still serving
is stale by the time the new estate is live, and the gap is silent — restrictions created in
it simply disappear. Hence: stop serving first, then dump.

### 1. Stand up the new estate

```bash
cd infra
terraform init && terraform plan     # read it
terraform apply
```

Both estates exist at once at this point, and only one has DNS pointing at it. That is
deliberate: it means step 5 is a rollback as well as a cutover.

### 2. Stop the box from serving

```bash
aws ssm start-session --target "$INSTANCE_ID"
sudo -i && cd /opt/freezehub
docker compose stop caddy backend      # NOT postgres -- the dump needs it
```

Customer pipelines now fail closed. **This is the outage window**, and it is why the
migration is scheduled rather than opportunistic. Announce it the way you would a freeze.

### 3. Dump and restore

```bash
docker compose exec -T postgres pg_dump -U freezehub -d freezehub --clean --if-exists \
  | gzip > /tmp/cutover.sql.gz
```

Copy it out through the session, then into RDS:

```bash
gunzip -c cutover.sql.gz | psql "$RDS_CONNECTION_STRING"
```

Liquibase will find the schema already at the right changeset and do nothing, which is what
should happen — the dump carries `databasechangelog` with it.

### 4. Verify before moving anything

```sql
select (select count(*) from organization)       as orgs,
       (select count(*) from users)              as users,
       (select count(*) from change_restriction) as restrictions,
       (select count(*) from api_key)            as keys,
       (select count(*) from deployment_check)   as checks;
```

Run it against both and compare. **If the numbers disagree, stop** — restart the box's stack
and you have lost only the outage window.

Also confirm the new service is healthy before it is reachable: the ALB target group reports
healthy only once `/actuator/health/readiness` does, which is false while Liquibase runs
(`FZ-062`).

### 5. Move one record

Point `api.<domain>` from the Elastic IP at the load balancer. `FZ-152` sets a **60 second
TTL** precisely so this is quick — that value exists for this step.

The SPA needs no change: it already points at `api.<domain>`.

### 6. Keep the box for a business day

Stopped, not destroyed. If something surfaces that the row counts did not catch, the fastest
recovery is moving the DNS record back — which only works while the box still exists.

```bash
cd infra/singlebox && terraform destroy     # the next day, deliberately
```

## What to watch afterwards

**API keys are unchanged**, so customer pipelines should continue without anyone touching
them. That is the single best signal the migration worked: the deployment checks console
keeps filling.

**Notifications are the thing most likely to be quietly broken**, because they depend on
environment variables that are set in two different places in the two postures. Verify Slack
and email end to end using `14-operations.md` § *Verifying Slack and email end to end* —
before you need them, not when a freeze starts.

**Cognito is untouched**, so nobody signs in again and no password resets.

## What this does not cover

Moving **region** is not this migration and is much larger: a Cognito user pool is
region-bound and `users.external_subject` stores the `sub` it issues, so changing region
means re-creating every identity (`D-32`, `FZ-135`). If a region change is ever needed, it is
its own story and its own outage.
