/**
 * Types mirroring the backend API. Kept deliberately close to the wire format rather
 * than reshaped, so a change on either side is obvious here first.
 */

export type RestrictionLevel = 'ADVISORY' | 'HARD_FREEZE'

export type RestrictionStatus = 'SCHEDULED' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED'

export type RestrictionType = 'DEPLOYMENT_FREEZE'

/** Shape returned by `GET /api/restrictions` — no scope, by design (FZ-021). */
export interface RestrictionSummary {
  id: number
  name: string
  reason: string
  type: RestrictionType
  level: RestrictionLevel
  status: RestrictionStatus
  /** ISO-8601 UTC instant. */
  startsAt: string
  /** ISO-8601 UTC instant. */
  endsAt: string
  createdBy: number
  createdAt: string
  updatedAt: string
}

/** Scope, as the API expresses it: one list per dimension (01-domain.md). */
export interface RestrictionScope {
  teamIds: number[]
  applicationIds: number[]
  environmentIds: number[]
}

/** Full representation from `GET /api/restrictions/{id}` — summary plus scope (FZ-022). */
export interface RestrictionDetail extends RestrictionSummary {
  description: string | null
  scope: RestrictionScope
}

/**
 * `POST /api/restrictions` body. No `type` or `status`: both are server-controlled, so
 * the client does not get to state them (FZ-020).
 *
 * `startsAt`/`endsAt` are ISO-8601 **UTC** instants — never the zoneless value a
 * datetime-local input produces.
 */
export interface CreateRestrictionBody {
  name: string
  description?: string | null
  reason: string
  level: RestrictionLevel
  startsAt: string
  endsAt: string
  scope: RestrictionScope
}

/** Teams and environments: name plus timestamps (FZ-013, FZ-015). */
export interface CatalogEntry {
  id: number
  name: string
  createdAt: string
  updatedAt: string
}

/** Applications additionally carry their associated teams (FZ-014). */
export interface ApplicationSummary extends CatalogEntry {
  teamIds: number[]
}

/** Shape returned by `POST /api/dev/token` (local development only — FZ-035). */
export interface DevSignInResponse {
  token: string
  userId: number
  organizationId: number
  email: string
  role: 'ADMINISTRATOR' | 'MEMBER'
}

export interface CurrentUser {
  userId: number
  organizationId: number
  email: string
  role: 'ADMINISTRATOR' | 'MEMBER'
}

export type IntegrationType = 'SLACK' | 'EMAIL' | 'WEBHOOK'

/**
 * Note the absent `config`: the API never returns stored channel settings, because they
 * can hold a credential. `summary` identifies a destination without being enough to
 * reuse it.
 */
export interface Integration {
  id: number
  type: IntegrationType
  enabled: boolean
  summary: string
  /** Announcements to this channel that were given up on and never arrived (`FZ-117`). */
  failedDeliveries: number
  createdAt: string
  updatedAt: string
}

/** A machine credential. Note the absence of the key itself — see `IssuedApiKey`. */
export interface ApiKey {
  id: number
  name: string
  keyPrefix: string
  createdBy: number
  createdAt: string
  /**
   * The day this key last authenticated something, or null if it never has.
   *
   * A date, not an instant: the question is whether anything still uses it, and a day
   * answers that without making the deployment gate a write path (`FZ-117`).
   */
  lastUsedOn: string | null
  revokedAt: string | null
  revoked: boolean
}

/** The creation response, and the only place the raw `key` ever appears. */
export interface IssuedApiKey {
  id: number
  name: string
  keyPrefix: string
  key: string
  createdBy: number
  createdAt: string
}

export interface Organization {
  id: number
  name: string
  startingSoonLeadTimeMinutes: number
}

/** A restriction as it was when a check happened — denormalised, immune to a later rename. */
export interface MatchedRestrictionSummary {
  id: number
  name: string
  level: RestrictionLevel
}

/**
 * A record that somebody asked whether they could deploy, and what they were told.
 *
 * A check, not a deployment: FreezeHub sees the question and nothing after it.
 */
export interface DeploymentCheck {
  id: number
  application: string
  environment: string
  decision: 'ALLOW' | 'BLOCK'
  blockedReason: 'RESTRICTION' | 'UNREGISTERED' | null
  matchedRestrictions: MatchedRestrictionSummary[] | null
  actor: string | null
  reference: string | null
  source: string | null
  /** The credential that asked — a pipeline, never a person. `actor` is the person. */
  checkedBy: string
  checkedAt: string
}

export type AuditActorType = 'USER' | 'SYSTEM' | 'API_KEY'

export type AuditResourceType =
  | 'RESTRICTION'
  | 'TEAM'
  | 'APPLICATION'
  | 'ENVIRONMENT'
  | 'API_KEY'
  | 'USER'
  | 'ORGANIZATION'
  | 'POLICY'

/** A change, as it was recorded. `details` is either a before/after diff or a flat object. */
export interface AuditEvent {
  id: number
  actorType: AuditActorType
  actorId: number | null
  actorLabel: string
  action: string
  resourceType: AuditResourceType
  resourceId: number | null
  details: Record<string, unknown> | null
  occurredAt: string
}

/** One countable resource, and how close the plan is to refusing the next one (`FZ-085`). */
export interface PlanUsage {
  resource: string
  current: number
  /** Null means unlimited, which is why it is not a number. */
  limit: number | null
  percentUsed: number | null
  atLimit: boolean
}

export interface Subscription {
  plan: string
  status: "TRIALING" | "ACTIVE" | "PAST_DUE" | "SUSPENDED" | "CANCELLED"
  canUpgradeSelfServe: boolean
  /** Whether this plan's freezes actually block a deployment (`FZ-146`, `D-33`). */
  blocksDeployments: boolean
  hasBillingAccount: boolean
  trialEndsAt: string | null
  trialDaysRemaining: number | null
  currentPeriodEndsAt: string | null
  usage: PlanUsage[]
}

/**
 * What the deployment checks add up to (`FZ-105`) — `GET /api/deployment-checks/summary`.
 *
 * Days are UTC, and the series covers a fixed fortnight including days on which nothing
 * happened, so a chart drawn from it does not compress time (`D-27`).
 */
export interface DeploymentCheckSummary {
  today: { total: number; allowed: number; refused: number }
  /** `seen` counts catalogued applications that have ever asked, so it never exceeds `total`. */
  applications: { seen: number; total: number }
  daily: { date: string; allowed: number; refused: number }[]
  /** All-time, per restriction. Only hard freezes are credited with a refusal. */
  refusalsByRestriction: { restrictionId: number; refused: number }[]
  /**
   * Refused over the same window as `daily` because a name was not recognised — a
   * different problem from a freeze, and a different fix.
   */
  unregistered: number
}

export type NotificationEvent =
  | 'SCHEDULED'
  | 'STARTING_SOON'
  | 'ACTIVATED'
  | 'COMPLETED'
  | 'CANCELLED'

export type NotificationStatus = 'PENDING' | 'SENT' | 'FAILED'

/** What one channel did with one announcement. */
export interface NotificationDelivery {
  integrationId: number
  channel: IntegrationType | null
  status: NotificationStatus
  attempts: number
  /** Why the last attempt failed, as the destination said it. Null unless it did. */
  lastError: string | null
  sentAt: string | null
}

/**
 * One lifecycle event and every channel's answer to it (`FZ-115`).
 *
 * Grouped by event rather than listed per delivery: the question is "was everybody told?",
 * and a flat list makes that a counting exercise.
 */
export interface NotificationEventRecord {
  restrictionId: number
  restrictionName: string
  event: NotificationEvent
  occurredAt: string
  deliveries: NotificationDelivery[]
}

/**
 * What a restriction actually did (`1d`'s "What it has done", `FZ-112`).
 *
 * Counted from the records it produced rather than stored beside it, so the figures
 * cannot drift from what happened.
 */
export interface RestrictionImpact {
  checksRefused: number
  pipelinesAffected: number
  notificationsSent: number
  notificationsFailed: number
}

/**
 * The answer to "can I deploy?", asked by a person (`FZ-120`).
 *
 * Deliberately the same answer a pipeline gets from `POST /api/policy/evaluate`, minus
 * the action — the backend derives both from one decision, so the product cannot disagree
 * with the gate about whether a freeze applies.
 *
 * Asking leaves no trace: a preview is not recorded as a `DeploymentCheck`, so the checks
 * console keeps meaning "every time a pipeline asked".
 */
export interface DeploymentCheckPreview {
  decision: 'ALLOW' | 'BLOCK'
  application: string
  environment: string
  evaluatedAt: string
  /** The whole answer in one sentence, written by the backend. */
  message: string
  /** Which names were not recognised. Empty is the normal case. */
  unregistered: ('APPLICATION' | 'ENVIRONMENT')[]
  restrictions: {
    id: number
    name: string
    reason: string
    level: RestrictionLevel
    startsAt: string
    endsAt: string
  }[]
}
