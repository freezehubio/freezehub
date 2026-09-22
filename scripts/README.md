# scripts


## `release.sh`

A release, in one command, instead of three trips through the Actions UI and two values
looked up by hand.

```bash
./scripts/release.sh                        # backend and frontend, from HEAD
./scripts/release.sh --backend-only
./scripts/release.sh --frontend-only
./scripts/release.sh --image-tag 9c1f0aa    # roll back; skips the build
./scripts/release.sh --dry-run              # print the dispatches, send nothing
```

**It dispatches the three workflows in `../docs/14-operations.md` § Releasing and waits for
each. It does not deploy anything itself**, and that is deliberate: everything that makes a
deploy safe lives inside those workflows. `Deploy single-box` runs `./connectors` against
our own Policy API first, so **a freeze in force stops our release exactly as it stops a
customer's** (`FZ-182`). The workflows assume a role by OIDC, so no AWS credential has to
exist on the laptop. The deploy arrives as an IAM-authorised SSM call with an actor in
CloudTrail. `concurrency: deploy-singlebox` stops two releases racing, which two laptops
cannot.

The slow part of a release was never any of that. It was finding the tag one run printed
and the instance id from `terraform output`.

**Two guards before anything is dispatched.** A dirty tree asks for confirmation, because
the build takes the commit from the remote and not your working copy; and a commit that is
not on `origin` is refused outright, because Actions cannot check out what it cannot fetch.

**AWS is optional.** It is reached only to resolve the instance id, and only if you have not
supplied one — pass `--instance-id`, or set `FREEZEHUB_INSTANCE_ID`, and the script never
touches AWS at all. When it does, it asserts the account against
`infra/singlebox/terraform.tfvars` first, for the reason `FZ-175` exists: an apply once went
into the operator's personal account and nothing stopped it.

## `seed-demo.sh`

Builds a believable organization to demonstrate against — teams, applications, freezes in
force and upcoming, an API key, and a history of pipeline checks including two engineers
refused during a freeze.

```bash
./scripts/seed-demo.sh
```

Everything goes through the **real API**, so the audit trail and deployment console fill
with genuine entries rather than fabricated rows. The single exception is the first user:
the API deliberately cannot create one, because there is no self-service signup
(`../docs/06-security.md`), so that one row is inserted directly.

It refuses to run twice rather than duplicating the demo. To start over:

```bash
docker compose down -v && docker compose up -d postgres
# restart the backend so Liquibase recreates the schema, then seed again
```

Takes about three minutes, most of it waiting for the lifecycle reconciler so that every
screen agrees with every other before it returns.

**It also solves an ordinary problem:** an empty database has no users at all, so
`POST /api/dev/token` returns `404` and nobody can sign in. Running this after a
`docker compose down -v` makes a fresh checkout usable.

See [`../docs/10-demo.md`](../docs/10-demo.md) for the walkthrough it was built for.
