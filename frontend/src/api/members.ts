import { apiRequest } from './client'
import type { Member, MemberPage } from '../types/api'

type Role = Member['role']

/**
 * Administrator only (`FZ-212`). One page of the organization, newest first, removed members
 * included. `page` counts from 0; the page size is the server's.
 */
export function listMembers(token: string | null, page: number, signal?: AbortSignal): Promise<MemberPage> {
  return apiRequest<MemberPage>(`/api/members?page=${page}`, { token, signal })
}

/** What `/api/invites` answers — its own shape, older than the members list (`FZ-016`). */
export interface InvitedUser {
  userId: number
  email: string
  role: Role
}

/**
 * Adds someone. Cognito emails them a temporary password.
 *
 * Returns the invite endpoint's own shape rather than a `Member`: the screen refetches the
 * list afterwards, and typing this as a `Member` would claim fields the response lacks.
 */
export function inviteMember(token: string | null, email: string, role: Role): Promise<InvitedUser> {
  return apiRequest<InvitedUser>('/api/invites', { method: 'POST', body: { email, role }, token })
}

export function changeMemberRole(token: string | null, id: number, role: Role): Promise<Member> {
  return apiRequest<Member>(`/api/members/${id}`, { method: 'PATCH', body: { role }, token })
}

/** Removal. They cannot sign in, but stay on the record for what they did. */
export function deactivateMember(token: string | null, id: number): Promise<Member> {
  return apiRequest<Member>(`/api/members/${id}/deactivate`, { method: 'POST', token })
}

export function reactivateMember(token: string | null, id: number): Promise<Member> {
  return apiRequest<Member>(`/api/members/${id}/reactivate`, { method: 'POST', token })
}
