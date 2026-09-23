# FreezeHub — Frontend Specification

## Purpose

Defines MVP routes, page responsibilities, shared UI conventions, and API interaction conventions, per `FZ-030`, before substantial UI implementation begins (`FZ-031`–`FZ-034`).

Scope is Milestone 3 only. The frontend handles presentation and interaction; **authoritative business decisions stay in the backend** (`02-architecture.md`, `CLAUDE.md` §5). No domain rule is re-implemented here as an independent source of truth.

## Stack

Per `02-architecture.md`: React, TypeScript, Vite, React Router, TanStack Query, React Hook Form, Zod. Bootstrapped in `FZ-003`; React Router is already wired, the rest are added when the first story needs them.

**Styling: CSS Modules with native form controls.** Decided by `FZ-030`. No UI framework and no styling dependency — `CLAUDE.md` says not to add dependencies without a concrete need, and native controls cover what the MVP forms require:

| Need | Control |
|---|---|
| Start/end instants | `<input type="datetime-local">` |
| Scope selection (teams, applications, environments) | `<select multiple>` |
| Restriction list | plain `<table>` + CSS Module |

Accepted trade-off: multi-select UX is basic. Revisit if usability testing shows scope selection is a real obstacle; swapping in a component library later is contained to the form components.

## Routes

```text
/                          -> redirect to /dashboard
/dashboard                 -> Dashboard            (FZ-031)
/restrictions              -> Restriction list     (FZ-032)
/restrictions/new          -> Create restriction   (FZ-033)
/restrictions/:id          -> Restriction detail   (FZ-034)
/restrictions/:id/edit     -> Edit restriction     (FZ-034)
/catalog                   -> Catalog management   (FZ-036)
/settings                  -> Integrations, API keys, advance warning (FZ-045, FZ-038)
/deployment-checks         -> Deployment checks    (FZ-071)
/audit                     -> Audit trail         (FZ-039)
*                          -> Not found
```

Every route except the dev sign-in is authenticated: without a token the app redirects to sign-in rather than rendering an empty page.

Added after Milestone 3, each with its own backlog item: catalog management (`FZ-036`), integrations (`FZ-045`), API keys and organization settings (`FZ-038`), the deployment checks console (`FZ-071`), and the audit screen (`FZ-039`).

The checks console is the one route open to any member rather than administrators only: it is where a team looks to see whether their own deployment got through, and making them ask an administrator would defeat the point.

## Page responsibilities

### Dashboard (`FZ-031`)

Answers `00-product.md`'s first question — "what deployment restrictions are active or upcoming?" Three groups: **active**, **upcoming**, **recently completed**.

Sourced from `GET /api/restrictions?status=…`; the list endpoint already supports repeating the parameter, so active+upcoming is one request (`?status=ACTIVE&status=SCHEDULED`) and recently completed a second (`?status=COMPLETED`). "Recently" is a presentation-side cap on an already sorted list — the backend applies no recency window.

Each entry links to its detail route. No scope is shown here: the list endpoint returns summaries without scope by design (`FZ-021`).

### Restriction list (`FZ-032`)

Browsing and filtering by status. Filter state belongs in the URL query string so a filtered view is linkable and survives reload. Ordering is the backend's (soonest start first) and is not re-sorted client-side.

### Create restriction (`FZ-033`)

Form for `POST /api/restrictions`: name, description (optional), reason (**required**), level, start/end, and the three scope dimensions.

Client-side validation with Zod mirrors the backend rules **for fast feedback only** — it never becomes the source of truth. The backend re-validates everything and its rejection is always authoritative:

- `startsAt < endsAt`
- window not entirely in the past
- at least one scope target across all three dimensions
- `reason` non-blank

Scope pickers need teams/applications/environments to choose from, fetched from the catalog endpoints. **See Open questions** — nothing in Milestone 3 creates that catalog data.

### Restriction detail (`FZ-034`)

Full representation from `GET /api/restrictions/{id}`, including scope. Shows status, dates, reason, level, and scope. Offers **cancel** (`POST /api/restrictions/{id}/cancel`) and **edit** (`PUT`) — but only when the backend rules permit, i.e. edit only while `SCHEDULED`, cancel only while `SCHEDULED` or `ACTIVE`. Those affordances are *hidden or disabled* from the returned `status`; the backend still enforces them, and a `409` is surfaced rather than swallowed.

### Deployment checks (`FZ-071`, `FZ-120`)

Every question the gate was asked and what it answered, with a fortnight's chart and figures above the list. Filter state lives in the URL, so "everything we refused" is a link.

**"Can I deploy?"** (`FZ-120`) sits at the top of this page, above the chart: an application, an environment, and the answer the gate would give, from `GET /api/deployment-checks/preview`. The decision, the sentence and the matched restrictions are all rendered as the backend sent them — nothing here decides anything, because a product that could disagree with the gate would not be worth asking.

Two consequences of `D-29` are visible on this screen and are the reason the control lives here rather than on the dashboard. A person's check is **not** recorded, so it never appears in the list underneath it — the panel says so, beside the list where somebody would otherwise look for it. And the preview invalidates no query: there is no new row for the list to fetch.

Asked with a mutation rather than a query, deliberately: a cached answer is a stale one, and it is a question somebody puts rather than something the page fetches on open.

## API interaction conventions

**Base URL** from `VITE_API_BASE_URL`, defaulting to the local backend. Never hardcoded.

**One fetch wrapper** is the single place that attaches `Authorization: Bearer <token>`, sets `Content-Type`, and normalises errors. No component calls `fetch` directly.

**TanStack Query** owns all server state — no server data duplicated into component state. Query keys mirror the resource shape, e.g. `['restrictions', { status }]`, `['restriction', id]`. After a successful create, update or cancel, invalidate `['restrictions']` and the affected `['restriction', id]` so the dashboard and list reflect the change without a manual refetch.

**HTTP status handling**, consistent with what the backend already returns:

| Status | Meaning here | Frontend behaviour |
|---|---|---|
| `400` | validation rejected | show the message against the form |
| `401` | missing/invalid/unknown token | clear the token, redirect to sign-in |
| `403` | not an Administrator (invite only) | explain, do not retry |
| `404` | unknown **or another tenant's** resource | "not found" — never implies existence |
| `409` | state conflict (edit non-scheduled, re-cancel, duplicate name) | surface the backend's message; refetch, as the state has moved |

Error bodies are **RFC 9457 Problem Details** (`FZ-061`), so the wrapper reads `detail` and exposes `fieldErrors` from the `errors` extension. Where a response names specific fields, the wrapper composes them into the message — "the request has 2 invalid fields" tells someone staring at a form nothing about which two.

It still tolerates a body it cannot parse, and that is not leftover caution: a `401` from the security chain has no body at all, and anything served by a proxy in front of the API is outside the backend's control entirely.

## Confirming what cannot be undone

Irreversible actions ask first through `ConfirmDialog` (`FZ-202`), never through
`window.confirm`, `alert` or `prompt` — `no-alert` makes those a lint error.

- The title **names the thing**: `Cancel “Peak trading”?`, not `Are you sure?`.
- The confirm button **says the action in full**; the dismiss button **says what it keeps**
  and never repeats the action word.
- Focus opens on the dismiss button.
- Tests assert the dialog's accessible name, not merely that it opened. Asserting that a
  confirmation *happened* is how `FZ-188` shipped one that named nothing.

## Time zone convention

Follows `01-domain.md` invariants 9 and 10: instants are stored and compared in UTC, and **time zone is purely presentational**.

- **Display** in the viewer's local zone, always with the zone shown, so "freeze starts 09:00" is never ambiguous.
- **Send** ISO-8601 UTC. `<input type="datetime-local">` yields a *zoneless* local string, so it must be converted to UTC before submission — doing this wrong is the most likely correctness bug in the create form, and it is why it is called out here.
- Never adjust a stored instant for display purposes beyond formatting.

## Authentication

Cognito issues the token; the frontend sends it as `Authorization: Bearer <token>` (`06-security.md`). Token in memory plus `sessionStorage` so a reload does not sign the user out; on `401` it is cleared and the user returns to sign-in.

**Development, until `FZ-063` provisions a real user pool:** decided by `FZ-030` — a dev-only endpoint, exposed **only** under the backend's `local` profile, mints the same locally-signed JWT the tests use. This mirrors the local/real split `06-security.md` already established for JWT *validation*, and it cannot exist in a deployed environment: the bean is `@Profile("local")`, and no deployed environment activates that profile. The frontend's sign-in screen is a thin dev affordance to be replaced by the Cognito Hosted UI redirect at `FZ-063`.

This endpoint is **not** part of `FZ-030` (a documentation story). It is required before `FZ-031` can render anything authenticated — see Open questions.

## Structure

Extends the feature-oriented layout in `02-architecture.md`, adding directories only as a story needs them:

```text
src/
├── app/          # App, router, layout, auth guard
├── features/
│   ├── auth/
│   ├── dashboard/
│   └── restrictions/
├── components/   # shared presentational pieces
├── api/          # fetch wrapper, typed endpoint functions
├── hooks/
├── types/        # request/response types mirroring the API
└── utils/        # date/UTC helpers
```

`features/applications`, `teams`, `environments`, `integrations` and `audit` stay unbuilt until their stories exist.

## Testing

Vitest + Testing Library (wired in `FZ-003`). Tests assert **behaviour through the rendered UI**, not implementation details. Network is stubbed at the fetch boundary so tests never require a running backend. Each page story covers at least: loading, empty, error, and populated states — the empty and error states are the ones most often skipped and most often broken.

## Tracked follow-ups

Each gap this specification surfaced now has an owner in `08-backlog.md`, rather than living only as prose here:

| Gap | Tracked as | Sequencing |
|---|---|---|
| No way to obtain a token in development until a Cognito pool exists | **`FZ-035`** — Development Sign-In Token | before `FZ-031` |
| No UI creates the catalog data `FZ-033`'s scope pickers need | **`FZ-036`** — Catalog Management UI | before `FZ-033` |
| UTC conversion of `datetime-local` values | acceptance criterion on **`FZ-033`** | within `FZ-033` |
| ~~Error response bodies are not standardised until after this milestone~~ | **`FZ-061`** | done — the wrapper now reads Problem Details and surfaces field errors |

`FZ-036` carries an explicit alternative: deferring it and accepting catalog setup as an API-only onboarding operation is a legitimate product choice — the same posture already taken for provisioning an organization's first user. What must not happen is `FZ-033` being built on the unexamined assumption that the data is there.

## Milestone 3 order

```text
FZ-030 (this document)
  ↓
FZ-035   backend: dev sign-in token
  ↓
FZ-031   dashboard
  ↓
FZ-032   restriction list
  ↓
FZ-036   catalog management UI   (or an explicit decision to defer it)
  ↓
FZ-033   create restriction
  ↓
FZ-034   restriction detail
```
