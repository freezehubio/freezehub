import { useQuery } from '@tanstack/react-query'
import { apiRequest } from '../../api/client'
import { useAuth } from './authContext'

/** What `/api/me` returns — `AuthenticatedUser` on the backend. */
export interface CurrentUser {
  userId: number
  organizationId: number
  email: string
  role: 'ADMINISTRATOR' | 'MEMBER'
}

/**
 * Who is signed in, and what they are allowed to do (`FZ-190`).
 *
 * <p>Cached for a minute, not for the session. Roles change since `FZ-212`: an administrator
 * demoted by a colleague must stop being offered administrator buttons without having to
 * reload, and a change to one's own role invalidates `['me']` at once. A minute keeps a
 * request from sitting in front of every navigation.
 */
export function useCurrentUser() {
  const { token } = useAuth()

  return useQuery<CurrentUser>({
    queryKey: ['me'],
    queryFn: ({ signal }) => apiRequest<CurrentUser>('/api/me', { token, signal }),
    staleTime: 60_000,
  })
}

/**
 * Whether to offer an action only an administrator may take.
 *
 * <b>This hides affordances; it does not enforce anything.</b> The rule lives in the backend,
 * where `@PreAuthorize` refuses the request regardless of what this returns — the frontend
 * must not become a second source of truth for a domain rule (`CLAUDE.md` §5). What it
 * prevents is a member filling in a form and being refused at submit.
 *
 * <b>Defaults to false while loading.</b> Briefly hiding a button an administrator may use is
 * a smaller wrong than briefly offering one a member cannot.
 */
export function useCanManage(): boolean {
  const { data } = useCurrentUser()
  return data?.role === 'ADMINISTRATOR'
}
