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
 * <p>Cached for the session: a role does not change while somebody is looking at a page, and
 * re-fetching it per screen would put a request in front of every navigation.
 */
export function useCurrentUser() {
  const { token } = useAuth()

  return useQuery<CurrentUser>({
    queryKey: ['me'],
    queryFn: ({ signal }) => apiRequest<CurrentUser>('/api/me', { token, signal }),
    staleTime: Infinity,
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
