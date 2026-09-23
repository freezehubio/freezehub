# FreezeHub — MVP Implementation Backlog

## Purpose

This file defines implementation order and scope for AI-assisted development.

A backlog ID is a boundary. When Claude Code is asked to implement one item, it must not silently implement later items.

Statuses:

- `TODO`
- `IN_PROGRESS`
- `DONE`
- `BLOCKED`

## Milestone 0 — Foundation

### FZ-001 — Repository Bootstrap
**Status:** DONE

Create the initial monorepo structure and baseline README.

Acceptance:

- `backend/`, `frontend/`, `infra/`, and `scripts/` exist.
- bootstrap documentation remains accessible.
- no unnecessary application framework code is generated.

### FZ-002 — Backend Bootstrap
**Status:** DONE

Initialize the Java/Spring Boot backend.

Acceptance:

- Java 21 project builds.
- Spring Boot application starts.
- health endpoint is available.
- baseline automated test runs.
- backend development commands are documented.

### FZ-003 — Frontend Bootstrap
**Status:** DONE

Initialize React + TypeScript frontend.

Acceptance:

- Vite application starts.
- routing foundation exists.
- baseline test/build/lint commands run.
- frontend development commands are documented.

### FZ-004 — Local PostgreSQL + Liquibase
**Status:** DONE

Introduce PostgreSQL local development and migration infrastructure.

Acceptance:

- PostgreSQL can run locally.
- backend connects through configuration/environment variables.
- Liquibase executes an initial migration.
- Testcontainers strategy is available for integration tests.

### FZ-005 — Domain Persistence Specification
**Status:** DONE

Create `docs/03-data-model.md` before implementing tenant/catalog persistence.

Acceptance:

- logical tables and relationships are documented.
- tenant-owned tables are identified.
- no speculative schema unrelated to MVP is added.

## Milestone 1 — Organization and Catalog

### FZ-010 — Security Specification
**Status:** DONE

Create `docs/06-security.md` and finalize MVP human/machine authentication approach before sensitive implementation.

### FZ-011 — Organization Foundation
**Status:** DONE

Implement the Organization tenant boundary.

### FZ-012 — User Authentication
**Status:** DONE

Implement human authentication according to `06-security.md`.

### FZ-013 — Teams
**Status:** DONE

Implement tenant-isolated team management.

### FZ-014 — Applications
**Status:** DONE

Implement tenant-isolated application management and team association.

### FZ-015 — Environments
**Status:** DONE

Implement tenant-isolated environment management.

### FZ-016 — Invite User
**Status:** DONE

**Known gap:** the real Cognito `AdminCreateUser` call is not implemented. `IdentityProvider` (`com.freezhub.shared.security`) is a port; only `LocalIdentityProvider` (fake, `local` profile) exists, mirroring `06-security.md`'s local/test JWT strategy — no real Cognito user pool exists yet (that's infrastructure work, `FZ-063`). A real `CognitoIdentityProvider` implementation must be added before this endpoint is used against a deployed environment; until then, running without the `local` profile active will fail to start (no `IdentityProvider` bean), the same fail-fast behavior as the JWT decoder.

Added during `FZ-012` (see `06-security.md`, Human Authentication): an organization's first user is admin-provisioned out-of-band; every subsequent user must be added in-product. Runs immediately after `FZ-012` in execution order, ahead of `FZ-013`.

Implement an invite endpoint restricted to `ADMINISTRATOR` users:

- Caller must be authenticated and have `role = ADMINISTRATOR` in their organization; otherwise `403`.
- Request: target email (and initial `role`, defaulting to `MEMBER`).
- Backend creates the Cognito identity (`AdminCreateUser` — Cognito emails a temporary password) and, using the returned Cognito `sub`, creates the matching `users` row scoped to the caller's `organization_id`.
- Duplicate invite (existing `users` row for that org + email) is rejected, not silently duplicated.
- Invited user's `organization_id` is always the caller's own organization — never client-supplied beyond that.

## Milestone 2 — Freeze Core

### FZ-020 — Create Change Restriction
**Status:** DONE

**Known gap (deliberately deferred out of this story; CLOSED by `FZ-036`):** the scope association tables reference `team`/`application`/`environment` with non-cascading foreign keys, so the database *refuses* to delete a catalog resource that a restriction references. That refusal is not yet translated into an HTTP response, so `DELETE /api/teams/{id}` (and the application/environment equivalents) on a **referenced** resource returns `500` instead of `409`. Data stays correct — the delete is genuinely refused — only the status code is wrong. Translating it to `409` needs its own story.

Scope matching semantics (OR within a dimension, AND across dimensions, empty dimension = wildcard) were specified by this story and are recorded in `01-domain.md` § Scope matching semantics. They are the contract `FZ-051` implements; no evaluation logic exists yet.

Implement creation of a scheduled deployment restriction.

Required rules:

- `startsAt < endsAt`.
- restriction cannot be entirely in the past.
- at least one scope target is required.
- scope targets belong to the authenticated organization.
- MVP type is `DEPLOYMENT_FREEZE`.
- initial status is `SCHEDULED`.

### FZ-021 — List Change Restrictions
**Status:** DONE

List restrictions for the authenticated organization with useful status filtering.

Implemented as `GET /api/restrictions`, ordered soonest-start-first (tie-broken by id, so ordering is total and results are deterministic). `?status=` may be repeated to select several states at once — `?status=SCHEDULED&status=ACTIVE` answers the product's "what is active or upcoming?" question directly. Omitting it returns every status; an unrecognised value is a `400`.

The list returns a **summary without scope**: `FZ-022` owns "retrieve a restriction and its scope". Keeping scope out of the list also keeps it to a single query regardless of row count. Adding fields later is additive and non-breaking, so `FZ-031` (dashboard) can revisit this if it needs scope inline.

The three scope collections on `ChangeRestriction` were switched from `EAGER` to `LAZY` as part of this story: `EAGER` would have made every list call issue 3N+1 queries. Verified — listing three restrictions issues one query and none against the scope tables.

### FZ-022 — Restriction Details
**Status:** DONE

Retrieve a restriction and its scope.

Implemented as `GET /api/restrictions/{id}`, returning the full representation including all three scope dimensions (unused dimensions come back as empty arrays, not null). Unknown ids and ids owned by another organization are both `404`, so cross-tenant existence is never revealed.

The scope collections are `LAZY` (see `FZ-021`) and `spring.jpa.open-in-view` is disabled, so the service initialises them explicitly inside its read-only transaction — otherwise mapping the response in the controller would fail with `LazyInitializationException`. The detail test covers this: removing the initialisation makes it fail, so the guard is real rather than incidental.

### FZ-023 — Update Scheduled Restriction
**Status:** DONE

Allow supported changes while a restriction is still scheduled.

Exact mutable fields, specified by this story:

| Editable while `SCHEDULED` | Never editable |
|---|---|
| `name`, `description`, `reason` | `id`, `organizationId`, `createdBy`, `createdAt` |
| `level` | `type` (server-controlled) |
| `startsAt`, `endsAt` | `status` (see `FZ-024`, `FZ-025`) |
| all three scope dimensions | |

Implemented as `PUT /api/restrictions/{id}` — a **full replacement**, not a partial patch. PUT avoids the null-versus-absent ambiguity a PATCH would hit on the nullable `description`: in a Java record there is no way to distinguish "field omitted" from "field explicitly set to null", so a PATCH could not express clearing a field.

Editing is permitted only while the restriction is still `SCHEDULED`; `ACTIVE`, `COMPLETED` and `CANCELLED` return `409`. Once a restriction has taken effect it is a record of what happened, and editing it would rewrite history. Every creation invariant is re-checked on update, so an update can never leave a restriction in a state creation would have rejected.

`CreateRestrictionRequest` was renamed to `RestrictionRequest` and is shared by create and update: full-replacement semantics mean both carry identical fields and identical validation, and one record cannot drift out of step with itself. The shared invariant checks were likewise extracted into a single private method used by both paths.

**Deferred architectural concern — mutable aggregate vs. immutability + activity history.** Editing in place overwrites the previous state with no record that it changed or who changed it. The alternative (immutable/versioned restrictions, or an append-only change history) was raised during this story and deliberately deferred, not overlooked. `FZ-060` (Audit Events) covers part of it — recording *that* an administrative action happened — but not making the aggregate itself pristine. This deserves its own focused decision before beta; `docs/07-decisions.md` is the place to record the outcome.

### FZ-024 — Cancel Restriction
**Status:** DONE

Cancel a scheduled or active restriction according to domain rules.

Implemented as `POST /api/restrictions/{id}/cancel` — modelled as an action, not `DELETE`. Cancelling **preserves the record**: the restriction stays visible with status `CANCELLED`, because what was communicated to engineers actually happened and deleting it would erase that. `COMPLETED` and already-`CANCELLED` restrictions return `409`.

Cancellation is deliberately **not idempotent**. The backlog scopes this to "a scheduled or active restriction", and a repeat cancel means the caller believed the restriction was still live — worth surfacing rather than silently succeeding. Revisit if a machine client ever needs safe retries.

Terminality (domain invariants 5 and 6) falls out of the existing rules rather than needing new code: `FZ-023` already refuses to update anything that is not `SCHEDULED`, so a cancelled restriction can neither be edited nor moved back to `ACTIVE`. There is a test asserting exactly that.

### FZ-025 — Restriction Lifecycle
**Status:** DONE

Implement reliable transitions:

```text
SCHEDULED → ACTIVE → COMPLETED
```

and ensure cancellation prevents future activation.

Lifecycle correctness must survive application restarts.

Implemented as **reconciliation**, not scheduling: `RestrictionLifecycleService.reconcile(now)` compares persisted timestamps against a supplied instant and corrects the stored status with two set-based `UPDATE`s. It holds no timers and no in-memory state, so it is idempotent and recovers by itself after an outage of any length — matching `02-architecture.md`'s "transitions must not depend exclusively on an in-memory timer; the persisted timestamps/status are authoritative".

Two triggers, in `RestrictionLifecycleScheduler`:

- **on `ApplicationReadyEvent`** — a restart may leave statuses stale by however long the process was down; reconciling at boot closes that gap immediately instead of leaving it open until the first tick;
- **periodically** — `freezehub.lifecycle.interval` (default `PT1M`), for restrictions coming due while running.

Both disabled by `freezehub.lifecycle.enabled=false`, which the test suite sets so it never races a background job mutating rows underneath it.

Decisions made in this story:

- **`SCHEDULED → COMPLETED` directly is allowed.** If the process was down for a restriction's entire window, it still elapsed; leaving it `SCHEDULED` for ever would be wrong. Nothing in `01-domain.md` forbids skipping `ACTIVE`, and invariant 4 only forbids `COMPLETED → ACTIVE`.
- **Cancellation is honoured implicitly.** Both queries filter on status and `CANCELLED` matches neither, so a cancelled restriction can never be activated or completed. No extra guard was needed — the requirement falls out of the query predicates, and there are tests for both directions.
- **`updatedAt` is stamped explicitly**, because a bulk JPQL update bypasses `@PreUpdate` and the row would otherwise silently keep a stale timestamp.

**Contract for `FZ-051`:** the stored `status` is a materialised convenience and can lag by up to one interval. Policy evaluation must decide from the persisted `startsAt`/`endsAt` (plus "not `CANCELLED`"), *not* from the `status` column alone, or a deployment could be allowed during a freeze whose activation tick had not yet run.

## Milestone 3 — Frontend Product Slice

### FZ-030 — Frontend Specification
**Status:** DONE

Create `docs/05-frontend.md` with MVP routes, page responsibilities, shared UI conventions, and API interaction conventions.

Two decisions were required that no existing document answered, and both shape every later frontend story:

- **Styling: CSS Modules with native form controls**, no UI framework and no styling dependency (`CLAUDE.md`: no dependencies without a concrete need). `<input type="datetime-local">` and `<select multiple>` cover the create-restriction form; multi-select UX is basic, and that is an accepted trade-off.
- **Development authentication: a dev-only token endpoint** (see `FZ-035`), because no Cognito user pool exists until `FZ-063` and a browser therefore has no way to obtain a token at all.

Gaps this specification surfaced are tracked as items rather than prose — `FZ-035` (dev sign-in token, blocks `FZ-031`), `FZ-036` (catalog management UI, blocks `FZ-033`), a UTC-conversion acceptance criterion on `FZ-033`, and a note on `FZ-061` about revisiting the frontend's error handling. `05-frontend.md` carries the resulting Milestone 3 order:

```text
FZ-030 → FZ-035 → FZ-031 → FZ-032 → FZ-036 → FZ-033 → FZ-034
```

`FZ-036` still needs a product decision: build it, or explicitly defer it and accept catalog setup as an API-only onboarding operation.

### FZ-035 — Development Sign-In Token
**Status:** DONE

Implemented as `POST /api/dev/token` (`{"email": …}` → a signed token plus the resolved user), fenced off by **three independent guards**: the controller is `@Profile("local")`; the `JwtEncoder` it depends on exists only under that profile; and `DevSignInSecurityConfig` — the profile-scoped filter chain that makes the path reachable without a token — is likewise absent outside it. Removing the profile annotation does not quietly expose the endpoint, it makes a deployed-shaped context fail to start.

Ambiguity is refused rather than guessed: email is unique *per organization*, not globally, so an email present in two organizations returns `409` instead of signing the developer into an arbitrary tenant.

Token minting lives in `LocalTokenIssuer`, which the test helper `TestTokens` also delegates to — one implementation, so the claim shape cannot drift between what tests assert and what development actually runs against.

Added during `FZ-030`. Runs immediately after it and **before `FZ-031`**, which cannot render an authenticated page without it.

No Cognito user pool exists until `FZ-063`, so the browser currently has no way to obtain a JWT. Provide a dev-only endpoint that mints the same locally-signed token the tests already use, mirroring the local/real split `06-security.md` established for JWT validation.

Acceptance:

- Exposed **only** under the `local` Spring profile — `@Profile("local")`, like `LocalJwtConfig`. No deployed environment activates that profile, so the endpoint cannot exist there.
- Accepts an identifier for an existing `users` row and returns a signed token whose `sub` matches that user's `external_subject`.
- Returns 404/400 for an unknown user rather than minting a token for an identity that does not exist.
- A test asserts the endpoint is **absent** when the `local` profile is not active — the security property, not just the happy path.
- Replaced by the Cognito Hosted UI redirect at `FZ-063`.

### FZ-031 — Dashboard
**Status:** DONE

Show active, upcoming, and recently completed restrictions.

First real UI story, so it also brings the frontend plumbing `05-frontend.md` anticipated: the single `fetch` wrapper with `ApiError`, TanStack Query with a central `401` handler that clears the token and drops the user at sign-in, the `RequireAuth` guard, the development sign-in screen (backed by `FZ-035`), the app layout, CSS Module tokens, and the UTC-aware date formatting.

Two requests, not three — `status` is repeatable, so active and upcoming arrive together (`?status=ACTIVE&status=SCHEDULED`) and are split client-side. "Recently completed" is capped at five in the UI, since the backend applies no recency window.

**CORS was added to the backend as part of this story.** The frontend runs on a different origin from the API — a Vite dev server locally, S3/CloudFront when deployed (`02-architecture.md`) — and preflight `OPTIONS` requests carry no `Authorization` header, so they were being rejected as `401` and *every* browser request failed before it was sent. Nothing caught this earlier because the frontend's own tests stub `fetch`; it only appeared under live verification. `freezehub.cors.allowed-origins` is **empty by default**, so a deployed environment has to name its frontend origin explicitly rather than inherit something permissive; the `local` profile fills in the Vite dev server. Covered by `CorsTest`, including that an unconfigured origin is refused.

Also fixed while building this: Testing Library's automatic DOM cleanup never registered, because it only self-registers when Vitest runs with `globals: true`. Every test was leaking its DOM into the next, which surfaced as phantom "found multiple elements" failures. `src/test/setup.ts` now calls `cleanup()` explicitly.

### FZ-032 — Restriction List
**Status:** DONE

Provide usable browsing/filtering of restrictions.

`GET /restrictions` with checkbox filtering across all four statuses. **Filter state lives in the URL**, not component state, so a filtered view can be linked to, bookmarked and survives a reload — and unknown values typed into the query string are discarded rather than forwarded to the API. Results are rendered in the order the backend returns them and are never re-sorted client-side: soonest-start-first is the API's contract, not this page's.

Empty results distinguish "nothing matches this filter" from "no restrictions yet" — very different things to tell someone.

The level badge was extracted to a shared `components/Badges.tsx` (with a new status badge) now that the dashboard and the list both render one, so how a level or status reads is defined once instead of drifting between pages. `RestrictionCard` was updated to use it.

### FZ-036 — Catalog Management UI
**Status:** DONE

`/catalog` — list, create, rename and delete teams, applications and environments, plus team↔application assignment. One shared `CatalogSection` component drives all three types so they cannot drift into three near-identical implementations.

**Two backend gaps had to be closed for this UI to be honest:**

1. **Deleting a referenced catalog entry now returns `409`, not `500`** — the gap deliberately deferred out of `FZ-020`. The database always refused the delete; the refusal simply was not translated. This story puts delete buttons in front of users, so a raw `500` was no longer acceptable. Enforcement stays in the database (`CatalogDeletion` catches the violation after an explicit `flush`), which keeps the dependency direction intact — `restriction` may depend on `catalog`, not the reverse.

2. **Error messages were never reaching clients at all.** Spring omits `message` from its error body by default, so *every* deliberate `ResponseStatusException` reason written anywhere in this API — "a team with this name already exists", "referenced by one or more change restrictions" — arrived as a bare status code. A narrow `ApiExceptionHandler` now returns the reason for deliberately-thrown `ResponseStatusException`s only; unexpected exceptions still fall through to Spring's default with no message, so enabling `server.error.include-message` (which would leak internals from a 500) was avoided. This affected the whole API, not just the catalog — it only became visible here because this is the first UI that has to *explain* a conflict. A full error contract remains `FZ-061`.

Added during `FZ-030`. Runs **before `FZ-033`**, which cannot offer scope pickers for teams, applications and environments that no one can create.

`00-product.md` names an Organization Administrator who "configures the organization, users, catalog, integrations", and `FZ-013`–`FZ-015` built the tenant-isolated catalog API — but no story ever exposed it in the UI. Without this, creating a restriction through the product requires first creating catalog entries with `curl`.

Scope is deliberately minimal — enough to make `FZ-033` usable, not full administration:

- List and create teams, applications and environments.
- Rename and delete where the API already supports it.
- Associate/disassociate a team with an application (`FZ-014`), since restriction scope is evaluated per dimension and the team dimension is meaningless without membership.
- Surface `409` on a duplicate name, and the tenant rules already enforced by the backend.

**Known rough edge:** deleting a team/application/environment referenced by a restriction currently returns `500` rather than `409` (see the known gap under `FZ-020`). This UI will make that reachable by a user rather than only by an API client, which raises its priority.

**Manual entry is one input path, not the only one.** Raised when this was scoped: an organization's catalog largely mirrors systems it already has — applications map onto repositories, teams onto GitLab/GitHub groups — so a realistic product ingests it rather than asking someone to retype it. Native GitHub/GitLab integration is explicitly out of MVP scope (`00-product.md`, Out of Scope), so this story builds typed entry only.

The consequence for the design is that this UI must stay a **thin CRUD over the existing catalog API** and must not become the place where anything about the catalog is decided. Specifically:

- No "source" or "origin" concept is modelled — that would be speculative ahead of an ingestion story.
- The UI adds no rules the API does not already enforce; a future sync writing to the same endpoints must produce the same result as a human typing.
- Nothing here assumes a human is the only writer, so an ingestion path can be added later without unpicking this.

An eventual sync will raise questions this story does not answer — what happens to a manually created application when a sync later claims the same name, and whether synced entries become read-only. Those belong to the integration story that introduces them.

### FZ-033 — Create Restriction UI
**Status:** DONE

Create the end-to-end form for FZ-020.

`/restrictions/new`, reachable from the restriction list. Posts name, description, reason, level, window and all three scope dimensions; `type` and `status` are deliberately absent from the body because both are the server's to decide.

**The UTC conversion is guarded by the test suite's timezone.** `vite.config.ts` runs the whole suite at `TZ=America/Bogota` (UTC-5), because on a UTC machine a missing conversion is indistinguishable from a correct one — the bug would pass every test and ship. `localInputToUtcIso` converts the zoneless `datetime-local` value; a test asserts 09:00 local posts as `14:00:00.000Z`. Verified by deliberately removing the conversion: the test fails with `expected '2026-11-27T09:00' to be '2026-11-27T14:00:00.000Z'`, so the guard is real rather than incidental. Confirmed end-to-end too — Postgres stores `2026-11-27 14:00:00+00`.

Client-side validation lives in `restrictionFormRules.ts` and mirrors the backend rules **for fast feedback only**; the file says so explicitly, and a backend rejection is surfaced rather than swallowed (there is a test where the backend rejects a payload the client considered valid). If the two ever disagree, the backend is right and that file is the bug.

If the catalog is empty the form says so and links to it, rather than presenting three empty pickers and a rule the user cannot satisfy.

**Not used:** React Hook Form and Zod, both listed in `02-architecture.md`'s target stack. The form's rules are a handful of field checks plus two cross-field ones, and the existing catalog forms are hand-rolled — adding two dependencies and a second form pattern for this was not justified (`CLAUDE.md`: reuse existing patterns; no dependencies without a concrete need). Worth revisiting if forms grow substantially.

Acceptance:

- Submits `name`, `description` (optional), `reason` (**required, non-blank**), `level`, `startsAt`, `endsAt` and the three scope dimensions to `POST /api/restrictions`.
- **Datetime values are converted to UTC before submission.** `<input type="datetime-local">` produces a *zoneless local* string; sending it unconverted silently shifts every freeze window by the user's UTC offset, violating `01-domain.md` invariants 9 and 10. This must be covered by a test that would fail under a non-UTC timezone — it is the single most likely correctness bug in this story.
- Instants are displayed in the viewer's local zone **with the zone shown**, so a stated start time is never ambiguous.
- Client-side validation mirrors the backend rules for fast feedback only and is never the source of truth: the backend re-validates, and its `400`/`404`/`409` responses are surfaced rather than swallowed (`CLAUDE.md` §5 — the frontend must not duplicate domain rules as an independent source of truth).
- Depends on `FZ-036` (or on the explicit decision to keep catalog setup API-only) for the scope pickers to have anything to select.

### FZ-034 — Restriction Detail UI
**Status:** DONE

Show status, dates, reason, level, scope, and lifecycle information.

`/restrictions/:id` shows the full representation, with scope **ids resolved to names** — a reader learns nothing from `teamIds: [3]` — and an unconstrained dimension shown as *Any*, which is what the wildcard rule actually means (`01-domain.md`).

**Actions follow the backend's rules rather than being offered and refused:** edit appears only while `SCHEDULED` (`FZ-023`), cancel only while `SCHEDULED` or `ACTIVE` (`FZ-024`), and a terminal restriction says so instead of showing dead buttons. A table-driven test covers all four statuses. The backend still enforces everything — a `409` from the realistic race (the restriction activated or completed while the page was open) is surfaced with its message, not swallowed.

Edit is `/restrictions/:id/edit`, reusing a `RestrictionForm` extracted from `FZ-033`: update is a full replacement server-side, so create and edit carry identical fields and identical rules, and one component cannot drift from the other. Stored UTC instants are converted back to local wall-clock time for the inputs via `utcIsoToLocalInput`, the inverse of the conversion `FZ-033` guards.

Verified live through the exact endpoints the UI calls: `GET` → `PUT` (200 while scheduled) → `cancel` (200) → `PUT` after cancellation (**409**, "Only a SCHEDULED restriction can be updated; this one is CANCELLED") → cancel again (**409**).

**This completes Milestone 3.**

### FZ-037 — Frontend Test Stability
**Status:** DONE

**Fixes `OI-10`.** `npm run test` passed 64 tests and simultaneously reported "Vitest caught 3 unhandled errors", which Vitest itself flags as a possible source of false positives.

Cause: `renderRoute` builds a memory router containing only the route under test and `/signin`. `CreateRestrictionPage` navigates to `/restrictions/{id}` on success, nothing matches it, and React Router dereferences the missing match while the test is unmounting.

Fixed by giving `renderRoute`'s memory router a catch-all route, so anywhere a component navigates lands somewhere. Generic rather than specific to this page: every future test gets it, and tests assert where they ended up through the returned `router`, so navigation is observable rather than swallowed.

The suite now reports **zero** unhandled errors, and two tests were added for behaviour that had never been asserted — the redirect to the new restriction (which proves the server-assigned id is read from the response rather than the form state reused), and staying put on a failed creation so a filled-in form is not lost. Changing the redirect target fails the first of them.

Worth noting how it hid: the suite passed all 64 tests *and* reported the errors, so nothing failed. That is exactly the state in which a real unhandled rejection goes unnoticed.

### FZ-038 — Administrator Settings UI
**Status:** DONE

**Closes most of `OI-11`.** Two things an administrator needed existed only in the API: issuing an API key — the primary integration step for the whole product — and setting how much warning a freeze gives. Requiring a hand-written HTTP call for either is not an onboarding path.

**The one-time key reveal is the part that had to be right.** The raw key exists in exactly one HTTP response and nowhere afterwards, so it is shown on its own with a warning and **stays until dismissed**. Not a toast: a message that disappears by itself is the wrong shape for something unrecoverable, and the only remedy for missing it is issuing another key. There is a test for the persistence, not just the display.

Revoking asks first, because there is no un-revoke and a pipeline stops working the moment it happens. A revoked key shows as revoked with no button, and a double revoke surfaces the backend's `409` rather than appearing to succeed.

The lead time is a fixed set of choices — an hour up to a week — rather than a minutes field. The API accepts anything from 1 minute to 30 days, but "how much warning does the team want" has about six sensible answers, and a number box invites someone to type 90000 and collect a validation error. A value set outside the UI is kept and shown rather than silently replaced by the nearest option.

`SettingsPage` now composes three sections (`IntegrationsSection`, `ApiKeysSection`, `OrganizationSection`), following the existing `CatalogSection` pattern — three inline would have made one component nobody wants to read. Each loads and fails independently, so a member sees three explanations rather than one blank page.

Lists gained accessible names, which the existing test needed anyway once there were two of them.

**Verified against a running backend**, comparing every response shape to the TypeScript types: `GET /api/api-keys` carries no `key` field, `POST` does, revoke returns the revoked row, and a member gets `403` on keys and on saving settings but `200` reading the organization — which is why this screen only reports forbidden on save.

### FZ-039 — Audit Screen
**Status:** DONE

**Closes `OI-11`.** `/audit`, Administrator only, matching the API — the trail names who did what.

The companion to the deployment console: that one shows a pipeline being refused, this one shows why the rules it was judged against look the way they do. After `FZ-072` the two connect — a wall of refusals in the console and, in the trail, the rename a minute earlier that caused it.

**The work here is making the record readable**, not fetching it:

- `details` arrives in two shapes and both had to render: a before-and-after diff from an edit (`{"name": {"from": …, "to": …}}`) and a flat object from everything else. Showing raw JSON would push the parsing onto whoever is reading the screen during an incident.
- A cleared field reads as *"description: Peak trading → nothing"* rather than as a blank half of an arrow.
- Wording is composed from the action **and** `resourceType`, so `CATALOG_RENAMED` + `APPLICATION` reads "Renamed application". That is why `FZ-072` stored three catalog actions rather than nine.
- An action this build has never heard of degrades to readable words instead of rendering as a blank row.
- **The system's own work is not attributed to a person.** A restriction takes effect because time passed; `RESTRICTION_ACTIVATED` shows as *automatic*, and naming someone would be a fiction.

Supporting backend change: `GET /api/audit` gained a `?resourceType=` filter. Once the trail holds catalog changes, key issuance and policy refusals together, "what happened to our restrictions" is a different question from "who has been issued a key", and scrolling past the other is not an answer. The UI ignores a resource type it does not offer, so a hand-edited URL cannot send the backend something it will reject.

## Milestone 4 — Notifications

### FZ-040 — Notification Model + Outbox
**Status:** DONE

Persist notification intent and delivery state without a message broker.

Schema specified just-in-time in `03-data-model.md` (`integration`, `notification`). Two decisions were required that no document answered:

- **One outbox row = one delivery attempt to one destination.** Slack succeeding while a webhook fails is a normal outcome, and a single status per event could not express it; retry (`FZ-044`) also has to be per-destination. Matches `01-domain.md`'s "a delivery attempt" literally.
- **A minimal `integration` table is included**, because fan-out at enqueue time needs to know an organization's destinations, and `03-data-model.md` grouped it with this story. `config` is opaque JSON-as-text: each channel needs different settings and only the owning channel interprets them.

**Rows are written in the same transaction as the domain change.** That is the entire point — a process dying between "restriction activated" and "notification queued" cannot lose the notification, which is what a broker would otherwise have been for (`02-architecture.md`). `NotificationOutbox.enqueue` is `Propagation.MANDATORY`, so calling it outside a transaction fails loudly rather than silently reopening that gap; there is a test for it.

**Enqueueing is idempotent**, enforced both in the service and by a unique constraint on `(restriction, integration, event)`. Reconciliation runs on a timer and is deliberately idempotent, so without this an organization would be notified once a minute for the life of a restriction. Verified live that the database itself refuses a duplicate.

Enqueue points: creation → `SCHEDULED`, cancellation → `CANCELLED`, and the lifecycle reconciler → `ACTIVATED` / `COMPLETED`. The reconciler's set-based updates report a count rather than rows, so the affected restrictions are read with the same predicate immediately before the update.

**Nothing is delivered yet** — `FZ-041`–`FZ-043` add the channel adapters, `FZ-044` the retry policy.

**Known gaps:**

1. **No API or UI configures integrations** — now tracked as `FZ-045`, which runs before `FZ-041`.
2. **The "restriction starting soon" notification is not implemented** — `OI-3`, owned by `FZ-047`.
3. **Destination credentials are stored in plain text** — `OI-4`, needs a decision before beta.

### FZ-045 — Integration Configuration
**Status:** DONE

`/api/integrations` (list, create, enable/disable, replace config, delete) plus a Settings area in the UI. `ADMINISTRATOR`-only, like invite.

**The stored credential is never returned.** A Slack webhook URL is a bearer credential — anyone holding it can post into that channel — so the API stores it and reads back only a `summary` that identifies the destination without being enough to reuse it (`hooks.slack.com`, `2 recipients`). There is no endpoint that returns `config`, which is why changing a credential means replacing it rather than editing it. Verified live: the secret is absent from the response body and still present in the database.

`IntegrationConfigs` owns what each channel's config must contain — the one place that knows, since `integration.config` is deliberately opaque to everything else (`03-data-model.md`). It also rejects non-`https` destinations: these carry credentials and freeze announcements.

Disabling is distinct from deleting: disabling stops announcements while keeping the configuration, so a destination can be switched back on without re-entering its credential. Deleting cascades any queued notifications for it, since a delivery attempt to a destination that no longer exists has nowhere to go.

Verified end to end with `FZ-040`: a destination created through the API receives fan-out on restriction creation, and once disabled receives nothing further.

Added during `FZ-040`. Runs **before `FZ-041`**, which is otherwise implementable but not usable: the outbox fans out to an organization's integrations, and nothing lets a customer create one.

`00-product.md` names an Organization Administrator who "configures the organization, users, catalog, integrations", and `FZ-040` built the schema — but no story exposed it. Without this, enabling Slack means inserting a row directly into the database.

Acceptance:

- List, create, enable/disable and delete integrations for the authenticated organization, tenant-isolated on the same rules as every other resource (another organization's integration is `404`, never `403`).
- Restricted to `ADMINISTRATOR`, like invite (`06-security.md`) — a destination is where freeze announcements go, and its config may hold a credential.
- Each type's `config` is validated by the code that owns that type, since `integration.config` is deliberately opaque to everything else (`03-data-model.md`).
- **A stored credential is never returned.** A Slack webhook URL is a bearer credential; the API must not read it back out in a list response. Show enough to identify the destination, not enough to reuse it.
- Deleting an integration cascades its queued notifications (`ON DELETE CASCADE`, already in the schema) — document that this discards undelivered ones.
- A Settings area in the UI covering the same operations.

### FZ-041 — Slack Notifications
**Status:** DONE

Deliver selected restriction lifecycle notifications to Slack.

A `NotificationSender` port (the same shape as `IdentityProvider`) plus `SlackNotificationSender`, drained by `NotificationDispatcher` on a timer (`freezehub.notifications.interval`, default `PT30S`, disabled in tests). Email (`FZ-042`) and webhook (`FZ-043`) arrive as new senders without touching the dispatcher; a channel with no adapter yet leaves its notification `PENDING` rather than marking it delivered or discarding it.

**Delivery is one notification per transaction**, in a bean of its own (`NotificationDelivery`). That separation is load-bearing rather than stylistic: Spring's transactions are proxy-based, so a `@Transactional` method called from another method of the *same* bean is not intercepted at all — the first draft had exactly that bug, and it would have run the whole batch with no per-notification transaction and nothing to show for it. Per-notification transactions are also what stop one failing destination rolling back deliveries that succeeded alongside it.

**A destination credential never reaches `last_error`.** That column is read by whoever is diagnosing a missing announcement, and most HTTP client exceptions include the request URI by default — which for Slack *is* the credential. The sender raises its own exception type carrying a description instead. Verified live: a genuine connection failure recorded `Slack rejected or could not be reached: ResourceAccessException`, with no URL in it.

Announcement wording lives in `NotificationMessage`, separate from any channel so every channel says the same thing and the wording can be asserted without sending anything. Times are rendered **in UTC and labelled**: a freeze announcement reaches a distributed audience with no shared local zone.

Verified end to end against a real HTTP receiver, not only mocks — creating a restriction produced an actual POST carrying
`{"text":"Deployment freeze scheduled: Black Friday Freeze\nReason: …\nWindow: 27 Nov 2027 14:00 to 2 Dec 2027 09:30 UTC"}`,
and the notification moved to `SENT` with `sent_at` set.

**Known gap — retry is unbounded.** Tracked as `OI-1` in `09-open-issues.md`, owned by `FZ-044`. A failed delivery stays `PENDING` and is retried on every dispatch pass, for ever; measured live at `attempts=3` within about twelve seconds at a five-second interval.

### FZ-042 — Email Notifications
**Status:** DONE

Deliver selected lifecycle notifications by email.

**Plain SMTP via `JavaMailSender`, not the AWS SES SDK.** SES exposes an SMTP endpoint, so the same code runs against a local mail catcher in development and against SES when deployed (`02-architecture.md`) — one implementation, and none of the local/real split Cognito needed (`OI-2`). It also keeps the AWS SDK out of the dependency list for now.

The sender is `@ConditionalOnProperty` on `freezehub.notifications.email.from`. Without it the dispatcher finds no EMAIL sender and those notifications **defer without consuming a retry attempt** — an unconfigured mail server is a configuration gap, not a delivery failure, and it must not exhaust a notification's budget (`FZ-044`). The property is deliberately **not declared** in `application.yml`: `@ConditionalOnProperty` counts an empty value as present, which would have registered a sender with a blank from-address.

Subject is the shared headline and body the shared text from `NotificationMessage`, so email says exactly what Slack says — there is a test asserting the body is character-for-character what the shared builder produces. Recipients are kept out of failure messages, since `last_error` is read by support and should not become an address list.

`docker-compose.yml` gains **Mailpit** behind a `dev` compose profile (`docker compose --profile dev up -d mailpit`), so email can be exercised for real without sending anything. Verified end to end: a destination created through the API received an actual SMTP message at both recipients — subject `Deployment freeze scheduled: Black Friday Freeze`, body carrying the reason and the UTC window — and the notification moved to `SENT`.

### FZ-043 — Generic Webhook
**Status:** DONE

Deliver machine-readable lifecycle events to configured webhook endpoints.

Unlike Slack and email, this body is a **contract** someone else's software parses, so it is treated as public surface: `WebhookPayload` carries a `version` (cheap now, and the only thing that makes a later change safe to roll out), instants are ISO-8601 rather than epoch numbers, and an `X-FreezeHub-Event` header lets a receiver route without parsing the body first.

**Scope is deliberately omitted from the payload.** A consumer given the affected teams, applications and environments would have to re-implement the matching rules (OR within a dimension, AND across them, empty meaning any) to decide whether a particular deployment is affected — a second implementation of domain logic, outside FreezeHub, that would inevitably drift and be wrong. The Policy API (`FZ-051`) answers "may I deploy"; this event says only that something changed and is worth re-checking. There is a test asserting scope is absent, so it cannot be added casually.

The endpoint URL is kept out of failure messages, as with Slack: a webhook URL commonly carries a token in its path or query, and `last_error` is read by support.

Verified live against a real receiver — creating then cancelling a restriction produced two POSTs with `X-FreezeHub-Event: SCHEDULED` and `CANCELLED`, each carrying the versioned payload with `status` reflecting the state at that moment.

**Milestone 4's delivery channels are now complete.** The "no sender configured" behaviour that previously used WEBHOOK as its example moved to `UnconfiguredEmailTest`, which exercises the realistic version: email with no from-address, deferring indefinitely without consuming retry attempts.

**Known gap:** webhook deliveries are not authenticated — a receiver cannot verify a request came from FreezeHub. Tracked as `OI-7`; needs a decision, since no document specifies a signing scheme.

### FZ-044 — Notification Retry
**Status:** DONE

**Fixed `OI-1`.** Verified by re-running the exact scenario that exposed it, with a *harsher* dispatch interval: previously a dead destination reached `attempts=3` in ~12 s at a 5 s interval; now it reaches `attempts=1` in 20 s at a **2 s** interval, with the next attempt scheduled 41 s out.

- `notification.next_attempt_at` (migration `012`) is what makes "not yet" expressible — without it every `PENDING` row was eligible on every pass, which *was* the defect. The dispatcher now selects on `(status, next_attempt_at)`, and the index was changed to match.
- `RetryPolicy` is standalone and static so the schedule is asserted directly rather than by waiting: 30 s doubling to a 15 min cap, six attempts, then terminal `FAILED`. The cap exists because unbounded doubling eventually means "never"; the limit exists so an undeliverable announcement is visibly given up on — nobody learns an announcement never arrived if it retries for ever.
- **Failures are separated by kind**, which the original code conflated:
  - *delivery failed* — consumes an attempt, backs off, eventually `FAILED`;
  - *destination or restriction deleted* — `abandon()`, terminal immediately, since retrying cannot fix it;
  - *destination disabled*, or *channel adapter not built yet* (`FZ-042`/`FZ-043`) — `deferUntil()`, **no attempt consumed**. A webhook notification must not exhaust its retry budget waiting for an adapter that does not exist yet, and being switched off is not a delivery failure.
- A transient failure followed by success ends `SENT` with `lastError` cleared, so a stale error is never presented as the current state.

Implement bounded retry and observable failure state.

**Fixes `OI-1`** (`09-open-issues.md`): today a failed delivery stays `PENDING` and is retried on every dispatch pass for ever. Measured during `FZ-041` — an unreachable destination reached `attempts=3` in about twelve seconds at a five-second interval.

Acceptance:

- A notification is attempted at most a bounded number of times, then becomes `FAILED` and is not attempted again.
- Attempts are spaced by a backoff, so a dead destination is not hit on every pass. This needs a `next_attempt_at` column — the current schema has no way to say "not yet", so the dispatcher cannot skip a row without either losing it or spinning on it.
- `FAILED` is observable: the reason is retained in `last_error`, and it is possible to see that an announcement was never delivered. A silently undelivered freeze notice is the failure mode that matters — engineers deploy believing nothing is frozen.
- Retry state is per notification, not per event, since the outbox is already per destination.
- A transient failure followed by a success still ends `SENT`, with the earlier error no longer presented as current.

### FZ-046 — Cognito Identity Provider
**Status:** DONE · **Resolves** `OI-2` · **Raises** `OI-44`

`CognitoIdentityProvider` implements the existing port outside the `local` profile: one
`AdminCreateUser` call, returning the `sub` Cognito issues, which is what
`users.external_subject` stores. The pool created by `FZ-063` exists now, which is what
`D-4` said to wait for.

**The fail-fast moved rather than disappeared.** `FZ-016` got it by defining no bean at all,
which this story necessarily gives up. `freezehub.cognito.user-pool-id` and
`freezehub.cognito.region` have no defaults in its place, so an environment that forgets
them refuses to start — at boot, not at the first invitation. `application.yml` carries the
names as a comment and no value, the same shape the encryption key already uses.

**The subject is read from the response, never derived.** Cognito assigns `sub`; it is not
the username, the email, or anything this application can compute. A missing or blank one
is an error rather than a placeholder, because a wrong `external_subject` is a user who can
never sign in and a row that looks correct.

**Failures do not leave a half-created user.** `IdentityProviderException` is unchecked and
`InviteService` does not catch it, so the transaction rolls back and no user row survives a
failed invitation. `UsernameExistsException` is separated out because it is reachable
despite `InviteService`'s own check — that one is scoped to an organization and the pool is
shared across all of them. The message never carries the address; the log line carries the
pool and the operation.

**One dependency, and it is the first AWS coupling in the backend.** `OI-15` records
cloud-neutrality as worth keeping, so it is the narrow `cognitoidentityprovider` module
rather than a bundle, both bundled HTTP clients are excluded in favour of
`url-connection-client`, and the whole of it sits behind one class. The alternative is not
"no AWS code": Cognito's admin API cannot be called without SigV4, so hand-rolling the
signature would be more AWS-specific code, not less.

**`DevSignInAbsentOutsideLocalProfileTest` gained something rather than being patched
around.** It was stubbing `IdentityProvider` to get a deployed-shaped context to boot; it
now boots the real adapter, which asserts that the client constructs with **no AWS
credentials present** — the situation in CI, and at container start before the instance
profile is first used.

**Not done here, and the reason it is an issue and not a silent addition:** the single box
passes neither property and its instance role cannot call `AdminCreateUser`, so the backend
will not start on the posture `D-35` actually deploys. `OI-44`, owned by `FZ-123`.

Acceptance:

- Selected outside `local`, configured per environment, no hardcoded pool.
- Returns the `sub` Cognito issued; a missing or blank one fails loudly.
- No SDK exception escapes the port, and no failure message carries the email address.
- Seven tests, five of them negative — the shapes that produce a plausible-looking user
  nobody can sign in as.

### FZ-047 — "Starting Soon" Notification
**Status:** DONE

**Fixes `OI-3`.** `00-product.md` lists a "restriction starting soon" notification; nothing implements it.

**Blocked on a product decision:** how soon is "soon", and is the lead time fixed, per organization, or per restriction? No document says.

It also differs structurally from every other lifecycle event — it is triggered by the passage of time rather than by a state transition, so a scheduled check writes the outbox rather than a domain change doing so. That check must be idempotent in the same way the lifecycle reconciler is, or a restriction would be announced as "starting soon" on every pass.

**Decision: the lead time is per organization, defaulting to 24 hours.** Release rhythms differ — a weekly train wants more notice than a shop deploying continuously — and defaulting rather than asking at sign-up means nobody answers a question they have no opinion about yet. Bounded between 1 minute and 30 days, in the database and in the API, so a bad value is a `400` rather than a `500`.

**Idempotence was the whole difficulty**, and it needed no new machinery: a restriction stays inside its warning window for the entire lead time, so the sweep sees it again on every pass, and the outbox's existing unique constraint on (restriction, integration, event) is what makes it announce exactly once. Verified live at a three-second sweep interval — roughly five passes produced exactly one announcement.

`StartingSoonNotifier` runs on the same tick as lifecycle reconciliation, and deliberately **after** it: reconciling status first is what stops a restriction that has just begun from also being announced as about to.

The sweep is bounded by the largest lead time any organization has configured, so it examines a small window rather than every future restriction across every tenant.

New: `GET /api/organization` (any member — knowing how much warning the team gets is not sensitive) and `PATCH /api/organization/settings` (Administrator only, like every other setting decided on the whole organization's behalf). Neither takes an organization id, because the organization comes from the credential.

**No frontend.** `05-frontend.md` has no settings screen, and adding one is a larger change than this story. The lead time is set through the API for now — recorded as `OI-11`.

### FZ-048 — Webhook Signing
**Status:** DONE

**Fixes `OI-7`.** A receiver currently has no way to verify that a webhook request came from FreezeHub, so anyone who learns a customer's endpoint can post a forged event to it — and a forged `CANCELLED`, telling an automated consumer that a freeze has been lifted, is exactly the event worth forging.

**Decision (recorded in `07-decisions.md`): HMAC-SHA256 over timestamp and body**, with a per-integration secret FreezeHub generates and shows once.

Acceptance:

- A webhook integration is issued a signing secret at creation, returned once and never readable again.
- Every delivery carries `X-FreezeHub-Timestamp` and `X-FreezeHub-Signature: sha256=<hex>`, computed over `timestamp + "." + body` so a captured request cannot be replayed with a new body or a stale one accepted for ever.
- The secret can be rotated without recreating the integration.
- The secret is never returned by any listing and never appears in a log or in `notification.last_error`.

**Verified against a real receiver**, not only in tests: a Node endpoint doing standard HMAC verification accepted two genuine deliveries (`SCHEDULED`, `CANCELLED`), then rejected an unsigned forgery, a forgery with a guessed signature, and a genuine body replayed with a stale timestamp. After rotating the secret, the receiver — still holding the old one — rejected the next delivery, which is rotation taking effect with no overlap window.

The signed string format is pinned by a test vector computed **outside this codebase** with `openssl dgst -sha256 -hmac`. That is the assertion that matters: change the `.` separator or drop the timestamp from the signed string and every receiver in the world silently starts rejecting deliveries, with nothing in Java to catch it.

`WebhookSigning` deliberately does not reuse `ApiKeySecret` despite near-identical generation. The storage lifecycle is the opposite — an API key is stored hashed and verified by hashing what arrives; this secret must stay recoverable because signing needs the key. Sharing a type would invite someone to store this one hashed, which would break every delivery at once.

A webhook created before this story has no secret and is delivered **unsigned with a warning logged**, rather than not at all: refusing would silently stop announcements a customer relies on. Rotating fixes it.

**Cost, recorded in `D-2` and against `OI-4`:** this is a second credential that cannot be hashed at rest.

### FZ-049 — Credential Encryption at Rest
**Status:** DONE

**Fixes `OI-4`.** `integration.config` (which holds a Slack webhook URL — itself a bearer credential) and `integration.signing_secret` were readable to anyone with a database connection, a dump, or a backup. Neither can be hashed instead: the channel needs the URL, and HMAC needs the key.

**Decision `D-3`: AES-256-GCM in the application, behind a `SecretProtector` port.** The port is the point — the key already comes from configuration (Secrets Manager in a deployed environment), and delegating storage to a provider outright is a second implementation of the same interface rather than a rewrite of its callers.

- **A JPA `AttributeConverter`, not explicit calls in services.** The entity field stays plaintext in Java, so nothing that reads or validates a config needed changing, and no code path can forget to encrypt. It is a Spring bean as well as a converter, which Hibernate resolves through Boot's `SpringBeanContainer`.
- **GCM rather than CBC**: it authenticates as well as encrypts, so a row edited directly in the database fails loudly instead of quietly yielding different plaintext. A tampering test asserts that.
- **No default key outside `local`.** An environment that forgets it fails to start, matching the existing `JwtDecoder` fail-fast. The committed local key protects a developer's own database and is worth nothing.
- **Stored as `fzenc1:` + Base64.** The scheme prefix is what makes a later change — a new algorithm, key rotation, or delegation to a secrets manager — rolloutable rather than a flag day. A value without it is pre-`FZ-049` plaintext, read as-is and encrypted on its next write, so no bulk migration is needed.

**Verified against the raw column through JDBC, deliberately bypassing JPA** — going through the repository would only prove the converter is symmetric and would pass just as happily if nothing were encrypted. Removing the `@Convert` annotations fails those tests.

Also verified live: a new Slack destination's credential is `fzenc1:…` in the table with **zero** rows containing the plaintext, while the API still summarises its host correctly; a webhook created afterwards still produced deliveries that an independent receiver verified, proving decryption works on the delivery path; and a pre-existing plaintext row was readable and came back encrypted once it was rewritten.

Known limits, recorded in `D-3`: no protection against a compromised application, which holds the key; and no key rotation yet.

One test needed changing rather than the design: `OrganizationRepositoryTest` is a `@DataJpaTest` slice, which does not scan `@Component`, so it now imports the protector explicitly and supplies a key — the same thing a deployed environment must do.

## Milestone 5 — Policy Enforcement

### FZ-050 — API Contract
**Status:** DONE

Create/finalize `docs/04-api.md` and the machine-facing policy contract.

`04-api.md` documents the conventions (auth, tenant isolation, status codes, UTC), summarises the human API, and specifies the Policy Evaluation contract that `FZ-051` implements.

Decisions made by this story:

- **Identify application and environment by name, not id.** A pipeline knows `payments-api` and `production`; making it discover FreezeHub's internal ids contradicts `00-product.md`'s "simple integration". Accepted consequence, recorded in the contract: renaming a catalog entry is a breaking change for any pipeline referencing the old name.
- **`POST`, not `GET`, despite being a read.** A `GET` is cacheable and a cached `ALLOW` is exactly the wrong thing to serve during a freeze; no proxy should be able to answer this.
- **"In force" is derived from `startsAt`/`endsAt`, never from `status`.** The status column is maintained by a reconciler on an interval (`FZ-025`) and can lag, so trusting it would allow a deployment during a freeze whose activation tick had not yet run — a hole that would surface only under load or just after a restart. `FZ-025` flagged this; the contract now formalises it.
- The response lists **every** matching restriction rather than only the deciding one, including on `ALLOW` with advisories — a caller told only "BLOCK" cannot act on it.

Two things `FZ-051` must not decide on its own:

- **`OI-8` — unrecognised names can bypass a freeze.** Blocking; needs an explicit decision. Written up in `04-api.md` § Open decision.
- **`OI-9` — the Policy API has no machine credential until `FZ-052`.** The backlog orders `FZ-051` before API keys; the endpoint that decides whether deployments are blocked should not ship without machine authentication. Simplest fix is to do `FZ-052` first or alongside — which is what happened; closed by `FZ-052`.

### FZ-051 — Policy Evaluation
**Status:** DONE

`POST /api/policy/evaluate`, the machine boundary and the reason FreezeHub is a service rather than a wiki page. Authenticated by API key alone (`FZ-052`), so the organization comes from the credential and a caller cannot ask about a tenant that is not its own.

- **"In force" is derived from `startsAt`/`endsAt`, never from `status`** — the contract `FZ-025` flagged and `FZ-050` formalised. Verified live rather than only in tests: with four restrictions whose windows had opened but whose stored status was still `SCHEDULED` because the reconciler had not run, evaluation correctly returned `BLOCK`. Making the service trust the status column instead makes five tests fail.
- **The matching rule lives on `ChangeRestriction.covers(...)`**, not in a query or a service — it is the rule the whole product exists to enforce, so it is expressed once, in the domain object, in a form that reads like `01-domain.md` and is provable without a database. `RestrictionScopeMatchingTest` walks the worked examples from the docs, including the one that is easy to get backwards: naming a team *and* an application **narrows** rather than widens.
- The response lists **every** matching restriction, advisories included, and carries each one's `reason`: someone staring at a blocked pipeline needs to know why the freeze exists, not merely that it does.

**`OI-8` is decided and closed: an unregistered application or environment name blocks**, and the response names which one. An unrecognised name matches no scope list, so evaluating it normally tended toward `ALLOW` — misspelling the environment was a deliberate route to deploying straight through a freeze with a legitimate-looking permission in the log.

Returned as a `200` carrying `BLOCK` rather than a `4xx`, deliberately. An error status lands in the pipeline's error branch, which is exactly where `04-api.md` tells clients to choose fail-open or fail-closed for themselves — so a rejection expressed as an error could be configured back into a deployment, while a decision cannot. Applied to the environment dimension as well as the application: the bypass originally raised was literally `prod` versus `production`.

Names match **exactly, including case**, because catalog uniqueness is case-sensitive and a looser match here would disagree with the registry being read. The accepted cost, taken knowingly: FreezeHub becomes a gate on catalog completeness — an unregistered application cannot deploy at all, even with no freeze anywhere.

**Performance.** Scope collections gained `@BatchSize`, so evaluation costs a constant number of queries rather than three per candidate restriction — this is asked once per deployment. Measured live: **20 in-force restrictions, three scope queries**, where the unbatched form would have issued 60.

Still open and belonging to `FZ-060`: whether evaluations naming an unregistered resource should be **recorded**, so a repeated bypass attempt is visible afterwards rather than only refused in the moment.

Evaluate a `DEPLOY` action for application/environment context.

Required semantics:

- no matching active restriction → `ALLOW`;
- only matching `ADVISORY` → `ALLOW` with advisory information;
- any matching `HARD_FREEZE` → `BLOCK`;
- cancelled/completed restrictions do not affect the decision.
### FZ-052 — API Keys
**Status:** DONE

**Resolves `OI-9`, and was pulled ahead of `FZ-051` to do it.** The backlog ordered the Policy API first, which would have meant the endpoint that decides whether deployments are blocked existing with no machine credential to call it. This story delivers both the credential and the machine-facing chain that consumes it, so `FZ-051` adds a handler behind a door that is already locked.

- **Key format** `fzh_` + 256 bits of `SecureRandom` in URL-safe Base64. The prefix is for leak scanners and support, not decoration; URL-safe so nothing between CI and here mangles it.
- **The raw key is stored nowhere.** Only its SHA-256 hash, and the assertion is written against the persisted row rather than the API response, so no future response shape can quietly reintroduce it. `key_prefix` (the first ten characters) is what a listing shows instead — enough to say which credential a row is, nowhere near enough to use it.
- **Revocation is one-way.** `revoked_at` is set once and never cleared, unlike `Integration.enabled`. A key is withdrawn precisely because it may already be in someone else's hands; restoring it would revive that copy. Re-revoking is `409`, matching restriction cancellation.
- **A key opens exactly one door.** The machine chain is scoped to `/api/policy/**`, so a credential leaked from a CI variable cannot read restrictions, edit the catalog, or mint further keys. The reverse also holds: a human JWT is refused on the machine chain. Both mismatches are `401`. Widening the matcher to `/api/**` makes those tests fail, which is how the scoping is known to be load-bearing.
- **`ApiKeyPrincipal` is deliberately not an `AuthenticatedUser`**, and carries no authorities. No person is behind a pipeline call, so there is no user id, email or role to invent — and an empty authority list means a machine credential can never satisfy `hasRole(...)`.

**Decision — the hash is not salted**, contradicting the literal wording of `06-security.md` ("salted hash (e.g. SHA-256)"), which has been updated with the reasoning. A salt defeats rainbow tables and offline brute force against *low-entropy* secrets; against 256 bits of randomness there is nothing to guess. A per-key salt would also stop the hash of an incoming key from identifying its row, forcing either a second lookup handle inside the token or hashing every stored row on every call — on the endpoint asked once per deployment. What the specification actually requires is untouched: the raw key is never stored, and lookup is by hash.

**Defect found and fixed — errors behind the machine chain came back as `401`.** A failed request is forwarded to `/error`, that forward is re-filtered by Spring Security, and `/error` does not match `/api/policy/**` — so it fell through to the human chain, found no JWT, and answered `401`. A pipeline receiving `401` for a malformed body would go looking at its credential, which is the wrong place entirely. Fixed by permitting the `ERROR` dispatch on the main chain: an error is already the outcome of a request that was authorized on its way in, and re-authorizing the forward only replaces that outcome with a worse one.

Worth recording *how* it was found: **only by running the application.** MockMvc resolves handler exceptions in place and never performs the forward, so no controller test could have shown it. `ApiKeyErrorDispatchTest` therefore runs against a real servlet container on a random port — the first test in the codebase that does — and fails without the fix.

Not built, deliberately: **no frontend.** `05-frontend.md` specifies no API keys screen and no backlog item asks for one, so keys are issued through the API for now. **No `last_used_at`**, which would mean a write on the hottest path in the product for a question nobody has asked yet; revisit if "is this key still in use?" comes up before a revocation.

Implement secure organization-owned machine credentials according to `06-security.md`.
### FZ-053 — CI/CD Integration Example
**Status:** DONE

`examples/` holds a worked deployment gate: `freeze-check.sh` (POSIX shell, `curl` + `jq`) and `gitlab-ci.yml` wiring it into a pipeline. No native plugin, per the story.

**Moved by `FZ-091`:** the script now lives at `connectors/freeze-check.sh` — it stopped being an example the moment connectors started wrapping it. `examples/` keeps the hand-rolled walkthrough for CI systems with no connector.

**The logic lives in a script, not in pipeline YAML.** It is then identical on every CI system, runnable on a laptop while debugging, and readable by whoever is looking at it during an actual freeze. `gitlab-ci.yml` is wiring; the README carries a six-line GitHub Actions equivalent calling the same script rather than a second copy of the logic.

**It answers the question `04-api.md` deliberately left to the client** — what FreezeHub's silence means. `FREEZEHUB_ON_ERROR` is `block` by default, because a gate that opens when it breaks is not a gate, and the trade-off is stated in the script itself rather than buried in prose.

Two cases are deliberately **not** subject to that setting, and this is the part worth keeping:

- **`HTTP 401` fails the pipeline even under `allow`.** A bad or revoked credential is not an outage. If it failed open, revoking a key — or fat-fingering a CI variable — would silently switch enforcement off for every pipeline still using it, and nothing would look broken.
- **A missing required variable fails.** Enforcement must not be disableable by breaking the configuration.

Exit `1` (blocked) and exit `2` (not evaluated) are distinct, because "you may not deploy" and "I could not find out" are different facts and only the second is the platform team's problem. A request timeout is set, since without one "fail closed" quietly becomes "hang until the job times out" — worse than either choice on offer.

**Verified by running it against a live backend**, not by inspection: ten paths — allow; allow with an advisory printed; hard freeze blocking; unregistered environment blocking with the corrective hint; bad key under `ON_ERROR=allow` still exiting `2`; unreachable FreezeHub under both `block` and `allow`; missing variable; invalid `ON_ERROR` value rejected rather than silently treated as permissive; and no temp file left behind.

The root `README.md` status section was five milestones stale ("Milestone 0 in progress"); corrected while adding `examples/` to the layout it documents.

Provide at least one simple pipeline example consuming the Policy API.

Do not build a native plugin yet.
## Milestone 6 — Audit and Beta Readiness

### FZ-060 — Audit Events
**Status:** DONE

Record important administrative and restriction lifecycle actions.

**Shaped by `D-1` (`07-decisions.md`), which resolves `OI-5`.** An audit event is how a change to a restriction is remembered; restrictions stay mutable and are *not* versioned. That makes this story responsible for more than "an action happened":

- Each event records who, when, the action, and the resource.
- For an update, it carries a **snapshot of the fields that changed, before and after**. Without that, "someone edited this freeze" is not an answer to anything.
- Events are immutable once written.

`FZ-023` already refuses edits once a restriction is `ACTIVE`, `COMPLETED` or `CANCELLED`, so this covers the window before a freeze takes effect — which is the only window in which a restriction can change at all.

Not in scope, and the accepted cost of `D-1`: point-in-time reconstruction. If that becomes a real requirement, an append-only revision table is the upgrade and these events are not wasted.

**Recorded:** restriction created / updated / cancelled (by a user) and activated / completed (by the system); API key issued and revoked; organization settings changed; and a policy evaluation refused because it named an unregistered application or environment.

That last one closes the thread `D-14` left open. Only *refusals* are recorded — an ordinary evaluation happens on every deployment and would bury the trail it belongs to — and it is the one entry attributed to an `API_KEY` actor rather than a person. One occurrence is a typo; twenty is a pattern, and the pattern is only visible if each one is written down.

Design points worth keeping:

- **`AuditTrail` uses `Propagation.MANDATORY`**, exactly like `NotificationOutbox`. The entry commits in the same transaction as the change it describes, so no entry can claim a change that rolled back and none is missing for one that did not. Calling it outside a transaction fails loudly rather than silently reintroducing the gap.
- **The actor's label is denormalised and there is no foreign key to `users`.** An audit trail that breaks — or is rewritten — when a user is renamed or removed is not an audit trail.
- **Only changed fields are recorded**, with before and after. A diff listing every field buries the one thing the reader came for. An update that changed nothing succeeds and records nothing.
- **`AuditDetails` exists so nothing builds that JSON by concatenation.** The first version of the API-key entry did, and an API key named `ci "quoted" name` would have produced a row no reader could parse. Verified live with exactly that name.
- **Keyset pagination on `id`, not an offset.** The trail is append-only, so with an offset every entry written between two page requests shifts the window and the reader silently skips some.

**Corrected during the story:** a comment claimed the scope collections had to be copied because `replaceEditableState` refills them in place. Removing the copy and re-running the tests showed they still passed — `FieldChanges.compare` evaluates eagerly, so it is the *ordering* that makes the diff correct, not the copy. The comment now says that; the copies are kept as cheap insurance, not presented as the thing that saves it.

**Not recorded by this story:** catalog changes, left out to keep it to what `00-product.md` asks for and tracked as `OI-12`. Added by `FZ-072`.

**No frontend.** `05-frontend.md` specifies no audit screen; the trail is readable through the API. Folded into `OI-11`.

### FZ-061 — Error Handling
**Status:** DONE

Standardize API error responses and frontend handling.

Note added during `FZ-030`: this milestone lands **after** the frontend is built, so Milestone 3 ships against unstandardised error bodies. `05-frontend.md` therefore requires the fetch wrapper to tolerate a body it cannot parse and fall back to a status-derived message. When this item is implemented, revisit that wrapper and the per-page error states rather than assuming they still match — the status codes the UI branches on (`400`/`401`/`403`/`404`/`409`) are already established by the endpoints and should not change, but the body shape will.

**Decision `D-17`: RFC 9457 Problem Details**, `application/problem+json`, one shape for the whole API. Status codes are unchanged, as this entry required.

What actually improved, beyond consistency:

- **A validation failure now names the fields.** Previously a `400` said only "400" — a form could not highlight what it was never told about. The `errors` extension lists each field with its message, and where there is exactly one, `detail` names it directly so a client ignoring extensions still gets something actionable.
- **The invariant is preserved and now stated:** a deliberate rejection explains itself, an unexpected failure never does. Every `500` reads "The request could not be completed."; the real exception is logged. There is a test that throws a deliberately identifiable message and asserts it appears nowhere in the response.
- Jackson's own message is not returned for a malformed body, because it quotes the offending JSON and names the Java types it tried to bind.

**A regression this story introduced and its own test caught:** adding a catch-all `@ExceptionHandler(Exception.class)` swallowed Spring's `NoResourceFoundException`, turning an unmapped path from `404` into `500`. `ApiKeyErrorDispatchTest` — written in `FZ-052` against a real servlet container for a completely different reason — failed immediately. The fix checks for the `ErrorResponse` interface rather than a list of exception types, so a Spring exception this code has never heard of is still answered with its own status.

Frontend: the wrapper reads `detail`, exposes `fieldErrors`, and composes them into the message. It still tolerates an unparseable body — deliberately, since a `401` from the security chain has no body and a proxy in front of the API is outside the backend's control.

### FZ-062 — Observability Baseline
**Status:** DONE

Add production-appropriate logs, health checks, and visibility for notification/policy failures.

**Request correlation.** Every request gets an id — an incoming `X-Request-Id` is honoured so a load balancer's or a caller's own tracing survives, otherwise one is generated. It goes into the MDC, onto every log line, into the response header, and into the error body (`FZ-061`). A user quoting the `requestId` from a failed response is now an exact log lookup rather than "it failed at about three".

The supplied header is **sanitised before it reaches a log file**: an unbounded value is somebody else's newline injected into the logs, and a forged log line is worse than a missing one. The MDC is cleared in a `finally`, because threads are pooled and inheriting the previous request's id is confidently wrong rather than merely absent.

**A gap the live check exposed:** the id had almost nothing to correlate with. Nothing logged a completed request — a validation failure logs nothing at all — so the id appeared only on the rare line the application chose to write. Grepping for a real request id found zero lines. The filter now writes one line per request (method, path, status, duration), excluding health probes, which the load balancer asks for every thirty seconds and which would bury everything else. No headers and no body: one carries credentials, the other carries customer data.

**Health checks.** Liveness and readiness probes are enabled and the load balancer now reads **readiness** rather than the aggregate endpoint — readiness reports false while Liquibase is still migrating, which is exactly when a task must not be sent traffic and exactly when the aggregate endpoint already says UP. The security chain needed `/actuator/health/**`, not an exact match, or the probes would have answered the load balancer with a `401`; there is a test for it.

**Visibility.** Micrometer counters, no new dependency:

- `freezehub.notifications{outcome=sent|failed|abandoned|deferred}`
- `freezehub.policy.evaluations{decision, reason}`

`abandoned` is the one worth alerting on — an announcement that will now never arrive — and it is logged at ERROR rather than WARN for the same reason. Policy blocks are counted separately by *why*: a block by a real freeze is the product working, while a rise in `unregistered` is a pipeline misconfigured or somebody probing for a way through (`D-14`). All series are registered at startup so a dashboard has a line to draw before the first deployment rather than a gap.

**Deliberately not a health indicator** — see `D-18`. Metrics are exposed behind authentication and `health` is public; moving actuator to an internal port is the next step once there is real traffic, and exporting to CloudWatch or Prometheus is a deployment concern for when the infrastructure is actually applied.

### FZ-063 — Production Infrastructure
**Status:** DONE (written and validated; **never applied**)

The minimum AWS/Terraform deployment architecture for beta, matching `02-architecture.md`'s target: ECS Fargate behind an HTTPS ALB, RDS PostgreSQL in private subnets, S3 + CloudFront for the frontend, Cognito for human authentication, ECR for images, and Secrets Manager for the database password and the application encryption key.

Deliberate choices, each with its cost stated in `infra/README.md`:

- **One NAT gateway, not one per AZ**, and **single-AZ RDS**. Both trade availability for roughly half the monthly bill at a scale with no availability commitment. The NAT gateway is a third of the total cost on its own; it earns that by keeping the application and database off the public internet.
- **Security groups reference each other rather than CIDR ranges**, so the chain is explicit and reviewable: internet → ALB → application → database, and nothing skips a step.
- **Immutable ECR tags, never `latest`.** A rollback is a tag change, not a rebuild and hope.
- **ARM64 Fargate**, cheaper per vCPU-hour, which the build must match.
- **`SPRING_PROFILES_ACTIVE` is the environment name, never `local`.** Its absence is what makes the self-signing `JwtDecoder` and the development sign-in endpoint impossible in a deployed environment — the guard `FZ-035` built, honoured here.
- **A 120-second health-check grace period**, because Liquibase runs at startup; without it the ALB marks a migrating task unhealthy and ECS replaces it in a loop that looks like a crash.
- **`prevent_destroy` on the encryption key** and its secret. Losing it makes every stored credential permanently unreadable (`D-3`), and key rotation is not implemented, so `terraform taint` there would destroy customer data rather than refresh a key.
- **State lives in S3 with a DynamoDB lock**, created by `infra/bootstrap` with local state. Not optional: state holds the database password and the encryption key.

**Verified:** `terraform validate` passes and `terraform fmt -check` is clean for both the main configuration and the bootstrap, with providers pinned by a committed `.terraform.lock.hcl` (aws 5.100.0, random 3.9.0).

**Not verified, and this matters:** `terraform plan` and `terraform apply` have never been run. `validate` checks syntax and internal references; it does not check provider-side constraints, so an invalid argument value or an unsupported combination would surface only at plan or apply time. Terraform is also not installed on the development machine — the binary used here was fetched to a scratch directory.

**This does not make the product deployable on its own.** `FZ-046` is still outstanding, so the backend refuses to start outside the `local` profile: the infrastructure can be created, and the service will not come up. That pairing is decision `D-4`, not an oversight.

### FZ-064 — CI/CD
**Status:** DONE (verification proven; **deployment never run**)

The story splits cleanly, and the two halves are in very different states.

**`verify.yml` needs no AWS and is proven.** Four parallel jobs — backend (`mvnw verify`, with Testcontainers starting a real PostgreSQL on the runner's own Docker daemon), frontend (`npm ci`, lint, test, build), infrastructure (`terraform fmt -check` plus `validate` with `-backend=false`, which is what makes it need no credentials), and the example gate (`sh -n`, because a syntax error there breaks a customer's pipeline rather than ours). Parallel so a lint failure does not hide a test failure. Every command was run locally exactly as CI runs it, including `npm ci` from a clean checkout.

**`deploy.yml` has never executed**, because there is no AWS account, no ECR repository and no GitHub remote. It is written and reviewed, not proven.

**A gap this story found: there was no `Dockerfile`.** `FZ-063` built an ECS task definition around a container image, and `infra/README.md` documented `docker buildx build backend/` — with nothing to build. Added here as a multi-stage build so `docker build backend/` works identically on a laptop and in CI, with no "build the jar first" step to forget.

**A bug caught while writing the deploy job:** the first version ran `aws ecs update-service --force-new-deployment`, which redeploys whatever revision the service already has — and that one still names the *previous* image. It would have reported a successful deploy and shipped nothing. It now registers a new task definition revision.

**No AWS credentials are stored in GitHub.** The runner exchanges a short-lived OIDC token for a role (`infra/github-oidc.tf`). The trust policy is scoped to this repository *and* the master branch: left open, a fork's pull request could deploy to production, or another account's repository could assume the role outright. `iam:PassRole` is scoped to the two task roles rather than `*`, since `*` there is escalation to anything either role can do.

**CI deliberately cannot run `terraform apply`.** That would need a role able to change the database, the certificates and the user pool — far more than deploying needs. Terraform stays a human operation, and the ECS service gained `lifecycle.ignore_changes = [task_definition]` so the two do not fight over the image tag. The accepted consequence: the running image is no longer described by the Terraform, but by the deploy that put it there — which is why tags are immutable and named after the commit.

**Verified locally** by building and running the image: healthy in 10 seconds against the real database, running as **uid 999 rather than root**, the JVM as **PID 1** so it receives the SIGTERM ECS sends (`docker stop` returns in 0s rather than being killed after the timeout), 379 MB. A production-shaped run — no `local` profile — fails fast on the missing `IdentityProvider`, which is `OI-2` behaving exactly as `FZ-016` designed and confirms that gap is the only thing between this image and a deployment.

**What remains unproven:** every step that touches AWS. The workflow's correctness rests on reading it, not on running it.

Build/test/deploy automation for backend and frontend.

### FZ-066 — Decision Log Backfill
**Status:** DONE

**Fixes `OI-6`.** `02-architecture.md` asked for `docs/07-decisions.md` "when meaningful architectural decisions accumulate". They had — a dozen of them, each recorded only in the backlog entry of the story that made it, which is not where anyone looks for "why is it like this".

`FZ-048` created the file for `D-1` and `D-2`; this backfills `D-5` to `D-16` and adds `D-4`, recording the decision to sequence the Cognito adapter with `FZ-063` rather than build it against a service nothing can reach (`OI-2`).

**Numbering is by when a decision was written down, not when it was made.** `D-1` to `D-3` are already referenced from `SecretProtector`, `AesGcmSecretProtector` and three documents, so renumbering into chronological order would have broken those references for a cosmetic gain. Each entry carries the story that made it instead.

Every entry states a **cost**. That is the part worth keeping: a decision recorded without what it gave up reads as a justification rather than a decision, and is no help to whoever revisits it.

### FZ-065 — Beta Hardening
**Status:** DONE

Perform focused review of:

- tenant isolation;
- API key security;
- date/time behavior;
- restriction lifecycle;
- notification reliability;
- policy determinism;
- critical UI paths.

**Reviewed by running things, not by reading them.** A review whose output is an opinion decays the day after it is written; each area below was checked with something that can be re-run, and what it found is a test that fails without the fix.

| Area | How it was checked | Outcome |
|---|---|---|
| Tenant isolation | Every controller inventoried for where `organizationId` comes from; every id-addressed endpoint checked against its cross-tenant test | **Clean.** No endpoint takes an organization identifier from a client; the four `findById` calls in the codebase are the caller's own organization or an internal sweep. One gap in *coverage*: renaming another organization's catalog entry had no test, though the service was always scoped |
| API key security | Read the credential path end to end against `06-security.md` | **Clean.** Revocation is checked before the key is usable and before it is stamped; failures are indistinguishable; the audit records the prefix, never the key. The filter is constructed rather than a `@Component`, which is what stops Boot registering it for *every* request — the comment in `ApiKeySecurityConfig` names that trap |
| Date/time | Whole suite re-run under `TZ=Asia/Tokyo` (`D-27`'s detector); every `now()` call audited | **Clean.** 453 tests pass in a non-UTC zone. No `LocalDate.now()`/`LocalDateTime.now()` anywhere — every clock read is an `Instant` — and exactly one query buckets by day, with `at time zone 'UTC'` spelled out |
| Restriction lifecycle | Traced `CANCELLED` through activation, completion, starting-soon and the policy query | **Clean.** Terminal in all four |
| Notification reliability | Pointed a webhook at a socket that accepts and never answers | **Defect.** See below |
| Policy determinism | Read the in-force query for a total order | **Defect.** See below |
| Critical UI paths | Frontend suite; read the fetch wrapper for the failure mode found on the backend | 221 tests pass. The wrapper has no request timeout — same defect, other direction, recorded as `OI-22` rather than fixed here |

**Three defects, all fixed here.**

1. **No timeout on any outbound call** (`D-30`). A customer's webhook that accepts the connection and never replies held `WebhookNotificationSender.send` in `SocketDispatcher.read0` indefinitely — and delivery runs inside a transaction on one dispatcher shared by every organization, so one wedged receiver held a database connection and stopped *every other tenant's* announcements. Fixed centrally on the injected builder, plus `spring.mail.*` for the SMTP path that has no builder. `WebhookTimeoutTest` is the experiment, kept.

2. **Actuator was open to any authenticated member** (`OI-21`). `/actuator/metrics` is aggregate across tenants — a member of one organization could read how many deployment checks every customer makes, and the JVM's internals. Verified live with two real accounts. Now ADMINISTRATOR-only; the real answer is a management port, which is deployment work. The existing test's own comment said these were "not for every member of every organization to browse" — the rule was in the comment and not in the code.

3. **`findInForce` had no `ORDER BY`.** Two freezes both in force came back in whatever order the database chose, so one evaluation could name them "A, B" and the next "B, A", recording a different matched set each time. Never a wrong answer — just not a reproducible one, which is the property a deployment gate exists to have. Its sibling listing query had carried a total order, and the documented reason, since `FZ-021`.

Each fix was mutation-checked: reverted, and the new test fails.

## Deferred

Do not implement during MVP unless explicitly promoted into scope:

- exception requests;
- approval workflow;
- native GitHub/GitLab/Jenkins/Argo CD integrations;
- Jira/ServiceNow/PagerDuty;
- advanced RBAC;
- enterprise SSO;
- AI/change intelligence;
- dependency graphs;
- arbitrary policy language;
- infrastructure/database-specific restriction types.

## Immediate Execution Order

Start with:

```text
FZ-001
  ↓
FZ-002 + FZ-003
  ↓
FZ-004
  ↓
FZ-005
  ↓
FZ-010
  ↓
FZ-011
  ↓
FZ-012
  ↓
FZ-016   (added during FZ-012; out of numeric order, runs here — see FZ-016)
  ↓
FZ-013...
```

Parallel work is allowed only when dependencies are clear and the changes do not create conflicting architectural decisions.

### FZ-072 — Catalog Changes in the Audit Trail
**Status:** DONE

**Fixes `OI-12`.** `FZ-060` recorded restriction, API key, user and settings changes but not catalog ones. That gap grew teeth once `FZ-071` shipped: because an unrecognised name blocks (`D-14`), renaming an application turns every pipeline still sending the old name into a refusal — so the console fills with red and nothing anywhere says when it started or who caused it.

Recorded now: `CATALOG_CREATED`, `CATALOG_RENAMED` and `CATALOG_DELETED` for teams, applications and environments, plus `APPLICATION_TEAM_ASSIGNED` and `APPLICATION_TEAM_UNASSIGNED` — the latter because moving an application between teams silently changes what a team-scoped freeze covers without anybody touching the freeze.

**Three actions rather than nine.** Which kind of thing it was is already `resourceType`, so `CATALOG_RENAMED` + `APPLICATION` says everything `APPLICATION_RENAMED` would, without nine enum values that would only ever be read together.

**No migration.** `FZ-060` put a `CHECK` on `actor_type` and deliberately not on `action` or `resource_type`, so new values cost nothing.

Nothing is recorded when nothing happened: a rename to the same name, a repeated assignment, or a deletion refused because a restriction still references the entry (`409`). Each has a test.

**Verified live by reproducing the scenario that motivated it** — a pipeline deploying happily, an administrator renaming the application, and the same unchanged pipeline refused a second later. The trail now reads as the story it is:

```text
07:12:05  dev@acme.test  CATALOG_RENAMED  APPLICATION  {"name":{"from":"payments-api","to":"payments-service"}}
07:12:05  gitlab-ci      POLICY_BLOCKED_UNREGISTERED   {"application":"payments-api","unregistered":"[APPLICATION]"}
```

### FZ-074 — Demo Data and Walkthrough
**Status:** DONE

`scripts/seed-demo.sh` builds a believable organization to demonstrate against, and `docs/10-demo.md` is the walkthrough it was built for.

**Everything goes through the real API**, so the audit trail and deployment console fill with entries the product genuinely produced rather than rows written straight into tables. The single exception is the first user, which the API deliberately cannot create — there is no self-service signup (`06-security.md`) — so that one row is inserted directly.

**It waits for the lifecycle reconciler before finishing.** For the first minute after seeding, a freeze that is already blocking deployments still reads `SCHEDULED` — correct, because policy decides from the timestamps and not the status column (`D-13`), and impossible to explain on camera. The script polls until the screens agree, and one restriction is deliberately given a two-minute window so the dashboard's recently-completed section is filled by a real transition rather than a pre-completed row.

It refuses to run twice rather than silently doubling the demo.

**It also fixes an ordinary gap:** an empty database has no users at all, so `POST /api/dev/token` returns `404` and nobody can sign in. A fresh checkout was unusable without hand-written SQL; this makes `docker compose down -v` a safe thing to do.

The walkthrough carries a **what not to say** section. The claim to avoid is *"FreezeHub prevents deployments during a freeze"* — it does not and cannot, since enforcement lives in the customer's pipeline. A technical buyer will test that claim, and being caught overclaiming costs the deal. The honest position is stronger anyway: no agent, no credentials into their repositories, and no blast radius.

## Milestone 7 — Deployment Visibility

Turns FreezeHub from "we announced the freeze and recorded the decision" into "here is every attempt to deploy, and what we told each one". The difference matters commercially: today a `BLOCK` vanishes the instant it is returned, and nobody can answer *"did anyone try to ship during Black Friday?"*

### FZ-070 — Deployment Check Record
**Status:** DONE

Record every policy evaluation — not just the refusals `FZ-060` records — with the metadata needed to say who tried what.

**Its own table, not `audit_event`.** `FZ-060` excluded ordinary evaluations deliberately: one happens per deployment and they would bury the administrative trail they sat in. That reasoning is unchanged, so this is a separate table with its own volume profile, retention and reader.

**"Checks", never "deployments".** What FreezeHub observes is a question, not an outcome — a pipeline can be told `ALLOW` and then fail for unrelated reasons, or be told `BLOCK` and deploy anyway. Naming these deployments would be a lie that surfaces during exactly the audit the feature exists to serve. Reporting the outcome afterwards is a possible later addition; it is not this.

The valuable metadata — who, which commit, which pipeline run — reaches FreezeHub only if the caller sends it, so the request gains three **optional** fields (`actor`, `reference`, `source`) and `freeze-check.sh` fills them from whatever the CI system exposes. Optional because not every runner has them, and because an existing pipeline must keep working untouched.

Matched restrictions are stored **denormalised** (id, name, level): the record must show what was true at check time, and a restriction can be renamed afterwards.

**Retention: one year, configurable per organization.** Matches the window most compliance regimes assume and lets a regulated customer keep more. A scheduled purge, following the lifecycle reconciler's pattern.

Acceptance:

- Every evaluation is recorded with its decision, and why it was blocked.
- Optional caller metadata is stored when supplied and absent when not; a request without it still succeeds.
- Records are readable by any member of the organization, newest first, filterable, keyset-paginated.
- Records older than the organization's retention are purged.
- **Data handling:** `actor` and `reference` are customer PII arriving on every deploy. They are never logged, and the retention setting is what bounds them.

### FZ-071 — Deployment Console
**Status:** DONE

`/deployment-checks`, open to **any member** rather than administrators only — it is where a team looks to see whether their own deployment got through, and making them ask an administrator would defeat the point.

Each row answers who tried what: application → environment, the person (from `actor`, falling back to the credential when the pipeline did not say), a truncated commit reference, when, and a link to the run. A refusal names **what refused it** — the hard freezes only, since an advisory that merely rode along did not stop anything and naming it would be wrong.

**The unregistered case reads differently from a real freeze**, deliberately: one is the product working, the other is a pipeline misconfigured or somebody trying a misspelling to get through (`D-14`).

**The page says in words that these are questions, not deployments**, and there is a test for that sentence. Decision `D-19` has to reach the person reading the screen, not just whoever reads the code — believing these are deployments would mislead in exactly the audit the screen exists to serve.

Filter state lives in the URL, so *"everything we refused"* is a link someone can send. Paging is by cursor, and "load older" appears only when a page came back full — otherwise it would be a button that does nothing.

## Milestone 8 — Commercial Onboarding

Everything before this milestone assumed the customer already existed. There is no way to acquire one without a developer: `06-security.md` provisions the first Administrator out-of-band, and `scripts/seed-demo.sh` inserts that row with raw SQL because the API refuses to.

`docs/11-commercial.md` is the specification for this milestone. It is the source of truth for pricing, plans, signup and billing; entries here say what to build, not why.

**Order.** `FZ-081` first — everything else reads the subscription it creates. `FZ-087` before anything unauthenticated is exposed. `FZ-082` cannot ship before `FZ-046` (`OI-2`): self-serve signup creates a Cognito identity, and the only `IdentityProvider` today is a `@Profile("local")` fake.

```text
FZ-081 ── FZ-087 ── FZ-082 ── FZ-083 ── FZ-084 ── FZ-085 ── FZ-086
              (FZ-046 required before FZ-082)
```

### FZ-080 — Commercial Model
**Status:** DONE

Specification only, no code: `docs/11-commercial.md`, plus `D-20`–`D-23`.

Four decisions were the user's to make and were made: hybrid go-to-market (self-serve trial alongside sales-assisted annual), **per registered application** as the pricing metric, Stripe Checkout for payment, and a 14-day full-feature trial with no card.

The reasoning that shaped the rest: **seats and evaluations are both the wrong metric.** Seats charge the customer for telling people about a freeze, which is the product. Evaluations tax calling the Policy API on every deploy, which is how a freeze is enforced at all — a customer optimising that bill would deploy past a freeze. Applications are what scope is built on, are already visible in the product, and cannot be gamed because `D-14` blocks unregistered applications outright.

### FZ-081 — Plans and Subscription State
**Status:** DONE

One `subscription` row per organization, and the plan limits that read from it.

Plans are **an enum with limits as code constants**, not a table. Limits are product decisions deployed with the code; a table invites per-customer edits that then contradict the pricing page. Enterprise is the exception and gets nullable override columns on the subscription row rather than a second mechanism.

Limits are enforced **on creation only** (`D-22`). A downgrade never deletes anything.

Acceptance:

- Every organization has exactly one subscription; creating an organization creates it.
- Creating a resource beyond the plan's limit is refused with `402` and a Problem Details body naming the plan, the limit, and the current count.
- A downgrade below current usage keeps every existing resource and refuses only the next creation.
- Trial expiry moves the subscription to `SUSPENDED`; a scheduled job does this, following the lifecycle reconciler's pattern.
- **A suspended organization's `/api/policy/evaluate` answers are byte-for-byte what they were before suspension** — same decision, same matched restrictions. There is a test for this, because it is the rule most likely to be broken by a later change (`D-21`).
- Suspension makes the human API read-only and stops notifications.
- Subscription changes are audited.

**Two things this story decided that the entry did not anticipate.**

*A missing subscription grants everything rather than nothing.* The row is unique and not null, every organization predating billing was backfilled, and signup will create one — so absence is a defect, and it is logged as one. But refusing on absence would make a customer's API read-only because of a bug in **our** billing data, which is exactly what `D-21` refuses to do everywhere else. It fails towards not billing, which is a conversation, rather than towards not working, which is an outage.

*The write guard is an interceptor, not a filter.* A filter throws outside the DispatcherServlet, so `@RestControllerAdvice` never sees it and a refusal would arrive as a generic 500 rather than the one error shape the API promises. `FZ-052` paid for that lesson once already with the `/error` forward.

Notifications are suppressed at **enqueue**, not at delivery. Skipping them later would leave rows `PENDING` for ever and flood the customer with stale announcements the moment they pay — "starting soon" about a freeze that ended three weeks ago.

### FZ-087 — Request Rate Limiting
**Status:** DONE · **Resolves:** `OI-11`

There is no rate limiting anywhere in the codebase. Today that is defensible: `/actuator/health` is the only endpoint reachable without a credential. `FZ-082` and `FZ-083` end that, and an unauthenticated endpoint that creates a Cognito user and sends an email is not something to expose without a limit.

Per-IP, in-application, fixed window — not a distributed limiter, which `CLAUDE.md` §4 excludes and which one backend instance does not need.

Acceptance:

- Signup and demo-request endpoints are limited per IP.
- Exceeding the limit returns `429` as Problem Details, with `Retry-After`.
- The limit is configuration, not a constant.
- Authenticated endpoints are unaffected.

**The part the entry missed entirely: who "one client" is.**

Nothing in the codebase configured forwarded headers. Behind the load balancer (`FZ-063`) that means `getRemoteAddr()` returns the balancer, so a per-IP limiter would put every customer in one bucket and refuse them all together — a limiter that is worse than none.

Setting `server.forward-headers-strategy: framework` fixes that, and introduces the opposite hazard: anywhere without a trusted proxy in front, a caller can send their own `X-Forwarded-For` and choose their bucket, or fill somebody else's.

**A YAML detail made that live in local development too.** A profile document *merges* with the default one rather than replacing it, so the deployed value applied under `local` as well. The local profile now sets `none` explicitly. This was found by a test failing, not by reading the file — and both halves are now covered: reading the header in the interceptor, and dropping the `none`, each fail the same test.

The interceptor uses `getRemoteAddr()` and never reads the header itself. That looks like an oversight and is the whole defence.

### FZ-082 — Self-Serve Signup
**Status:** DONE · **Was blocked by:** `FZ-046` (`OI-2`), which resolved it

`POST /api/signup` — the first unauthenticated write endpoint in the product. Creates the organization, its first Administrator, its Cognito identity and its trial subscription in one transaction.

**The response never varies.** `202 Accepted`, same body, whether the organization was created or the email is already in use. Anything else turns signup into a customer-enumeration oracle — the same reasoning that makes a cross-tenant resource `404` rather than `403`.

Acceptance:

- A new company signs up, receives the Cognito temporary password, signs in, and lands in an active 14-day trial.
- A duplicate email produces the identical `202` and creates nothing.
- The organization is `PENDING_VERIFICATION` until first successful sign-in.
- Organizations still unverified after 7 days are purged, including the Cognito identity.
- Free-mail addresses are accepted (`11-commercial.md` §4).
- A failure at any step leaves nothing behind — no orphan organization, no orphan Cognito user.

**What the blocker turned out to be.** `OI-2` had been closed by `FZ-046` and this line was
never updated, so the story sat labelled blocked long after it was not. The other two
preconditions were already met as well — `FZ-087` named `/api/signup` in its default
rate-limit paths before the endpoint existed.

**Cognito is the global uniqueness check.** `users.email` is unique only within an
organization; the pool is shared across all of them. So the duplicate case is
`AdminCreateUser` refusing, not a second index that could disagree with it.
`LocalIdentityProvider` now refuses too — a fake that happily issued a second subject for
the same address would have made the one path that must not be wrong the one path no test
could see.

**The compensating delete, and two places the transaction was nearly lost.** Cognito is not
in the transaction, so the order is: create the identity, write the rows, delete the
identity again if the rows fail. Both `SignupService` and `UnverifiedOrganizationPurge`
first wrote that boundary as `@Transactional` on a method called from the same class, which
a Spring proxy never sees — the annotation would have been silently ignored and each save
committed alone, which is precisely the half-created organization the compensation exists
to prevent. Both use a `TransactionTemplate` instead.

**The purge deletes three tables out of eleven, and the foreign keys guard the rest.** An
organization nobody has signed in to can only have `users`, `subscription` and
`audit_event` rows, because everything else needs an authenticated request and
authenticating is what ends `PENDING_VERIFICATION`. If that is ever wrong, the foreign key
refuses the delete and the organization survives. Found by a test rather than by reasoning:
the first version deleted identities before the rows and then swallowed an FK violation,
leaving users who existed and could never sign in.

**No user interface reaches the endpoint.** The form belongs on the public site — `FZ-111`,
still blocked — so `curl` is the only caller today. `gtm/customer/setup-guide.md` still
tells prospects there is no self-serve signup, and that stays **true for them** until a
form exists. `FZ-111` has to change it; this story deliberately did not.


### FZ-083 — Demo Requests
**Status:** DONE

`POST /api/demo-requests`, unauthenticated, plus the internal notification that a request arrived.

~~**Reuses the notification module** rather than sending mail directly: retries, backoff and a dead-letter state already exist and are tested (`FZ-040`–`FZ-044`). The only new thing is a destination owned by FreezeHub rather than by a customer.~~

**That was wrong, and the code said so.** A `Notification` requires a non-null `organizationId` **and** `restrictionId`, and `NotificationSender.send` takes a `ChangeRestriction`. A demo request has none of the three. Reusing the module would have meant changing the port signature and rippling through the email and webhook senders and their tests — to carry one message that goes to us rather than to a customer.

What *is* reused is the part worth reusing: `RetryPolicy`, a pure function of attempt count with no coupling at all. Same backoff, same give-up point, no second schedule to drift. The rest is a small outbox of its own — four columns on the row.

**The lead is the row, not the message.** The request is stored before anything is sent, so a Slack outage or an unconfigured webhook cannot lose it. That is also what makes it acceptable to stop retrying after six attempts rather than for ever.

Acceptance:

- A request is stored with name, work email, company, team size and message.
- A Slack notification reaches FreezeHub's own workspace, and a delivery failure is retried rather than lost.
- Requests carry a status (`NEW`, `CONTACTED`, `CONVERTED`, `DECLINED`) and, once converted, the organization they became.
- Storage is not tenant-scoped — a demo request belongs to no organization yet, which makes it the one table outside the tenant boundary. It is read by operators, never by the tenant API.

### FZ-084 — Stripe Checkout and Subscription Lifecycle
**Status:** DONE

Checkout sessions, portal sessions, and the webhook that is the only thing allowed to change entitlement.

`POST /api/webhooks/stripe` gets **its own security chain**, matching `/api/webhooks/stripe/**`, authenticated by `Stripe-Signature` — HMAC over timestamp and body, the same construction as `D-2` pointed inward. No CORS.

Acceptance:

- An administrator reaches Stripe Checkout and returns to an `ACTIVE` subscription on the plan they bought.
- **Entitlement changes only from a signature-verified webhook** — never from the Checkout redirect, which is a browser navigation anyone can forge. There is a test that forges the redirect and proves it grants nothing.
- An invalid or missing signature is `401`.
- A redelivered event is ignored: every processed `event_id` is stored.
- `invoice.payment_failed` moves the subscription to `PAST_DUE` and notifies the administrator.
~~- The Stripe secret key and endpoint signing secret are held the way destination credentials are (`D-3`), never in configuration in plaintext.~~

**That criterion could not be implemented as written, and the difference is not cosmetic.** `D-3` encrypts values that live in database rows and belong to tenants, using a key supplied to the application. These are FreezeHub's own credentials and they *are* what the application is configured with — encrypting them would need a key, which would have to be configured, which is the same problem again.

They come from the environment, populated from Secrets Manager at deploy time. Nothing is committed, nothing is defaulted, nothing is logged, and absent configuration disables billing rather than starting with a blank key that would fail on the first customer instead of on startup.

**Four defects found by the tests, all in code that looked right:**

- A null `Stripe-Signature` header made the SDK throw `NullPointerException`, surfacing as `500` — telling a caller the server broke when their delivery was simply unsigned.
- `received_at` was populated in `@PrePersist`, which does not fire dependably: the id is assigned rather than generated, so Spring Data treats `save()` as a merge.
- Idempotency by catching the constraint violation does not work inside a transaction — the violation has already marked it rollback-only, so the request fails anyway as an `UnexpectedRollbackException`. It is now a check first, with the primary key as the real guarantee: a genuine race fails one request, and Stripe's redelivery is absorbed by the check.
- `getDataObjectDeserializer().getObject()` returns empty whenever the event's API version differs from the SDK's — **which happens in production every time an account's version and the library drift apart**, not only in tests. Silently doing nothing there means a customer pays and is never activated. The documented escape hatch is used, and only a genuinely unreadable payload is skipped.

### FZ-085 — Billing and Plan UI
**Status:** DONE · **Resolves:** `OI-14`

A Billing section in Settings, Administrator-only: current plan, usage against each limit, trial days remaining, and the buttons that open Stripe.

The trial banner is shown to **every member** in the last 5 days, not only administrators. The person who notices a trial ending is rarely the person who signs.

Acceptance:

- Usage against limits is visible before a limit is hit, not only when a creation is refused.
- A `402` refusal renders as the limit it hit, with the upgrade path, never as a generic error.
- Nothing in the UI decides entitlement — every limit shown comes from the backend.

**Nothing exposed subscription state**, so this story started in the backend: `GET /api/billing/subscription` returns the plan, its limits, the counts, and the trial countdown. Deliberately **not** administrator-only, unlike the rest of `/api/billing` — the banner has to reach everyone, and a member who cannot see why a creation was refused files a bug instead of asking their administrator to upgrade.

The counts are the same ones the limit checks use. A usage bar that disagrees with the code that actually refuses is worse than no usage bar.

**A `402` is composed into a sentence where the error is built**, not at each call site, so every screen that already renders `error.message` gets "Your STARTER plan allows 10 applications, and you are using 10. Upgrade under Settings → Billing" — and no future screen can forget to.

**Two mistakes of mine, both caught by tests:**

- Making `deploymentCheckRetentionDays` a required field turned `PATCH` into a `PUT` and broke three existing callers with a `400`. It is optional and applied only when sent, which is what PATCH means.
- `percentUsed` and `atLimit` were plain accessors on a record. Jackson serialises components, so they never reached the JSON and the usage bar had no percentage in it. They are components now.

**`OI-14` is closed.** Retention is settable and capped by plan, so the priced lever is no longer fiction: Starter is refused at 365 days with a `402` naming the cap, and Enterprise may keep seven years.

### FZ-086 — Operator Provisioning
**Status:** DONE

`scripts/provision-organization.sh`, following `seed-demo.sh`: create an organization on an agreed plan, invite its first Administrator, mark the originating demo request converted.

**Deliberately a script and not an admin console** (`D-23`). An in-product principal that can act across tenants negates the invariant the whole security model rests on.

Acceptance:

- One command provisions a named organization on a named plan and invites its administrator.
- It refuses to run twice for the same company.
- It goes through the API wherever the API allows it, matching `seed-demo.sh`.
- The operational prerequisites — which credentials, which access — are written down, because whoever runs this is not necessarily whoever wrote it.

**Two guards, because there are two ways to duplicate a customer.** A name already in use, and an email that already belongs to somebody. The second matters more: a person exists once, and inviting them into a second organization is a different feature this product does not have.

**One SQL statement, in a transaction.** An organization with no administrator is unreachable and one with no subscription is unbilled, so a part-way failure has to leave nothing rather than either of those.

**It reads back through the API before claiming success**, rather than trusting its own inserts — that is the only check proving the organization resolves from a credential, which every later request depends on.

**`--applications` is refused on anything but ENTERPRISE.** Every other plan's limit is a published number; overriding one here would mean a customer paying for Starter with a limit nobody can look up, and the pricing page quietly becoming untrue. `TRIAL` is not provisionable either — a trial is something an organization starts for itself at signup, not something sales hands out.

**A bug found by running it, not by reading it:** the closing summary used an unquoted heredoc, so a backtick-quoted `local` was executed as a command. The word vanished from the operator's instructions and `local: can only be used in a function` leaked into them.

**What it honestly cannot do.** Outside the `local` profile there is no real `IdentityProvider` (`OI-2`), so no Cognito user is created and no invitation is sent — the customer cannot sign in. The script says so in its closing summary rather than reporting a success that is only half true.

### FZ-088 — Organization Domain Claiming
**Status:** DEFERRED · **Owns:** `OI-12`

Two colleagues signing up separately create two unrelated organizations, and nothing merges them. The honest MVP answer is that support fixes it by hand.

Deferred rather than scheduled: the fix worth building depends on whether the common case is "join the existing organization automatically" (fast, and wrong for a contractor at a client's domain) or "request access from an administrator" (correct, and more machinery). One real occurrence answers that; guessing first does not.

## Milestone 9 — Repository and CI Connectors

`FZ-053` shipped a gate that works and that every customer has to vendor into their own repository by hand. This milestone removes that friction without changing what FreezeHub can reach.

`docs/12-connectors.md` is the specification. `00-product.md`'s deferred list excludes "native GitHub/GitLab/Jenkins/Argo CD integrations" from the MVP; **this milestone promotes the non-native half of that into scope** — packaging around the existing call — and leaves the native half (an installed app with a write credential) deferred as `FZ-096`.

**Runs before Milestone 8**, by decision: adoption friction blocks usage today, whereas billing blocks revenue from customers who are not yet using the product.

```text
FZ-091 ── FZ-092 ── FZ-093 ── FZ-094 ── FZ-095 ── FZ-097
   │
   └── everything below depends on the canonical script and image it produces
```

### FZ-090 — Connector Strategy
**Status:** DONE

Specification only, no code: `docs/12-connectors.md`, plus `D-24`.

Four connectors were chosen and one was deliberately not. The rule that shapes all of them: **one implementation, four wrappers.** A customer running GitLab in one team and Jenkins in another must get the same answer from the same freeze, and four native implementations would drift until one team deployed during a freeze that stopped another.

### FZ-091 — Connector Runtime and Image
**Status:** DONE

The foundation the other four sit on.

`examples/freeze-check.sh` moves to `connectors/freeze-check.sh` — it stopped being an example the moment it became a shipped artifact. `examples/` stays as the hand-rolled walkthrough for CI systems with no connector, and points at the new location.

`connectors/Dockerfile` builds `ghcr.io/freezehubio/freeze-check` — Alpine, `curl`, `jq`, script on `PATH`, non-root, following `backend/Dockerfile`'s shape. **Required regardless of preference**: an Argo CD PreSync hook is a Kubernetes Job and cannot run without an image.

Acceptance:

- The script's behaviour is unchanged by the move; the existing exit codes and fail-closed rules hold.
- `examples/README.md` and `examples/gitlab-ci.yml` reference the new path and still work.
- The image runs the check with no arguments beyond environment variables, as non-root.
- A test proves the image exits `1` on a blocked decision and `2` on an unreachable server — the two outcomes a broken image would most plausibly turn into `0`.
- The image is built in CI. Publishing it is `FZ-097`.

### FZ-092 — GitHub Actions Connector
**Status:** DONE

A composite action at `connectors/action.yml`, usable as `uses: freezehubio/freeze-check@v1`.

**Moved to the `connectors/` root by `FZ-097`**, together with the GitLab template, so that this directory *is* the published repository's layout. Marketplace requires `action.yml` at a repository root; publishing now copies rather than rewrites the script path, so what a customer runs is what the tests ran.

Composite rather than a Docker action: it runs on the runner's own `curl`/`jq`, so it costs no image pull on the platform where most usage will be, and it works on self-hosted runners with no Docker.

Acceptance:

- Inputs `url`, `api-key`, `application`, `environment`, `on-error`, `timeout`, mapping onto the script's variables and adding nothing.
- Blocked fails the step; not-evaluated fails the step; the two are distinguishable in the log.
- **No input downgrades a freeze to a warning** (`12-connectors.md` §4).
- `actor`, `reference` and `source` are populated from the GitHub context, as the script already does.
- Exercised by a workflow in `.github/workflows/` that runs the action against a stubbed endpoint — the action must be proven to fail, not only to pass.

### FZ-093 — GitLab CI Connector
**Status:** DONE

A CI/CD component at `connectors/templates/freeze-check.yml` (moved there by `FZ-097`), running the `FZ-091` image so no pipeline installs `curl` and `jq` on every run.

**Kept as a tested reference implementation, not published** (`D-26`): `component:` resolves against a repository the customer can read. The GitLab guideline in `connectors/README.md` is what customers actually use, and it is the same job with the image inlined.

Acceptance:

- A customer adds the check with an `include:` and one variable block.
- Defaults to running only on the deploying branch; asking on every feature branch would fail merge-request pipelines during a freeze, which is not the point.
- The documented wiring uses `needs:` so a failed check prevents the deploy job from being created, and says explicitly why `allow_failure: true` must not be added.

### FZ-094 — Jenkins Connector
**Status:** SUPERSEDED by `FZ-097`

A Jenkins shared library needs its own repository for Jenkins to load it, the same constraint that stopped the Action and the component being installable (`D-26`). The Jenkins guideline in `connectors/README.md` delivers the outcome — `withCredentials` around a `docker run` — without a second repository to maintain. Reopen if a customer needs a library rather than a snippet.

The original plan below is kept for the reasoning it carries, not as work to do.


A shared library at `connectors/jenkins/`: `vars/freezeCheck.groovy` plus the script as a library resource.

**This is the one connector that carries a copy of the script**, because Jenkins loads library resources from within the library itself. `12-connectors.md` §2 requires the copy to be byte-identical to the canonical file, and a check enforces it — a drifted copy is exactly the failure the one-implementation rule exists to prevent.

Acceptance:

- `freezeCheck(application: 'payments-api', environment: 'production')` gates a stage.
- A blocked check fails the build; `catchError` is not used anywhere in the step.
- A test fails if the resource copy and `connectors/freeze-check.sh` differ by one byte.
- The API key is read from Jenkins credentials, never from a pipeline literal, and does not appear in the build log.

### FZ-095 — Argo CD Connector
**Status:** SUPERSEDED by `FZ-097`

An Argo CD PreSync hook is a manifest a customer copies, not something installed, so it is a guideline rather than an artifact. `connectors/README.md` carries it, including the two things that surprise people: `backoffLimit: 0`, because a freeze is not a transient error, and the fact that Argo retries the sync on its own schedule. Reopen if a maintained, tested manifest is wanted rather than a documented one.

The original plan below is kept for the reasoning it carries, not as work to do.


A PreSync hook manifest at `connectors/argocd/`, running the `FZ-091` image.

**Different in kind from the other three.** They gate a step in a pipeline; this gates a *sync*, and by the time Argo syncs, the change is already committed and merged. With auto-sync on, the freeze is the only thing between a merged commit and production — the strongest form of the product, and the most surprising.

Acceptance:

- Application and environment are read from annotations on the Argo `Application`; a missing annotation fails the hook rather than defaulting to something.
- A blocked check fails the sync and leaves the Application `OutOfSync`.
- The API key comes from a Kubernetes `Secret`, never from the manifest.
- **The documentation states that Argo will retry the failed sync on its own schedule**, so repeated failures during a freeze are expected and are not an incident. Nobody should learn this from an alert at 3am.

### FZ-097 — Integration Guidelines
**Status:** DONE · **Resolves the usable half of** `OI-13`

Taken out of order, ahead of `FZ-094` and `FZ-095`, because two connectors existed and **neither could be installed by anyone**.

The approach changed during this story, and the change came from asking what customers actually run. Most CI systems run containers, so **the image is the connector** and one published artifact covers nearly the whole market (`D-26`). What was nearly built instead — a second public repository, an assemble step, a cross-repo token and a force-pushed release — was machinery to maintain before there is a customer, bought with about two lines of YAML per pipeline.

`connectors/README.md` is now the integration guide: GitHub Actions, GitLab CI, Jenkins, Argo CD, and a generic form for everything else. Each is the same program with the same environment variables, which is the point.

**`connectors/` was restructured** so `action.yml` and `freeze-check.sh` are siblings — done for a publishing layout that is no longer needed, kept because the action's script path is now identical wherever it runs.

Acceptance:

- A guideline per CI system, each one runnable, each naming what it deliberately does not do.
- Argo CD's guideline states that **Argo retries a failed sync on its own schedule**, so repeated failures during a freeze are expected and are not an incident. Nobody should learn that from an alert at 3am.
- The Jenkins guideline uses `withCredentials`, never a pipeline literal, so the key stays out of the build log.
- The reference implementations stay tested, and the documentation **does not offer them as installable** — they are not, while the repository is private.

### FZ-099 — Publish the Connector Image
**Status:** DONE · **Resolves** `OI-13`

`.github/workflows/publish-connectors.yml` builds `linux/amd64` and `linux/arm64`, runs the connector tests first, tags the exact version and moves `v1`, and has a dry-run mode. It refuses a version that is not `vN.N.N`, and there is no `latest` tag — a moving `latest` in a deploy gate is how a pipeline changes behaviour on a day nobody touched it.

**Prerequisite 1 is met.** The `freezehubio` organization exists and this repository was moved into it. The image is named for the organization because a personal username in a customer's deploy pipeline undercuts a product sold to companies, and renaming later breaks every pipeline using it.

**Prerequisite 2 was removed rather than satisfied, by the same move.** It read: *a secret `CONNECTOR_PUBLISH_TOKEN` with `packages: write`, because `GITHUB_TOKEN` cannot write to another owner's package namespace.* That was true while the repository sat under a personal account. `freezehubio` is no longer another owner, so the workflow declares `packages: write` in its `permissions:` block and authenticates with the built-in `GITHUB_TOKEN`. **No secret is configured for this workflow, and none is needed.**

**That is a better credential, not merely a cheaper one.** A personal access token is long-lived, has to be stored and rotated, outlives whoever created it, and grants what its scopes say wherever it is pasted. `GITHUB_TOKEN` is minted for one run, bounded by the `permissions:` block in the file, and expires with the job.

Then one thing that is easy to miss: **GHCR package visibility is set on the package, not inherited from the repository**, and a newly published package is private. It must be set to public after the first push or customers get `denied` on pull.

**The actions were bumped off Node 20 first.** The dry run warned that `checkout@v4`, `build-push-action@v6` and both `docker/setup-*@v3` actions target a deprecated runtime and were being forced onto Node 24. They are now `checkout@v7`, `setup-qemu@v4`, `setup-buildx@v4`, `login-action@v4` and `build-push-action@v7` — versions read from each action's latest release rather than guessed, and verified by a second dry run before anything was published. Doing it in this order means the image was never published by a workflow that needed changing straight afterwards.

Only this workflow was bumped. `deploy.yml` and `verify.yml` carry the same deprecated actions and are not this story's to change — `verify.yml` in particular runs the suite, so bumping `setup-java` and `setup-node` there deserves its own run to prove it (`OI-33`).

**What remains is one `workflow_dispatch`** with a version. It stays a human action because it publishes to a real registry under a name customers will pin — the same reasoning that makes `deploy.yml` manual. Until it runs, every guideline in `connectors/README.md` names an image that does not exist, and the README says so.

**Published and verified against the real artifact, not a local build.** `v1.0.0` pushed and
`v1` moved to match — the two resolve to the same digest. The package was then made public,
which GHCR does not do on its own: a newly published package is private, and a customer
pulling one gets `unauthorized` indistinguishably from one that does not exist.

Four things were checked by pulling it anonymously:

- **Both architectures survived into what was pushed.** `linux/amd64` and `linux/arm64` are
  in the manifest index. The dry runs built both; nobody had confirmed the push kept them.
- **It refuses cleanly with no configuration** — `FREEZEHUB_URL is not set`, rather than a
  stack trace or a silent pass.
- **It fails closed.** With FreezeHub unreachable it retries, reports
  `refusing to deploy without a policy decision`, and **exits 2**. That exit code is the
  entire gate: the message without it would be a warning nobody's pipeline acts on.
- **`FREEZEHUB_ON_ERROR=allow` exits 0**, so the documented escape hatch works and is the
  only way past a failure.

That is `D-24`'s central property demonstrated on the published artifact rather than
inferred from the source.

**The `GITHUB_TOKEN` change from the earlier half of this story is also now proven.** The
dry runs skipped `Log in to GHCR` because it is gated on `inputs.publish`, so the credential
swap was unexercised until the real publish authenticated with it.

### FZ-096 — GitHub App and Required Checks
**Status:** DEFERRED · **Decision required before scheduling**

A GitHub App that posts a commit status, so branch protection can make FreezeHub a **required check** — the one thing that turns a `HARD_FREEZE` from voluntary into enforced, and the strongest thing the product could offer.

Deferred deliberately, not for effort. It **inverts the trust direction**: FreezeHub would hold an installation token that can write to the customer's repository metadata, ingest their webhooks, and appear in their audit log. Every sentence in `10-demo.md` about having no credentials into their repositories and no blast radius stops being true, and the answer to the first question a security review asks changes.

That trade may well be worth making — it is the natural Scale/Enterprise differentiator, and `11-commercial.md` has nowhere else to put an upsell of that weight. It is not something to arrive at as a side effect of "we added GitHub support", which is why it is a separate decision with its own security review rather than a task inside `FZ-092`.

## Milestone 10 — Defects Found After Milestone 9

### FZ-098 — Timestamp Precision in the Audit Trail
**Status:** DONE · **Found by:** the first CI run, `FZ-092`

`AuditTrailTest.recordsNothingWhenAnUpdateChangedNothing` failed on Linux and passed on macOS. The cause was not the test.

PostgreSQL `TIMESTAMPTZ` stores **microseconds**. `Instant` carries **nanoseconds**, and on Linux `Instant.now()` actually populates them. A client sending `2026-09-06T07:03:40.000000123Z` therefore has its value silently truncated on the way to the database — and on the next update, the incoming nanosecond value is compared against the stored microsecond one, they differ, and an audit event is written saying the freeze window moved:

```json
{"startsAt":{"from":"2026-09-06T07:03:40Z","to":"2026-09-06T07:03:40.000000123Z"}}
```

Three things wrong with that entry, in increasing order of seriousness:

1. Nothing changed. The client sent back exactly what it sent before.
2. It appears on **every** update, so genuine changes arrive buried in noise.
3. **The `to` value was never persisted.** The database truncates it straight back to `from`. The audit trail — whose entire purpose is answering "who changed this freeze, and to what" — records an after-state that never existed.

The fix truncates user-supplied instants to microseconds as they enter the aggregate, so the entity's state is always what the database will hold and a comparison between them is meaningful (`D-25`).

Acceptance:

- A no-op update with nanosecond-precision timestamps records nothing.
- A real change to the window is still recorded, with both values at storable precision.
- The API returns what was stored, so a client that sends nanoseconds is told plainly what it got.
- The macOS/Linux split is gone: the regression test supplies nanoseconds explicitly rather than depending on the host clock's precision.

## Milestone 11 — Visual Identity

### FZ-100 — Industry Design System
**Status:** DONE

A design hand-off in `design_handoff_industry_theme/` puts the **Industry** system behind the frontend: steel-blue on a light technical ground, Barlow Condensed over Barlow, square corners, hairline frames with "+" registration marks, one solid accent object per screen.

**The architecture is the part worth keeping.** The repo already centralises tokens as `--fh-*`, so Industry's token block is pasted above them and the aliases are redefined on top — every existing CSS Module inherits the new look with no edit at all. Component classes (`.btn`, `.card`, `.tag`, `.blueprint`) arrive as one global sheet; the modules keep only layout. **Look comes from the global layer, layout from the module**, and that split is what the next screen should follow.

**Three defects in the change set, found before applying it:**

1. **`main.tsx` would not compile.** It changed `import App from './app/App.tsx'` to a named import, but `App` is a default export. Only the one `industry.css` import its README describes was taken.
2. **Losing red is bigger than its README weighed.** It presents the mono decision as being about badges. `--fh-danger` carries **22 declarations across nine stylesheets** — every validation error and every `.actionError` in the product, most of them in routes the change set does not touch. Decided: **red stays** for errors and blocking states, as a documented functional exception. It is state, not decoration, and in a tool whose job is refusing deployments a refusal rendered in ordinary chrome is a legibility regression.
3. `.cardBlocking` shipped in the CSS but was never wired. It now keys on `HARD_FREEZE` **and** `ACTIVE` — a scheduled freeze has not stopped anything yet, and framing it the same way cries wolf.

**A claim of mine that was wrong, corrected:** I reported that `--fh-surface: transparent` would break two unported stylesheets. It does not. My grep used `fh-surface\b`, which also matches `--fh-surface-strong` — a real neutral tint. Exactly one rule uses `var(--fh-surface)`, a button that already has a border, where transparent is correct Industry styling. The hand-off's claim was right.

**Dark mode is dropped**, as the hand-off intends: Industry ships no dark ramp, so `color-scheme` is pinned to light. Reinstating it means choosing dark values for every role — design work, not a port (`OI-17`).

Acceptance:

- Tests pass **untouched** — 117 of them, asserting text and roles rather than class names. That was the hand-off's own proposed signal that this is style-only, and it holds.
- Every `styles.X` reference in the six rewritten modules resolves, and no class ships unused.
- Errors remain red everywhere they were red.

**Most of the app is unported.** Restriction list, detail, create, checks, settings, catalog and audit still carry the old layout on the new tokens — they inherit the palette and type but not the Industry layout. Two of those carry product changes rather than restyles and must not be smuggled in as design work: the create form replaces the native `<select multiple>` scope pickers with chip toggles (`05-frontend.md` accepted the native control knowingly), and the checks screen wants a 14-day bar chart needing a per-day aggregate **the API does not expose**.

### FZ-101 — Broadsheet Design System
**Status:** DONE · **Replaces:** `FZ-100`

The look and feel changed direction. Broadsheet is newsprint set for the web — near-black Source Serif 4 on paper, cyan used small like spot colour — and it **inverts** Industry rather than varying it: *"do not structure the page with rules, borders or boxes"* and *"do not introduce a sans-serif for UI chrome; the serif is the chrome"* are both direct contradictions of what `FZ-100` shipped.

`Blueprint.tsx` is deleted with it. Corner registration marks are Industry's signature, and Broadsheet forbids the frames they decorate.

**Unlike `FZ-100` there was no change set** — Broadsheet ships the system and the mockups, not ready-to-commit modules. This is a port, not a copy, so more of it is judgement.

**Fully mono, decided.** Broadsheet carries cyan and magenta but no red, and this port distinguishes a refusal by weight rather than hue. That puts real load on the rest: a hard freeze is a filled tag and an advisory an outlined one; a refused deployment check is the heaviest ink on the sheet reversed out to paper; error text is full-strength ink at heading weight. Wherever that distinction gets flattened, a refusal stops reading as one — the failure to watch for in a product whose job is refusing deployments. Reversing it is one line, noted in `index.css`.

**Three things found that no theme could have fixed:**

1. **Audit and Deployment Checks were never on the token system at all.** Both carried a Tailwind-ish palette inline — `#4b5563`, `#1d4ed8`, `#e5e7eb`. That, not "old layout on new tokens", is why neither theme reached them. Converted by role rather than by nearest colour, so they follow the system from here.
2. **`--color-warning`, `--color-warning-surface` and `--color-warning-text` were never defined.** The one-time API key reveal had always rendered its amber fallbacks, unreachable by any theme. It now carries its loudness in ink.
3. **A raw `#9ca3af`** in a button border — exactly what the system's own adherence config forbids.

Every colour in every module now comes from a token; the only hex left in the frontend is the token definitions themselves.

**Radius was normalised** onto `--radius-md`. The modules carried five different values predating either theme.

Acceptance:

- 117 frontend tests pass untouched; every `styles.X` reference resolves; `tsc`, `oxlint` and `vite build` clean.

**Two things in the mockups deliberately NOT built**, because they are product changes wearing design clothes and belong to their own stories:

- **Create restriction** — the mock replaces the native `<select multiple>` scope pickers with chip toggles and adds a live "this will match…" summary. `05-frontend.md` accepted the native control knowingly.
- **Deployment checks** — the mock adds a 14-day allowed/refused bar chart. That needs a per-day aggregate **the API does not expose**.

**Also not built: the landing page.** The mockups include one, but there is no public marketing route — Milestone 8 deliberately deferred the public site (`FZ-080`), so there is nothing for it to live in yet.

**Spacing is still literal.** 120 padding/gap/margin declarations use rem values rather than `--space-*`, so Broadsheet's 1.25× density reaches the app only through the type scale. Converting them is a large mechanical change with real breakage risk and is left for its own story (`OI-18`).

## Developer Tooling

### FZ-102 — Test Accounts Helper
**Status:** DONE

`scripts/test-users.sh` — who can sign in locally, what each account is for, and what cannot be tested with the accounts that exist.

**Reads the database, not a document.** A list of test accounts written down is wrong the first time anybody seeds, provisions or resets, and this question had been asked three times.

**It names the gap, which is the useful half.** With no `MEMBER` account the product cannot be checked at all from a non-administrator's side — the trial banner shown to every member (`FZ-085`), the member-visible billing read, and how a `402` looks to someone who cannot fix it. `--add-member` closes that, and the difference is real: a member gets `200` on `GET /api/billing/subscription` and `403` on `POST /api/api-keys`, verified live.

It also reports whether more than one organization exists, since cross-tenant leakage cannot be tested against a single tenant.

Acceptance:

- Lists every account with its organization, plan, status and trial end.
- States what is untestable with the current set rather than only what is present.
- Needs Postgres but not the backend, so it answers even when the app will not start.

### FZ-147 — Next Story
**Status:** DONE

`.claude/skills/next-story/SKILL.md` — what has actually landed on `master`, and what is available to work on because of it.

**The check is "did it land", not "did it merge".** §9 warns that a pull request merged while a later commit was still being pushed leaves that commit behind, and that the merge commit looks the same either way. The skill compares each merged PR's head SHA against `origin/master` with `git merge-base --is-ancestor`, and lists remote branches never merged at all. Both are invisible unless specifically looked for.

**It recomputes blockers rather than reading them.** A `**Blocked by:**` line is written once and rarely revisited. The skill looks up the current status of every story a line names, and reports the disagreement when the line claims a blocker that is DONE — which it did on its first run: `FZ-123` had been available since `FZ-122` finished and its line still said otherwise.

**It reports and stops.** No branch, no commit, no edit to the backlog — not even to fix a stale line it found. §9 keeps those with the operator.

Acceptance:

- Names any merged PR whose head commit is not an ancestor of `origin/master`, and any remote branch not merged into it.
- Recomputes each open story's blockers from current statuses and reports lines that disagree.
- Separates stories blocked by a story from those blocked on a human action, and never recommends a `DEFERRED` item.
- Prints the branch command without running it.
- Answers "nothing is available" when that is true, rather than inventing a candidate.

## Milestone 12 — Build the Mockups

`FreezeHub UI mockups/FreezeHub Screens.dc.html` is the design of record. `FZ-101` swapped the tokens; this milestone builds what the screens actually specify, including the parts that need the backend.

**The dashboard is a composition, decided by the operator:** `1a` as the base, plus `1b`'s metrics row, plus `1c`'s "Then what" timeline. `1b`'s left rail and `1c`'s answer-first hero are not taken.

**Everything else is taken as drawn:** `1d` detail, `1e` create, `1f` checks, `1g` notifications, `1h` settings, `1i` sign-in, `1j` landing, `1k` mobile.

### FZ-103 — The Colour Rule, Density and Furniture
**Status:** DONE · **Partly addresses:** `OI-18`

The brief states one rule and `FZ-101` broke it:

> *"Magenta means a restriction is in force — a block, a refusal, a failed delivery. Cyan means you can act on it: links, buttons, the thing you press. Nothing else is coloured, so a colour on the page always carries meaning."*

`FZ-101` shipped fully mono on an earlier decision, so refusals render in the same ink as ordinary chrome. Reversed here.

Two more things `FZ-101` deferred that the brief specifies: **density** — body copy 15px, the airy 1.25× spacing scale, nothing under 12px anywhere — and **furniture** — a thick-thin rule pair marking the one status line per screen. "No boxes" was read too broadly; the system does print rules, as front-page furniture.

**The type scale was not reaching any screen.** Two causes, both found by measuring the running app against the mockup rather than by reading:

- `:root { font-size: 15px }` — set by `FZ-101` to express "body copy is 15px". The modules are authored in rem, so it silently rescaled **every one of them by 6%**. The root is not the place to say that; the body is.
- Every page set its own `.title` size — `1.5rem`, `27px`, `26px` — so `h1` rendered at 27px against the mockup's 34px, and the system's scale reached nothing. All nine sit on real `<h1>` elements, so the overrides are removed and the scale applies.

Sizes are taken from the mockup, not chosen: h1 34/36.7, h2 21/25.2, body 15/23.25.

**The brief contradicts its own mockup, and the mockup wins.** It states *"nothing is smaller than 12px anywhere"*; screen `1a` renders twelve elements at 11px — the small letterspaced labels. The mockup is the design of record, so 11px labels stay.

Frontend only.

### FZ-104 — Scope Names in the API
**Status:** NOT NEEDED · **Investigated, not built**

Planned on an assumption the code disproves. `ScopeResponse` does return ids only — but the detail page already resolves them, against the team, application and environment catalogs it loads anyway, and renders `production` today. The mockup's scope sentence is already in the page verbatim, from `FZ-022`.

Verified in a browser against a real restriction rather than by reading: the Environments row reads `production`, and empty dimensions read `Any`.

Resolving an id to a name is a lookup, not a domain rule, so doing it client-side does not put the frontend in charge of anything (`CLAUDE.md` §5). The create form needs the same catalogs for its pickers, so it has names too.

The only cost is three catalog requests per detail view, which is not worth an API change at this size. Reopen if a screen ever needs a name without already holding the catalog.

**What `1d` actually needs is layout, not data** — the two-column definition grid, hairline rules instead of boxes, letterspaced labels, scope values as tags, and GMT times. That is `FZ-107`.

### FZ-105 — Deployment Check Aggregates
**Status:** DONE

Four figures the mockups show and no endpoint produces:

- **checks refused per restriction** — `1a`'s completed table
- **today's checks by decision** — `1b`'s "86 · 9 refused, 77 allowed"
- **applications seen in checks, against the catalog total** — `1b`'s "11 of 14 applications"
- **a 14-day allowed/refused series** — `1f`'s bar chart

All read from `deployment_check`, which already records every evaluation (`FZ-070`).

### FZ-106 — Dashboard
**Status:** DONE

`1a` with the status line ("Deploys are blocked in production and staging" — a derived sentence naming the blocked environments), the active card with its magenta spine, upcoming cards, and the completed **table** with `CHECKS REFUSED`.

Plus `1b`'s four metrics, and `1c`'s **"Then what"** — a forward list of transitions as date plus plain sentence, ending "Clear from here." It reads from the restrictions already loaded; the value is that it says what happens rather than listing what exists.

### FZ-107 — Restriction Detail
**Status:** DONE · **Except:** "What it has done", split out as `FZ-112`

`1d`, taken as drawn: the two-column definition grid, scope resolved to names, and the scope explanation — *"A deployment is affected when it matches **every** dimension below. 'Any' means the dimension places no constraint."* That sentence documents the AND-across-dimensions rule (`FZ-020`) where somebody will actually read it.

### FZ-108 — Create Restriction
**Status:** DONE

`1e`. Two things beyond a restyle:

- **Chip toggles replace the native `<select multiple>`.** `05-frontend.md` accepted the native control knowingly, so this reverses a recorded decision rather than ignoring one. Common options show as chips; **"n more…" opens a dialog listing every value**, per the operator's direction.
- **An overlap warning.** Overlapping restrictions are deliberately allowed (`FZ-020`), so this is advisory and must not block submission — it says another restriction already covers this window and scope.

### FZ-109 — Checks Console
**Status:** DONE

`1f`: the fortnight of decisions charted above the list, so a refusal spike is visible before a single row is read. The four figures beneath it, the URL-carried filter, and the list as a table.

**Split from what this story used to be.** It covered `1f`, `1g` and `1h` together. `1g` needs a notifications endpoint that does not exist, and `1h` is a page that already exists and only needs re-setting — three different kinds of work in one branch. They are now `FZ-115` and `FZ-116`.

One figure needs backend work: **refused as unregistered** is a count of `blocked_reason = UNREGISTERED` over the window, and nothing aggregates it. Small, and strictly required by the screen.

### FZ-110 — Sign-in and Mobile
**Status:** DONE · **Except:** `1k`'s "Can I deploy?" control, split out as `FZ-120`

`1i` and `1k`. `1k` is the dashboard at 390px, so it is responsive work on `FZ-106` rather than a separate screen.

### FZ-111 — Landing Page
**Status:** DONE · **The signup form it owed** was built by `FZ-185`

`1j`, at `/`, public.

**Resolved: part of this application, not a separate site.** The question `FZ-111` left open,
answered the cheap way. CloudFront already serves this bundle at `domain_name` and `Deploy
frontend` already publishes it, so a public route costs no bucket, no distribution, no
certificate and no DNS — and a separate estate would mean renaming the hostname Cognito
callbacks already point at. The trade accepted is that marketing copy ships in the app bundle
and the page is client-rendered; both are weak at zero customers, and moving later is a
hostname change rather than a rewrite.

**The blocker was stale.** *"No public route exists"* stopped being true when the box went
live. The route was one router entry away.

**The authenticated tree became a pathless layout route**, so every product path stays exactly
where it was — `/dashboard` is still `/dashboard`, and no in-app link, redirect or Cognito
callback moves. Only the index redirect was removed, because `/` now has a page of its own.

**Two things `1j` draws are not built, and the deck is wrong rather than the page.**

- **"Start free trial"** — there is no signup screen. `FZ-082` landed `POST /api/signup` while
  this was in review, and an endpoint does not make the claim true: a visitor still has
  nothing to fill in. The button is **"Book a demo"**, the motion that exists, and the trigger
  for changing it is a signup *page*, not the API.
- **`uses: freezehub/freeze-check@v1`** — not a real reference. The sample is the shipped
  `docker run ghcr.io/freezehubio/freeze-check:v1` from `connectors/README.md`. A landing
  page's code sample is the first thing a visitor copies and the first thing that fails for
  them.

**Not built: the deck's 1440×900 product screenshot slot.** Filling it needs a real dashboard
showing a real active freeze, and the deployed organization is empty. A placeholder rectangle
on a landing page is worse than the section not existing.

**The signup form is deliberately not in this branch.** The status line above gained *"owes
the signup form"* from `FZ-082` while this was being built, and that debt is real — the API
has no caller. It is not folded in here because a marketing page and an account-creation form
are different pages with different acceptance, and one branch containing both is the
unreviewable diff §9 exists to prevent. **It wants its own story**, and until it has one the
product has a signup endpoint nobody can reach.

Acceptance:

- `/` renders without a token; `/dashboard` and every other product path still require one.
- Nav, pricing and closing link only to sections that exist on the page.
- No claim that is not true today — asserted by test rather than by reading.
- Look comes from `broadsheet.css` and the tokens; the module is layout only.

### FZ-112 — What a Restriction Has Done
**Status:** DONE

The one block of `1d` that `FZ-107` could not build, because it is the only part that is not layout. Three figures under **"What it has done"**, for one restriction:

| Figure | Where the data is |
|---|---|
| **Checks refused** — "since it activated" | `FZ-105` already counts this, per restriction, in `GET /api/deployment-checks/summary`. Org-wide though, so a detail page pulls the whole list to read one row |
| **Pipelines affected** | **Does not exist.** Distinct applications among the checks this restriction refused. `deployment_check` holds it; nothing counts it |
| **Notified** — "Slack, email, webhook" | **Not exposed.** `notification` rows carry `restrictionId` and a channel, and there is no notifications endpoint at all — `FZ-109` needs one for `1g` too |

Worth doing as one story because all three are the same question — *what did this restriction actually do?* — and answering it needs one endpoint scoped to a restriction rather than three org-wide aggregates read and filtered client-side.

**Decide when starting it:** whether this is `GET /api/restrictions/{id}/impact`, or extra fields on the restriction detail response. The endpoint is probably right — the detail response is a domain object, and these are counts about it — but that is a real choice and not a foregone one.

The three figures are also the reason a restriction is worth reviewing after the fact, so this is the story that makes a completed freeze more than a row in a list.

### FZ-113 — One Forward List, and Metrics That Are Not Restatements
**Status:** DONE · **Refines:** `FZ-106`

Operator feedback on the shipped dashboard: **Upcoming and "Then what" say the same thing twice.** Every scheduled restriction appears three times on one page — once as an Upcoming card, then again as its start and its completion in "Then what".

- **Drop Upcoming; keep "Then what"**, and put it beside `Active now` rather than under it. Left is what is true, right is what happens — the present and the future, read together.
- Because the Upcoming cards were the only route from the dashboard to a scheduled restriction, **the restriction name in each transition becomes a link**. `1c` draws the line as plain text; without the cards, plain text would be a dead end.

**Metrics.** Two of the four were restatements once the layout changed: `Active` repeated the status rail directly above it, and `Scheduled` repeated the forward list. Replaced with three, and deliberately not a fourth:

| Tile | From | Why |
|---|---|---|
| **Checks · 14 days** | `daily` | Adoption. A one-day window read `0` on a quiet morning and made the gate look dead |
| **Refused** | `daily` | Effect — what the gate actually stopped, as a share of what it was asked |
| **Pipelines integrated** | `applications` | Coverage. The gap is the risk: services whose pipelines sail through a freeze |

No fourth tile invented to fill the row. "Clear runway" was considered and rejected: "Then what" already answers it in words, and saying it twice in two forms is what this story exists to remove.

### FZ-114 — The Retention Purge Never Ran
**Status:** DONE · **Fixes:** a defect in `FZ-070`

`DeploymentCheckRetention.purgeScheduled()` calls `purge()` on `this`. `@Transactional` is proxy-based, so a self-invocation never reaches the proxy, no transaction starts, and the `@Modifying` delete throws `TransactionRequiredException` — every day, five minutes after boot, for as long as the application has existed.

**The test passes because it exercises the wrong path.** `DeploymentCheckTest` calls `retention.purge(...)` on the *injected* bean, which does go through the proxy. The only path that runs in production is the only one nothing covered.

Consequences: `deployment_check` grows without bound, and the per-organization retention window — priced per plan in `11-commercial.md` and settable since `FZ-085` — does nothing at all.

The fix is small. The test that goes with it is the point: it has to drive the **scheduled entry point**, not the method underneath it, or the same bug returns unnoticed.

### FZ-115 — Notifications
**Status:** DONE · **Except:** manual retry, split out as `FZ-119`

`1g` — lifecycle events with per-channel delivery, because *"was Slack actually told?"* is the question that gets asked after a freeze goes wrong.

The data exists: `notification` rows carry `restrictionId`, an integration, a status and an attempt count, and `FZ-044` gave them a terminal state. **Nothing exposes them.** There is no notifications controller at all, so this story is an endpoint before it is a screen.

Decide when starting it: whether delivery is listed per notification or per restriction, and whether a failed delivery can be retried by hand from the screen. The second is a new capability, not a view.

### FZ-116 — Settings
**Status:** DONE · **Except:** two columns `1h` draws that have no data — see `FZ-117`

`1h` — organization, integrations and API keys on one page, with the section rail the settings page already implies.

Unlike the other screens in this milestone this is a re-set of a page that already works: `SettingsPage` has its sections, and what changes is how they are laid out and led into.

### FZ-117 — What a Key and a Channel Are Actually Doing
**Status:** DONE

Two columns `1h` draws that `FZ-116` could not fill, because nothing records what they show.

**"Last used" on an API key.** `api_key` has `created_at` and `revoked_at` and nothing else. Deciding which of four keys is safe to revoke is exactly the question this column answers, and without it the answer is a guess.

It is not free: the policy check is a read path, and stamping a key on every evaluation makes it a write path — once per deployment, per pipeline. **Decide before building:** a coarse `last_used_on` date updated at most once a day per key is probably enough to answer "is anything still using this?", and costs one write per key per day instead of one per check.

**"Failing" on an integration.** `1h` draws a channel whose deliveries are failing in magenta. `integration` carries only `enabled`; whether its last deliveries succeeded lives in `notification` rows. Overlaps with `FZ-115`, which needs the same aggregate for `1g` — do them together or make `FZ-115` first.

Until both exist the settings page shows what it can prove: a key's prefix and whether it is revoked, and a channel's enabled state.

### FZ-118 — Settings Sections, One at a Time
**Status:** DONE · **Refines:** `FZ-116`

Operator feedback on the shipped rail: **the rail should switch sections, not scroll to them.** Clicking an option shows that section and only that section; Settings opens on the first.

`1h` draws a rail beside a page of stacked sections, which is what `FZ-116` built — and reading it as a table of contents rather than as a switch made the page long enough that the rail existed to compensate for its own length.

Consequences worth taking:

- The `IntersectionObserver` goes. Nothing scrolls past anything, so there is nothing to follow — and a whole file of enhancement-that-degrades disappears with it.
- **The selected section belongs in the URL**, as the checks console's filter already does (`FZ-071`). Settings → API keys should be a link somebody can send, and browser back should step between sections rather than leaving the page.
- Only the visible section's data is fetched, because only it is mounted. Four requests on open become one.

### FZ-119 — Retry a Failed Delivery
**Status:** DONE

`1g` draws a **Retry** button beside the failure banner. `FZ-115` built the screen without it, because re-sending is a capability rather than a view and the two should not be reviewed as one change.

What it needs: an endpoint that puts an exhausted notification back to `PENDING` with its attempt count reset, so the existing dispatcher picks it up on the next pass. Nothing new has to send anything — the outbox already knows how.

**Decide when starting it:** whether retrying is per delivery or per event. Per event is what the banner implies and is kinder to use; per delivery is what the data models, and re-sending to channels that already accepted would announce a freeze twice to everyone who was told the first time. That argues for per event, retrying only its failed deliveries.

### FZ-120 — Check a Deployment From the Product
**Status:** DONE

`1k` puts a **"Can I deploy?"** field and an Evaluate button on the phone, and `1c` draws the same thing on the desktop. Neither is buildable today, for a reason worth stating plainly: **policy evaluation is a machine endpoint.** `POST /api/policy/evaluate` sits behind the API-key filter chain, and a signed-in person has a JWT, not a key. There is no way for the product to ask its own question.

What it needs is a human-authenticated evaluation that answers from the same code as the machine one — not a second implementation of the matching rules, which would be a second source of truth for the one thing this product exists to decide.

**Decided:** a person's check is **not** recorded (`D-29`). The console says *"every time a pipeline asked"*, and filling it with people trying the form would make that sentence false and inflate every refusal figure drawn from those rows — the dashboard's *Refused*, and a restriction's *checks refused* and *pipelines affected*.

Built as `GET /api/deployment-checks/preview?application=&environment=`, beside the console that shows the history rather than under `/api/policy`, which accepts an API key and nothing else. A `GET` because nothing is enforced on the answer and a GET cannot record.

**The control lives on the checks console, not the dashboard** — operator's call, made after seeing it on both. `1c` drew it under the dashboard's status block, but the checks screen is the one about the deployment gate, so the question and its history sit together. It also puts "nothing is recorded" beside the list where somebody would otherwise expect their own check to appear.

`PolicyService.decide` was extracted from `evaluate` and now carries the matching rules and the sentence describing them; both callers derive their answer from it, so the product cannot disagree with the gate. `DeploymentCheckPreviewTest` asks both paths the same three questions and compares the answers field by field, and asserts that a preview leaves no check row and no audit entry.

Worth having. It answers "is the freeze on for me?" without reading a restriction and working out whether its scope covers you — which is exactly the sum this product exists to do for people.


## Milestone 13 — Running It

The first milestone about the deployment rather than the product. `FZ-063` designed a
production-shaped AWS environment and it has never been applied; `OI-15` records that
applying it as written costs about $96 a month with no customers, and that two questions
block closing it. This milestone answers both, and fixes the defect found while asking.

**Order.** `FZ-121` first and independently — it is a defect in shipped code, it gates the
second instance wherever that instance runs, and nothing about it depends on a platform
decision.

```text
FZ-121 ──┐
         ├── FZ-123
FZ-122 ──┘
```

### FZ-121 — Scheduler Locking
**Status:** DONE · **Owns:** `OI-19`

Five `@Scheduled` jobs and no distributed locking anywhere in the codebase — no advisory
lock, no `FOR UPDATE SKIP LOCKED`, no version column. Every one of them runs on every
instance.

`backend_desired_count` defaults to **2**, justified in `variables.tf` as *"so a deployment
or an AZ failure does not mean an outage."* Applied against today's application code that
default is not a redundancy setting, it is a duplication setting.

| Scheduler | Every | On two instances |
|---|---|---|
| `RestrictionLifecycleScheduler` | 1m | Duplicate notifications **enqueued** and duplicate audit rows |
| `NotificationDispatchScheduler` | 30s | Every pending row **delivered twice** |
| `DemoRequestNotificationScheduler` | 30s | Duplicate lead alerts |
| `TrialExpiryScheduler` | 1h | Duplicate `SUBSCRIPTION_SUSPENDED` audit rows |
| `DeploymentCheckRetention` | 24h | Harmless — a delete is idempotent |

**The audit duplication is the worse half.** `RestrictionLifecycleService.reconcile()` bulk-updates
status — idempotent, because the `WHERE` clause saves it — and then loops calling
`notificationOutbox.enqueue()` and `auditTrail.record()` per transition. Neither is guarded.
Combined with the dispatcher, one freeze is announced four times; and the trail that
`11-commercial.md` §1 argues is *what makes this a purchase rather than a Slack channel*
reports two activations of one restriction. A record that cannot be trusted to say how many
times something happened is not evidence of anything.

**Decided: a hand-rolled `scheduler_lock` table, not ShedLock.** Roughly forty lines and one
changeset, against two dependencies. It follows the precedent this repository has already
set twice — `FZ-087` hand-rolled per-IP rate limiting rather than adding a limiter library,
and `FZ-083` wrote a four-column outbox rather than bending the notification module to fit.
What is given up is real and worth naming: ShedLock handles lock extension, a minimum lock
duration, and clock skew, and those are the three things a hand-rolled version gets subtly
wrong. Revisit if a second instance ever exposes one of them.

**Decided: one mechanism for all five, not a claim-based outbox.** The technically better
design lets both instances work the notification queue through `FOR UPDATE SKIP LOCKED`
rather than one skipping — but it needs a `SENDING` state and a reaper for claims stranded
by a dying instance, and dispatch volume is nowhere near justifying parallel workers. It
becomes right when it becomes necessary.

**It cannot be a transaction-scoped advisory lock.** `pg_advisory_xact_lock` would be
smaller and needs no table, but the dispatcher makes HTTP calls to Slack and to customer
webhooks inside its sweep, and holding a pooled connection open across those calls trades
one failure mode for a worse one. The lock has to outlive a transaction, which means a TTL.

Acceptance:

- A `scheduler_lock` table via Liquibase; one row per job name, carrying `locked_until`.
- Acquisition is a **single atomic statement** — `INSERT … ON CONFLICT (name) DO UPDATE …
  WHERE locked_until < now()` — so two instances racing cannot both win.
- All five scheduled methods acquire before doing work and skip silently when they cannot.
- The `reconcileOnStartup` and `sweepOnStartup` entry points take the lock too. Two
  instances booting together is precisely when this collides.
- A lock TTL comfortably longer than its job's worst case, so an instance dying mid-sweep
  releases it on expiry rather than wedging the job for ever.
- **A test that runs two dispatchers concurrently against one `PENDING` row and asserts one
  delivery.** This is the criterion the story exists for. `FZ-114` records the same lesson
  from the other side: a scheduler whose test called the method underneath the scheduled
  entry point passed for as long as the only path that runs in production never worked.

### FZ-122 — Measure the Container, and Decide the Platform
**Status:** DONE · **Decided in:** `D-28`

A spike. Its output is two numbers and one decision, not code that is kept.

**The Dockerfile's comment is true and incomplete.** It says the heap is sized from the
container's memory limit rather than the host's, which is correct — but the JVM's default
`MaxRAMPercentage` is 25%, so a 1 GB container caps the heap near 256 MB and leaves most of
what is being paid for reserved and unused. `backend_memory = 1024` was chosen on that
basis. Whether it is the right number has never been measured.

**Measure container RSS, not JVM heap.** A hosted runtime bills and OOM-kills on container
memory; actuator says where the memory went, which is what tells you what to tune. Both
already exist — `spring-boot-starter-actuator` exposes `metrics`, and `FZ-062` registered
Micrometer — so this needs no dependency and no code.

Three moments peak differently and all three matter:

1. **Startup while Liquibase migrates** — usually the metaspace peak, and where a container
   sized on idle dies before serving its first request.
2. **Steady state idle** — what is paid for 24 hours a day.
3. **Under load** — `POST /api/policy/evaluate` plus a dispatcher batch.
   `connectors/test/run-tests.js` and `scripts/seed-demo.sh` already exist and are more
   honest than a synthetic loop.

Then repeat at half the size with `MaxRAMPercentage` raised and see whether it holds. Size
on the **load peak plus headroom**, never on idle.

**Two platform facts must be verified in the same spike, because either one changes the
answer:**

- **Does App Runner run ARM64?** `backend.tf` sets `cpu_architecture = "ARM64"` and the
  image is built for it. If App Runner is x86-only the image is rebuilt and the ARM pricing
  advantage — the reason two Fargate tasks cost $28.84 rather than $36 — disappears.
- **App Runner egress is `DEFAULT` or `VPC`, not per-destination.** Reaching RDS in a
  private subnet needs a VPC connector, and then *every* outbound call routes through the
  VPC — Slack, customer webhooks, Stripe and SES included. That needs a NAT, which is the
  single largest line item the platform was chosen to remove. A Fargate task in a public
  subnet has a public IP and an internet gateway route and needs no NAT at all, so the
  saving may belong to public-subnet Fargate rather than to App Runner. **Confirm against
  current AWS documentation before committing to either.**

Acceptance: a measured CPU/memory pair, a `MaxRAMPercentage` value, and a written platform
choice with the egress and architecture questions answered rather than assumed.

### FZ-123 — Apply the Beta Deployment
**Status:** DONE · **Resolves** `OI-15`, `OI-21` · **Deployed:** 2026-09-21

**No longer blocked.** The account exists (`FZ-175`), `bootstrap/` and `shared/` are applied,
and the identity work the whole thing waited on is done (`FZ-046`, `FZ-128`, `FZ-176`).
What remains is applying `singlebox/`, building an image and deploying — all human actions
against a live account.

**Rewritten by `FZ-159`, because `D-35` changed what "the beta deployment" means.** This
entry described applying `infra/` — the ALB-and-ECS estate. The beta now applies
`infra/shared/` and `infra/singlebox/`, and somebody picking this up as written would have
stood up the $64 estate instead of the $18 one.

**Its stated blockers were also stale.** `FZ-121` and `FZ-122` are both DONE; the only thing
in the way is an AWS account.

What to apply, in order: `bootstrap/`, then `shared/`, then `singlebox/`. Then deploy with
the `Deploy single-box` workflow (`FZ-154`) and follow `14-operations.md`.

Still true, and still to do:

- ~~**`frontend.tf` moves to `PriceClass_All`.**~~ **Done.** The old comment said "widen when
  customers are elsewhere", which understated it: `PriceClass_100` is North America and
  Europe, so it excluded **South America — where the company is**, and the first live check
  of `app.freezehub.io` was duly served from Miami. The SPA is a few hundred kilobytes
  inside CloudFront's perpetual free tier, so the wider class costs approximately nothing.
  Applying this updates the distribution, which takes a few minutes and serves throughout.
- **`terraform destroy` must be able to run** on `ecs/`: `deletion_protection` on RDS,
  `skip_final_snapshot = false`, and `prevent_destroy` on both secrets block it. Correct for
  production, wrong for a pre-customer beta. `singlebox/` has none of those blockers and is
  already destroyable, which is the posture the beta actually uses — so this is no longer
  urgent, only still true.

No longer applicable: `backend_desired_count` is an `ecs/` variable that the box does not
use, and `FZ-121` shipped the locking its old note was waiting on.

**Acceptance met.** `https://app.freezehub.io` serves the SPA and
`https://api.freezehub.io/actuator/health` answers `{"status":"UP"}`, both on valid
certificates, with Liquibase migrated and protected routes answering `401`. The API's
certificate is Let's Encrypt, obtained by Caddy without intervention — which is the saving
`D-35` is built on, working rather than assumed.

What was applied, in order: `bootstrap/`, `shared/`, `singlebox/`. One `linux/arm64` image
built under emulation and pushed to ECR as `:v1`. Deployed by hand, because `OI-43` still
blocks the workflow.

**Three things the deploy found, none of which any test could have.**

*The deploy script used the wrong profile.* `terraform output` cannot read the session
`aws login` writes, so every value it fetched failed. `FZ-175` documented exactly this gap
and the script still defaulted to the wrong profile — knowing a trap is not the same as
having removed it.

*Transcribing the workflow's escaping was wrong even though transcribing the workflow was
right.* `deploy-singlebox.yml` escapes for YAML, then JSON, then shell. A hand-run has no
YAML layer, so the literal `\\\$(` lost its `$` and the remote shell died on a bare `(`.
Rebuilt with `jq`, which constructs the JSON array rather than counting backslashes: the
commands stay byte-identical to the workflow's and the quoting is correct for its context.
**The workflow still hand-escapes**, which is the fragile half of this and now has a known
better pattern next to it.

*The frontend would have shipped pointing at localhost.* `client.ts` falls back to
`http://localhost:8099` when `VITE_API_BASE_URL` is unset, its comment says "any deployed
build sets VITE_API_BASE_URL", and `deploy-frontend.yml` **never set it**. The value is
baked in at build time with no runtime override, so a browser could not have been told
otherwise. Found by building by hand and checking what was in the bundle. The workflow now
sets it from `vars.API_BASE_URL` and **fails if it is empty**, because a deploy that
silently produces a broken site is worse than one that stops.

The same shape as `FZ-159`, `FZ-166`, `OI-44` and `FZ-176` before it: two halves built in
different stories, each assuming the other. This is the fifth, and the first found by a
thing actually running rather than by reading.

## Milestone 14 — What Beta Found

`FZ-065` reviewed the seven areas Milestone 8 named and fixed what it found on the
backend. This is the part it deliberately did not do in a review.

### FZ-124 — A Request That Never Answers
**Status:** DONE · **Resolves** `OI-22`

`apiRequest` passes the caller's `AbortSignal` through and adds nothing of its own, so a
request that is accepted and never answered leaves every screen in its loading state
indefinitely — no error, no retry, no way for the person to tell a slow page from a broken
one. `FZ-065` fixed exactly this on the backend's outbound calls (`D-30`); this is the same
defect on the other side of the wire, and the side a customer actually looks at.

The realistic cause is not an unreachable API — that fails fast and is already handled —
but a connection that is accepted and then abandoned: a load balancer holding the socket to
a task that has wedged. It is invisible in local development, where the API is either up or
refusing connections.

**Decide when starting it:** a timeout has to be told apart from a cancellation. TanStack
Query aborts requests on unmount and on refetch, and those aborts are *normal* — rendering
them as errors would flash a failure banner every time somebody navigates away. Both arrive
as the same `AbortError`, so the wrapper has to know which signal fired and say so.

Scope is the one wrapper, its tests, and the one line of retry policy the change collides
with. A timeout has already cost its whole deadline before it is reported, so the default
two retries meant a minute of spinner before the person saw a word — and retrying even
once was found, in the running application, to leave the query pending indefinitely: both
attempts were made and abandoned and the screen still said "Loading restrictions…" a
minute later. Timeouts are therefore not retried, which is also the behaviour that shows a
message soonest. No screen changes beyond that — every page already renders
`error.message`, which is precisely why the message is the deliverable.

### FZ-131 — Dark Mode, Derived Rather Than Invented
**Status:** DONE · **Resolves** `OI-17`

The application supported `prefers-color-scheme: dark` until `FZ-100`, which pinned
`color-scheme: light` because Industry shipped no dark ramp. Broadsheet ships none either,
so anyone on a dark OS got a light application with no warning — and taking away something
an application already did is the kind of change nobody remembers making, which is why it
was recorded rather than dropped.

**Derived from the system's own construction rule, not invented beside it.** Broadsheet's
ramps are generated in OKLCH *on one shared lightness scale*; in a dark context that scale
runs the other way, so step 100 is the darkest and 900 the lightest. Every module keeps
asking for the step it already asks for — a tinted fill is still 100, text on a tint is
still 800 — and the ink becomes the ground while the paper becomes the type.

**Two roles are remapped rather than mirrored**, because the step that carries a role
changes with the ground: accent-at-paragraph-size moves from 700 to 600, and the "in force"
ink moves to 500. Mirroring sent the refusal magenta to a pale pink that read as decoration
rather than as a deployment being stopped — the one rule for colour survives only if
magenta still looks like a refusal.

**Found on the way, and fixed:** eighteen declarations across seven modules reached past
`--fh-danger` for `--color-accent-2-700` directly. Identical in light — the frame is
byte-for-byte unchanged — but it meant the "in force" ink could not be remapped in one
place, which is exactly what an alias is for. They now use the alias.

**Decided:** a `[data-theme]` hook ships alongside the media query. `prefers-color-scheme`
alone cannot be seen on a light machine, so nothing about the dark set could be verified,
reviewed or screenshotted — the hook earns its place today rather than being scaffolding
for a toggle. Nothing in the interface sets it, and that is stated where it is defined.

Out of scope: a toggle in the interface, and a stored preference. Neither has been asked
for; the reader's system already says which they want.

### FZ-132 — A Column That Does Not Name Its Vendor
**Status:** DONE · **Resolves** `OI-16`

`users.cognito_subject` named a provider rather than a concept. The column holds whatever
subject an OIDC issuer put in the `sub` claim, and the backend has no coupling to Cognito
at all — no SDK, nothing in `pom.xml`, nothing in `application.yml`. The name asserted a
coupling that does not exist, and a name is the first thing a reader believes.

Now `external_subject`. Cognito stays named in prose, because it is genuinely the chosen
provider (`06-security.md`); what changes is the schema, which should describe the concept
it stores.

**A rename, not a new column plus a backfill.** There is no production data and no second
writer, so `renameColumn` is one statement — and the whole point of doing it now is that
`FZ-046` has not yet wired a real provider. Once there are rows in a deployed environment
this stops being free.

**The constraint is renamed too.** PostgreSQL carries a generated constraint name across a
column rename, so `users_cognito_subject_key` would have gone on saying "Cognito" from the
one place nobody thinks to look — the schema half-renamed is worse than not renamed,
because it reads as an oversight rather than a decision.

**Rehearsed against real rows.** The integration suite runs the changelog on an empty
database every time, which cannot show what a rename does to data. The live local database
was cloned, the application started against the copy, and the result checked: column
renamed, constraint and its index renamed with it, all three rows intact and distinct, and
a real token round-trip resolving a user through the renamed column. The rollback
statements were then executed against that copy and returned it to the old shape. The copy
was dropped; the live database was never touched.

### FZ-133 — Spacing on the Scale
**Status:** DONE · **Resolves** `OI-18`

`OI-18` recorded that layout spacing was mostly literal, so the system's density was
unreachable by changing tokens: Broadsheet specifies a 1.25× airier scale and the
application received it through the type scale alone.

**The count said 47 rem literals; the map said four files.** Two screens carried almost all
of them — `RestrictionsPage` and `CatalogPage`, 33 between them — with `AuditPage` and one
line of `RestrictionDetailPage` making up the rest. Everything else had already moved onto
the tokens as screens were rewritten. The issue read as a survey of the whole application;
it was two screens that were never re-pitched.

**The rule, so the judgement is inspectable rather than per-declaration taste:**

| Literal | Becomes | Why |
|---|---|---|
| within ~2px of a step | that step | 12px → `--space-2` (10), 14px → `--space-3` (15), 16px → `--space-3` |
| exactly a step | that step | 20px → `--space-4`, 10px → `--space-2`, 5px → `--space-1` |
| below the scale (≤2.5px) | plain `px` | a 2px nudge under a chip is furniture, not layout, and forcing it onto the scale would triple it |
| any `px` already there | untouched | the deck specifies its furniture in px — a 4px rule over a 1px one, 12px between chart columns. `OI-18` warned that converting those would overwrite the system with itself |

Recounted after the change: **159 token uses, 0 rem literals, 133 px** — the px column
unchanged, which is the point.

**Found while photographing it, and not fixed here:** `RestrictionsPage` is still wearing
Industry's furniture — a bordered filter fieldset with a legend, the table inside a boxed
panel, outlined status chips — while every screen re-cut for Broadsheet uses rules and
negative space instead. Spacing was the symptom `OI-18` recorded; that is the cause, and it
is a redesign rather than a sweep. Recorded as `OI-28`.

### FZ-134 — Restrictions, Re-cut for Broadsheet
**Status:** DONE · **Resolves** `OI-28`

The last screen still wearing Industry's furniture. `FZ-133` put its spacing on the token
scale and, in photographing it, made plain that spacing was the symptom: the screen was a
faithful Industry layout that survived the theme swap.

Two boxes removed, both replaced with something the system already draws:

- **The status filter** was a bordered `fieldset` with a `legend` — a frame around four
  words. It is now a small caps label and chips, which is how the deck draws a multi-select
  (`1e`, where scope is "chips instead of native multi-selects"): filled when chosen,
  outlined when not. The `fieldset` gave the group its accessible name, so that became
  `role="group"` with `aria-labelledby`; the checkboxes are still checkboxes, visually
  hidden behind their chips exactly as the system's own `.seg-opt` hides its radios.
- **The table** was a local copy of `.table` inside a bordered, rounded panel. It now uses
  the system's `.table`, unboxed, as the checks console has since `FZ-071`.

**Two corrections to `OI-28`, which I wrote and got partly wrong:**

1. It said `1e` "draws a list screen". It does not — `1e` is create-restriction. What it
   draws is the *multi-select*, which is the part this needed. There is no list screen in
   the deck; the reference for the table was the checks console, in the application.
2. It called the outlined status chips Industry furniture. They are not: `FZ-103` chose an
   outline for `active` deliberately, so that the magenta beside it stays the only claim
   that something is in force. They are untouched.

**Found while photographing the phone**, and fixed here: the table's own comment said
narrow screens "scroll the table rather than squashing the date columns", and nothing
implemented it — `.table` is `width: 100%`, so inside a scrolling box it shrank to fit and
every title broke to one word per line. The two timestamps were taking 53% of the table
between them, measured, leaving the name column 108px. A `min-width` makes the scroll real,
and below 48rem the timestamp is allowed to wrap so the name gets its width back.
## Milestone 15 — Security Requirements

`FZ-065` reviewed the areas it named — tenant isolation, API keys, date/time, lifecycle — and
found them sound. This milestone reviews what that story did not scope, against OWASP, and
fixes what the review found.

**Order.** `FZ-125` first: it is the specification the rest read. `FZ-128` is sequenced with
`FZ-046`, not before it. `FZ-126` is decided together with `FZ-122`/`FZ-123`, because egress
is one decision seen from two sides.

```text
FZ-125 ──┬── FZ-126   (with FZ-122 / FZ-123)
         ├── FZ-127
         ├── FZ-128   (with FZ-046)
         ├── FZ-129
         └── FZ-130
```

### FZ-125 — Security Requirements and OWASP Coverage
**Status:** DONE · **Owns:** `OI-23`–`OI-27`

Specification only, no code: `docs/06-security.md` gains a **Threat Model and OWASP
Coverage** section and, inside Human Authentication, the **token validation rules** a
deployed environment must enforce.

**Deliberately six findings rather than a coverage matrix.** Several OWASP categories cannot
be answered honestly while nothing is deployed and `FZ-046` does not exist, and a matrix that
answers them anyway is worth less than none. The matrix is worth writing once there is
something running to answer it about.

The one finding worth reading the section for: **a webhook URL is attacker-chosen by design**,
so the control is egress rather than validation. That reframing is what makes `FZ-126` a
deployment story rather than a validator story.

### FZ-126 — Egress Control for Outbound Deliveries
**Status:** DONE in the application · **Owns:** `OI-23` · **Network half:** `FZ-123`

Acceptance:

- Redirects are **not** followed on outbound deliveries. This is the single change that
  closes the `https://`-to-`http://` downgrade, and it is a request-factory setting.
- The resolved address is checked **at connect time**, not at save time, and loopback,
  link-local, private and unique-local ranges are refused. Checking at save leaves a name
  that can be repointed afterwards.
- A userinfo authority no longer satisfies the scheme check — the host is what is tested.
- The refusal is a delivery failure the customer can see on the notification, not a silent
  drop: a destination that will never work should say so.
- Tests cover a redirect to a link-local address, a userinfo authority, and a hostname that
  resolves to a private address.


**Built (`FZ-126`).** All four acceptance criteria are in the application, with one
correction to the first: it said not following redirects "is a request-factory setting". On
the factory this application had — `SimpleClientHttpRequestFactory` over
`HttpURLConnection`, which `FZ-065`'s hang stack trace names — there is no redirect setter
at all. It took a different factory: the JDK `HttpClient` with `followRedirects(NEVER)`,
which is also where the connect timeout now lives.

The guard is a request interceptor on a `RestClient` bean that only the customer-facing
senders take, so the demo-request notifier — which calls a webhook this organization
configures for itself — is deliberately not subject to it.

**Verified by taking the fix away**: with `Redirect.NORMAL`, the delivery follows the `302`
to the second server and raises nothing at all, which is what the product did before this.

**The `https://` requirement stays** and keeps its existing reason — these carry credentials
and announcements. What changes is that it is never again read as a statement about *which
host*, only about the transport.

Whether the boundary is additionally enforced in the network — a security group, or egress
through a proxy — is `FZ-123`'s to decide, and is the more durable half of the fix.

### FZ-127 — Dependency and Image Scanning
**Status:** DONE · **Owns:** `OI-24` · **Found:** `OI-29`, `OI-30`

Acceptance:

- The build fails on a dependency with a known exploitable vulnerability, at a severity
  threshold written down rather than left to a default.
- **The container image is scanned as well as the dependency tree** — the base image is not
  in `pom.xml`, so a dependency scan alone does not cover what ships.
- The frontend's dependencies are scanned too; `npm` is a dependency tree like any other.
- A finding that cannot be fixed immediately is suppressible **with an expiry date**, not
  indefinitely. A permanent suppression is how a scanner becomes decoration.

Prefer what is already available in the toolchain over a new service. This is a CI change,
not a platform.

**Built (`FZ-127`).** A `security` job in `verify.yml` runs Trivy over all three trees this
repository ships — the Maven tree, the npm tree, and both base images — gated at
`HIGH,CRITICAL`, which is written in the workflow rather than left to a default. One tool
rather than three, which is what "prefer what is already available" bought.

Two things it taught, both worth keeping:

- **The Maven scan needs a populated `~/.m2`.** Without one, Trivy resolves every POM over
  the network and Maven Central answers `429 Too Many Requests` with a half-hour block. Not
  hypothetical — it happened while this was being written, which is why the job runs
  `dependency:go-offline` first.
- **The base images are scanned by name, not by building the application image.** The
  shipped image is base + jar, and both halves are covered — the jar's dependencies by the
  Maven scan, the base by the image scans. Building it here would cost a full Maven build
  inside Docker to learn the same two things.

**The suppression file is dated and owned.** Everything HIGH-and-above in the tree today is
listed in `.trivyignore.yaml` with `expiredAt: 2026-10-13` and `FZ-136` named as the owner,
so the gate blocks anything *new* from the moment it is switched on, and blocks these too
once the date passes. That is the difference between a baseline and a blanket.

**Both halves were verified.** With the baseline the scan is clean and exits 0; remove one
entry — `CVE-2025-24813`, a tomcat CRITICAL — and it exits 1 and names it.

### FZ-128 — Enforce the Token Validation Rules
**Status:** DONE · **Resolves** `OI-25` · **Sequenced after** `FZ-046`

`CognitoJwtConfig` replaces the resource server's default `JwtDecoder` outside `local`.
What it adds is the two checks the default does not make, because **Cognito issues ID
tokens and access tokens from one issuer signed by one JWKS**: signature and expiry accept
both.

- `token_use` must be `access` — the only claim that tells them apart.
- The app client is read from `client_id`, **not** `aud`. Cognito populates `aud` on the
  ID token only, so a validator configured on `aud` in the ordinary way validates an
  absent claim: it passes everything while reporting that it checked something.

**Two findings while building it, both of which changed the implementation.**

*`withIssuerLocation` is not lazy.* It fetches the pool's OpenID configuration at bean
creation, so the application could not start unless Cognito answered — which the
deployed-shaped test proved immediately by failing to boot. A context that cannot start
during a Cognito outage cannot serve the Policy API during one either, and that endpoint
answers from the database and needs no human's token. `withJwkSetUri` against Cognito's
documented constant path gets the same keys, lazily. Nothing is lost: the issuer is still
validated, against the same configured value the URI is built from.

*A 401 is too weak an assertion, and so is `invalid_token`.* `/api/me` answers both when
the token is perfectly valid but its subject matches no user row — the application reports
that as `invalid_token` too. Every rejection test would have passed with none of this
story's code present. What discriminates is **how far the request got**: a token stopped
by a validator never reaches user provisioning, so the tests assert on the presence or
absence of that later failure. The positive case asserts it *is* reached, which is what
proves the token was accepted.

That second one is the `FZ-114`/`FZ-121` failure mode this repository has now hit three
times — a guard whose test exercised a path production does not use — caught before merge
rather than after.

**The client id is wired where it is consumed, in this story.** `compose.yaml`, the deploy
workflow, and `infra/ecs/backend.tf` — which also gained the `FREEZEHUB_COGNITO_REGION` it
was missing. `OI-44` was exactly this omission one story ago; repeating it immediately
afterwards would have been hard to excuse.

Acceptance, all met by test:

- An **ID token** from the same pool, a token from **another pool**, and a token for
  **another app client** are each refused, with a correctly signed and unexpired token.
- A local development token — no `token_use`, no `client_id` — is refused.
- An access token with the client in `aud` instead of `client_id` is refused.
- A genuine access token is **not** refused, without which none of the above means
  anything.
- The issuer is configuration with no default, so an environment that omits it does not
  start.

### FZ-129 — Response Headers on the Distribution
**Status:** DONE · **Owns:** `OI-26`

An `aws_cloudfront_response_headers_policy` and its association. Content-Security-Policy,
HSTS, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`.

The policy that needs thought is CSP: the frontend holds its bearer token in
`sessionStorage`, so a script injection is how that token leaves, and CSP is the control
against it. Write it tight enough to matter — the app loads no third-party script today, and
that is the moment to say so in a header.

Cheapest item in the milestone, and it should not wait for the rest.

### FZ-130 — Rate Limiting Beyond the Unauthenticated Endpoints
**Status:** DONE · **Owns:** `OI-27`

Extends `FZ-087`'s limiter, which was deliberately scoped to signup and demo requests.

**The endpoint that matters is `/api/policy/**`, and for an unusual reason.** It is not
credential exposure — a 256-bit key is not guessable. It is that `freeze-check.sh` fails
closed, so the endpoint whose unavailability blocks every customer's deployments is the one
currently unmetered.

Acceptance:

- Failed API-key attempts are limited per source, and a limit is `429` with `Retry-After`,
  as `FZ-087` established.
- **A successful, authenticated policy evaluation is limited per key, generously, and never
  in a way that refuses a legitimate deploy.** A limiter that blocks a real pipeline has
  reproduced the outage `D-21` exists to prevent, from the other direction. If that cannot
  be done safely, say so and limit only the failures.
- The Stripe webhook endpoint is limited; Stripe retries, so a `429` there is safe.
- Limits are configuration, not constants.

**Built.** Four limits now, not one, each counted against whoever is responsible for the
traffic:

| What | Counted per | Default |
|---|---|---|
| signup, demo requests, dev token | source address | 10 / minute (unchanged, `FZ-087`) |
| the Stripe webhook | source address | 120 / minute |
| `/api/policy/**` with no usable key | source address | 30 / minute |
| `/api/policy/**`, authenticated | **API key** | 60 / 10 seconds |

All four are `freezehub.rate-limit.*` properties — `requests` and `window` each — so a
number can be changed without a release.

**The successful path is limited, and the acceptance criterion's escape hatch was not
taken.** What made it safe was not the size of the number but two other things.

*The key.* `freeze-check.sh` fails closed, so a `429` does not slow a deployment, it stops
it. Counting authenticated evaluations per address would mean one pipeline with a stale
credential could fill the bucket for every pipeline sharing a corporate NAT — FreezeHub
blocking deployments for a reason unrelated to any freeze. Per key, a pipeline can only
refuse itself, and `PolicyRateLimitTest` exhausts the failure budget from an address and
then deploys with a valid key from that same address to prove it.

*The window.* 60 per ten seconds and 360 per minute allow the same rate, but only the long
window can answer `Retry-After: 54`. The short one bounds the penalty instead of the rate,
so the worst a refused pipeline waits is ten seconds — which `freeze-check.sh` now sits out
(`curl --retry`, honouring `Retry-After`, ceilinged at 30 seconds) rather than failing the
build. Recorded as `D-31`; the connector change is part of the decision rather than a
follow-up, because a limit the caller cannot recover from eventually becomes an incident.

**Enforced in two places, because the endpoints are reached differently.** The path limits
stay an interceptor. The Policy API's are a filter inside its security chain: a request
whose key does not resolve is refused by the entry point and never reaches the
DispatcherServlet, so an interceptor would count only the calls that succeeded. Both refuse
with `429`, `Retry-After`, and the same Problem Details body as every other refusal.

Verified by running: 472 backend tests pass, and the ordering the design depends on was
checked by breaking it — moving the filter ahead of authentication turns two tests red,
because every authenticated call then lands in the address bucket. The connector's retry
was likewise proved against a stub that answers `429` once and `200` next, and against one
that asks for a wait longer than the ceiling.

**Left alone deliberately:** the counters are still in memory and per instance, so two
tasks mean twice the limit (`CLAUDE.md` §4 keeps shared state out of the MVP), and abuse
spread across many addresses still needs a WAF above the application.

### FZ-135 — Decide the Region Before Anything Is Applied
**Status:** DONE · **Owns** `OI-20` · **Decided in** `D-32`

`OI-20` recorded that `us-east-1` was chosen by being the `variables.tf` default, with the
residency question knowingly deferred. This story does the part that is not a decision —
establishing what the choice actually costs, and stopping the default from making it — and
leaves the choice itself to the operator, because it is a commercial judgement about who
the product is sold to.

**What it costs to change, today:** one variable. The Terraform is already parameterised
end to end — `var.region` drives the single default provider, availability zones are read
from `aws_availability_zones` and sliced rather than named, and the only pinned `us-east-1`
is the ACM certificate for CloudFront, which AWS accepts from nowhere else. A certificate
holds no customer data, so that pin is not a residency question. The state bucket in
`bootstrap/` takes its own region variable.

**What it costs after the first apply — worse than `OI-20` said.** It recorded "a migration
of live data", meaning the database. It is also every identity: **a Cognito user pool is
region-bound and cannot be moved**, and the `sub` it issues is what `users.external_subject`
stores. Moving region after `FZ-046` creates the pool means a new pool, new subjects for
every user, and a re-mapping of that column — a migration of who people are, not just of
what they own. Right now the pool does not exist and there are no identities, which is why
this is in front of `FZ-046` rather than after it.

**Where customer data would live**, which is what a security questionnaire actually asks:
the database, the user pool, the CloudWatch log groups and the Secrets Manager entries are
all in `var.region`. The S3 bucket holds the built frontend, and CloudFront caches it at
edges worldwide — static assets, no customer data. So a single EU region answers the
question completely, with the certificate as the only US-resident object.

**Recommendation: `eu-west-1`.** `OI-20`'s asymmetry is the argument and it still holds —
EU buyers frequently require EU residency, US buyers rarely require US residency — and
Ireland carries every service this uses. The cost figures in `infra/README.md` were taken
against `us-east-1` and would need re-checking; the difference is single-digit percent, not
a different posture.

**Done here, because it is not the decision:** `region` no longer has a default. It must be
stated, exactly like `domain_name`, so that the next person to run `terraform apply` cannot
inherit a region nobody chose. `terraform validate` needs no variable values, so CI is
unaffected, and the deploy workflow deliberately never runs Terraform.

**Not done here:** choosing. Set `region` in `terraform.tfvars` and this unblocks.

**Built (`FZ-129`).** An `aws_cloudfront_response_headers_policy` on the distribution's
default behaviour: CSP, HSTS (one year, subdomains, no preload — preload is a one-way door),
`X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, and a
`Permissions-Policy` switching off everything the product has no use for.

**The CSP is derived from what the application loads, directive by directive**, not copied
from a template:

| Directive | Why it says what it says |
|---|---|
| `script-src 'self'` | the application loads **no third-party script at all** — no analytics, no tag manager, no CDN. Worth stating in a header while it is still true |
| `style-src 'self' https://fonts.googleapis.com` | Source Serif 4 arrives through an `@import` in `index.css` |
| `style-src-attr 'unsafe-inline'` | React sets four inline style attributes and **two are load-bearing** — the usage bar's width and the checks chart's bar heights are computed from data. Scoping the allowance to the *attribute* keeps `<style>` elements and stylesheets strict |
| `font-src 'self' https://fonts.gstatic.com` | where the `woff2` actually comes from |
| `connect-src 'self' https://api.<domain>` | the only origin the application calls |
| `img-src 'self'`, `object-src 'none'`, `base-uri 'none'`, `frame-ancestors 'none'` | nothing needs them, and refusing loudly is the point |

**Verified against the real bundle before it was ever applied.** The production build was
served locally behind these exact headers, and the browser was asked what happened: the
Google Fonts stylesheet *and* the `woff2` both loaded, five API calls went through, and the
chart's bars measured 149px rather than collapsing — which is what `style-src-attr` is
there to prevent. Then the inverse, to prove the policy is enforced rather than merely
present: a script from `cdn.jsdelivr.net` was **refused**, and a `fetch` to `example.com`
was **refused**.

Without that rehearsal the first evidence either way would have been a customer looking at
a chart of flat bars.

### FZ-136 — Upgrade the Platform Off Spring Boot 3.3.4
**Status:** DONE · **Owns:** `OI-29`

`FZ-127` switched the scanner on and measured 39 HIGH-or-above findings in the backend's
dependency tree, 9 of them CRITICAL. **Decided (operator's call): the 4.x line, not 3.5.x.**

**Spring Boot 3.3.4 → 4.1.1, plus Tomcat pinned to 11.0.25. The scan goes 39 → 0.**

Boot 4 is a major version and it moved five things this application depended on. None of it
was guessed — each was located in the actual jars before anything was edited:

| What moved | Where it went |
|---|---|
| **Jackson 2 → 3** | `com.fasterxml.jackson.{core,databind}` → `tools.jackson.*`, across 24 files. Annotations stayed put. `JsonProcessingException` became the unchecked `JacksonException`, and Java-time support is built in, so `JavaTimeModule` is gone |
| `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` | `DateTimeFeature`, set on an immutable mapper's builder |
| `ClientHttpRequestFactories` / `RestClientCustomizer` | `ClientHttpRequestFactoryBuilder` + `HttpClientSettings`, and `org.springframework.boot.restclient` — a module the web starter no longer pulls |
| `@AutoConfigureMockMvc`, `@DataJpaTest`, `@AutoConfigureTestDatabase` | per-technology test modules (`spring-boot-webmvc-test`, `-data-jpa-test`, `-jdbc-test`) |
| `TestRestTemplate` | **removed.** The one test using it now drives the running server with `RestClient`, using `exchange` rather than `retrieve` because the statuses under assertion are exactly the ones `retrieve` would throw on |

Testcontainers also had to move to 2.x, whose modules are renamed (`testcontainers-postgresql`,
`testcontainers-junit-jupiter`), because Boot 4 no longer manages its versions.

**The failure worth recording**, because it is the one a test suite catches and a reviewer
would not: with everything compiling and the upgrade apparently done, **342 of 468 tests
errored with `relation "organization" does not exist`**. Boot 4 moved Liquibase's
auto-configuration into `spring-boot-liquibase`, and `liquibase-core` on its own no longer
runs the changelog at startup. The schema was simply never created. One dependency fixed
all 342.

**Tomcat is pinned ahead of Boot's own default.** 4.1.1 brings 11.0.24, which still carries
three CRITICALs; 11.0.25 has them fixed. Pinned in `<tomcat.version>` rather than waited
for, because the alternative was shipping them or suppressing them.

Verified: **468 tests pass**, the application **starts** against an empty database and
Liquibase applies all 27 changesets, `/actuator/health` is UP, and the scan returns **0
findings at HIGH or above**.

**The baseline shrank with it.** `FZ-127` landed first and baselined the 39 findings this
removes, so `.trivyignore.yaml` is regenerated here: the 40 Java entries are gone and only
the 8 base-image ones (`OI-30`) remain. A baseline that does not shrink when the debt is
paid is one nobody is reading.

### FZ-139 — Pin the Base Images by Digest
**Status:** DONE · **Owns:** `OI-30`

`FZ-127` switched the scanner on and `OI-30` was what it found second: all three `FROM`
lines in this repository name a tag, and a tag moves. It was not a hypothesis — a local
scan of `eclipse-temurin:21-jre` found nothing while the same scan in CI found eight HIGH,
because the tag had gone from Ubuntu 24.04 to 26.04 in between. The base of the deployed
application changed distribution release with no commit, no review, and no way to tell from
the repository which one a given build used.

**Both halves of the fix, because pinning alone would have frozen the bad one.** Every base
was measured by digest before anything was chosen:

| Base | Resolves to | HIGH+ |
|---|---|---|
| `eclipse-temurin:21-jre` — what shipped | Ubuntu 26.04 | **8** (`/usr/bin/pebble`, Go stdlib) |
| `eclipse-temurin:21-jre-noble` | Ubuntu 24.04 | **0** |
| `eclipse-temurin:21-jre-alpine` | Alpine 3.24.1 | 5 (openssl, expat) |
| `eclipse-temurin:21-jdk` — the build stage | Ubuntu 26.04 | 8 |
| `eclipse-temurin:21-jdk-noble` | Ubuntu 24.04 | **0** |
| `alpine:3.20` — the connector | Alpine 3.20 | **0** |

So both stages move to `-noble` and all three `FROM` lines carry a digest. **The baseline is
now empty** — `.trivyignore.yaml` went 39 → 8 → 0 across `FZ-127`, `FZ-136` and this story,
and the last eight are gone rather than suppressed, which is what `OI-30` said choosing the
variant would do.

**The scanner now reads the Dockerfiles.** `verify.yml` named the base images itself, which
was survivable while they were tags and a liability the moment they became digests: two
copies of a digest disagree eventually, and a scan passing against a base the build no
longer uses is worse than no scan, because it reads as evidence. The workflow extracts the
`FROM` line instead — the runtime stage, since only the jar leaves the build stage.

**What a pin costs, and why it is still right.** A digest does not pick up the base's own
security patches. The answer is not to avoid pinning but to make staleness loud: CI scans
exactly these digests on every run, so the build fails the day one of them acquires a
finding, and the bump is a commit somebody approves. Ubuntu 24.04 is the previous LTS,
supported to 2029; the intent is to move forward when the newer image stops carrying
pebble, not to sit on it.

**Not pinned, deliberately:** `apk add --no-cache curl jq` in the connector image still
resolves against Alpine's repository at build time, so two builds of the same base digest
can differ. Pinning package versions holds until Alpine drops one and then every build
fails — a silent change traded for a loud outage inside customers' pipelines. Also left
alone: `docker-compose.yml`'s `postgres:16` and `axllent/mailpit:latest`, which are local
development and ship to nobody.

Verified by running: both images build from the pinned digests, the backend image starts
against a real PostgreSQL and answers `/actuator/health` with `UP`, the connector image
passes its own smoke test, and a scan of all three pinned bases plus both built images
returns **0 findings at HIGH or above** with an empty ignore file.

### FZ-140 — Move the Workflows Off the Node 20 Runtime
**Status:** DONE · **Owns:** `OI-33`

`FZ-099` bumped `publish-connectors.yml` because that was the file it was already changing
and could prove with a dry run, and raised `OI-33` for the other two rather than folding an
unproven change into a story about publishing an image.

**The issue undercounted the problem, which measuring found.** Rather than bumping by eye,
every `uses:` in the repository was resolved to the `using:` its `action.yml` declares at
the exact ref pinned here, and at the current major:

| Action | Was | Runtime | Now |
|---|---|---|---|
| `actions/checkout` ×7 | `v4` | node20 | `v7` |
| `actions/setup-java` ×2 | `v4` | node20 | `v6` |
| `actions/setup-node` ×2 | `v4` | node20 | `v7` |
| `hashicorp/setup-terraform` | `v3` | node20 | `v4` |
| `aws-actions/configure-aws-credentials` | `v4` | node20 | `v6` |
| `docker/setup-qemu-action` | `v3` | node20 | `v4` |
| `docker/setup-buildx-action` | `v3` | node20 | `v4` |
| `docker/build-push-action` | `v6` | node20 | `v7` |
| `aws-actions/amazon-ecr-login` · `-ecs-render-task-definition` · `-ecs-deploy-task-definition` | `v2` · `v1` · `v2` | **already node24** | unchanged |
| `aquasecurity/trivy-action` | SHA, `v0.36.0` | **composite — no Node at all** | unchanged |

Two of those — `configure-aws-credentials` and `setup-terraform` — are not in `OI-33`'s
list, and three of the `aws-actions` are on it only by implication and did not need
touching. A list read off the file would have bumped the wrong set.

**The version jumps were read, not assumed.** Three actions move more than one major, so
each release note was checked against how this repository actually calls them:
`setup-java` v5 stops installing JetBrains pre-releases by default (this uses `temurin`);
`setup-node` v5 caches automatically when `package.json` carries `packageManager` (it does
not, and `cache: npm` is set explicitly anyway); `checkout` v5 raises the minimum runner
version, which GitHub-hosted runners exceed. Everything else in those notes is the node24
move itself.

**`deploy.yml` is the one nothing can prove.** It is `workflow_dispatch` only and there is
no AWS account to dispatch it against (`FZ-138`, `FZ-123`), so its first real run is still
its first run. The only semantic change there is `configure-aws-credentials` v5 altering
how *invalid boolean* inputs behave, and this workflow passes no boolean inputs — checked
rather than hoped, and written into the file's header so the next person does not have to
re-derive it.

**The pinning question `OI-33` raised, answered rather than deferred:** actions stay on
floating major tags. It looks inconsistent beside `FZ-139` pinning base images by digest,
and the inconsistency is the point — a base image is what ships to customers, so it is
pinned and reviewed; an action is what *inspects* what ships, and a frozen inspector
quietly stops learning about new vulnerabilities. `trivy-action` stays SHA-pinned for the
reason `FZ-127` gave: third-party, runs on every pull request, and a moving tag there is
code this repository did not review running against every branch.

Verified by the run that matters: `verify.yml` exercises `checkout`, `setup-java`,
`setup-node` and `setup-terraform` on every pull request, so this story's own CI run is the
proof — six jobs green, including the full backend suite against Testcontainers, which is
exactly the thing `OI-33` said had to be watched rather than read.

### FZ-196 — The Wordmark and the Favicon
**Status:** DONE

The operator chose two options from the design deck (`FreezeHub Screens.dc.html`, turns 2
and 3): **`2c`** for the logo and **`3a`** for the favicon. This implements exactly those.

**`2c` — the wordmark.** "FreezeHub" set in the heading face with a **magenta full stop**,
between a 5px rule above and a 1px rule below at masthead size, 3px/1px in a nav. The point
is the mark: it is `--color-accent-2`, the ink the product already spends on *a restriction
is in force*, so the logo says what the interface says. The deck's own label makes that the
argument for it — *"una sola tinta de acento, y el color significa lo mismo que en el resto
del producto"*.

**`3a` — the favicon.** The literal crop of `2c`: the initial and the point, paper on ink,
in a square. It replaces a purple gradient bolt that shared no ink with Broadsheet and
predated it.

**A component, because a logo that differs per screen is not a logo.** `Wordmark` separates
the two halves deliberately: the point works at any size and is the brand; the rules are
masthead furniture and exist only at the sizes the deck drew (44 / 17 / 13). So the header
and the landing nav take `rules="nav"`, and the sign-in screen takes the point alone at the
30px `1i` chose for it, rather than a variant the deck never drew. Four call sites, one
definition, and two type-only `.brand` rules deleted as dead.

**What the favicon cannot do, stated rather than discovered later.** Its colours are the
tokens written out, because a favicon renders outside the document and cannot read the app's
custom properties — retune the tokens and this file must be retuned with them. And the
letterform is `<text>`, not an outline, so it depends on a serif being installed: a favicon
does not fetch webfonts, and the stack falls back to Georgia. At 16px — the size `3a` was
chosen to survive — that is close enough in colour and weight. Outlining the glyph would
make it exact on every machine and needs the font binary plus a tool to extract the path,
which is its own job.

Verified by running: 261 frontend tests pass, `oxlint` clean, `tsc -b && vite build` clean,
and both marks photographed in a browser against the running backend — `docs/ui/FZ-196/`,
which also shows the bolt this replaces.

### FZ-197 — A Skill for Working This Repository in Parallel
**Status:** DONE

`.claude/skills/session-sync/SKILL.md`. No application code.

`next-story` answers **what to work on** and is deliberately single-session: it reads
`master` and the backlog, so run it in three windows and it recommends the same story three
times. This is the other half — **who else is live, what have they taken, and may I take
this.**

**The gap it closes is a window git cannot see.** A story is invisible to every other
session between "a session decided to do it" and "a branch reached the remote". Everything
collides in that window, and all of it happened here on 2026-09-22: `FZ-193`, `FZ-194` and
`FZ-195` were claimed within minutes of being checked and one already carried a commit;
a worktree named `freezehub-FZ-146` held `FZ-168`; `freezehub-FZ-138b` was a second attempt
at a story another session was finishing; and 36 of 37 worktrees were residue on merged
branches.

So the skill reads three sources and trusts none alone — `ListAgents` for live sessions,
remote branches and open pull requests for claims that landed, `git worktree list` for work
in progress — and cross-references them into one table. It also carries the two documents
that collide when several stories finish at once, `08-backlog.md` and `09-open-issues.md`,
with the anchored-insert rule and the assertion that goes with it: an insert anchored at
"the first `|---|---|---|`" once put four rows inside an unrelated table and survived a
merge.

**What it does not claim.** The claim protocol is push-the-branch-before-you-work, and there
is a residual race it states rather than hides: between checking `ls-remote` and pushing,
another session can claim the same id, and because both branches point at the same commit
both pushes succeed. Git has no create-only-if-absent that helps. The resolution is stated —
first session whose *commit* lands keeps the id, the other re-claims, nobody force-pushes —
and the live announcement to peers is what covers the case git is blind to. A lane that
exists only in a message is still a lane: `FZ-193` read as free from `ls-remote` while
another session held it, and honouring the message rather than the mechanical check is what
avoided the collision.

Tracked, because that is what makes it exist: project skills are discovered per working
directory, and until this is on `master` it is visible only from the shared checkout — the
one place the skill itself says not to work in.

### FZ-198 — A Release in One Command
**Status:** DONE

`scripts/release.sh`. No application code, no change to how a deploy works.

Releasing was three dispatches through the Actions UI — Build image, Deploy single-box,
Deploy frontend — with two values fetched by hand in the middle: the tag the first run
prints, and the instance id from `terraform -chdir=infra/singlebox output -raw instance_id`.
Correct, documented in `14-operations.md`, and slow in the way that invites shortcuts.

**It dispatches those workflows and waits for each. It does not deploy**, and refusing to
is the whole design. Everything that makes a release safe is inside them:

- **FreezeHub asks FreezeHub** (`FZ-182`): `Deploy single-box` runs `./connectors` against
  our own Policy API before anything else, so a freeze in force stops our release exactly
  as it stops a customer's. A script that shelled out to SSM would walk around the one
  control this product exists to sell.
- **No credential on the laptop.** The workflows assume a role by OIDC; deploying locally
  would need an access key that then exists.
- **Attribution.** SSM Run Command is an IAM-authorised call in CloudTrail with an actor.
- **`concurrency: deploy-singlebox`** stops two releases racing. Two laptops cannot.
- The moving-tag refusal, and waiting for the SSM *outcome* rather than for the API
  accepting the request, are already written and already tested there.

So the slow part was never the safety. It was the lookups.

**Two guards of its own, before anything is dispatched.** A dirty tree asks for
confirmation — the build takes the commit from the remote, not the working copy, and that
difference is invisible at the moment it matters. A commit not on `origin` is refused:
Actions cannot check out what it cannot fetch, and `git branch -r --contains` is the
question that actually answers it.

**AWS is optional and asserted.** It is reached only to resolve the instance id, and only
when one was not supplied — `--instance-id` or `FREEZEHUB_INSTANCE_ID` and the script never
touches AWS. When it does, it checks the account against `infra/singlebox/terraform.tfvars`
first, for the reason `FZ-175` exists: an apply went into the operator's personal account
and nothing stopped it. A guard that is a sentence in a document is not a guard.

Rollback is the same script with `--image-tag`, which skips the build — what the runbook
already says to do by hand.

Verified by running: `sh -n` clean, and every path exercised with `--dry-run`, which prints
the dispatches and sends nothing. The not-on-origin guard was watched firing on a real
unpushed commit, the frontend-only path confirmed not to reach for AWS at all, and the
full path checked to emit the three dispatches in order with the right inputs. **Not
verified: a real release.** Dispatching one publishes to the live beta, which is the
operator's call and not a test.

## Going to Market

Not a milestone: one story, and it is separate from `Milestone 15` because it is not security
work. Every story it sequences already exists elsewhere; what this adds is the order and the
reason.

### FZ-137 — Validation Plan
**Status:** DONE · **Owns:** `OI-31`, `OI-32`

Specification only, no code: `docs/13-validation.md`.

The question behind it was "what infrastructure and company setup does launching need?", and
the useful answer turned out to be **how little** — so the document is mostly a list of what
is deliberately not built, with the trigger that would change each.

**What the review changed about sequencing.** `FZ-046` is the hard blocker and is easy to
miss, because `FZ-086` is DONE and provisioning appears to work: its own entry records that
outside the `local` profile no Cognito user is created and no invitation is sent, so a
deployment made today is a running system nobody outside the developer's laptop can enter.
Billing, signup and a company are all downstream of it and none is on the path.

**`FZ-135` is what actually comes first, and it is blocked on a question that now has a
name.** That story leaves the region to the operator as "a commercial judgement about who
the product is sold to"; `13-validation.md` §4 is that judgement — local customers validate
the product, foreign ones validate the price, and they are not the same sample. Answering §4
unblocks `FZ-135`, which unblocks `FZ-046`.

**The validation metric is not signups.** It is whether a pipeline keeps calling the Policy
API, because every other claim this product makes depends on that happening (`D-14`, `D-20`,
`D-24`). Nothing needs building to measure it: `FZ-113` already put **Pipelines integrated**
on the dashboard and `FZ-062` registers `freezehub.policy.evaluations`.

**`FZ-099` is on the critical path, which it was not before.** `OI-13` says every guideline
names an image that does not exist, so a design partner must hand-roll the integration from
`examples/` — and asking someone to hand-roll the thing being validated measures the wrong
thing. It is blocked on creating the GitHub organization, which is free.

**Two findings, both raised rather than fixed.** `OI-31`: `FZ-084` is DONE and assumes a
Stripe account that can take payments, and whether a Colombian business can hold one has
never been checked. `OI-32`: production would run in a personal AWS account, and a Cognito
user pool cannot be moved between accounts — the same argument `FZ-135` makes about region,
pointed at the account instead.

Neither blocks validation, which invoices by hand. Both are cheap now and expensive after
`FZ-046`.

**No change to `08-backlog.md`'s Immediate Execution Order**, which describes the original
bootstrap sequence and is history rather than a live schedule. The path lives in the new
document.

### FZ-138 — Who Owns Production
**Status:** DONE · **Resolves** `OI-32` · **How:** `docs/16-accounts.md` (`FZ-164`, corrected by `FZ-168`, `FZ-170`, `FZ-171`)

**Done, and verified against the account rather than asserted.** The estate runs in a
standalone account of its own on `freezehubio@gmail.com`, created through AWS's new sign-up
experience (`D-37`). What `iam:GetAccountSummary` reports:

- **No root access keys** — `AccountAccessKeysPresent: 0`.
- **One IAM user**, and it can do nothing. Its access key is deleted, and
  `iam:*LoginProfile*` is denied by a service control policy, so it cannot be given console
  access either. An inert artefact of the attempt that produced `FZ-170`; deletable.
- **No long-lived credential is used.** `aws login` issues role sessions rotated every 15
  minutes; Terraform reads them through a `credential_process` profile (`FZ-175`).
- **Applying into the wrong account fails before creating anything** —
  `allowed_account_ids` on all six providers, exercised both ways (`FZ-175`).
- **The personal account holds nothing of this product**, verified after `FZ-175`'s
  wrong-account apply was cleaned up.

**One item is unresolved rather than done:** `AccountMFAEnabled` reports `0`. On this
experience sign-in is through AWS Builder ID rather than a root password, so the flag may
simply not apply — but `16-accounts.md` §2 says to enable root MFA and AWS says it is off,
and those cannot both be right. Recorded as `OI-45` rather than waved through: the MFA that
certainly matters, on the Google account behind the Builder ID, is not what this flag
measures.

**What it took, which is worth recording.** Eight stories (`FZ-164`, `FZ-168`, `FZ-170`,
`FZ-171`, `FZ-172`, `FZ-173`, `FZ-175`, `FZ-176`) and five corrections, because every claim
written from documentation was wrong in some way: the account model, the region, the
registrar, the free tier, and which credentials Terraform can read. Each was found by
something being run. The two guards that came out of it — the account assertion and the
credential bridge — are the only parts that make the system catch the mistake instead of a
person.

`infra/README.md` told you to use an IAM role rather than account root and never said which
account any of it belongs in. The target today is the operator's personal AWS account, where
that instruction cannot be followed at all, because there the operator *is* root.

**The argument is `FZ-135`'s, pointed at the account instead of the region.** The
infrastructure is portable and costs nothing to redirect — there is no account id anywhere
in `infra/`, `locals.tf` reads `aws_caller_identity`, and the only use is making the
frontend bucket name unique. What is not portable is identity: a Cognito user pool cannot
be moved between accounts, and `users.external_subject` stores the `sub` it issues. Before
`FZ-046` this is free; after the first real user it is a forced password reset for everyone.

**Done in this story:** `infra/README.md` § *Before the first apply* now names the account
layout, the one-unique-root-email-per-account constraint, and the plus-addressed convention
that satisfies it. That last part is worth writing down rather than discovering: spend the
plain address on production and the management account needs a second mailbox.

**Blocked on one human action**, in the shape `FZ-099` uses:

1. **A standalone AWS account** on `freezehubio@gmail.com`, upgraded to the Paid plan, with
   root secured by MFA and no root access keys, and an IAM user whose only permission is to
   assume an MFA-gated administrator role for Terraform.

**This was two actions and an Organization until `FZ-168`.** Joining an Organization expires
a new account's free credits immediately — about $200, or eleven months at `D-35`'s $18 a
month — so it is deferred to a named trigger (`D-36`). The free-tier question this entry
used to ask is answered there, and the answer is the reason the shape changed. The old
twelve-month model it assumed no longer exists either.

**Not done here, deliberately:** nothing is applied and no account is created. This story
makes the decision legible and leaves it where `D-23` leaves provisioning — with an operator
who has production access, which is the point of the change.

**`OI-32` arrived with `FZ-137`** (PR #42), which landed first; this branch was cut from
`master` before it. Merging `master` in brought the entry, and its **Owner** now reads
`FZ-138` rather than *needs a story*.

### FZ-141 — The Region Is `us-east-1`
**Status:** DONE · **Resolves** `OI-20` · **Unblocks** `FZ-046`, `FZ-123`

`FZ-135` did everything about the region that was not the decision — it removed the default
so nobody could inherit a region by accident, and it priced what the choice costs. The
decision itself was the operator's, and it has been made: **`us-east-1`**, recorded as
`D-32`.

**It goes against `FZ-135`'s own recommendation**, which was `eu-west-1`, and the decision
record says so rather than smoothing it over. The asymmetry that story argued — EU buyers
frequently require EU residency, US buyers rarely require US residency — is still true. It
was outweighed by sequencing: `13-validation.md` §4 expects the first design partners to be
Colombian or US, and validation is about finding out whether anyone wires a pipeline at all,
not about passing a review no prospect has asked for.

**The cost is accepted, not deferred.** An EU buyer's questionnaire now gets the answer
"United States", and a later change is a migration of identities rather than of data: a
Cognito user pool is region-bound and `users.external_subject` stores the `sub` it issues.
That cost is near zero today and becomes real the moment `FZ-046` creates the pool — which
is why `D-32` says to revisit *before* that story if an EU prospect appears sooner.

One convenient consequence worth noting: every cost figure in `infra/README.md` was measured
against `us-east-1`, so they stay honest. Choosing `eu-west-1` would have meant re-taking
them.

Changed: `D-32`, `FZ-135` to DONE, `OI-20` resolved, and `terraform.tfvars.example` now
carries `us-east-1` with the decision referenced beside it.

**Nothing is applied.** This unblocks `FZ-046` and `FZ-123`; it does not do either.

## Milestone 16 — The Free Tier

`FZ-080` built a commercial model that begins at $99 and ends a trial in a read-only account.
This milestone adds the plan below it, and the one capability that separates them.

**Order.** `FZ-142` first — it is the specification the rest read. `FZ-143` before everything
after it, because the `FREE` constant is what they all reference.

```text
FZ-142 ── FZ-143 ──┬── FZ-144
                   ├── FZ-145
                   └── FZ-146
```

Nothing here needs AWS. Every story runs against Testcontainers.

### FZ-142 — Freemium Packaging
**Status:** DONE · **Owns:** `OI-34`

Specification only, no code: `docs/11-commercial.md` §3 and §4, plus `D-33`.

**The decision that shapes everything else: gate the *level*, not the answer.** A free
organization cannot create a `HARD_FREEZE`, so the row never exists — rather than existing
and being ignored at evaluation time, which `D-21` already rejected as the silent-`ALLOW`
failure in disguise. `PolicyService`, `01-domain.md` and every connector are untouched, and
that is the point rather than a happy accident.

**`D-33` supersedes `D-21` narrowly and says so.** `D-21` rejected degrading a restriction
that already exists, as a punishment for non-payment. This is published plan behaviour where
no blocking freeze is ever created in the first place. If a later change downgrades an
existing `HARD_FREEZE` for a billing reason, it has re-broken `D-21` and `D-33` does not
authorise it.

Three choices inside the plan that are easy to get wrong, with reasons: freezes announced
stay **unlimited** (capping them caps what sells the product), the one destination may be
**Slack** (email-only sends announcements where they go to die), and applications are **five,
not three** (a squad needs to be able to succeed, and `D-14` makes the limit self-enforcing).

### FZ-143 — The FREE Plan and Its Capability Gate
**Status:** DONE

`Plan.FREE(5, 1, 1, 7)` plus a capability the enum does not yet have.

Acceptance:

- `Plan` gains a `hardFreeze()` capability. Every existing plan returns true; `FREE` false.
- Creating a `HARD_FREEZE` restriction on `FREE` is refused with `402`, Problem Details.
- **The refusal names a capability, not a count.** Reusing `PlanLimitExceededException`
  renders *"your FREE plan allows 0 blocking freezes, and you are using 0"*, which is worse
  than the truth. A sibling exception on the same `@ExceptionHandler` says what happened.
- Creating an `ADVISORY` restriction on `FREE` succeeds.
- Changing an existing restriction's level to `HARD_FREEZE` is refused the same way — the
  guard belongs on the write path, not only on create.
- `PolicyService` is not modified, and there is a test asserting a `FREE` organization's
  evaluation is byte-for-byte what the same persisted state produces on any other plan.

**Found by the tests, not by reading: the plan set is enforced in the database too.**
`018-subscription.yaml` puts a `CHECK (plan IN (...))` on the column, so adding an enum
constant is a schema change and not only a Java one. Every test failed on
`chk_subscription_plan` before `024-subscription-free-plan.yaml` existed. Nothing else would
have caught it — the enum compiles, the service is correct, and the row is simply refused at
insert. This is the reason the check is worth having and the reason the test came first.

**The guard sits in `validateRequest`**, which both `create` and `update` already call, so
the write path is covered once rather than twice. `update` re-validates in full by design
(its own comment says an update can never leave a restriction in a state creation would have
rejected), and that property is what made this a one-line change instead of two.

### FZ-144 — Trial Expires Into Free
**Status:** DONE

`TrialExpiryScheduler` currently sends an expired trial to `SUSPENDED`. It sends it to plan
`FREE`, status `ACTIVE`.

Acceptance:

- An expired trial becomes `FREE`/`ACTIVE`, and the organization keeps working.
- **A paid subscription that stops paying still becomes `SUSPENDED`**, and there is an
  explicit test for it. This is the criterion the story exists for: a paying customer falling
  to `FREE` would silently stop enforcing their hard freezes, which is the failure `D-21`
  calls worse than an outage arriving as a side effect of billing.
- Per `D-22`, nothing is retroactive: trial-created hard freezes keep their level, trial
  API keys keep working, and the organization keeps applications above the `FREE` limit.
- The transition is audited, as every other subscription change is.

**`suspendExpiredTrials` is now `expireTrials`**, because it no longer suspends anything and
a method named for what it used to do is how the next reader gets it wrong.

**The criterion that matters is a query, not a branch.** `findExpiredTrials` filters on
`TRIALING`, so a paying subscription cannot be swept here at all — there is no code path
from "stopped paying" to `FREE`. `aPaidSubscriptionIsNeverSweptToFree` pins that by
activating a subscription onto `GROWTH` with a trial end long past and asserting the sweep
leaves it alone. It is the test most worth keeping, because a later change to the query is
what would break it silently.

**The audit action is `SUBSCRIPTION_PLAN_CHANGED`, not `SUBSCRIPTION_SUSPENDED`.** It is
what happened, and the old value would make the trail claim an outcome that no longer occurs.

**One existing test changed rather than moved.** `SuspensionTest.anExpiredTrialIsSuspendedAndAudited`
asserted the behaviour this story reverses. It is now `anExpiredTrialIsNoLongerSuspended` and
keeps the boundary that class owns — a trial ending must not produce a suspended organization
— while `TrialExpiryTest` covers the whole transition.

### FZ-145 — What the Build Log Says
**Status:** DONE

The conversion mechanism, and one string.

When a `FREE` organization's advisory restriction matches, `PolicyEvaluationResponse.message`
says the freeze is active *and* that this deployment would be refused on a paid plan.
`freeze-check.sh` prints it verbatim on `ALLOW`, so no connector changes.

Acceptance:

- The clause appears only when a restriction actually matched, so the common
  allow-with-nothing-matched path takes **no additional query**. The Policy API is the one
  endpoint that must not get slower.
- It appears only for `FREE`. No other plan's message changes.
- The `decision` is `ALLOW` and the matched restriction is returned at its real `ADVISORY`
  level. Nothing is misreported to make the sales point.

**The short-circuit is the design, not an optimisation.** `advisoryOnFreePlan` returns
`false` before reading anything when nothing matched or the deployment was blocked, so the
usual answer — no restriction in force — costs exactly what it cost before. A test covers
that path explicitly, because a guard nobody exercises is a guard that quietly stops being
one.

**A blocked free organization is told nothing.** It has a hard freeze surviving from its
trial (`D-22`) and is already getting what a paid plan sells; an upsell there would be
noise.

**`explain` still has one implementation.** The preview a person sees inside the product and
the line a pipeline prints come from the same method, including this clause — `FZ-120`'s
reasoning holds, and a sentence that appeared only in CI would be a second answer to the
same question.

### FZ-146 — The Free Tier in the Product
**Status:** DONE

Frontend. `FZ-085` already renders plan, limits, usage and the trial countdown from
`GET /api/billing/subscription`, and composes a `402` into a sentence at the point the error
is built — so most of this is the new refusal rendering correctly rather than new screens.

Acceptance:

- The Billing section shows `FREE` as a plan rather than as an absent subscription.
- The capability refusal from `FZ-143` renders as what it is, with the upgrade path — not as
  a generic error.
- **The create-restriction form does not hide the blocking option on `FREE`.** It shows it,
  refused, with the reason. Hiding it hides the product's best sales argument at the exact
  moment somebody wants it.
- Nothing in the UI decides entitlement; every limit shown comes from the backend.

**Most of this was already true, and saying so is the honest part of the story.** `FZ-085`
renders the plan, its limits and its usage from the backend, and composes a `402` into a
sentence where the error is built — so `FREE` already appeared as a plan, the create form
already showed the blocking option rather than hiding it, and the refusal already rendered
as its own message rather than a generic error. Three of the four acceptance criteria needed
no code.

**What was actually missing was narrower and worth finding.** `planLimitFrom` requires
`typeof body.limit === 'number'`, and a capability refusal deliberately sends no numbers
(`FZ-143`) — so it fell past the composition that appends *"Upgrade under Settings →
Billing"*. **The one `402` a free organization actually meets was the only one in the product
with no way out on it.** A sibling parse fixes it, and keeps refusing to invent a count:
`planLimit` stays null, so nothing can render a usage bar for something with no usage.

**The capability comes from the backend, not from the plan's name.** `SubscriptionView`
gained `blocksDeployments`, because a UI deriving it from `plan === 'FREE'` would be the
frontend deciding entitlement — the thing every other limit on that endpoint avoids.

Billing now states the distinction in words, which nothing did before: the counts were all
visible and the one capability separating `FREE` from Starter was not mentioned anywhere.

### FZ-148 — Which Rail Takes the Money
**Status:** DONE · **Answered:** `OI-31` · **Decided in:** `D-34`

`FZ-084` built Checkout, the Customer Portal and a signature-verified webhook, and it is
correct. It also assumes a Stripe account that can accept payments, and **the operator cannot
have one** — verified against Stripe's own availability page rather than assumed either way:
Brazil and Mexico are supported in Latin America, Colombia is absent, and Stripe's support
material states that payments are not supported there.

**Decided: Paddle, as merchant of record** (`D-34`). Of the three routes, it is the one that
removes work rather than adding an entity — Paddle registers for and remits US sales tax and
EU VAT itself, and is the seller on the customer's receipt. A US Stripe account would have
kept `FZ-084` untouched and handed a solo founder tax registration wherever a customer
happens to be.

**Checked before recommending, because this entry exists to correct exactly that mistake.**
Paddle publishes the countries it cannot support suppliers from and Colombia is not among
them. That is absence from an exclusion list rather than an explicit statement of support,
and Paddle vets suppliers — so the account wants opening and approving **before** `FZ-149` is
scheduled, or the same class of surprise happens twice.

The cost is roughly double the transaction fee — about 5% + $0.50 against 2.9% + $0.30, which
on the Stage 3 projection is ~$1,120 a month rather than ~$650. That buys tax liability in
every jurisdiction the product sells into.

**Nothing was implemented here.** The decision needed an input, has one, and the code is
`FZ-149`.

### FZ-149 — Paddle Replaces Stripe
**Status:** TODO · **Decided by:** `D-34` · **Not scheduled:** see below

Replaces `FZ-084`'s integration. Checkout sessions, the Customer Portal, the event shapes and
the `stripe-java` dependency all go; `pom.xml` loses a dependency and nothing replaces it,
because Paddle publishes no official Java SDK and the integration is plain HTTP plus an HMAC
this codebase already computes in two other places.

**What must survive, verbatim in behaviour if not in code:**

- **Entitlement changes only from a signature-verified webhook.** Never from the redirect the
  browser follows after paying, which anyone can forge. `FZ-084` has a test that forges the
  redirect and proves it grants nothing; that test is rewritten, not dropped.
- **Its own security chain**, matching the webhook path and nothing else, with no CORS. A
  credential that works on one boundary must not work on another (`FZ-052`).
- **Deliveries are idempotent** by stored event id. Paddle retries like Stripe does.
- **Every subscription change is audited**, with the provider as the actor.

Paddle signs with `Paddle-Signature` — HMAC-SHA256 over timestamp and body — the same
construction as `D-2` pointed inward, so the verification is a rewrite of similar size rather
than a new idea. The four defects `FZ-084` found are worth re-reading before starting: a null
signature header arriving as `500`, `@PrePersist` not firing on an assigned id, idempotency
by caught constraint violation failing inside a transaction, and an SDK deserialiser silently
returning empty on an API-version mismatch. Three of those four are not Stripe-specific.

**Deliberately not scheduled.** `13-validation.md` §3 invoices the first customers by hand so
that no payment rail sits on the critical path, and that is still the plan. This becomes due
at the **first self-serve payment** — which needs `FZ-082`, which needs `FZ-046`. Building it
now would be building against an account that does not exist yet for a flow nobody can reach.

**Blocked on one human action first:** a Paddle account, opened and approved. `D-34` says why
that comes before the code rather than after it.

### FZ-150 — The README Still Argued for the NAT
**Status:** DONE

`D-28` removed the NAT gateway. `infra/README.md` did not follow, and it is the document an
operator reads immediately before `terraform apply`.

It listed the NAT at ~$32 in a table totalling ~$100, named it among the "deliberate cost
choices for beta", and closed with a paragraph arguing that removing it *"would weaken that
boundary"* — **the opposite of the decision already recorded.** A wrong figure is a
nuisance; a paragraph arguing against a settled decision, at the point of applying it, is how
somebody re-adds a $32 line believing they are being careful.

Corrected to the decided posture: two tasks in a public subnet, no NAT, **~$64/month**. The
reasoning is now `D-28`'s — the boundary moves from the subnet to the security group rather
than disappearing, the database stays private either way, and the trade is right for a
pre-customer beta and worth revisiting when there is something to protect.

**The IPv4 charge is stated rather than buried.** Removing the NAT means paying for a public
address on each task — about $3.65 a month each, roughly $7 the private-subnet posture did not
pay. Whether the load balancer's own addresses are billed the same way is flagged as worth
confirming, because at this scale it is the difference between ~$64 and ~$71.

**Not `FZ-123`'s work, though it was twice deferred there.** That story applies the
infrastructure; this only makes a document match a decision taken weeks earlier. Leaving them
disagreed until `FZ-123` runs would mean the wrong number sat in front of every reader in the
meantime, and `FZ-123` is blocked on an AWS account that does not exist yet.

## Milestone 17 — One Box

`FZ-063` designed an ALB-and-ECS environment and it has never been applied. `D-28` trimmed it
to ~$64 a month; `D-35` decided that the beta launches on **a single instance at ~$18**, and
that the existing Terraform is what it graduates to rather than something replaced.

The point is not the money. Nothing is deployed, and a deployment somebody can stand up in an
afternoon gets design partners in front of the product sooner.

**Order.** `FZ-152` before `FZ-153` before `FZ-154`. `FZ-155` and `FZ-156` can be written
alongside but neither is optional: a box with no tested restore and no runbook is a box that
will be rebuilt from memory at the worst possible time.

```text
FZ-151 ── FZ-152 ── FZ-153 ── FZ-154
                        ├──── FZ-155
                        └──── FZ-156
                              FZ-157  (documented, not built)
```

**Blocked on the same human action as everything else:** an AWS account (`FZ-138`). This
milestone changes what gets applied into it, not whether it is needed.

### FZ-160 — Is FZ-123 Still the Plan
**Status:** DONE · **Owns:** `OI-35`

Backlog hygiene, no code.

**The defect this story set out to fix was fixed twice.** `FZ-123` named `FZ-121` and `FZ-122`
as blockers after both shipped, hiding an available story behind a dependency that no longer
existed. `FZ-159` corrected it from one direction — re-reading the story — and this one found
it from the other, by recomputing every blocker from real statuses (`FZ-147`). `FZ-159`
landed first and its wording is better, naming *why* it is blocked and resolving `OI-21` as
well, so that line is left exactly as it stands.

**What neither fixed is the larger question**, and it is the reason this story survived rather
than being closed. Milestone 17 deploys on a single box; `FZ-157` documents graduating to ECS
as a later step; `FZ-123` still describes applying the ECS posture. Whether it is superseded,
or is the ECS apply that follows the beta, is a sequencing decision nobody has made — and an
open story describing a plan that may have been abandoned is worse than either answer.
Recorded as `OI-35` rather than decided here.

**Two sessions finding the same stale line independently is the useful part.** It is evidence
the field goes unread rather than that one reader was careless, which is an argument for
`FZ-147` running before a story starts rather than for being more careful.


### FZ-151 — Deploy on One Box
**Status:** DONE

Specification only: `D-35`, and this milestone.

The measurements are what make it defensible rather than a hunch. `D-28` put the JVM's
working set at **353 MiB**; the growth model puts *Stage 3* — 75 paying customers and 1,500
free organizations — at about **0.3 requests a second**. One small instance carries the whole
projection.

**Caddy is what saves the money, not the box.** Automatic Let's Encrypt removes the load
balancer, which was the largest line left after `D-28` removed the NAT.

**Kept unchanged: S3 and CloudFront, Cognito, ECR, Route 53, the GitHub OIDC role.** That is
what makes `FZ-157` small — only the compute and database tier moves.

### FZ-152 — The Box
**Status:** DONE · **Not applied** — needs `FZ-138`

Terraform for one instance, in `infra/singlebox/`, separate from `infra/` so neither is
half-applied by accident and `FZ-157` is a switch rather than a rewrite.

Acceptance:

- One `t4g.small` (ARM64, matching the image CI already builds) in the **default VPC**, with
  an Elastic IP so the address survives a stop.
- **Security group: 80 and 443 inbound, nothing else.** No port 22.
- **IMDSv2 required, `http_put_response_hop_limit = 1`.** Not a default worth inheriting:
  it is what keeps `OI-23`'s SSRF away from instance credentials.
- An instance profile scoped to exactly three things — pull from this ECR repository, read
  this environment's SSM parameters, write to the backup bucket. Nothing wildcarded.
- Encrypted EBS, unattended security upgrades, SSM agent enabled.
- A Route 53 A record pointing at the Elastic IP.
- `terraform fmt` and `validate` clean in CI, like the rest of `infra/`.

### FZ-153 — The Stack
**Status:** DONE · **Cannot run until** `FZ-046`

`docker-compose.yml` on the box: Caddy, the backend, PostgreSQL 16.

Acceptance:

- **Caddy terminates TLS** with automatic Let's Encrypt and proxies to the backend. No
  certificate in the repository and none to renew by hand.
- **PostgreSQL is never published to the host.** It listens on the Compose network only.
  Publishing 5432 from a public instance is the mistake this bullet exists to prevent.
- A named volume for the database, on the encrypted EBS volume.
- **Secrets are read from SSM Parameter Store at boot** — the database password and
  `freezehub.secrets.encryption-key`. Never in the compose file, the image, or the repository.
- The backend runs with `SPRING_PROFILES_ACTIVE` set to something that is **not** `local`, so
  the dev sign-in endpoint and its fake `IdentityProvider` cannot exist (`FZ-035`, `OI-2`).
- Container health checks, with Compose configured to wait for healthy on start.
- One replica, and the resulting **~15 second gap on deploy is documented, not hidden**. Two
  replicas remove it and need a 4 GB instance; `FZ-121` already made running two safe.

**Confirmed by running it, not by reading the YAML.** The jar was started under
`SPRING_PROFILES_ACTIVE=beta` with the environment this stack supplies, against a real
PostgreSQL. Two things came out of it:

- **`FREEZEHUB_SECRETS_ENCRYPTION_KEY` does bind to `freezehub.secrets.encryption-key`.**
  Spring's relaxed binding handles the underscore-to-hyphen mapping, which was worth
  proving rather than assuming — `FZ-063`'s ECS task definition uses the same name, so a
  wrong guess would have been wrong in both postures at once and discovered on a first
  deploy.
- **The application then failed exactly where `OI-2` says it will**, on
  `No qualifying bean of type 'IdentityProvider'`. So the box cannot serve anything until
  `FZ-046` ships. That was already written down; it is now executed.

**`server.forward-headers-strategy` is deliberately not set here.** It is already `framework`
in the default document and `none` only under `local`. The rate limiter depends on it
(`FZ-087`), and configuration that load-bearing belongs in one place rather than two that can
drift.

**The port is Spring's default 8080, not 8099.** `server.port: ${SERVER_PORT:8099}` lives
inside the `local` profile document, below the `---` at line 99, so it does not apply here.
The `EXPOSE 8080` in the Dockerfile is right and the local port is the exception.

### FZ-154 — Deploy Without SSH
**Status:** DONE · **Not executed** — needs `FZ-138`, `FZ-152`

A GitHub Actions job that deploys by **SSM Run Command**, reusing the OIDC role
`github-oidc.tf` already creates.

**No SSH, deliberately.** It removes the open port, the key somebody has to hold, and the
question of who still has a copy. Every deploy becomes an IAM-authorised API call recorded in
CloudTrail.

Acceptance:

- Manual dispatch, like `deploy.yml`: publishing changes what customers reach.
- The job authenticates by OIDC, sends one command, and **fails the workflow if the command
  fails** — a deploy that reports success because the API call was accepted is worse than one
  that reports nothing.
- The command pulls the pinned image tag and restarts the stack. Never `latest`, for the
  reason `FZ-099` gives: a moving tag changes behaviour on a day nobody touched it.
- The workflow prints how to read the logs on failure, pointing at `FZ-156`.
- Rollback is redeploying an earlier tag, and that is written down.

### FZ-155 — Backups, and a Restore Somebody Has Run
**Status:** DONE · **Resolves** `OI-46` · **Drill performed:** 2026-09-21

Nightly `pg_dump` to S3, with lifecycle expiry.

**This story is not done when the backup runs. It is done when a restore has been performed
into a scratch database and the result checked.** An untested backup is the classic way to
discover there was none, and a single box has no automated snapshots to fall back on.

Acceptance:

- A nightly dump, encrypted at rest, in a bucket the instance role may write and not read
  back broadly.
- Lifecycle expiry so it does not grow without bound.
- A failed backup is **visible** — a silent one is the same as no backup.
- **A documented restore, executed once, with the command and its output recorded in
  `14-operations.md`.**

**The script exists and the story is still open, deliberately.** `deploy/backup.sh` and its
systemd units are written; the acceptance criterion that matters — *a restore somebody has
performed* — cannot be met until there is a box with a database on it. Marking this DONE on
the strength of a script that has never produced a file anyone restored would be exactly the
failure the criterion exists to prevent.

Two things the script does that a naive `pg_dump | aws s3 cp` does not:

- **It refuses to upload a dump under 1 KiB.** An empty or truncated dump uploads perfectly
  happily and restores into nothing. The size check is the difference between a backup and
  a file.
- **It reports failure to the journal at `user.err`**, not just to stderr. A backup that
  fails silently is worse than no backup, because it removes the reason to check.

### FZ-156 — The Operations Runbook
**Status:** DONE · **Unverified against a running box** — needs `FZ-138`

`docs/14-operations.md`. Symptom-driven, not tour-driven: somebody reading it is already
having a bad morning.

Sections, each starting from what was observed rather than from a component:

- **"The site is down"** — reaching the box through SSM Session Manager, `docker compose ps`,
  and what a container in a restart loop looks like.
- **"A customer's pipeline is failing"** — the Policy API path, and how to tell a real
  refusal from an outage. `freeze-check.sh` fails closed, so those look identical from the
  customer's side and are opposite problems.
- **"Notifications are not arriving"** — the outbox, the dispatcher, and `notification` rows
  in a terminal state.
- **"A deploy failed"** — reading the SSM command output, and rolling back to a prior tag.

**It must explain `X-Request-Id`.** `FZ-062` puts a correlation id on every log line and in
every error body, so a customer quoting one turns an anecdote into an exact log lookup. That
is the single most useful thing in the runbook and nothing else in the repository says it.

Also: where Caddy's access log is, where PostgreSQL's log is, and how to widen the backend's
log level temporarily without a redeploy.

**Written from the code, not from memory of how systems like this usually behave.** The
integration shapes were read out of `IntegrationConfigs.validate`, the notification states
out of `NotificationStatus`, the log pattern out of `application.yml`, and the email
condition out of the comment that explains why `freezehub.notifications.email.from` is
deliberately not declared.

**The Slack and email section exercises the real path** — outbox row, dispatcher sweep,
sender — rather than asserting that configuration looks right. Two failures it names because
they cost the most time and produce no error:

- **Email with no from-address does nothing, silently.** The sender is
  `@ConditionalOnProperty` on it, so an unset value means no sender is registered, the
  notification defers rather than fails, and the outbox looks healthy. Check the environment
  before the logs.
- **A revoked Slack webhook and a typo'd one are indistinguishable**, because the path
  segment of the URL is the secret and both return `404`.

**It also separates "we are down" from "the product said no".** `freeze-check.sh` fails
closed, so those are identical from the customer's side and are opposite problems — the
section says to ask for the request id and whether the message said `BLOCK` before
reassuring anyone.

**Unverified against a running box**, because there is not one. Every command is derived from
the compose stack in `FZ-153` and the code, and the first real incident will find whatever is
wrong with it.

### FZ-157 — Graduating to ECS
**Status:** DONE · **Documented, not executed**

The route off the box, written down while the reasons are fresh rather than discovered under
pressure. `D-35` names three triggers: the first paying customer, an availability commitment,
or a security review asking about isolation.

It is small because most of the estate never moved: **the SPA, Cognito, ECR, Route 53 and the
OIDC role are identical in both postures.** What changes is the compute and database tier.

The shape, to be written rather than executed:

1. Apply the existing `infra/` into the same account.
2. Restore the latest dump into RDS and verify row counts against the box.
3. Move one Route 53 record from the Elastic IP to the load balancer.
4. Keep the box, stopped, until the new one has served a full business day.

The `SPRING_DATASOURCE_*` overrides the backend already honours are what make step 2 a
configuration change rather than a code one.

Written as `docs/15-migration.md`, its own document rather than a section of the runbook.
`FZ-156` owns that file and was unmerged when this was written, and `CLAUDE.md` §9 says not
to branch one story from another — so the two merge in either order and neither waits.

**What makes the migration small is what `FZ-152` deliberately did not create.** The SPA
bucket, CloudFront, Cognito, ECR, the hosted zone and the OIDC role all live in `infra/` and
are shared by both postures. Only the compute and database tier moves.

**The step that needs care is the cutover, not the data.** `SPRING_DATASOURCE_*` is already
an override the backend honours, so pointing it at RDS is configuration rather than code.
What is not automatic is that the box keeps accepting writes until DNS moves — so the dump
has to be taken *after* it stops serving, which is why the order in the document is stop,
dump, restore, verify, then move the record.

### FZ-159 — Split the Estate So the Cheap Posture Is Actually Cheap
**Status:** DONE · **Fixes a flaw in** `D-35` / `FZ-152`

`FZ-152` said it deliberately created no Cognito, SPA bucket, registry or deploy role
"because `infra/` owns them". **`infra/` could not be applied in part.** It held the ALB,
the ECS service, RDS, the NAT and the VPC in the same root module, with no toggle — so
"apply `infra/` for the shared half, then `singlebox/`" meant standing up the entire
$96 estate *and* the box. The $18 figure in `D-35` only ever held if the shared estate
could be applied on its own, and it could not.

Found by re-reading `FZ-123` rather than by anything failing, which is the uncomfortable
part: every document said the estates were separable and none of them was wrong about the
intent.

Split into three root modules, each applicable whole:

| | Holds | Applied on |
|---|---|---|
| `shared/` | Cognito, SPA bucket and distribution, ECR, the GitHub deploy role | both postures, first |
| `singlebox/` | One instance running Caddy, the backend and PostgreSQL | the beta |
| `ecs/` | ALB, Fargate, RDS, and their VPC | after `FZ-157` |

**The deploy role was the awkward part.** It needs ECR push (shared), SSM for the box
(`FZ-154`), the SPA bucket and CloudFront (shared), *and* `ecs:UpdateService` with
`iam:PassRole` on the task roles. The role now lives in `shared/` with everything
posture-independent, and `ecs/` attaches a second inline policy to it by name. That is what
lets the role exist on a posture where no ECS service does.

**Cross-module values are variables, not a `terraform_remote_state` data source.** Reading
another module's state couples a module to where that state lives and to its schema; these
are four values pasted from `terraform output`, and being explicit about them is worth more
than saving the paste.

**Separate state files, deliberately.** One state for the shared and ECS halves would mean
destroying the compute estate could not be planned without the user pool and the registry in
the same plan — which is exactly the failure this story exists to fix, in a different shape.

**Timing is the reason this was worth doing immediately.** Nothing is applied, so this is a
file move. After a first apply it is a `terraform state mv` exercise across three states.

Also rewrote `FZ-123`, which still described applying `infra/` and still named `FZ-121` and
`FZ-122` as blockers after both shipped.
## Milestone 18 — Selling It

Customer-facing and sales material. Distinct from `Going to Market`, which is launch
readiness: that milestone asks what must exist before anyone can sign in, this one asks what
must be said before anyone will want to.

Lives in `gtm/`, not `docs/`. The numbered documents decide what gets built; these decide
what gets said, and confusing the two produces a roadmap driven by a pitch deck.

### FZ-158 — Positioning, Objections, Discovery
**Status:** DONE

`gtm/01-positioning.md`, `02-objections.md`, `03-discovery.md` — the source every other piece
of sales material derives from. Internal: they name what the product cannot do yet, which is
essential for selling honestly and unhelpful read over a customer's shoulder.

**The spine is the four questions in `11-commercial.md` §1**, and the line that orders
everything else: the first two make it used daily, the last two make it renewed. So a demo
opens on *is there a freeze* and *does it apply to me*, and closes on *did anyone deploy
anyway* and *who lifted it* — the two a Slack channel cannot answer.

**No claim that is not true today.** No logos, testimonials, case studies or usage counts.
There are no customers, and §1 of the commercial model already notes that a technical buyer
tests claims. `01-positioning.md` §8 is the list of what cannot be said: no public signup, no
applied deployment, demos local, pilots hand-provisioned, billing invoiced.

**Two findings from checking claims rather than asserting them.** The connector does not
simply fail closed — `FREEZEHUB_ON_ERROR` defaults to `block`, and a setup problem is never
subject to that switch, so enforcement cannot be disabled by breaking it. That is the
stronger answer and it is the one written down. And the category does not exist: nobody
searches for "deployment freeze management", so discovery opens on the situation and adopts
the prospect's own word for it.

Acceptance:

- Every factual claim is checkable against `docs/`, `connectors/` or a decision record.
- Objections concede the true part first, and none answers with a roadmap promise.
- Qualification names who to walk away from, not only who to pursue.
- Nothing claims a capability that is not built.

## Milestone 19 — Data Protection

The operating company is Colombian, so Ley 1581 de 2012 applies to everything the product
holds. GDPR mechanics are specified alongside it because signup is self-serve and a product
anyone can sign up for does not get to choose where its data subjects live.

### FZ-161 — Data Protection Specification
**Status:** DONE

Specification only, no code: `docs/17-data-protection.md`.

**Two roles, and the document turns on the difference.** FreezeHub is *encargado* for what a
customer puts in — `users`, `audit_event`, `deployment_check`, the catalog — and *responsable*
for what it collects itself: `demo_request`, signup, billing, logs. A subject request arriving
against the first is forwarded to the customer; against the second it is answered directly.
`demo_request` is the sharp edge, being the one table with no `organization_id` and the only
place FreezeHub holds data about someone who never became a customer.

**The GDPR reasoning does not transfer, and that is the finding.** Ley 1581 Art. 9 makes
*autorización previa, expresa e informada* the general basis — there is no broad
legitimate-interest basis to lean on — and Art. 8(b) gives the titular the right to demand
**proof** of it. So the authorization must be stored: the timestamp, and the exact text shown
at the time. `demo_request` has no column for either, which makes it a schema change rather
than a checkbox (`FZ-163`).

**`us-east-1` is a transmisión, not a transferencia.** AWS and Paddle are encargados
processing on FreezeHub's behalf, so Decreto 1377 Art. 25 governs and the route is a contrato
de transmisión rather than an adequacy finding. That is a materially easier path than the
Art. 26 transfer prohibition, and it is why the section says so explicitly.

**Written against the deployment that exists.** There is no RDS: PostgreSQL 16 runs in Compose
on the box with its volume on encrypted EBS, secrets come from SSM Parameter Store, and
backups are a nightly `pg_dump` to S3 whose restore has never been drilled (`FZ-155`). An
untested restore is also how an erasure claim turns out to be untrue.

Ten gaps are recorded in §7 with owners, `FZ-162` through `FZ-168`. None is implemented here.

Acceptance:

- Every claim about current behaviour is read from the code, the schema or `infra/singlebox/`.
- Roles are assigned per store, not per product.
- Retention and erasure behaviour is stated per class, including where erasure is refused.
- Limits are stated rather than left to be discovered — free text, billing, backups.

### FZ-162 — Repair the Split FZ-123 Entry
**Status:** DONE · **Resolves** `OI-35`

`FZ-159` rewrote `FZ-123` to say the beta applies `shared/` then `singlebox/` rather than
the ECS estate. A later conflict resolution split that entry in half: the heading kept the
**new status line** and the **old body**, and the rewritten body was left orphaned thirty
lines down, under `## Milestone 14 — What Beta Found`, with no heading of its own.

So the backlog simultaneously said `FZ-123` was the ECS apply *and* contained an unattributed
block explaining that it was not — which is exactly what `OI-35` then recorded as "both
readings are live". The issue was real; its cause was a merge, not a decision nobody had
made.

**Nothing failed and nothing flagged it.** Markdown has no structure to violate, so a
paragraph under the wrong heading renders perfectly. It was found by reading `OI-35`,
following it to `FZ-123`, and noticing the body did not match the status line above it.

Worth recording because it is the second time this file has been damaged by a conflict —
`FZ-150` hit the same class, where a stale branch carried an older version of an entry that
had since been rewritten. Both times the diff looked plausible. A backlog entry whose status
line and body disagree is the signature.

Resolves `OI-35`: `FZ-123` is the beta apply, and the restored body says so.

### FZ-163 — Each Module Gets Its Own tfvars Example
**Status:** DONE

`FZ-159` split the estate into three root modules and left `terraform.tfvars.example` at the
old root, belonging to none of them. `infra/README.md` then told you to copy it *upwards*
into `shared/` — which works, and hands that module a file full of `db_instance_class`,
`backend_cpu` and `backend_desired_count`, none of which it declares. Terraform warns on
on an undeclared value rather than failing, so the first apply of the project would have been
preceded by a wall of warnings that are not wrong about anything.

It is a small thing that sits in an expensive place: it is the **first file anybody touches**
when applying, and warnings on a first apply are exactly when a person cannot tell noise from
a real problem.

Now one per module, each carrying only what that module declares:

- `shared/` — region, domain, hosted zone, and the repository the OIDC trust policy is scoped
  to. Applied first, because both compute postures read its outputs.
- `ecs/` — the same three, plus the four values pasted from `shared`'s outputs, with the
  exact `terraform -chdir=../shared output` commands in a comment above them.
- `singlebox/` — unchanged from `FZ-152`.

`ecs/`'s header says plainly that the beta runs on `singlebox/` and that applying both means
paying for both — the same trap `FZ-159` fixed in the Terraform, restated where somebody
about to run `apply` will actually read it.

### FZ-164 — How to Create the Accounts
**Status:** DONE · **Serves** `FZ-138` · **Corrected by** `FZ-168`

> **Superseded in part.** This entry describes the guide as first written: two accounts in
> an Organization, Identity Center, `OrganizationAccountAccessRole` as break-glass. AWS does
> not permit that on a new account, and `FZ-168` replaced it with one standalone account. The
> CloudTrail and Route 53 findings below still stand.

`FZ-138` says *what* must exist — an Organization, a production member account, root secured,
a role for Terraform. It does not say how, and "create an AWS Organization" is an hour of
console archaeology for somebody who has not done it before. `docs/16-accounts.md` is the
sequence.

**The decision inside it is Identity Center rather than an IAM user.** The obvious path is an
IAM user with an access key in `~/.aws/credentials`, and it is the wrong one for the same
reason `infra/README.md` gives about root keys: a long-lived credential on a laptop cannot be
scoped down after issue, cannot be rotated without coordination, and survives the laptop
being lost. Identity Center is free, issues temporary credentials, and is the piece that does
not need redoing when a second person arrives.

**It admits what it compromises on.** Terraform runs as an administrator-equivalent role,
because Terraform creates IAM roles and policies — so a least-privilege Terraform role needs
`iam:CreateRole` and `iam:PutRolePolicy`, which is escalation to anything it can create.
Saying that plainly is better than a policy that looks scoped and is not. What makes it
acceptable is the credential being temporary, MFA-gated and recorded; `D-23`'s trigger for
revisiting is the same one — somebody who should not be an administrator needing to run it.

**It documents break-glass and says to test it now.** `OrganizationAccountAccessRole` from
the management account, and production root with its MFA device. A recovery path nobody has
walked does not exist, which is `FZ-155`'s argument about restores applied to access.

**One claim elsewhere in the repository depended on something nobody had checked.** `FZ-152`
and `FZ-154` both say deploys are "recorded in CloudTrail". CloudTrail Event history is on by
default, free, and retains **90 days** — so the claim holds, and now says for how long. It
also notes what it does not cover: the API call that opened an SSM session is recorded, not
what was typed inside it.

Ends by naming the prerequisite AWS cannot satisfy on its own — a domain with a Route 53
hosted zone that already delegates it, without which both certificate validations hang.

### FZ-165 — Owners That Belonged to Other Stories
**Status:** DONE · **Fixes** two defects in `FZ-161` · **Owns:** `OI-36` … `OI-42`

`FZ-161` shipped `docs/14-data-protection.md`, and it was wrong twice over.

**It shared a number with the operations runbook.** `docs/14-operations.md` was `DONE` in the
backlog when the number was chosen, but its file had not merged yet — so `docs/` looked free
at 14 and was not.

**Then the repair collided too.** This story first renamed it to `16-`, and `docs/16-accounts.md`
landed from `FZ-164` while the pull request was open — the same mistake inside the fix for it.
It is `17-` now, chosen by listing `docs/` across **every remote branch** rather than on
`master`, which is the only check that would have caught either one.

**Its §7 named seven stories that did not exist.** `FZ-162` through `FZ-168` were invented to
give ten gaps an owner, and **every one of those IDs was claimed within a day by unrelated
work** — `FZ-162` is the repair of a split backlog entry, `FZ-163` a tfvars example, `FZ-164`
the account layout. A reader following §7 landed on infrastructure stories.

**The fix is to stop inventing IDs.** `09-open-issues.md` exists for exactly this: *"Every
entry names the story that will resolve it. If none exists, that is itself the next action —
'no owner' is not a status."* The ten gaps are now seven issues, `OI-36` to `OI-42`, each
marked *needs a story*, which is the sanctioned state for work nobody has scheduled. Seven
rather than ten because deleting a user, deleting an organization, exporting, and
pseudonymizing are one request arriving from four directions.

**Why the numbering keeps failing is worth naming.** A story ID is claimed by creating a
branch, and a branch is invisible to anyone reading `docs/`. Checking the backlog is not
enough — `FZ-147` exists because of this, and it was not run before §7 was written.

Acceptance:

- No file in `docs/` shares a number with another.
- No reference in `17-data-protection.md` names a story that does not exist or belongs to
  unrelated work.
- Every gap in §7 resolves to an issue in `09-open-issues.md`.
- `CLAUDE.md` and the `FZ-161` entry name the new path.
### FZ-166 — Nothing Built an Image for the Box
**Status:** DONE

`FZ-154` deploys the single box from an image tag that **must already exist in ECR**, which
is right — it is what makes rollback a redeploy rather than a rebuild. Nothing produced one.

`deploy.yml` was a single job that built the image, rolled out an ECS task definition and
published the SPA. On the single-box posture (`D-35`) it cannot run: there is no cluster, so
it would push the image and then fail on `aws ecs describe-task-definition` — **reporting
red after a successful push**, which is the worst of both outcomes because the image is
there and the workflow says it is not.

**Two of those three steps are shared, and the split follows `FZ-159`'s.** The ECR repository
and the SPA bucket both live in `infra/shared` and serve either posture, so building the
image and publishing the frontend cannot be steps inside an ECS rollout.

| | Runs on | Callable |
|---|---|---|
| `build-image.yml` | both | dispatched, and called by `deploy.yml` |
| `deploy-frontend.yml` | both | dispatched, and called by `deploy.yml` |
| `deploy.yml` | ECS only | dispatched; now `image → rollout → frontend` |
| `deploy-singlebox.yml` | the box | unchanged |

**`deploy-singlebox.yml` is deliberately untouched.** It still takes an exact tag rather than
building one, because a workflow that builds on every deploy cannot roll back — and rolling
back to a tag already in ECR is the fastest recovery the box has.

**The same class of gap as `FZ-159`, found the same way.** Each half assumed the other
existed: the Terraform assumed a shared estate that could be applied alone, and the deploy
workflows assumed a posture that could build. Both were found by reading what a person would
actually have to run, in order, rather than by anything failing — nothing can fail yet.

`14-operations.md` gains a *Releasing* section naming the three workflows in order, and
saying not to run `Deploy` on this posture.

### FZ-167 — What a Buyer Actually Reads
**Status:** DONE

`gtm/customer/` — the four documents handed to a prospect: one-pager, technical overview,
security brief, FAQ. `FZ-158` decided what to say; this is the half a customer sees.

**The security brief was blocked and is not any more.** It needed `17-data-protection.md`,
which landed with `FZ-161`/`FZ-165`. It answers the security questionnaire directly — what is
held and what is not, authentication, tenant isolation, encryption, residency, retention,
subprocessors — and then names what does not exist: **no SOC 2 report, no published DPA, no
EU residency, deletion done by hand, and a backup whose restore has never been drilled**
(`FZ-155`). A buyer finds all of that out anyway; the only question is whether they find it
out from us.

**Every technical claim was checked against the code, and two came back sharper than
written.** The connector exits non-zero on `BLOCK` and prints advisories on `ALLOW`
(`freeze-check.sh`), and retry is 30 seconds doubling to a 15-minute cap over six attempts
before a terminal failure (`FZ-044`) — specific enough for a reviewer to verify, which vague
phrasing is not.

**What the customer documents deliberately omit** is the internal half: what an alternative
costs us, who to walk away from, how to run a price. `01-positioning.md` §8 stays internal
because it is written for someone about to speak, not someone about to buy.

Contract documents — DPA, subprocessor page, privacy notice, Política de Tratamiento — remain
missing (`OI-41`). They need counsel and the company's own details, so no placeholder version
of them is in the repository.

Acceptance:

- Every factual claim resolves to `docs/`, `connectors/` or a decision record.
- The security brief states what does not exist, by name, rather than omitting it.
- No customer document claims a capability that is not built — no self-serve signup, no
  trial link, no references, no SLA below Enterprise.
- Nothing internal leaks into `customer/`.

### FZ-168 — One Account of Its Own, and the Organization Deferred
**Status:** DONE · **Corrects** `FZ-164` · **Decision:** `D-36`

`16-accounts.md` was wrong twice, in opposite directions, and the operator caught both
before acting on either.

**First error: the personal account as management account.** The guide was written anchored
on `OI-32`'s phrasing — "the operator's personal account" — instead of asking whether that
account belonged in the picture at all. The management account is not a bystander: it owns
the Organization, can create and close member accounts, and reaches into any of them
through `OrganizationAccountAccessRole`. Making it the personal one is `OI-32`'s objection
moved up a level rather than answered.

**Second error: keeping the Organization.** The correction kept a two-account Organization
on a fresh `freezehubio@gmail.com`, and left an open question — "does a member account get
its own credits?" — for the operator to check. They checked, and AWS refused: **AWS
Organizations are not available on the free account plan.**

The answer to the question the guide left open is worse than "no":

- *"When your account joins an AWS Organization … your Free Tier credits expire
  immediately, and your account will be ineligible to earn more AWS Free Tier credits"*,
  and the free plan *"will automatically be upgraded to a paid plan"*.
- A new account gets **$100 at signup plus up to $100 earned**, and the comparison table
  grants them on *both* plans — so it is the Organization that destroys them, not the plan.
- At `D-35`'s $18 a month, that is **about eleven months of the box**, spent on day one.

**A third finding, which decided the shape.** Without an Organization there is no Identity
Center path into the account either: *"Account instances do not support permission sets and
therefore do not support access to AWS accounts."* So the cheap option is not free — it
costs a long-lived access key, and the guide says so rather than implying the two options
are equivalent.

Resolved as **one standalone account** (`D-36`): the personal account is untouched, which
was the whole of `OI-32`'s objection; the Organization is deferred to a trigger, because
the credits are destroyed whenever it is created and doing it later costs the same minus
eleven months. §7 is the migration, including deleting the access key.

**And the Free plan is not an option regardless.** It *closes the account* after six months
or when credits run out, whichever comes first. For a product whose connector fails closed,
an account closing on a timer blocks every customer's deployments. Production upgrades to
Paid before anything depends on it — the credits apply either way.

`OI-15`'s free-tier line is corrected too: it described the old twelve-month model, for an
account the estate will no longer use.

Acceptance:

- Every claim about the free tier, Organizations and Identity Center cites the AWS page it
  came from, because all three are things the guide previously got wrong from memory.
- The cost of the single-account shape is stated as a list, not implied — access key, root
  as sole break-glass, no SCPs.
- The migration back is a numbered sequence with a named trigger, and ends by deleting the
  key.
- Nothing in `infra/` changes; there is no account id in it.

### FZ-169 — The Guide's First Three Steps Did Not Work
**Status:** DONE · **Corrects** `FZ-168`

`16-accounts.md` §3 could not be followed. Two defects, both of which stop the reader before
anything exists, and both found by walking the sequence against AWS's documented behaviour
rather than by reading it back.

**The order was wrong.** §3 created the role first and the user second. IAM *"transforms the
ARN to the user's unique principal ID when you save the policy"*, so the principal named in a
trust policy has to exist when the policy is saved — creating the role first returns
`MalformedPolicyDocument: Invalid principal in policy`. Reordered to user → role → the user's
inline policy, which needs a second pass over the user because the role did not exist when it
was created. Two passes is the price; the alternative is a wildcard `Resource`, which hands
the access key a broader grant than it needs.

**The MFA advice contradicted the CLI profile it was written for.** §2 said "a hardware key
if you have one", and §3's profile makes the CLI *"prompt the user to enter the one-time
password (OTP) that the MFA device provides"* — which a FIDO2 key or passkey cannot produce.
Anyone following both ends up with a working console sign-in and a CLI that cannot assume the
role.

Split by where the device is used, which is the distinction that actually matters:

- **root** — passkey or security key. It only ever signs in at the console.
- **the IAM user** — a virtual MFA device, or a hardware *TOTP* token. `mfa_serial` needs an
  OTP.

`mfa_serial` also took `<your-user>` where it needs the **device** name. The console defaults
one to the other and they do not have to match, so the guide now says to read the ARN off the
Security credentials tab instead of assuming it.

**Two smaller corrections found in passing.** The role's maximum session duration defaults to
1 hour and has to be set to 8 before `duration_seconds = 28800` is accepted — stated where
the role is created, not left to fail later. And "Billing → IAM access to billing" is called
**Activate IAM Access**; AWS is explicit that it grants nothing on its own
(`AdministratorAccess` supplies the other half), which the old wording implied it did.

Acceptance:

- The sequence in §3 can be followed top to bottom without an error.
- Every claim quotes the AWS page it came from — the same discipline `FZ-168` adopted, applied
  to the steps rather than to the decision.
- No decision changes. `D-36` stands; this is the guide being wrong about how to carry it out.

### FZ-170 — There Are Two AWSes, and We Are on the Other One
**Status:** DONE · **Supersedes** `D-36` · **Raises** `OI-43`

The operator could not finish `16-accounts.md` §3. Creating the IAM user, the console said:

> Los usuarios de IAM solo se deben utilizar para el acceso mediante programación. Si desea
> conceder acceso a usuarios humanos, puede hacerlo en la página del equipo.

Not advice — a service control policy. `iam:*LoginProfile*` is denied, so an IAM user cannot
be given console access at all. **§3 was unfinishable, not merely awkward.**

**The cause is that AWS now has two sign-up products, and the guide described the other
one.** FreezeHub's account is on *Sign up for AWS (new)*: sign-in through AWS Builder ID, the
account is a **project** inside an Organization **AWS owns and manages**, human access is
**team members** in an AWS-run Identity Center, and CLI credentials come from **`aws login`**
— a browser flow issuing role sessions rotated every 15 minutes.

**Which dissolves `D-36` rather than contradicting it.** `D-36` weighed $200 of free credits
against having an Organization and accepted a long-lived access key as the price of keeping
the credits. On this experience:

- the Organization already exists, at no cost, so there is nothing to weigh;
- Identity Center already exists — `sso:CreateInstance` is denied because AWS runs it;
- there are no access keys, so the residual `D-36` accepted does not exist.

Two stories of reasoning about a trade-off that was never real. Worth naming plainly: the
guide was written from documentation about a different product, and only creating the
account revealed which one we were on.

**What survives, checked service by service rather than assumed.** Every service the one-box
posture needs is on the Free Tier list — EC2, RDS, Cognito, CloudFront, Route 53, ACM, SSM,
ECR, S3, SES, ELB, ECS, CloudWatch, CloudTrail, Secrets Manager, KMS, STS, VPC, EBS, IAM. And
`RegionFloor` permits `unspecified`, `us-east-1`, the project's Region and `us-west-2`, so
**`D-32` holds and CloudFront's `us-east-1` certificate is not blocked** — the risk that
looked most likely to break the SPA, and did not.

**What does not survive is CI** (`OI-43`). `iam:*Provider*` is denied, and
`infra/shared/github-oidc.tf:7` creates an `aws_iam_openid_connect_provider`, so `shared/`
cannot be applied and every deploy workflow loses its credential path. The SCP applies on the
Free Tier *and* the Paid Plan and cannot be modified.

**Resolved as `D-37`: activate advanced features, but immediately before wiring CI, not
now.** Activation is irreversible and removes the project's **spend limit** — a hard enforced
monthly ceiling ordinary AWS does not offer. While the cost shape of a live box is unmeasured
that ceiling is worth more than a budget alarm that only sends email, and until something is
deployed there is nothing for CI to deploy. Deploys are by hand until then, and that is named
as a stopgap rather than a plan: a hand-deploy leaves no record of what shipped.

Acceptance:

- The guide opens by establishing **which** AWS the reader is on, because every instruction
  below depends on it and the two are not distinguishable from inside one of them.
- Every restriction quoted from the published SCP, with the statement that imposes it.
- The blocker is separated from the inconveniences — one thing stops the deploy, the rest do
  not.
- The classic shape is kept as one reference section rather than deleted, including the two
  defects `FZ-169` found in it.

### FZ-171 — The Region Was Wrong, and Only the Account Could Say So
**Status:** DONE · **Amends** `D-32` · **Corrects** `FZ-170`

`aws login` worked, which gave the first real credentials into the account — and the first
chance to check claims by calling AWS instead of reading about it. Two of them were checked.
One held and one did not.

**`OI-43` held.** `iam:ListOpenIDConnectProviders` returns `AccessDenied` with *"an explicit
deny in a service control policy"* naming one of the managed policies. GitHub OIDC is blocked exactly as
documented, and now with the policy id.

**`D-32` did not.** `FZ-170` claimed `RegionFloor` permits `us-east-1`, so `us-east-1`
survived as the region. It read one policy and missed a second. `UsEast1Partitional` then
denies everything in `us-east-1` outside a short allow-list, and the allow-list is global
services:

| Allowed in `us-east-1` | Denied in `us-east-1` |
|---|---|
| `acm`, `cloudfront`, `route53`, `iam`, `sts`, `kms`, `logs` | `cognito-idp`, `rds`, `ecr`, `ssm`, `elasticloadbalancing`, `secretsmanager` |
| three `ec2:Describe*` calls | every other `ec2:*`, and `s3:CreateBucket` |

Confirmed by calling each one: `ec2:DescribeAvailabilityZones` in `us-east-1` and `us-west-2`
denied, in `us-east-2` returns `us-east-2a`; `cognito-idp` denied in `us-east-1`, fine in
`us-east-2`; `acm` fine in both.

**So `D-32`'s region was unbuildable, and `us-east-2` replaces it.** Which matters more than
a string: a Cognito user pool is region-bound and `users.external_subject` stores the `sub`
it issues (`FZ-135`), so this had to be found **before** `FZ-046` creates the pool. After the
first real user it is a new pool, new subjects and a forced password reset for everyone. It
was found with three read-only API calls and nothing deployed.

**`bootstrap/` would have failed on its own default.** `infra/bootstrap/main.tf` defaulted
`region` to `us-east-1` and creates the state bucket, and `s3:CreateBucket` is denied there.
The other modules were safe only because `FZ-135` had already removed their defaults — the
same class of bug, caught once and missed once, in the one module that story did not touch.

**The residency answer is unchanged.** Ohio is the United States, which is what `D-32`
promised. `17-data-protection.md`, the security brief, the FAQ and `02-objections.md` change
one string each and say the same thing.

Acceptance:

- Every claim about what is permitted comes from an API call against the account, named in
  the story, rather than from the published policy alone.
- The Cognito region is settled before `FZ-046`, not after.
- No module can inherit a region by accident — `bootstrap/` keeps a default because it runs
  before anything exists, and it is now one that works.
- Customer-facing residency wording still says United States, because it still is.

**One thing arrived incidentally and is kept deliberately:** `infra/bootstrap/.terraform.lock.hcl`
is now tracked. Every other module already tracked its lock file and `bootstrap/` did not —
the same module, overlooked the same way, as the region default above. Disclosed rather
than left to be noticed in a diff.

### FZ-172 — The Domain Is the Gate, and the OIDC Block Is Not
**Status:** DONE · **Extends** `OI-43` · **Corrects** `FZ-170`'s ordering

Went to scope separating `github-oidc.tf` so `shared/` could be applied, and found the work
was not needed yet. Recording why, because the same investigation would otherwise be
repeated.

**`shared/` cannot be applied without a domain, whatever happens to OIDC.** It has two
required variables with no default — `domain_name` and `hosted_zone_id` — and creates an
`aws_acm_certificate_validation`, which blocks until DNS resolves. So the order is:

```
domain → hosted zone → shared/ → Cognito pool → FZ-046 → singlebox/ → deploy → CI
```

`OI-43` bites at the last step. `FZ-170` drew the diagram with "needs advanced features"
under `shared/`, which reads as though the module is unreachable and puts the blocker three
steps too early. Corrected in `16-accounts.md` and `infra/README.md`.

**The separability was scoped anyway, since the investigation was done.** `github-oidc.tf`
holds three resources; nothing outside the file references them except two outputs. It
depends on exactly three things from the rest of `shared/` — the ECR repository ARN, the
frontend bucket ARN and the CloudFront distribution ARN — all of which `singlebox/` already
demonstrates passing between root modules as variables.

Two shapes, left open rather than chosen, because the choice belongs with whoever wires up
CI:

- a **fourth root module**, matching `FZ-159`'s precedent of lifecycle boundaries as module
  boundaries — this one's lifecycle is "after advanced features", which is nobody else's;
- a **`count` toggle** on three resources and `one()` on two outputs — twenty lines, one
  tfvars line to flip, and an honest representation of an account capability that does not
  exist yet.

**`route53domains:*` is permitted on the Free Tier** and the API answers, so the domain can
be registered inside the account rather than delegated from elsewhere. That removes the last
reason the one remaining prerequisite has to be satisfied outside AWS.

**Nothing was built.** The finding is that building it now would be speculative, which is
`FZ-170`'s own lesson applied one step earlier: check what actually blocks before fixing what
looks like it does.

Acceptance:

- The ordering is stated once, in the two places that previously implied a different one.
- The separability is recorded precisely enough — three resources, two outputs, three ARNs —
  that it need not be re-derived.
- No Terraform changes. The story is a correction to documentation and a deliberate
  non-implementation.

### FZ-173 — AWS Will Not Sell Us the Domain, and It Does Not Matter
**Status:** DONE · **Corrects** `FZ-172`

`FZ-172` ended by saying the domain could be registered inside the account, because
`route53domains:*` is permitted by the SCP and `list-domains` answered. The operator tried
it and got:

> Registro de dominios con error: We can't finish registering your domain. Contact AWS
> Support.

**Amazon Registrar applies fraud-prevention checks to new accounts.** The restriction is
account-level, the error is deliberately generic — there is no detail to look up — and it is
lifted only through a support case. Basic support covers domain registration issues at no
cost, so the case is available; it is simply not worth waiting on.

**The same mistake as three stories running, in a smaller form.** An API being permitted by
policy is not the same as an operation succeeding. `FZ-170` checked a service list and missed
a second SCP; `FZ-171` checked one SCP and missed another; this checked the SCP and missed
that registration is refused for reasons that have nothing to do with IAM. The reliable test
has been the same every time: do the thing, then read the error.

**It blocks nothing, which is the part worth recording.** `infra/shared/` declares
`domain_name` and `hosted_zone_id` and never asks who the registrar is. So:

1. Buy the domain at any registrar.
2. Create a Route 53 hosted zone for it — about $0.50 a month, and not restricted.
3. Point the registrar's nameservers at the four Route 53 returns.

`hosted_zone_id` is then the zone's id and `shared/` has everything it needs. Route 53 stays
in the picture because the Terraform creates `aws_route53_record` for certificate validation
and the CloudFront alias; moving DNS elsewhere would be a Terraform change, and there is no
reason to make one.

Acceptance:

- Both places that told the reader to register in-account now say what actually happens and
  what to do instead.
- The distinction is stated plainly: the registrar is interchangeable, the hosted zone is not.
- No Terraform changes. Nothing in `infra/` was ever registrar-specific.

### FZ-174 — Making `shared/` Appliable, and Settling the Hostnames
**Status:** DONE · **Resolves** `OI-43`'s implementation · **Domain:** `freezehub.io`

Two changes, both of which had to be true before the first `terraform apply` and neither of
which is worth a separate branch, because they touch the same three files and would only
conflict with each other.

**1. `github_oidc_enabled`, default `false`.** `shared/` could not be applied at all on this
account: `iam:*Provider*` is denied by an SCP that cannot be modified (`OI-43`), and
`github-oidc.tf` creates an `aws_iam_openid_connect_provider` as its first resource.
`count` on the provider, the assume-role policy document, the role and its inline policy;
`one()` on the two outputs.

The alternative was a fourth root module, which would have matched `FZ-159`'s precedent of
lifecycle boundaries as module boundaries. Rejected: these resources **share** the shared
estate's lifecycle. They are absent only because of an account capability we intend to
remove, and a module boundary would assert a difference that is not there. A flag says
"temporarily unavailable", which is the truth.

`data.aws_iam_policy_document.github_deploy` is deliberately *not* counted — it references
only always-present resources and a policy document is computed locally, so gating it would
add `[0]` noise and buy nothing.

**2. `api_domain_name` is stated, not derived.** All three modules had
`api_domain = "api.${var.domain_name}"`. With the SPA at `app.freezehub.io` that produces
`api.app.freezehub.io` — and **that hostname goes into every customer's CI configuration**
through `freeze-check.sh`. A URL a customer pastes into a pipeline should not inherit
whichever subdomain the SPA happens to use.

Decided: `app.freezehub.io` for the SPA, `api.freezehub.io` for the Policy API, apex left
free for marketing. Both are certificate subject names, so both are decided before the first
apply rather than reissued after — the same argument `FZ-135` makes about the region.

The derivation was found only because the choice of subdomain forced someone to ask what the
API hostname would become. Had the apex been chosen, `api.freezehub.io` would have fallen
out by accident and the coupling would still be there, waiting for the first time the SPA
moved.

**The domain is live.** `freezehub.io` is registered at GoDaddy and delegated to Route 53 —
the `.io` registry and public resolvers all return the four `awsdns` nameservers. Hosted
zone recorded in `infra/shared/terraform.tfvars`, which is gitignored.

Acceptance:

- `shared/` applies on this account with the toggle off, and turning it on is one tfvars
  line after advanced features — not a migration.
- No `api_domain` is derived from `domain_name` anywhere.
- Every remaining `var.domain_name` use is genuinely the SPA: Cognito callback URLs, the
  CloudFront alias and certificate, the A record, and the CORS origin.
- All four modules `validate`, and `fmt -check` passes.

### FZ-175 — An Apply Went Into the Wrong Account, and Nothing Stopped It
**Status:** DONE · **Owns** `OI-32`'s last residue · **Found by:** applying

`terraform apply` on `bootstrap/` reported success. It had created the state bucket and lock
table in the **operator's personal AWS account**, using that account's **root access keys**.
Nothing failed, nothing warned, and the output was indistinguishable from a correct run.

**Two causes, and the second is the one worth fixing.**

`aws login` writes its session to the AWS CLI's own store — `~/.aws/cli/cache/session.db` and
a `login_session` key in config. Terraform uses the AWS SDK for Go, which does not read that
format. With `AWS_PROFILE` unset it fell through to `[default]`, which held root access keys
for a 2022 personal account (`OI-15`). When the profile *is* named, the same gap produces a
confusing error instead:

```
Error: No valid credential sources found
... no EC2 IMDS role found
```

— the SDK reaching the bottom of the credential chain and looking for an instance profile on
a laptop.

That is the mechanism. The cause is that **nothing made the right credentials the default**,
and `16-accounts.md` told the reader to `aws login` without saying that Terraform cannot use
the result. Every guard in this repository up to now has been a sentence in a document.

**The fix is an assertion, not a habit.** Every provider in all four modules now carries
`allowed_account_ids = [var.aws_account_id]`, with the id a required variable — no default,
validated as twelve digits. Proven both ways rather than assumed:

```
wrong credentials -> Error: AWS account ID not allowed: <the other account>
right credentials -> plan proceeds
```

Six provider blocks, including the two `us_east_1` aliases that issue CloudFront
certificates — an aliased provider takes its own credentials and would otherwise be
unguarded.

**The `credential_process` bridge is documented** in `16-accounts.md` §1 and
`infra/README.md`, as a second profile:

```ini
[profile admin-tf]
credential_process = aws configure export-credentials --profile admin --format process
```

It must be a second profile; pointing `admin` at itself recurses.

**What the incident cost, and what it did not.** The bucket was empty — `bootstrap/` keeps
its own state locally, so nothing had been written to it — and both resources were removed
from the personal account. `prevent_destroy` on the state bucket blocked `terraform destroy`,
which is the guard working correctly on a bucket that happened to be in the wrong place; the
two empty resources were deleted directly rather than disarming it.

Acceptance:

- An apply with credentials for any other account fails **before** creating anything, shown
  by running it.
- Every provider is covered, aliases included.
- `aws_account_id` cannot be inherited: no default, and a validation rule.
- The `aws login` gap is stated in the guide *before* the first instruction that depends on
  it, because the silent failure is worse than the loud one.

**And no real identifier goes in the repository, because it is public.** The operator caught
this on review: the account guard itself is fine — `var.aws_account_id`, with
`123456789012` in every example — but three real identifiers had been written into
documentation as evidence. An AWS account id is the one that matters: it is not a secret,
but it is what an attacker needs to enumerate roles and guess bucket names, and publishing
a *personal* one next to the sentence "this account had root access keys" is worse than
publishing it alone. A hosted zone id and a service control policy id are lower, and were
removed for consistency rather than because they were dangerous.

The documentary value was always in the *shape* of the evidence, never the digits — the
error message proves the guard fires whether or not the account id in it is real. Real
values live in `terraform.tfvars`, which `infra/.gitignore` already covers.

### FZ-176 — Wiring the Box to Cognito, and an API Hostname Still Being Derived
**Status:** DONE · **Resolves** `OI-44` · **Completes** `FZ-174` in the deploy path

`FZ-046` made `CognitoIdentityProvider` the `IdentityProvider` outside `local`. `infra/ecs/`
was already wired for it; the single box — the posture `D-35` actually deploys — was not, so
the backend would not have started there at all. Three additions and one correction.

**The instance role can invite users.** An `InviteUsers` statement scoped to the pool ARN and
to `AdminCreateUser` and `AdminGetUser` — the two calls the adapter makes. Not `AdminDeleteUser`,
not `AdminDisableUser`, not `ListUsers`: a foothold on this box cannot quietly remove an
administrator or enumerate every customer. `../ecs` already granted exactly this pair, which
is what made the omission easy to miss.

**The container gets the configuration it refuses to start without.** The issuer URI, the pool
id and the region, exported by the deploy workflow and read in `compose.yaml`. All three use
`${VAR:?}` rather than a default, matching the database password and the encryption key: the
backend has no default for these deliberately (`FZ-046`), and a default here would defeat that
from the outside.

**`API_DOMAIN` is set rather than derived, which `FZ-174` fixed in Terraform and missed here.**
`deploy-singlebox.yml` still had `export API_DOMAIN=api.${ROOT_DOMAIN}`. With the SPA at
`app.freezehub.io` that yields `api.app.freezehub.io` — the exact outcome `FZ-174` decoupled
the variables to avoid, and the hostname that ends up in every customer's CI configuration.
The decoupling was only half applied.

**`ROOT_DOMAIN` is renamed `APP_DOMAIN`.** It was never the root: it feeds
`FREEZEHUB_CORS_ALLOWED_ORIGINS`, so it is the SPA's origin, and calling it the root while the
apex is deliberately unused is the kind of name that produces the mistake above. Free to
change because no repository variable is set yet — checked rather than assumed.

**Fourth instance of the same shape** (`FZ-159`, `FZ-166`, `OI-44`, and the derivation above).
Two halves built in different stories, each assuming the other, and every one found by reading
what has to be true for something to run rather than by anything failing. Nothing is deployed,
so nothing can fail yet — which is precisely why the reading has to happen.

Acceptance:

- The box can create a Cognito identity, and can do nothing else to the pool.
- Every new value is required, none defaulted, consistent with the backend.
- No `api.` hostname is derived from another hostname anywhere.
- `infra/README.md` lists the single box's repository variables, which it never did — the
  table there was the ECS posture's.

### FZ-177 — The First Administrator of Every Organization Could Never Sign In
**Status:** DONE · **Completes** `FZ-046` where it is actually used · **Found by:** deploying

`provision-organization.sh` created the organization, the subscription and the first
Administrator — and invented that Administrator's identity:

```sql
SELECT id, 'provisioned-' || id || '-' || md5(random()::text), ...
```

No Cognito user, and an `external_subject` matching nothing. The row looks correct and the
person can never sign in. The script knew: it ended by saying so and blaming `OI-2`.

**`FZ-046` closed `OI-2` and nothing came back here.** Which is how the gap survived a story
written specifically to fix it — the adapter was built, tested and deployed, and the one
path that creates the *first* user of an organization does not go through it, because the
API cannot: inviting somebody needs an Administrator, and this is how the Administrator
comes to exist.

**Fixed by creating the identity and storing what Cognito returns.** `--user-pool-id`, or
`COGNITO_USER_POOL_ID`, and the script calls `AdminCreateUser` and reads the `sub` from the
response. Never derives it: Cognito assigns that value and it is not the email, the username
or anything this script can compute.

**Deliberately before the transaction.** If Cognito fails, nothing has been written and the
operator runs the script again. The reverse order leaves an organization whose Administrator
cannot sign in, which is the bug being fixed.

**Omitting the pool stays valid, because under `local` it is correct.** There is no pool,
`LocalIdentityProvider` invents subjects too, and `/api/dev/token` accepts any email. So the
flag is optional and the script *says which it did* — the closing summary now reads "They can
sign in" or "They CANNOT sign in" rather than a fixed paragraph that was true once.

**Sixth instance of two halves assuming each other** — `FZ-159`, `FZ-166`, `OI-44`,
`FZ-176`, `FZ-123`, this. The first three were found by reading, the last two by running.
This one was found by asking a question the deploy made askable: *how does the first person
actually sign in?*

Acceptance:

- The first Administrator has a real Cognito `sub`, read from the response and never derived.
- A Cognito failure writes nothing to the database.
- Running without a pool still works and says plainly that the person cannot sign in.
- No claim anywhere in the script that creating an identity is impossible.

### FZ-178 — The Runbook Could Not Be Run on the Box
**Status:** DONE · **Decided by:** the operator · **Raises** `OI-46`

Every command in `14-operations.md` failed on the deployed box:

```
$ docker compose ps
error while interpolating services.backend.environment.FREEZEHUB_COGNITO_CLIENT_ID:
  required variable COGNITO_CLIENT_ID is missing a value
```

`compose.yaml` uses `${VAR:?}` throughout, and the deploy exported those values for the
length of one command. Interpolation happens for `ps` and `logs` and `exec` too, not only
for `up` — so **the runbook written to diagnose a broken box could not be run on one**, and
`docker ps` showed all three containers healthy the whole time.

Found by trying to use it: `provision-organization.sh` needs `docker compose exec` for
psql, and failed on seven interpolation errors before touching the database.

**Fixed by writing `/opt/freezehub/.env` at deploy time**, mode 0600, which compose reads
automatically. Every runbook command works unchanged.

**The cost is two secrets at rest, and it is smaller than it first appears.** The database
password and the encryption key now sit in a root-owned file. They were already readable by
anyone who can run docker on that box — `docker inspect`, or `/proc/<pid>/environ` — so the
file does not widen who can read them. It removes a runbook nobody could use. The operator
made this call; the alternative was rewriting every runbook command to plain `docker` and
container names, which keeps nothing off disk that was ever off disk.

**And it exposed something worse, which is not fixed here.** `deploy/backup.sh` also calls
`docker compose exec`, so the nightly backup would have failed too — except it has never
run at all, because **nothing installs it on the box**. No timer, no unit, no script, empty
bucket. `OI-46`, owned by `FZ-155`, whose own description ("script written; the restore
drill is what completes it") turns out to be wrong: there is nothing to drill.

That is the seventh instance of two halves assuming each other, and the first that is a
data-loss risk rather than an inconvenience. It is free to fix while there is no data.

Acceptance:

- `docker compose ps`, `logs` and `exec` work on the box without arguments.
- `.env` is 0600 and gitignored, so it cannot reach the repository.
- The secrets-at-rest trade is stated where the file is written, not only here.
- The backup gap is raised rather than quietly folded in — it belongs to `FZ-155`.

### FZ-155 — the drill, and what it found
**Appended 2026-09-21, completing the entry above.**

**The script was never installed.** `OI-46`: no `backup.sh` on the box, no unit, no timer,
an empty bucket. `deploy-singlebox.yml` shipped `compose.yaml` and `Caddyfile` and nothing
else, and `user-data.sh` installs Docker and stops. This entry said "script written; the
restore drill is what completes it", which was wrong in a way nobody could see from the
repository — the script existed, it simply had never been anywhere near the box.

The deploy now ships all three files, writes `backup.env`, and enables the timer. Armed for
03:17 UTC, confirmed on the instance.

**The drill, performed rather than described:**

```text
dump                  55 715 bytes uncompressed, 7 735 gzipped
restore               0 errors
tables                21
liquibase changesets  28
constraints           185
```

**Four numbers because "psql exited zero" is not a passing restore.** The schema is whole
rather than a truncated prefix; `databasechangelog` survived, so a restored box continues
instead of re-running every migration; constraints survived, and a dump that restores
tables but loses foreign keys looks healthy until the first bad write; and the row count
is the one to watch when it should stop being zero.

**The restore used operator credentials, not the box's** — `WriteBackupsNeverReadThem`
means the instance can upload and cannot download, so the drill exercised the same path a
real recovery would. That guard turned out to shape the procedure rather than obstruct it,
which is the good outcome for a guard.

**It proves the mechanism and not the contents.** The database was empty. The entry stays
useful only if the drill runs again once there is something to lose — `14-operations.md`
says so where the numbers are recorded.

### FZ-179 — Nobody Could Sign In
**Status:** DONE · **Raises** `OI-47`

The product was deployed, creating real Cognito identities and validating real tokens, and
**had no way for anyone to obtain one.** Signing in returned `401`.

`SignInPage` posted to `/api/dev/token`, which exists only under the `local` profile. Its
own comment said it would be *"replaced by a redirect to the Cognito Hosted UI at
`FZ-063`"* — and `FZ-063` created the user pool without touching the frontend. **The
replacement was never scheduled**, so it was never written, and nothing in the repository
said so: the page looked finished and the comment pointed at a story that had shipped.

**Authorization code with PKCE**, because the app client is public (`generate_secret =
false`). Redirect to the Hosted UI, return to `/signin?code=`, exchange with the verifier
this browser generated.

**The access token is kept, not the ID token.** Cognito returns both from one issuer signed
by one key, so anything checking only a signature accepts either — which is precisely what
`FZ-128` refuses. Storing the ID token would produce a `401` that reads like a broken
sign-in rather than a wrong token, and the test for it is named accordingly.

**`state` is checked.** Without it a code from somebody else's authorization can be
delivered to this callback and exchanged.

**Local development is untouched.** The dev form remains when Cognito is unconfigured, and
the choice is made by configuration rather than a mode flag — so there is no way to point
the development sign-in at a deployed backend, whose endpoint would not exist anyway.

**Three things had to change around it, none of them obvious from the page:**

- **The CSP blocked the token exchange.** `connect-src 'self' https://api...` has no entry
  for the Cognito domain, and the SPA POSTs there itself. Sign-in would have failed at the
  last step with a console violation and nothing on screen.
- **The build needs two more variables**, and the workflow now fails if they are missing —
  without them the build silently ships the development form, which is `FZ-123`'s
  localhost bug in a new place.
- **The Hosted UI domain is derived in Terraform**, from the pool domain and the region,
  rather than configured a third time.

**Found by trying to sign in.** Nothing else would have: the backend was right, the pool
was right, the certificates were right, and the gap was a story nobody wrote.

Acceptance:

- A deployed build redirects to Cognito and completes the exchange.
- The stored credential is the access token, asserted by a test.
- A mismatched `state`, a missing verifier, a refused exchange and a missing access token
  each fail with a message naming the cause.
- The local development form still works and is still the only thing local gets.

### FZ-180 — The Backend Had No Way Out
**Status:** DONE · **Found by:** signing in

Sign-in worked. Every screen behind it returned `500`:

```
java.net.UnknownHostException: cognito-idp.us-east-2.amazonaws.com
  fetching .../.well-known/jwks.json
```

**The backend container had no outbound network.** `compose.yaml` put it on `internal`
alone, and `internal` is declared `internal: true` — which denies routing entirely, so it
could not resolve DNS, let alone fetch Cognito's signing keys.

**The split was right and its application was not.** The comment above it says "so the
database is not on the network that has a published port", and that is a good reason —
PostgreSQL belongs on `internal` alone and still does. The backend was put there too,
which grants the same isolation to a service whose job includes calling out.

**`infra/ecs/network.tf` gets this right for the other posture**, down to the list:
*"Outbound to webhooks, Slack, SES, Cognito"*. The compose file never had an equivalent,
so nothing contradicted it and review had nothing to catch.

**It was never only about sign-in.** The same denial breaks Slack notifications, SES mail,
and every customer webhook (`FZ-043`, `FZ-044`) — none of which had run yet, so the
`UnknownHostException` would have arrived later and looked like a different bug each time.

Joining `edge` publishes nothing: there is no `ports:` block on the backend, and inbound
still arrives only through Caddy. What it grants is egress.

**Ninth instance of two halves assuming each other**, and the second found by using the
product rather than reading it. Reading would not have found this one: both files are
internally coherent, and the contradiction only exists between them.

Acceptance:

- The backend resolves and fetches the Cognito JWKS — verified in the container, `200`.
- PostgreSQL remains on `internal` alone.
- No port is published for the backend; Caddy is still the only ingress.

### FZ-181 — The Guide a Customer Configures From
**Status:** DONE · **Corrects** `technical-overview.md`

`connectors/README.md` explains the CI half well — four systems, exit codes, configuration.
It starts from "you have an API key and an application name" and nothing said how to get
either, or how to decide what the names should be. `gtm/customer/setup-guide.md` is the
half before it.

**Written because the operator could not answer the question themselves.** Asked how to
model applications, teams and environments for FreezeHub's own dogfooding, the honest
answer was "I am not sure how to test this part" — and if the person who built it cannot
work that out, nobody evaluating it will.

**The answer, which nothing said anywhere:** the catalog is names. FreezeHub does not
discover them, validate them against infrastructure, or care whether they exist. An
environment called `production` can exist here before you have one. They only have to match
what the pipeline sends.

**Scope is the section that earns the guide.** Entries within a dimension are OR'd, the
dimensions AND'd, and an empty dimension is a wildcard — so naming *more* things makes a
restriction match *less*. That is the opposite of what people assume, and it is worked
through in a table rather than asserted.

**Writing it found a defect in a document already given to buyers.**
`technical-overview.md` said "Scoping to nothing means the whole organization".
`ChangeRestrictionService:246` enforces the opposite — invariant 3, at least one target
required. A prospect planning "leave the scope empty for the release window" would have
been designing against a `400`. Corrected, and the real semantics put in its place.

**Four claims were checked against code rather than written from memory**, and one was
wrong: the guide first said an API key can read the catalog. `ApiKeySecurityConfig` has
`securityMatcher("/api/policy/**")` and nothing else, so a key evaluates policy and can do
nothing else at all — which is a better sentence for a security-conscious reader than the
one it replaced. The exit code for a setup failure is `2`, not "other". `X-Request-Id` is
set on the response, not only logged. The preview endpoint exists and is on the human
chain, which is where the guide puts it.

**What it admits, in its own section rather than by omission:** the check is voluntary, no
self-serve signup, no SSO, **no email notifications yet**, one region, no SOC 2 and no DPA.
A buyer finds all of that out anyway.

Acceptance:

- Someone with an account and a pipeline can reach a blocked deploy without asking us a
  question.
- Every factual claim resolves to code, not to another document.
- The scope semantics are demonstrated, not stated.
- Nothing claims a capability that is not built.

### FZ-182 — FreezeHub Asks FreezeHub
**Status:** DONE · **Depends on** a catalog and an API key, both created by hand

Both deploy workflows now call the deployment gate before deploying, using **the connector
customers use** — `uses: ./connectors`, the composite action published as
`ghcr.io/freezehubio/freeze-check:v1`, not a copy of it. If it is wrong for us it is wrong
for them, which is the only version of dogfooding worth the name.

**On deploy, not on pull requests.** The operator's first sketch put the check on PR
checks. A freeze stops *deployments*, not merges — blocking a merge during a freeze is a
different product, and wiring it that way would have taught us the wrong lesson about our
own semantics on the first day of using it.

**Before the AWS credentials step, in both.** A deploy that is not allowed should not
assume a role at all. `deploy-frontend.yml` assumed credentials at step two, before
anything needed them; that step now waits until after the check, so the reasoning holds in
both files rather than being a sentence in one of them.

**After the build, before the publish.** Building and testing during a freeze is fine —
the freeze is about what reaches the environment. Checking first would also mean learning
at minute zero of a ten-minute pipeline that you cannot ship, having wasted nothing but
also having learned nothing the last step would not have told you.

> **The second sentence of that paragraph is wrong — corrected by `FZ-183`.** Checking
> first saves the build; that is the point of doing it. The placement was right for a
> reason this entry never gave: a freeze can begin *during* the build, so a check at
> minute zero can miss it. Read `FZ-183` instead.

**It works despite `OI-43`.** The check needs the Policy API and a key, not AWS, so it runs
while the rest of these workflows still cannot.

**And it fails closed, against us.** If FreezeHub is unreachable, FreezeHub cannot deploy
itself. That is the behaviour the guide asks customers to accept, and the first time it
bites will be worth more than any amount of reasoning about whether it should.

Acceptance:

- Both workflows call `./connectors`, not an inlined script.
- Neither assumes an AWS role before the check.
- The application names match the catalog entries exactly: `freezehub-backend`,
  `freezehub-frontend`.
- `infra/README.md` lists the variable and the secret, with how to set the secret without
  it reaching a shell history.

---

### FZ-183 — Check Early, Gate Late
**Status:** DONE · **Corrects** `FZ-182` · **Found by** the operator, reading a build log

`FZ-182` put the deployment check after the build and justified it badly. The operator
asked the obvious question — *shouldn't the check come first, to save building an artifact
we cannot ship?* — and on resources they were right. The entry claimed checking first
"wastes the ten minutes anyway". Checking first is exactly what saves them.

**But the late check is not an efficiency choice, and cannot be moved.** A freeze can be
announced *while the pipeline runs*. Declared at minute three of a forty-minute build, it
is invisible to a check at minute zero, and the deploy goes out at minute forty into a
freeze that was in force for most of it — the precise failure this product exists to
prevent, committed by the product itself. The early check is cheap. The late check is
correct.

**So: both.** `deploy-frontend.yml` asks twice — once after checkout to fail fast, once
immediately before the publish as the gate.

**The early one runs `on-error: allow`, and only the early one.** A one-second blip at
minute zero should not cost a build for a reason that is not a freeze, and an
authoritative check runs later regardless. This does not weaken enforcement: a missing key
or a wrong URL still fails the step, because that switch has never covered setup problems
(`FZ-094`) — otherwise revoking a key would silently disable the gate everywhere.

**`deploy-singlebox.yml` gets no early check.** It builds nothing; everything before its
gate is a checkout and a string comparison. An early check there would ask the same
question two seconds sooner and introduce a second answer that can disagree with the
first. Recorded in the file so the asymmetry is not later "fixed".

**Checking twice is free, and that is a decision we already made.** `11-commercial.md` §3:
metering evaluations "taxes the behaviour the product depends on", so they are unlimited on
every plan as a stated commitment. It would be incoherent to then advise customers to
check once to save resources.

**The documentation mattered more than our own pipeline.** For the FreezeHub frontend the
saving is about fourteen seconds of a seventeen-second job. But `setup-guide.md` — the
document handed to prospects — told customers to put the check *"immediately before the
step that deploys, not at the start of the pipeline"*, which costs a customer with a
forty-minute build a runner-hour per blocked deploy. `connectors/README.md` and
`examples/README.md` additionally offered *"or in a job the deploy job `needs`"*, which is
the stale-answer pattern presented as equivalent to the gate. All three corrected.

Acceptance:

- `deploy-frontend.yml` checks after checkout with `on-error: allow` and before the
  publish at the default.
- `deploy-singlebox.yml` is unchanged except for a comment recording why it has one check.
- No comment or backlog entry still claims checking first saves nothing; `FZ-182` carries
  a correction pointing here.
- The three customer- and engineer-facing documents give the two-call pattern, the
  mid-build-freeze reasoning, and the fact that evaluations are unmetered.

---

### FZ-184 — Record the Dashboard/Gate Disagreement
**Status:** DONE · **Records** `OI-48` · **Found in:** `FZ-183` testing

Documentation only. The operator noticed that a restriction starting now takes about a
minute to appear under **Active now**, and decided to leave it — *"in case we see it as a
blocker for a customer, we change it."*

Recorded rather than fixed, because the investigation found more than a refresh delay: the
dashboard reads the reconciled `status` column, which `ChangeRestrictionRepository:83`
documents as the wrong source and which `PolicyService` deliberately refuses to use. The
gate and the screen therefore disagree for up to a minute. It errs safe — enforcement never
reads `status`, so nothing is ever wrongly permitted — but the deferral should be a decision
someone can find later, not a thing that was noticed once in a conversation.

`OI-48` carries the causes, the safety argument, and the fix to make if a customer hits it.

Acceptance:

- `OI-48` states both causes, why it is safe, and why shorter polling is not the fix.
- The deferral is attributed and dated.

---

### FZ-185 — The Signup Form
**Status:** DONE · **Completes** `FZ-082` · **Discharges** `FZ-111`

`/signup`, public: company name, work email, `POST /api/signup`. `FZ-082` built the
endpoint and `FZ-111` built the public route, and neither could be used by a customer
without the other plus this.

**Both doors, demo still primary.** The operator's decision, asked before building rather
than assumed: at five customers the conversations are worth more than the volume, so
"Book a demo" keeps the primary button and "Start free trial" takes the secondary.
Promoting the trial later is one line. `LandingPage.test.tsx` asserts on the two button
*classes*, because which one leads is the decision — swapping them should be a deliberate
act, not a tidy-up somebody does while adjusting spacing.

"See what a pipeline asks" gave way rather than the hero growing a third button: three
equal-weight actions is no hierarchy, and `#pipeline` is still one click away as
"Integrations" in the nav.

**The acknowledgement is identical for every outcome, and that is the feature.** An
address already in use renders the same "Check your email" screen as a new one, because
the endpoint answers the same `202` either way. A page that said "already registered"
would be a customer-enumeration oracle wearing a helpful message. Verified live, not just
in a unit test: submitting a taken address produced a screen indistinguishable from the
successful one and created zero rows.

**Three documents were making a claim that had stopped being true.**
`gtm/customer/setup-guide.md` told prospects there is no self-serve signup — correct while
no form existed, wrong the moment one did. It now describes both routes and keeps the
point the old wording was really making, which was about there being no cross-tenant admin
console. `docs/04-api.md` still called `FZ-111` blocked. The landing page's own comment
said two things the deck draws were not built; one of them now is.

Acceptance:

- `/signup` is reachable with no token and sends no `Authorization` header.
- A duplicate address is indistinguishable from a new one, in the UI as well as the API.
- The landing page offers the trial as the secondary action, demo primary.
- No document still says self-serve signup does not exist.

---

### FZ-186 — Announcements Worth Reading
**Status:** DONE · **Extends** `FZ-041` · **Found by** using the product

Slack announcements were a single `text` field: a headline, a reason and a window, run
together with newlines. The operator wired the integration up, looked at one, and said
it was too plain. All five events are now laid out with Block Kit.

**The biggest change is not visual.** The message never said *which applications and
environments a freeze covered*, so every reader had to go and look up whether it applied
to them — which is the question on the landing page: *"Is the freeze on, and does it
apply to me?"* The old announcement answered the first half. Scope is now four of the
six fields.

**Colour tracks consequence, not level.** A live hard freeze is red, a warning amber, a
finished or cancelled one grey or green. The bar is read before any word is, so a
cancellation must not arrive looking like an emergency.

**`NotificationMessage` did not learn about Slack.** It says outright that it exists "so
every channel says the same thing"; Block Kit lives in a new `SlackMessage`, and the
wording — including the UTC formatter that invariants 9 and 10 make a domain rule —
stays where it was. The new class is pure, which is what lets the layout be asserted
without posting anything. That matters for a channel nobody looks at until it is already
in front of a customer's engineers.

**Three things the rendering forced into the open.**

- **An empty scope dimension is a wildcard**, so it renders "All applications", never
  blank. Blank would invert the rule and turn *every application deploying to
  production* into a message that looks like it covers nothing.
- **A finished freeze stopped claiming to block.** The first cut showed
  "Level: Hard freeze — deployments blocked" on a CANCELLED announcement, contradicting
  the same message's "this no longer applies". Found by looking at the rendered output,
  not by reading the code.
- **The restriction id comes from the notification, not the entity.** Taking it from
  `restriction.getId()` meant an unpersisted restriction produced no button, which made
  the button's presence untestable — the first version of the test asserted only its
  absence and passed for the wrong reason.

**The button needed the backend to learn its own address.** It had never had one.
`freezehub.app-url` is empty by default and the button is omitted when unset, rather than
rendered pointing nowhere — a dead link in an announcement is worse than no link, because
it is clicked during an incident. `deploy/compose.yaml` derives it from `APP_DOMAIN`,
already on the box.

**Escaping.** Names and reasons are customer input rendered as mrkdwn; `&`, `<` and `>`
are escaped, and headers truncated at Slack's 150-character limit.

**What this does not give the message is an identity.** It still arrives from an app with
no picture, next to every other bot in the channel. That is `FZ-192`, filed independently
from the same session.

Acceptance:

- All five events carry a header, fields, guidance and a colour — not one formatted event
  and four plain.
- `text` is still set on every message, or push notifications arrive blank.
- An empty scope dimension reads as "All", never as blank.
- `NotificationMessage` contains no Slack-specific formatting.
## Milestone 20 — What Using It Surfaced

Observations from the operator using the deployed product, recorded 22 September 2026. They
are not one theme; what they share is that each was found by using the thing rather than by
reading it.

### FZ-187 — Seven Observations From Using It
**Status:** DONE · **Files** `FZ-188`–`FZ-192`, `OI-50`

Recording only, no behaviour.

Seven observations, checked against the code before being written down. Four became stories,
one an open issue, one needed nothing, and one was found to be a better argument than the
observation made for it:

| | Verdict |
|---|---|
| Cancel from the restrictions list | `FZ-188` |
| Integrations should take a URL, not JSON | `FZ-189` — broader than webhooks |
| Administrator and developer roles | `FZ-190` — the product doc already agrees |
| A scheduling view on the dashboard | `FZ-191` — underspecified, decision named |
| A mark for the Slack app | `FZ-192` |
| Catalog sync from GitHub/GitLab | `OI-50` — contradicts a published claim |
| Starter / Pro / Enterprise plans | **Nothing to do.** Five tiers ship, priced and gated, and the landing page has carried the table since `FZ-111` |

**"Sourcetree" was read as Bitbucket.** Sourcetree is a desktop git client and hosts nothing.
Recorded because the substitution is a guess, and a guess that goes unmarked becomes a
requirement nobody agreed to.

### FZ-188 — Cancel From the List
**Status:** DONE

> **Corrected by `FZ-202`:** its confirmation read `Cancel ""?` — the name was lost from the source when the file was written, and the test asserted only that a confirmation was shown. It is now an in-app dialog that names the freeze.

`cancelRestriction` exists and is wired on the detail page. The list has no cancel action, so
lifting a freeze costs a navigation to find the button.

**Cancelling is the action with the most time pressure in the product.** Every other thing a
list offers can wait; a freeze that should have been lifted is blocking deployments across the
organization for as long as it takes to find the screen. It belongs where the freezes are.

Acceptance:

- Cancel is reachable from the list for a restriction whose status allows it, and absent where
  it does not.
- It confirms before acting — cancellation is not reversible and the row is one click from
  every other row.
- The audit entry is indistinguishable from cancelling on the detail page. Two routes to one
  action must not produce two kinds of record.

### FZ-189 — A URL, Not a JSON Document
**Status:** DONE · **Touches** `OI-23`, and does not close it

`IntegrationsSection` asks the operator to hand-type raw JSON, and its placeholders are the
only guidance: `{"webhookUrl": "https://hooks.slack.com/..."}`, `{"recipients": [...]}`,
`{"url": "https://acme.test/hooks/freezehub"}`. A misplaced brace fails at the backend with a
parse error rather than at the field with a hint.

**Broader than the observation.** It was reported about webhooks; all three channels have it.

The stored shape does not need to change — `integration.config` stays opaque JSON that the
owning channel interprets (`03-data-model.md`). What changes is that the form collects a URL
or a list of addresses and composes the JSON, instead of asking a human to compose it.

**Validate the URL while there is a validator to put it in.** `OI-23` records that outbound
webhooks reach any host the network can reach — link-local, metadata endpoints, anything
inside the deployment. A field that parses a URL is the natural place for that check, and
building the field without it means opening the same file twice.

Acceptance:

- Each channel type presents its own fields; no user types a brace.
- An invalid URL is refused at the field, naming what is wrong with it.
- Existing integrations keep working — the change is to how config is composed, not to what is
  stored.

**Built with one deliberate departure from this entry.** It said to put `OI-23`'s host
validation in the new field. `OI-23` says the opposite, and it is the more considered of
the two: *"Decided in `FZ-125`: the fix is egress, not validation"*, because a webhook URL
is attacker-chosen by design and *"validating at save and resolving at send is a gap a DNS
name can be moved through"*. Its application half is **already closed by `FZ-126`** —
connect-time address checks, no redirects followed.

So the field checks **shape, and nothing that resolves a name**: is it a URL, is it https,
does it have a host, does it carry a userinfo authority. No `InetAddress` lookup, no
allowlist, and three comments saying so, because the next person to read this code will be
looking for a security boundary and must not think they have found one. The boundary stays
in `OutboundAddressPolicy`.

The userinfo case is worth refusing at the field anyway, and not for security: `OI-23`
names `https://something.example.com@<internal-address>/` as the trick that defeats a
prefix check, and an operator who pastes one should be told immediately rather than
watching deliveries fail with a message they cannot act on.

**What changed, mechanically.** `CHANNELS` replaces `CONFIG_HELP` and carries each
channel's field label, placeholder, hint and config key; `composeConfig` turns what was
typed into the JSON the backend already expects. Stored shape unchanged. Slack and webhook
get a single-line URL input — a three-row textarea for one URL invites a second line the
backend will refuse — and email keeps a textarea because a recipient list is genuinely
multi-line. Changing channel clears the field, so a webhook URL cannot be left behind in a
recipients box.

**A test caught the contract change, as it should have.** `SettingsPage.test.tsx` drove
the old textarea. One case needed more than a mechanical edit: *"explains a config the
backend rejects"* used an `http://` URL, which the field now refuses before any request is
made, so it was asserting a path it could no longer reach. It now uses a value the form
accepts and the server refuses, which is the case that actually matters.
### FZ-195 — The Fake Forgot, So Signup Allowed It Twice
**Status:** DONE · **Resolves** `OI-49` · **Found in:** `FZ-185` testing

Signing up with an address a seeded organization already held created a *second*
organization. `LocalIdentityProvider` remembered only the current process, and it had
restarted since the seed ran.

**The design it was faking is right and stays.** Global email uniqueness lives in Cognito,
because `users.email` is unique only within an organization while the pool is shared. So
the duplicate case is `AdminCreateUser` refusing — which is what keeps `POST /api/signup`
from being a customer-enumeration oracle (`FZ-082`). The fake just could not reproduce it
across a restart.

It now checks two places: **the database**, because a row written by a seed script or an
earlier process is what a persistent pool would still know about, and **its own map**,
because during signup the identity is created before the user row exists and two rapid
signups would otherwise both be allowed.

**This is test fidelity, not security** — the bean only exists under the `local` profile.
It is the same lesson as `FZ-193`: the substitute that diverges from reality hides the one
path that must not be wrong.

### FZ-190 — Administrator and Developer
**Status:** DONE

**The decision, taken by the operator:** two roles, not three. An administrator manages
freezes and the catalog; everyone else reads. The consequence is that `00-product.md`'s
Release/Engineering Manager must hold `ADMINISTRATOR`, since creating a freeze is now an
administrator action — recorded in both documents, because a third role would be the
advanced RBAC `00-product.md` excludes and is a separate decision.

**Enforced with `@PreAuthorize` on the write endpoints** of restrictions and all three
catalog controllers. Reads are untouched: an engineer's whole use of the product is finding
out whether they may deploy, and breaking that would defeat the point.

**48 existing tests failed, and every one of them was right to.** They encoded the old rule
by creating their acting user as `MEMBER` and then writing. Each was a shared token helper,
so the fix was one line per file — but the count is the honest measure of how much of the
product this changes. The tests that deliberately assert a member is refused were left alone.

**A test written badly taught something worth keeping.** `POST /api/restrictions` with `{}`
as a member answers **400, not 403**: Spring binds and validates the request body during
argument resolution, before `@PreAuthorize` runs on the method. The test now sends a valid
body, so the guard is what refuses it rather than the validator.

**The frontend hides what a member cannot use**, which is a courtesy and not a control — the
backend refuses regardless. `useCurrentUser` reads `/api/me`, which already returned the
role and which nothing had ever called. The two pages differ on purpose: the list *hides*
the controls, because a row has no room to explain itself, and the detail page *disables*
them with the reason beside them, which is the convention that page already had for a
completed restriction.

`renderRoute` seeds the role and defaults to `ADMINISTRATOR`, so existing tests keep
exercising what they were written for and a member view is one option away.

`users.role` is `ADMINISTRATOR` or `MEMBER`, and a `MEMBER` may create, edit and cancel
restrictions. The request is that only an administrator manages freezes and everyone else
reads.

**The product document already agrees.** `00-product.md` names three actors — Organization
Administrator, Release/Engineering Manager, Engineer — and the schema has two roles. The
Engineer is described as someone who *"checks active/upcoming restrictions and determines
whether their application/environment is affected"*: read-only, in the document, since before
the role model existed.

**And the security document invited this.** `06-security.md` says catalog and restriction
management *"remain open to any authenticated org member **unless a future requirement says
otherwise**"*. This is that requirement, which makes it a gap being closed rather than the
advanced RBAC `00-product.md` excludes.

**The decision is the middle actor.** Three actors, and the request names two roles. Either
the Manager is an administrator — in which case "administrator" stops meaning "manages the
organization" and starts meaning "manages freezes too" — or a third role appears, which is
where this starts to become RBAC. Not guessed here.

Requires updating `06-security.md`, `00-product.md` and a migration for existing rows.

### FZ-191 — The Dashboard's Other View
**Status:** TODO · **Decision required before building**

A control that swaps **Active now** and **Then what** for a view of the schedule itself,
described as a carousel with a *Next* affordance.

**Underspecified in one way that decides the work.** "The scheduling itself" is at least three
different components: a calendar month with freezes drawn on days, a horizontal timeline of
overlapping windows, or a sorted list of upcoming windows with their scope. The first is the
most work and the most familiar; the second shows overlap, which is the thing the list cannot
show; the third is nearly free and adds least.

Worth answering with the question the view exists for: *what can I not see today?* The
dashboard already answers "what is on now" and "what is next". A calendar answers "when is it
safe to plan a release", which neither of the others does.

### FZ-193 — The Suite Was Starving Itself
**Status:** DONE · **Resolves** `OI-47`

The frontend suite failed three to eight tests on a developer machine and passed every
time in CI. Every failing file passed in isolation, and the failing set differed between
consecutive runs.

**It was not flaky tests. It was starvation, and the suite was causing it.** Vitest
defaults to one worker per core. On this 16-core machine — already running a backend
build and two other agent sessions — `npm run test` took the load average from 16 to 77.
Each worker then gets a fraction of a core, a test needing one second of CPU takes five,
and five seconds is the default timeout. CI passed because a CI runner has the machine to
itself, which is exactly why CI could never reproduce it.

Three changes, for three different reasons:

- **`testTimeout: 15_000`.** A timeout catches a hang; it is not a performance budget.
  Five seconds was measuring the machine, not the code.
- **`maxWorkers: '50%'`.** Still parallel, and no longer the reason somebody else's build
  times out. On a machine running several sessions this is what keeps any result
  meaningful.
- **`setupUser()`, a `userEvent.setup({ delay: null })` helper**, in the three files
  `OI-47` named. Filling one restriction form types about seventy characters, and by
  default `userEvent` yields to the event loop after each one — seventy scheduler
  round-trips and seventy renders. Nothing in those tests asserts on typing *timing*, so
  the delay bought nothing and cost the most exactly when the machine was busiest.

**Measured, not asserted.** Before: 2 failures with the load average going 16 → 77.
After: three consecutive full runs, 255 passing each time, at load averages of 89, 141
and 256. The fix holds at three times the load that broke it.

**The first attempt was not enough, which is worth recording.** Config alone — timeout
plus worker cap — passed once at load 171 and then failed one test at 118. One green run
does not disprove a flake; it took the `userEvent` change to make it survive repetition.

Also fixed here, because it is the same subject: `SignUpPage.test.tsx` had a
`no-unsafe-optional-chaining` warning from `FZ-185`, where `init?.headers` short-circuits
and the assertion would pass whether the header was absent or the request was.

Acceptance:

- Three consecutive full-suite runs pass on a loaded developer machine.
- The suite does not drive the machine to saturation on its own.
- `npm run lint` is clean.

### FZ-192 — A Mark for the Slack App
**Status:** DONE for the SVG · **Follows** `3a` from `FZ-196` · **PNG export is a human step**

The Slack application had no profile picture, so announcements arrived from the default
grey square in a channel where everything else is also a bot. `FZ-186` gave the message
structure; this gives it an identity.

**It is `3a` at 512, not a new mark.** This story was first built against the old purple
favicon and argued for the product's teal accent. `FZ-196` landed while it was in review
and settled the question properly — the operator chose `2c` and `3a` from the design deck
— so the icon was rebuilt as the favicon multiplied by eight: same ink, same paper, same
magenta point, same font stack, same letter-spacing. A product whose avatar differs from
its favicon has two marks rather than one.

**The export is the fragile part, and it failed on the first attempt.** ImageMagick — the
obvious tool, and present on this machine — renders the SVG with **no letterform at all**:
its SVG renderer drops `<text>`, leaving a magenta square on ink, and exits `0`. That PNG
would have been uploaded and nobody would have noticed until it was live. The output is
committed as `docs/ui/FZ-192/bad-export-imagemagick.png` so the next person does not
rediscover it, and the README names the tools that work.

**Why it matters more here than for the favicon.** Both depend on a serif being installed,
and `FZ-196` accepted that: the favicon re-renders on every viewer's machine and degrades
to Georgia at the 16px `3a` was chosen to survive. A PNG cannot degrade — it can only be
wrong, permanently, from the moment it is uploaded. `FZ-196` already names outlining the
glyph as the durable fix and its own job; this entry is the second caller waiting on it.

Verified by rendering in a browser at 128 / 64 / 20 px beside the shipped favicon and in a
message row at actual size — `docs/ui/FZ-192/after.jpg`.


### FZ-194 — A Bound, and a Rule About What Goes In It
**Status:** DONE · **Resolves** `OI-40`

`notification.last_error` was unbounded `text`. It is `varchar(500)` now, truncated in the
entity, and `03-data-model.md` states what may go in it.

**The senders were already careful, which is why this was easy to miss.** Each composes its
own message, and the webhook one reads the response with `toBodilessEntity()` and reports the
exception's *class name* rather than its message — deliberately, because Spring puts the
request URI in that. Nothing passes a customer's response body through.

**What made the column unbounded is one line in the dispatcher.**
`NotificationDelivery` catches every `RuntimeException` and stores `getMessage()` verbatim, so
any library's message — a driver's, a parser's, one carrying a fragment of whatever it was
handed — arrives at whatever length it happens to be. The catch is not narrowed here: it is
what stops one bad destination killing the dispatch pass for every other notification.

**Bounded in the entity, not at the call sites.** Three writers set this field today and a
fourth would be easy to add. One door means a later writer cannot forget.

**A null message now becomes an empty string.** `getMessage()` is null for a
`NullPointerException` and several others, and a `FAILED` row whose only account of itself is
null cannot be told from one that never recorded a reason.

500 matches `demo_request.notify_error`, which bounds the same kind of value (`FZ-083`). One
convention rather than two.

Acceptance:

- Every write is truncated, asserted per writer rather than once.
- A short message is kept whole — the bound costs the ordinary case nothing.
- Clearing on eventual success still works, so a stale error is never shown as current.
- The migration truncates before it narrows the type; an existing long row would otherwise
  fail the alter, and this table is the record of announcements that never arrived.


### FZ-199 — The Exports, Committed
**Status:** DONE · **Revisits** `FZ-192`

`docs/brand/exports/` — ten PNG sizes, a multi-resolution `favicon.ico`, WebP, JPG and a PDF,
beside the SVG they come from. Plus two transparent variants of the mark, SVG only.

**This reverses `FZ-192`'s call, and its objection deserves answering rather than ignoring.**
That story kept exports out on the grounds that an SVG can be diffed and a PNG cannot, so a
committed raster could drift from its source unnoticed. The risk is real; the remedy was
backwards. Regenerating by hand on whichever machine happens to have a renderer does not
prevent drift, it relocates it somewhere no diff will ever show — and it made every consumer
of the mark wait on a human step. Committed exports make staleness **visible in a review**,
which is what the objection actually wanted. The README now carries the obligation that comes
with it: change the SVG, regenerate in the same commit.

**Rendered per size, never downscaled.** `3a` was chosen to survive at 16 px and downscaling
a serif is how that is thrown away. Verified by looking, magnified, at 16/32/48/64: clean
from 32 up; at 16 the middle arm softens and the point becomes a 2×2 blob, which is the
documented floor.

**ImageMagick never touched the SVG.** It is installed, and `FZ-192` proved its SVG renderer
drops `<text>` while exiting `0` — the broken output is committed as evidence. It is used
here only for PNG→ICO/WebP/JPG/PDF, where it is sound. The SVG rasterising is `qlmanage`,
which is the "a browser, which is what renders the favicon anyway" route the README already
sanctioned.

**The transparent variants are SVG only, and that is a finding rather than a shortcut.**
Transparent PNGs were produced and then deleted: `qlmanage` composites onto opaque white, so
the corner pixel of its output reads alpha `1`. The files were named `transparent` and were
not. The check is one command and it is in the README, because the failure is silent in every
viewer that shows a white page behind a white background.

**They are derivations, not approved brand.** `FZ-192` is explicit that the mark is the
operator's choice from the deck; the plate was subtracted and the letterform recoloured. The
README says so where somebody reaching for them will read it.

Acceptance:

- Every export is regenerable from the committed commands, and those commands are the ones
  that produced these files.
- No export is named for a property it does not have.
- `README.md` no longer argues against what the directory contains.

### FZ-202 — Asking in the Product, Not Through the Browser
**Status:** DONE · **Corrects** `FZ-188` · **Found by** the operator, cancelling a freeze

Cancelling a freeze from the list opened the browser's own dialog, and it read:

> Cancel ""? It ends now and every channel is notified. This cannot be undone.

Two defects, and the second is the one that matters.

**The name was missing from the source.** `RestrictionsPage.tsx` held a literal `""` where
`${restriction.name}` belonged. It was written through a `perl -0777 -i -pe` substitution,
and Perl read `${restriction.name}` as a Perl variable — undefined, so empty. The file
compiled, `tsc` passed, `oxlint` passed, and **so did the test**, because it asserted
`expect(window.confirm).toHaveBeenCalled()`: that a confirmation happened, never what it
said. A dialog asking whether to lift a freeze, without naming the freeze, is the one
safeguard between a misclick and an organization's deployments being unblocked.

**And it was the browser's dialog at all** — a different typeface, a different voice, and a
title bar reading `localhost:5173 says`, at the moment the product most needs to be trusted.
`ApiKeysSection`'s revoke used the same pattern; it is the one `FZ-188` copied.

**`ConfirmDialog`**, in `components/`: a native `<dialog>` opened with `showModal()`, which
gives the page behind it `inert`, traps focus, closes on Escape and draws the backdrop
without a dependency. Mounted only while open, so its title is always read from the row that
was clicked. Focus starts on the safe choice, so Enter on a freshly opened destructive dialog
destroys nothing. **The dismiss button never repeats the action word** — a dialog asking
"Cancel this freeze?" with a button labelled "Cancel" is a coin toss — so it is *Keep it* /
*Cancel freeze*, and *Keep it* / *Revoke key*.

**The test that would have caught it now exists, and was proved to.** `names the freeze it is
about to cancel` asserts the dialog's accessible name is `Cancel “Peak trading”?`. With the
name removed from the component it fails; restored, it passes. Checked by doing it rather than
reading it.

**`no-alert` is on**, so a native `confirm`, `alert` or `prompt` is now a lint error — proved
with a throwaway file, which `oxlint` rejected with *"Use a custom UI instead"*. It catches the
class of mistake, not this instance: the call was legitimate, the string was not. That is what
the new test is for.

**Found by looking, not by testing.** The first render drew *Cancel freeze* as bare text:
`.btn` sets `background: transparent` at one class of specificity, and the dialog's danger
style lost to it on stylesheet order — the dangerous choice looking weaker than *Keep it*.
Fixed with specificity rather than load order, and kept as
`docs/ui/FZ-202/caught-in-review-unfilled-button.jpg`.

**Not changed, deliberately:** the detail page still cancels without asking. That is
`FZ-188`'s decision — a list row is one misclick from its neighbours and the detail page is
not — and it is not this story's to reverse.

### FZ-200 — The Runbook Described a Path Nobody Could Take
**Status:** DONE

`14-operations.md` § *Releasing* listed three workflows to dispatch. **None of them can run.**
`OI-43` leaves `AWS_DEPLOY_ROLE_ARN` null, so every deploy fails at the credentials step and
every release is by hand — which the runbook did not mention, let alone describe.

**A hand deploy is the workflow with its assertions removed**, and the section is now
organised around putting them back. Each guard is there because its absence already cost
something:

| Guard | What happened without it |
|---|---|
| You are in the right account | `FZ-175` applied into the wrong one; a laptop's ambient credentials have since been seen pointing at a different account as **root** |
| Your checkout is current | A deploy shipped a tree 24 commits behind, so one merged story reached the site and a later one did not |
| The build variables are the ones the code reads | A deploy shipped without `VITE_COGNITO_DOMAIN`; the site fell back to the development sign-in and **nobody could sign in at all** |
| You looked at the result | A `200` proves the bucket accepted bytes, not that the right bytes are served |

**The backend rollout is deliberately not restated here.** It is thirty quoted shell
fragments in `deploy-singlebox.yml`, and the runbook now says to copy them from the workflow
rather than from prose — `OI-46` found the backup had never been installed because a step
went missing in exactly that translation. A runbook that paraphrases a command list competes
with it.

**The variable name is called out by name**, because it is the specific mistake that broke
sign-in: the workflow assigns `vars.COGNITO_HOSTED_UI_DOMAIN` to `VITE_COGNITO_DOMAIN`, and
copying from the wrong side of that colon produces a build that deploys cleanly and cannot
authenticate.

Ends with the argument for `OI-43`: four hand deploys have produced four divergences from
what the workflow would have done, and lifting it makes every guard above automatic.

Acceptance:

- Every command is checked against the workflow or the code it came from, not reconstructed.
- The bucket, distribution, region and registry match the repository variables.
- The section says plainly that the workflow path does not work today.

### FZ-201 — Making CI Able to Deploy, and Quickly
**Status:** DONE · **Unblocks** `OI-43` once the operator activates advanced features

Two defects that would each have surfaced on the *first* CI deploy — which is to say after
the irreversible step — plus the enablement procedure.

**`ECR_REPOSITORY` was never a repository variable.** `build-image.yml` and `deploy.yml` both
read `vars.ECR_REPOSITORY`; the repository has `ECR_REPOSITORY_URL`, which `deploy-singlebox`
already uses. The tag would have rendered as `…amazonaws.com/:abc1234` — an empty repository
name — and the push would have failed. Found by diffing every `vars.*` the three workflows
read against `gh variable list`: two missing, and only one of them (`AWS_DEPLOY_ROLE_ARN`)
was supposed to be. Both now use `ECR_REPOSITORY_URL`, so there is one variable and no way
for two to disagree.

**The image built under emulation, at 7.3x.** Measured rather than assumed, on one machine
with `--no-cache`: **2m03s native against 15m02s emulated**. `javac` alone was 18.5x slower,
and the JVM took nearly four minutes to reach *"Scanning for projects"*. The runner is now
`ubuntu-24.04-arm` and `setup-qemu-action` is gone; `platforms:` goes with it, because the
platform follows the host. `linux/arm64` is not the problem and does not change — the box is
a `t4g.small` and an amd64 image would not start.

**The Dockerfile's layering did nothing in CI.** It resolves dependencies in their own layer
and says that layer *"survives most builds and the download is paid once"* — true on a laptop,
false where no cache persisted between runs. It was re-resolving all 23 dependencies every
time: 97.8s natively, 520.4s emulated. `cache-from`/`cache-to: type=gha` makes the layering
mean something.

**Audited rather than hoped:** the deploy role carries ECR push, SSM `SendCommand` and
`GetCommandInvocation`, S3 read/write/delete and CloudFront invalidation — matched against
what each of the three workflows actually does. The trust policy is scoped to
`repo:<github_repository>:ref:refs/heads/master`, so a dispatch from another branch fails at
the credentials step; the runbook says so, because it is confusing the first time.

**What is left is the operator's**, and step one is irreversible: activating advanced features
lifts the service control policy behind `OI-43`, and removes the enforced spend limit — which
is why `D-37` pairs it with a Budget alarm the same day. `14-operations.md` § *Turning CI on*
is the sequence.

**Not verified end to end, and cannot be.** The workflows cannot run until the step above is
taken, so this is a reading of them against the code and the repository variables, not an
observation of them working. The timings are from this machine reproducing CI's conditions;
the ratio transfers, the absolute seconds may not.

Acceptance:

- No workflow references a repository variable that does not exist.
- The image builds natively, with a cache that survives between runs.
- The enablement sequence names every step, including the one nobody can undo.

### FZ-203 — One Release, In Order
**Status:** DONE

`Release` — a workflow that chains build → box → frontend and passes the image tag itself.

**The hand-off was where releases went wrong, not the workflows.** Each of the three was
correct; what failed was the coordination between them. One release went out from a tree 24
commits behind, and another shipped a frontend built without the Cognito variables, leaving
nobody able to sign in. Both were somebody carrying a value between two dispatches. That is
the part a workflow can do and a person should not have to.

**Deliberately not triggered on `push`, and this is the part to disagree with if any.**
`deploy-singlebox.yml` already carried the rule — *"publishing changes what customers reach,
and `freeze-check.sh` fails closed, so a deploy is the one moment FreezeHub can block every
customer at once"* — and it is specific to this product rather than ordinary caution: a bad
deploy does not merely break FreezeHub, it stops every customer's pipeline, because their
connector fails closed on an unreachable API. So merging still does not publish. What is
automated is the clerical work around the decision, not the decision.

**Backend and frontend are separately tickable**, because most releases are one of them. A
frontend-only release should not take the box's ~15 second gap (`FZ-153`), and a backend-only
one should not invalidate CloudFront for nothing.

**Two mistakes found while writing it, both of which would have failed on first use:**

- **A called workflow inherits no secrets.** Without `secrets: inherit` the freeze check
  receives an empty API key and fails closed — FreezeHub refusing to deploy FreezeHub, for
  entirely the wrong reason.
- **A skipped dependency skips its dependants.** Plain `needs: [box]` would have made a
  frontend-only release silently do nothing. It is `always()` plus an explicit result check,
  which still withholds the frontend when the box *fails* — shipping a UI against a backend
  that did not deploy is worse than shipping neither.

**Needs one new repository variable**, `SINGLEBOX_INSTANCE_ID`, so nobody pastes an instance
id. `14-operations.md` § *Turning CI on* sets it beside `AWS_DEPLOY_ROLE_ARN`.

**Not verified by running it**, and cannot be until `OI-43` lifts. YAML validity is checked;
workflow semantics are reasoned from the documentation, and `actionlint` is not installed
here. The two mistakes above were found by reading, which is the only method available and
is not as good as running it once.

Acceptance:

- One dispatch releases both halves, in order, with no value carried by hand.
- Either half can be released alone.
- Merging to `master` still publishes nothing.
- The three workflows stay individually dispatchable, which is how a rollback is done.

### FZ-204 — The Guard That Replaces the Spend Limit
**Status:** DONE · **Prerequisite for** `OI-43`

`infra/shared/budget.tf` — a monthly AWS Budget with an actual and a forecasted alert, plus
the ordering change that makes it useful.

**Activating advanced features takes a control away.** It lifts the service control policy
behind `OI-43`, which is the point, and in the same moment removes the **enforced spend
limit** — until now the only thing between a mistake and an unbounded bill, applied for free
and without anybody configuring it. `D-37` already required a Budget "the same day"; nothing
had built one, so activating today would have left the account with no spend guard at all.

**The runbook now sets the budget first and activates second.** "The same day" leaves a
window; doing it first removes the window. The apply that creates the budget is the same one
step 3 runs later with the OIDC toggle on, so it costs nothing extra.

**$40, against `D-35`'s projected $18 a month.** High enough not to cry wolf on an ordinary
month, low enough that the 80% actual alert arrives around $32 — while the month can still
be salvaged. The forecast alert at 100% is the one that matters: something left running at
3am shows up there days before it shows up in actual spend.

**`budget_alert_email` has no default, deliberately.** An alert nobody receives is worse than
no alert, because it looks like a control.

**Stated rather than discovered: an alert is weaker than the limit it replaces.** The limit
refused; a budget only tells somebody. That downgrade is the price of CI and belongs in the
decision, not in a bill.

**Two defects of mine, fixed here.** `FZ-201` wrote step 4 of the runbook through a `perl`
substitution, and Perl interpolated `$(` as its own GID variable: the published command read
`--body "20 20 12 61 79 … terraform -chdir=…"`. The same substitution ate `$18` in a later
line. Both were on `master`. Step 4 is rewritten — from a heredoc, not a substitution — and
now **checks each value before setting it**, because `gh variable set` with an empty `--body`
does not fail: it prompts, so a missed step quietly sets the variable to whatever is pasted
next. That is the same silent-empty-value shape that shipped a frontend without
`VITE_COGNITO_DOMAIN`.

Acceptance:

- A budget exists before the enforced limit is removed, not the same day.
- Alerts reach a named address; there is no default to fall back on.
- No runbook command contains a value that was interpolated away.
- Publishing a variable fails loudly when its source is empty.
