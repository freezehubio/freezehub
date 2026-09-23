import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import type { ReactElement } from 'react'
import { AuthProvider } from '../features/auth/AuthProvider'

/**
 * Renders a component at a route with the providers the app supplies, so tests exercise
 * it the way it actually runs.
 *
 * Retries are off: a test asserting an error state should not wait for TanStack Query to
 * exhaust attempts first.
 */
/**
 * `path` is the URL actually visited. `route` is the pattern to match it against, needed
 * whenever the page reads params — without it a component would receive the literal
 * ":restrictionId" instead of an id.
 */
export function renderRoute(
  element: ReactElement,
  options: {
    path?: string
    route?: string
    token?: string | null
    /**
     * Who is signed in, for anything gated on role (`FZ-190`).
     *
     * Defaults to ADMINISTRATOR so existing tests keep exercising the behaviour they were
     * written for; pass 'MEMBER' to assert what a read-only user sees.
     */
    role?: 'ADMINISTRATOR' | 'MEMBER'
  } = {},
) {
  const { path = '/', route, token = 'test-token', role = 'ADMINISTRATOR' } = options

  if (token === null) {
    sessionStorage.removeItem('freezehub.token')
  } else {
    sessionStorage.setItem('freezehub.token', token)
  }

  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })

  // Seeded rather than fetched. useCurrentUser reads ['me'], and a test that had to stub
  // /api/me alongside the endpoint it actually cares about would be asserting plumbing.
  queryClient.setQueryData(['me'], {
    userId: 1,
    organizationId: 1,
    email: 'test@acme.test',
    role,
  })

  // The route pattern must not carry the query string, but the initial entry must — that
  // is how a page whose filter state lives in the URL gets exercised.
  const [pathname] = path.split('?')
  const pattern = route ?? pathname

  const router = createMemoryRouter(
    [
      { path: pattern, element },
      { path: '/signin', element: <div>Sign in screen</div> },
      /*
       * A catch-all, so anywhere the component navigates lands somewhere. Without it a
       * page that redirects on success — CreateRestrictionPage goes to the new
       * restriction — sends the router to a path nothing matches, and it throws while
       * the test is unmounting. That surfaced as three unhandled rejections that passed
       * the suite anyway, which is exactly the state in which a real one would hide
       * (OI-10).
       *
       * Tests assert where they ended up through the returned `router`, so this makes
       * navigation observable rather than swallowing it.
       */
      { path: '*', element: <div>Navigated away</div> },
    ],
    { initialEntries: [path] },
  )

  const result = render(
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthProvider>,
  )

  // Returned so tests can assert on the URL itself, not just what was rendered.
  return { ...result, router }
}
