# FreezeHub — Claude Code Instructions

## 1. Product

FreezeHub is a multi-tenant SaaS that centralizes deployment/code freeze management, communicates restrictions to engineering teams, and exposes a policy API that CI/CD systems can query before deploying.

The MVP is intentionally narrow: create, communicate, inspect, and evaluate deployment freezes.

## 2. Source of Truth

Before implementing any feature:

1. Read this file.
2. Read `docs/00-product.md`.
3. Read `docs/01-domain.md`.
4. Read `docs/02-architecture.md`.
5. Read the requested item in `docs/08-backlog.md`.
6. Check `docs/09-open-issues.md` for known defects and deferred decisions touching the area.
7. Inspect the existing implementation before changing it.

Do not invent business rules.

If documentation, tests, API contracts, migrations, and implementation conflict, report the conflict before changing domain behavior.

## 3. Architecture

- Monorepo.
- Modular monolith.
- Backend: Java + Spring Boot.
- Frontend: React + TypeScript.
- Database: PostgreSQL.
- Communication: REST/JSON.
- Database migrations: Liquibase.
- Infrastructure target: AWS.
- Infrastructure as Code: Terraform.

Modules are logical boundaries inside one deployable backend. They are not microservices.

## 4. MVP Constraints

Do not introduce unless explicitly requested:

- microservices;
- Kafka;
- Kubernetes;
- GraphQL;
- event sourcing;
- CQRS frameworks;
- custom policy DSL;
- Elasticsearch;
- distributed caching;
- complex workflow engines;
- AI features;
- speculative infrastructure.

Prefer the simplest implementation that satisfies the documented requirement.

## 5. Development Principles

- Implement features vertically when practical.
- Business rules belong in the backend.
- The frontend must not duplicate domain rules as an independent source of truth.
- Every tenant-owned resource must be isolated by organization.
- Never trust an `organizationId` supplied by a client as authorization.
- Database schema changes require Liquibase migrations.
- API changes must be intentional and reflected in the API contract when it exists.
- New domain behavior requires automated tests.
- Prefer explicit code over premature abstraction.
- Reuse existing project patterns before introducing new ones.
- Avoid unrelated refactors.
- Do not implement future backlog items while implementing the current item.
- Do not add dependencies without a concrete need.

## 6. Feature Implementation Workflow

When asked to implement a backlog item such as `FZ-020`:

### Before coding

1. Read the backlog item.
2. Read the relevant domain and architecture sections.
3. Inspect affected backend/frontend code.
4. Identify database changes.
5. Identify API changes.
6. Identify domain invariants involved.
7. Identify tenant-isolation implications.
8. Report unresolved requirements instead of guessing.

### During implementation

- Implement only the requested feature and necessary supporting work.
- Keep changes small and reviewable.
- Add/update tests as part of the feature.
- Preserve module boundaries and tenant isolation.

### After implementation

1. Run relevant tests.
2. Run configured build/lint/static-analysis commands.
3. Summarize changed files.
4. State any architectural decision introduced.
5. State unresolved questions or limitations.
6. Never claim a command or test passed unless it was actually executed.

## 7. Repository Shape

```text
freezhub/
├── CLAUDE.md
├── README.md
├── docs/
│   ├── 00-product.md
│   ├── 01-domain.md
│   ├── 02-architecture.md
│   ├── 08-backlog.md
│   └── 09-open-issues.md   # known defects, gaps and deferred decisions
├── backend/
├── frontend/
├── infra/
├── scripts/
├── connectors/        # shipped CI/CD connectors (FZ-091+)
└── examples/          # hand-rolled CI/CD integration walkthrough (FZ-053)
```

Additional documentation is created just-in-time when implementation requires it:

- `docs/03-data-model.md`
- `docs/04-api.md`  — created by FZ-050
- `docs/05-frontend.md`
- `docs/06-security.md`
- `docs/07-decisions.md`
- `docs/10-demo.md`      — created by FZ-074
- `docs/11-commercial.md` — created by FZ-080
- `docs/12-connectors.md`  — created by FZ-090
- `docs/13-validation.md`  — created by FZ-137
- `docs/14-operations.md`  — created by FZ-156
- `docs/15-migration.md`   — created by FZ-157
- `backend/CLAUDE.md`
- `frontend/CLAUDE.md`

## 8. Commands

Do not invent project commands during bootstrap.

Once backend/frontend are initialized, document the exact commands here for:

- local dependencies;
- backend run/test/build;
- frontend run/test/build/lint;
- complete local startup.

### Local dependencies

```bash
# start local PostgreSQL (repo root)
docker compose up -d postgres

# stop it
docker compose down
```

### Backend (`backend/`)

Requires Java 21 (`backend/.java-version` pins this via jenv; otherwise ensure `JAVA_HOME` points to a Java 21 JDK).

`./mvnw clean verify` requires Docker running (integration tests use Testcontainers to start a real PostgreSQL and run Liquibase against it) — it does not require `docker compose up` first.

`./mvnw spring-boot:run` connects to PostgreSQL via `spring.datasource.*`, overridable with `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` (defaults match `docker-compose.yml`) — start `docker compose up -d postgres` first.

Every endpoint except `/actuator/health` requires a Cognito-issued JWT (`06-security.md`). **Local runs need the `local` Spring profile active** — without it there's no `JwtDecoder` bean and the app won't start (see `backend/README.md` § Authentication).

```bash
cd backend

# build + run tests (spins up Postgres via Testcontainers; tests activate the local profile themselves)
./mvnw clean verify

# run tests only
./mvnw test

# run the app locally (port 8099 — see server.port under the local profile); requires `docker compose up -d postgres` first
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# health check (no auth required)
curl http://localhost:8099/actuator/health
```

### Frontend (`frontend/`)

Requires Node 20+.

```bash
cd frontend
npm install

npm run dev      # dev server, http://localhost:5173
npm run build    # type-check (tsc -b) + production build
npm run lint      # oxlint
npm run test      # vitest run
```

### Connectors (`connectors/`)

Requires Node 20+ (the test harness), `curl`, `jq`, and Docker for the image checks.

`connectors/freeze-check.sh` is the one implementation every connector wraps (`D-24`).
Change behaviour there, not in a connector.

```bash
# behaviour — drives the script and every connector against a stub Policy API
node connectors/test/run-tests.js

# the connectors' shape: every input wired, nothing that downgrades a freeze,
# and no API key where a pipeline definition would keep it
node connectors/test/check-connectors.js

# the same rules, as a container
docker build -t freeze-check:test connectors/
sh connectors/test/image-smoke.sh freeze-check:test
```

The image is the connector (`D-26`): every CI system in `connectors/README.md` runs it.
It is not published yet — `FZ-099` — so build it locally to try a guideline.

### Scripts (`scripts/`)

All three need `docker compose` for database access. The first two also need `curl`, `jq`
and a running backend on the `local` profile; `test-users.sh` deliberately does not, so it
still answers when the application will not start.

```bash
# a believable organization to demo against (FZ-074)
./scripts/seed-demo.sh

# turn a demo into a customer (FZ-086)
./scripts/provision-organization.sh --company "Contoso" --admin ops@contoso.test --plan GROWTH

# who can sign in, and what cannot be tested with them (FZ-102) — see Test accounts below
./scripts/test-users.sh
```

Provisioning is a script and not an admin console on purpose (`D-23`), so it needs database
access — which in a deployed environment means an operator with production credentials.

### Infrastructure (`infra/`)

Requires Terraform >= 1.6 and AWS credentials. See `infra/README.md` for the full runbook
and what must exist first.

```bash
cd infra
terraform fmt -check -recursive
terraform init -backend=false && terraform validate   # no AWS credentials needed
terraform plan                                        # read-only, needs credentials
```

Nothing here has been applied. `terraform apply` creates billable resources and is a
human decision.

### Test accounts

There are no passwords. Under the `local` profile the sign-in page posts an email to
`/api/dev/token` and gets a signed JWT back (`FZ-035`), so **the email is the credential**.

```bash
./scripts/test-users.sh              # who can sign in, and what each one can test
./scripts/test-users.sh --add-member # add a MEMBER, needed for the non-administrator view
```

It reads the database rather than a list written down, because any list of test accounts
is wrong the first time anybody seeds, provisions or resets. It also names what is
*missing* — with no `MEMBER` account the trial banner, the member-visible billing read and
a `402` seen by a non-administrator cannot be checked at all.

None of these accounts can sign in to a deployed environment: there is no real identity
provider yet (`OI-2`).

### Complete local startup

```bash
docker compose up -d postgres
(cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local)   # separate terminal
(cd frontend && npm run dev)                                             # separate terminal
```

## 9. Git discipline

Development follows a **one backlog item = one feature branch** strategy.

### Base Branch

`master` is the base branch for all feature development.

Do not implement backlog items directly on `master`.

### Feature Branch Naming

Every backlog item must be developed in its own branch created from the latest local `master`.

The branch name must be the backlog item ID exactly as defined in `docs/08-backlog.md`.

Examples:

```text
FZ-002
FZ-003
FZ-020
FZ-051
```

Do not introduce alternative prefixes such as:

```text
feature/FZ-002
feat/FZ-002
claude/FZ-002
FZ-002-backend
```

unless the repository policy is explicitly changed.

Each backlog item should produce a small, reviewable unit of work.

Unless explicitly requested:

* do not create commits;
* do not push;
* do not rewrite Git history;
* do not combine unrelated stories;
* do not modify unrelated files.

At the end of a story, provide a suggested commit message using:

`<type>(<scope>): <description>`

Examples:

`chore(project): bootstrap repository structure`

`feat(restrictions): create deployment restriction`

`feat(policy): add deployment policy evaluation`

`fix(notifications): retry failed webhook delivery`

The human operator decides when to commit.

### Before Starting a Story

**Always sync first.** Before anything else, `git fetch` and fast-forward `master`, and confirm what actually landed rather than assuming the local copy is current. Branches merged elsewhere, commits that did not make it into a merge, and work that looks present locally but is absent on the remote are all invisible until you look.

Before modifying code for a backlog item:

1. `git fetch --prune origin` and `git pull --ff-only origin master`.
2. Confirm the previous story's work is genuinely on `master` — check for the change, not just for a merge commit. A pull request merged while a later commit was still being pushed leaves that commit behind, and the branch it came from is the only place it survives.
3. Inspect the current Git status.
4. Confirm there are no unexpected uncommitted changes.
5. Confirm the requested backlog item exists in `docs/08-backlog.md`.
6. Create a new feature branch from the freshly pulled `master` using the backlog ID.

If something is missing from `master`, say so and recover it before starting new work. Building on a branch that is behind produces a diff nobody can review.

Conceptually:

```text
master
  │
  ├──── FZ-002
  │       │
  │       ├── implementation
  │       ├── tests
  │       └── documentation
  │
  └──── FZ-003
```

If the expected feature branch already exists, do not recreate, delete, reset, or overwrite it automatically.

Inspect its state and report the situation before continuing.

### One Story Per Branch

A feature branch must contain changes related only to its backlog item and the supporting changes strictly necessary to complete it.

Do not:

* implement another backlog item in the same branch;
* mix unrelated refactors;
* introduce speculative future functionality;
* reuse a previous story branch for a new story.

If implementation reveals work belonging to another backlog item, report it instead of silently implementing it.

### Commits

Do not create commits unless explicitly requested.

When the story is complete:

1. Run the Definition of Done.
2. Inspect the final Git diff.
3. Verify that changes belong to the current backlog item.
4. Provide a suggested Conventional Commit message.

Format:

```text
<type>(<scope>): <description>
```

Examples:

```text
chore(backend): bootstrap Spring Boot application

feat(restrictions): create deployment restriction

feat(policy): add deployment policy evaluation

fix(notifications): retry failed webhook delivery
```

The human operator decides when the commit is created.

### Push and Merge

Do not automatically:

* merge into `master`;
* delete branches;
* rewrite history;
* force push.

These operations require explicit instruction.

Pushing a finished story's branch and opening a pull request for it are **expected**, not exceptional — see the standing instruction below.

**Superseded on 2026-09-06.** The pre-approval to merge finished stories straight into `master` (granted during `FZ-031`) no longer applies. Every fix and every feature goes through a pull request the human operator approves.

**Standing instruction — pull requests:** once a story has passed its Definition of Done and been committed on its own branch:

1. Push the branch.
2. Open a pull request against `master` with `gh pr create`.
3. Report the PR link and stop.

Do **not** merge it. Do not merge your own pull request, and do not branch the next story from an unmerged one — branch from `master` and say so if that means the next story starts without the previous one's changes.

The pull request body carries what a reviewer needs to disagree with the work: what was decided and why, anything found in passing, anything left undone, and what was actually executed to verify it. A PR that only restates the diff wastes the review.

Still requiring explicit instruction each time: deleting branches, rewriting history, force pushing, and committing work that is not a completed story.

#### Screenshots — every change a person can see

**Standing instruction, granted 2026-09-08.** If a story changes a page or a component, its pull request shows **before and after**. A diff of a stylesheet does not tell a reviewer what the screen now looks like, and "verified live" is a claim the reviewer has to take on trust.

**Capture the "before" first.** It is the state on `master`, so it has to be photographed *before* the work starts, or recovered afterwards by stashing and checking `master` out again — which is slower and easy to forget. Take it in the same browser, at the same window size, signed in as the same account, showing the same data. Two screenshots that differ in three ways at once prove nothing.

Commit them to the repository:

```text
docs/ui/<story-id>/before.jpg
docs/ui/<story-id>/after.jpg
```

More than one pair is fine when a change has more than one state worth seeing — `before-empty` / `after-empty` for an empty organization, `after-mobile` for a narrow viewport. Name what the frame shows.

Embed them in the pull request body with `raw.githubusercontent.com` URLs **pinned to the commit SHA**, never to the branch name:

```markdown
![before](https://raw.githubusercontent.com/<owner>/<repo>/<sha>/docs/ui/FZ-113/before.jpg)
```

A branch-name URL breaks the moment the branch is deleted; a SHA URL is permanent, so the pull request still shows what it showed on the day it was reviewed.

This applies to any visible change, including one made in passing. It does not apply to backend-only stories, documentation, or scripts — there, say plainly that there was nothing to see.

### Story Completion

A backlog item is considered ready for human review when:

```text
Feature branch
      │
      ▼
Implementation
      │
      ▼
Tests / Build / Lint
      │
      ▼
Acceptance Criteria
      │
      ▼
Diff Review
      │
      ▼
Suggested Commit
      │
      ▼
Human Review
```

After human approval, commit/merge operations can be explicitly requested.

### Safety

Never automatically use destructive Git operations such as:

```text
git reset --hard
git clean -fd
git push --force
git branch -D
```

If repository state prevents safe continuation, stop and report the problem rather than attempting to repair Git history destructively.

## Story Execution Rules

Development is driven by backlog items defined in `docs/08-backlog.md`.

### One Story at a Time

* Work on exactly one backlog item at a time.
* Do not implement requirements belonging to later backlog items.
* Do not perform speculative work for future features.
* Supporting changes are allowed only when strictly required to complete the current story.
* If a required change appears to belong to another backlog item, report it before implementing it.

### Before Coding

For every story:

1. Read the root `CLAUDE.md`.
2. Read the story in `docs/08-backlog.md`.
3. Read the documentation referenced by the story or relevant to the affected domain.
4. Inspect the existing implementation before proposing changes.
5. Identify:

   * affected modules;
   * domain rules involved;
   * API impact;
   * database impact;
   * frontend impact;
   * security/tenant-isolation impact;
   * required tests.
6. Check whether the requested behavior is sufficiently specified.

If an important business, domain, security, or architectural decision is missing, **do not guess**.

Report:

* what is unspecified;
* why it matters;
* reasonable alternatives;
* the recommended option.

Wait for the decision before implementing behavior that depends on it.

Trivial implementation details that do not affect product behavior or architecture do not require approval.

## Scope Discipline

Prefer the smallest change that completely satisfies the current story.

Do not introduce:

* abstractions for hypothetical future requirements;
* dependencies without an immediate use;
* infrastructure for future features;
* generic frameworks when a simple implementation is sufficient;
* unrelated refactors;
* undocumented domain behavior.

Follow existing patterns unless there is a concrete reason to change them.

If an existing pattern should be changed, explain why before introducing a new architectural pattern.

## Vertical Implementation

When applicable, implement a feature through all layers required by the story:

```text
Database
   ↓
Domain
   ↓
Application
   ↓
API
   ↓
Frontend
   ↓
Tests
```

Do not create unused layers merely to satisfy this structure.

Business rules must remain authoritative in the backend.

The frontend may validate for user experience but must not become the authoritative implementation of domain rules.

## Definition of Done

A story is not complete only because the code was generated.

Before reporting completion:

1. Run the relevant build.
2. Run all tests affected by the change.
3. Run configured lint/static-analysis/type-check commands.
4. Verify the primary acceptance criteria.
5. Review the resulting diff for unrelated changes.
6. Verify no future backlog items were accidentally implemented.
7. Update documentation only when the story changes a documented contract, decision, command, or invariant.

Never claim that a command, build, test, or validation succeeded unless it was actually executed.

If something cannot be executed, explicitly state:

* what was not executed;
* why;
* what remains to be verified.

## Documentation Discipline

Documentation exists to constrain implementation decisions, not to duplicate source code.

Update documentation when:

* a domain invariant changes;
* an architectural decision changes;
* an API contract changes;
* a security rule changes;
* a development command changes;
* the scope or acceptance criteria of a backlog item changes.

Do not create documentation for implementation details already obvious from the code.

Create just-in-time documents only when their corresponding implementation requires them.

## Dependency Discipline

Before adding a dependency:

1. Confirm the functionality is not reasonably available in the existing stack.
2. Confirm the dependency is needed by the current story.
3. Prefer established, actively maintained libraries.
4. Avoid overlapping libraries solving the same problem.
5. Explain non-obvious dependency additions in the implementation summary.

Do not add dependencies only because they may be useful later.

## Final Story Report

After implementation, report using this structure:

### Implemented

What was implemented for the requested backlog item.

### Changed

Important files/modules changed.

### Validation

Commands and tests actually executed and their results.

### Decisions

Any implementation or architectural decisions introduced.

### Documentation

Documentation created or updated, if any.

### Remaining Issues

Known limitations, unresolved questions, or validations that could not be performed.

### Suggested Commit

Suggested Conventional Commit message.

If there are no remaining issues, explicitly state `None`.

