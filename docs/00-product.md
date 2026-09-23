# FreezeHub — MVP Product Definition

## Problem

Engineering organizations introduce deployment/code freezes during high-risk periods such as holidays, commercial events, migrations, incidents, or infrastructure changes.

Freeze information is commonly fragmented across messaging tools, email, calendars, documents, CI/CD systems, and tribal knowledge.

Engineers may not know:

- whether a freeze is active;
- when it starts or ends;
- which applications or environments are affected;
- whether deployments are blocked or only discouraged;
- where the authoritative information lives.

## Product

FreezeHub provides one authoritative place to create, communicate, inspect, and evaluate deployment freezes.

The MVP must answer two questions especially well:

1. **What deployment restrictions are active or upcoming?**
2. **Is this deployment currently allowed?**

## Primary Actors

### Organization Administrator

Configures the organization, users, catalog, integrations, and machine access.

### Release / Engineering Manager

Creates, updates, schedules, and cancels deployment restrictions.

Requires `ADMINISTRATOR` (`FZ-190`): these are administrator actions, so this actor and the Organization Administrator are the same role today. Distinguishing them is a new role and a new decision.

### Engineer

Checks active/upcoming restrictions and determines whether their application/environment is affected.

Read-only (`FZ-190`). `MEMBER` may read everything in the organization and change nothing.

### CI/CD System

Calls the Policy Evaluation API before deploying.

## MVP Capabilities

### Organization Foundation

- Multi-tenant organization boundary.
- Users belong to an organization.

### Software Catalog

- Teams.
- Applications.
- Environments.
- Basic ownership relationships.

### Deployment Restriction Management

- Create restriction.
- Update a scheduled restriction.
- View restriction details.
- List restrictions.
- Cancel restriction.
- Automatically manage restriction lifecycle.

### Restriction Scope

The initial scope model supports:

- environments;
- teams;
- applications.

Repository and tag-based scope can be added later if validation shows they are needed.

### Restriction Levels

- `ADVISORY`: communicates risk but does not block deployment.
- `HARD_FREEZE`: blocks matching deployment policy evaluations.

### Notifications

Initial channels:

- Slack;
- email;
- generic webhook.

Relevant lifecycle notifications include:

- restriction scheduled;
- restriction starting soon;
- restriction activated;
- restriction cancelled;
- restriction completed.

### Policy Evaluation API

Machine clients can ask whether a deployment for an application/environment is currently allowed.

### API Keys

CI/CD and other machine clients can authenticate without user credentials.

### Audit

Important administrative and lifecycle actions are recorded.

### Dashboard

The initial dashboard shows:

- active restrictions;
- upcoming restrictions;
- recently completed restrictions.

## Core User Journey

```text
Manager creates restriction
        ↓
Defines dates, level and scope
        ↓
Restriction is SCHEDULED
        ↓
Notifications are delivered
        ↓
Start time arrives
        ↓
Restriction becomes ACTIVE
        ↓
CI/CD evaluates deployment
        ↓
FreezeHub returns ALLOW or BLOCK
        ↓
End time arrives
        ↓
Restriction becomes COMPLETED
        ↓
Completion notification is delivered
```

## Out of Scope

The MVP does not include:

- exception request workflows;
- approval workflows;
- native GitHub/GitLab applications;
- Jenkins/Argo CD plugins;
- Jira;
- ServiceNow;
- PagerDuty;
- advanced RBAC;
- enterprise SSO;
- mobile applications;
- AI features;
- dependency graphs;
- custom policy language;
- change intelligence;
- infrastructure/database-specific freeze behavior;
- microservices.

## Product Principles

1. **Central authority** — FreezeHub is the authoritative source for freeze state.
2. **Tool agnostic** — Core business logic does not depend on a CI/CD vendor.
3. **Simple integration** — REST provides value before native integrations exist.
4. **Clear decisions** — Policy results are understandable by humans and machines.
5. **Safe by default** — Tenant isolation and auditability are MVP requirements.
6. **Small MVP** — A feature belongs in the MVP only if it directly improves creation, communication, visibility, or evaluation of freezes.

## Validation Signals

Early validation should measure behavior:

- organizations creating at least one restriction;
- organizations creating a second restriction;
- restrictions created per organization;
- applications represented in FreezeHub;
- policy evaluations performed;
- CI/CD pipelines using the Policy API;
- notification delivery success.

The strongest early signal is repeated use for a later freeze event.
