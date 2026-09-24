import type { AuditEvent, AuditResourceType } from '../../types/api'

const RESOURCE_NOUN: Record<AuditResourceType, string> = {
  RESTRICTION: 'restriction',
  TEAM: 'team',
  APPLICATION: 'application',
  ENVIRONMENT: 'environment',
  API_KEY: 'API key',
  USER: 'user',
  ORGANIZATION: 'organization',
  POLICY: 'deployment check',
}

/**
 * What happened, in a sentence rather than an enum.
 *
 * `CATALOG_RENAMED` plus `APPLICATION` reads as "renamed application" — the backend
 * deliberately stores the kind separately (`FZ-072`), so the wording is composed here
 * instead of there being nine action names to translate.
 */
export function describeAction(event: AuditEvent): string {
  const noun = RESOURCE_NOUN[event.resourceType] ?? 'record'

  switch (event.action) {
    case 'RESTRICTION_CREATED':
      return 'Created restriction'
    case 'RESTRICTION_UPDATED':
      return 'Edited restriction'
    case 'RESTRICTION_CANCELLED':
      return 'Cancelled restriction'
    case 'RESTRICTION_ACTIVATED':
      return 'Restriction took effect'
    case 'RESTRICTION_COMPLETED':
      return 'Restriction ended'
    case 'CATALOG_CREATED':
      return `Added ${noun}`
    case 'CATALOG_RENAMED':
      return `Renamed ${noun}`
    case 'CATALOG_DELETED':
      return `Removed ${noun}`
    case 'APPLICATION_TEAM_ASSIGNED':
      return 'Application joined a team'
    case 'APPLICATION_TEAM_UNASSIGNED':
      return 'Application left a team'
    case 'API_KEY_ISSUED':
      return 'Issued an API key'
    case 'API_KEY_REVOKED':
      return 'Revoked an API key'
    case 'USER_INVITED':
      return 'Invited a member'
    case 'USER_ROLE_CHANGED':
      return "Changed a member's role"
    case 'USER_DEACTIVATED':
      return 'Removed a member'
    case 'USER_REACTIVATED':
      return 'Reinstated a member'
    case 'ORGANIZATION_SETTINGS_CHANGED':
      return 'Changed organization settings'
    case 'POLICY_BLOCKED_UNREGISTERED':
      return 'Refused a deployment for an unregistered name'
    default:
      // A backend that has learned a new action must not render as a blank row.
      return event.action.toLowerCase().replace(/_/g, ' ')
  }
}

function isChange(value: unknown): value is { from: unknown; to: unknown } {
  return typeof value === 'object' && value !== null && 'from' in value && 'to' in value
}

function show(value: unknown): string {
  if (value === null || value === undefined) return 'nothing'
  if (Array.isArray(value)) return value.length ? value.join(', ') : 'nothing'
  return String(value)
}

/**
 * Turns the recorded `details` into something readable.
 *
 * Two shapes reach here and both have to work: a before/after diff from an edit
 * (`{"name": {"from": …, "to": …}}`) and a flat object from everything else
 * (`{"name": "gitlab-ci"}`). Rendering the raw JSON instead would push the job of parsing
 * it onto whoever is reading the screen during an incident.
 */
export function describeDetails(details: Record<string, unknown> | null): string[] {
  if (!details) return []

  return Object.entries(details).map(([field, value]) =>
    isChange(value)
      ? `${field}: ${show(value.from)} → ${show(value.to)}`
      : `${field}: ${show(value)}`,
  )
}
