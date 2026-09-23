import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { RestrictionsPage } from './RestrictionsPage'
import { renderRoute } from '../../test/renderRoute'
import type { RestrictionSummary } from '../../types/api'

function stubFetch(body: unknown, status = 200) {
  // Declares the real fetch parameters so the recorded call arguments stay typed and the
  // URL assertions below are checked rather than cast.
  const spy = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
    Promise.resolve(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  )
  vi.stubGlobal('fetch', spy)
  return spy
}

function restriction(overrides: Partial<RestrictionSummary> = {}): RestrictionSummary {
  return {
    id: 1,
    name: 'Black Friday Freeze',
    reason: 'Revenue-critical period',
    type: 'DEPLOYMENT_FREEZE',
    level: 'HARD_FREEZE',
    status: 'SCHEDULED',
    startsAt: '2026-11-27T00:00:00Z',
    endsAt: '2026-12-02T00:00:00Z',
    createdBy: 1,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  }
}

function lastRequestUrl(spy: ReturnType<typeof stubFetch>): string {
  return String(spy.mock.calls[spy.mock.calls.length - 1][0])
}

describe('RestrictionsPage', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('shows a loading state first', () => {
    stubFetch([])
    renderRoute(<RestrictionsPage />, { path: '/restrictions' })

    expect(screen.getByRole('status')).toHaveTextContent(/loading/i)
  })

  test('lists restrictions returned by the API', async () => {
    stubFetch([
      restriction({ id: 1, name: 'Peak trading', status: 'ACTIVE' }),
      restriction({ id: 2, name: 'Migration window', level: 'ADVISORY' }),
    ])
    renderRoute(<RestrictionsPage />, { path: '/restrictions' })

    const table = await screen.findByRole('table')
    expect(within(table).getByRole('link', { name: 'Peak trading' })).toHaveAttribute(
      'href',
      '/restrictions/1',
    )
    expect(within(table).getByText('Migration window')).toBeInTheDocument()
    expect(within(table).getByText('Blocks deploys')).toBeInTheDocument()
    expect(within(table).getByText('Advisory')).toBeInTheDocument()
  })

  test('says so when there is nothing at all', async () => {
    stubFetch([])
    renderRoute(<RestrictionsPage />, { path: '/restrictions' })

    expect(await screen.findByText('No restrictions yet.')).toBeInTheDocument()
  })

  test('distinguishes an empty filter result from an empty account', async () => {
    // Different message on purpose: "nothing matches your filter" and "you have nothing"
    // are very different things to tell someone.
    stubFetch([])
    renderRoute(<RestrictionsPage />, { path: '/restrictions?status=CANCELLED' })

    expect(await screen.findByText('No restrictions match this filter.')).toBeInTheDocument()
  })

  test('surfaces an error with a retry', async () => {
    stubFetch({ message: 'Boom' }, 500)
    renderRoute(<RestrictionsPage />, { path: '/restrictions' })

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not load/i)
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
  })

  test('applies the filter from the URL to the request and the checkboxes', async () => {
    const spy = stubFetch([])
    renderRoute(<RestrictionsPage />, { path: '/restrictions?status=ACTIVE&status=SCHEDULED' })

    await waitFor(() => expect(spy).toHaveBeenCalled())
    const url = lastRequestUrl(spy)
    expect(url).toContain('status=ACTIVE')
    expect(url).toContain('status=SCHEDULED')

    expect(screen.getByRole('checkbox', { name: /active/i })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /scheduled/i })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /completed/i })).not.toBeChecked()
  })

  test('ignores a status the API would not understand', async () => {
    // The filter comes from the URL, where anyone can type anything.
    const spy = stubFetch([])
    renderRoute(<RestrictionsPage />, { path: '/restrictions?status=NONSENSE' })

    await waitFor(() => expect(spy).toHaveBeenCalled())
    expect(lastRequestUrl(spy)).not.toContain('NONSENSE')
    expect(lastRequestUrl(spy)).not.toContain('status=')
  })

  test('ticking a status puts it in the URL so the view can be linked to', async () => {
    const user = userEvent.setup()
    stubFetch([])
    const { router } = renderRoute(<RestrictionsPage />, { path: '/restrictions' })

    await user.click(await screen.findByRole('checkbox', { name: /active/i }))

    await waitFor(() => {
      expect(router.state.location.search).toContain('status=ACTIVE')
    })
  })

  test('clearing the filter empties the query string', async () => {
    const user = userEvent.setup()
    stubFetch([])
    const { router } = renderRoute(<RestrictionsPage />, {
      path: '/restrictions?status=ACTIVE',
    })

    await user.click(await screen.findByRole('button', { name: /clear/i }))

    await waitFor(() => {
      expect(router.state.location.search).toBe('')
    })
  })

  test('does not re-sort what the API returned', async () => {
    // Ordering is the backend's contract (soonest start first), not this page's.
    stubFetch([
      restriction({ id: 1, name: 'Zulu', startsAt: '2026-01-01T00:00:00Z' }),
      restriction({ id: 2, name: 'Alpha', startsAt: '2026-06-01T00:00:00Z' }),
    ])
    renderRoute(<RestrictionsPage />, { path: '/restrictions' })

    const rows = within(await screen.findByRole('table')).getAllByRole('row')
    // rows[0] is the header
    expect(within(rows[1]).getByText('Zulu')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Alpha')).toBeInTheDocument()
  })
  describe('cancelling from the list (FZ-188)', () => {
    test('offers cancel only where the status allows it', async () => {
      stubFetch([
        restriction({ id: 1, name: 'Scheduled one', status: 'SCHEDULED' }),
        restriction({ id: 2, name: 'Active one', status: 'ACTIVE' }),
        restriction({ id: 3, name: 'Completed one', status: 'COMPLETED' }),
        restriction({ id: 4, name: 'Cancelled one', status: 'CANCELLED' }),
      ])
      renderRoute(<RestrictionsPage />, { path: '/restrictions' })

      await screen.findByRole('table')
      expect(screen.getByRole('button', { name: 'Cancel Scheduled one' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Cancel Active one' })).toBeInTheDocument()
      // Absent, not disabled. A greyed control still reads as "there is something here".
      expect(screen.queryByRole('button', { name: 'Cancel Completed one' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Cancel Cancelled one' })).not.toBeInTheDocument()
    })

    test('asks before cancelling, and does nothing when declined', async () => {
      const spy = stubFetch([restriction({ id: 7, name: 'Peak trading', status: 'ACTIVE' })])
      renderRoute(<RestrictionsPage />, { path: '/restrictions' })

      await screen.findByRole('table')
      const before = spy.mock.calls.length
      await userEvent.click(screen.getByRole('button', { name: 'Cancel Peak trading' }))
      await userEvent.click(
        within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Keep it' }),
      )

      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
      expect(spy.mock.calls.length).toBe(before)
    })

    /**
     * The assertion FZ-188 did not have (`FZ-202`). It checked that a confirmation was
     * *shown*, never what it *said*, and shipped `Cancel ""?` with every check green — the
     * name had been lost from the source as the file was written. The question is only
     * worth asking if it names the freeze about to be lifted.
     */
    test('names the freeze it is about to cancel', async () => {
      stubFetch([restriction({ id: 7, name: 'Peak trading', status: 'ACTIVE' })])
      renderRoute(<RestrictionsPage />, { path: '/restrictions' })

      await screen.findByRole('table')
      await userEvent.click(screen.getByRole('button', { name: 'Cancel Peak trading' }))

      expect(
        screen.getByRole('alertdialog', { name: 'Cancel “Peak trading”?' }),
      ).toBeInTheDocument()
    })

    test('asks in the product, not through the browser', async () => {
      stubFetch([restriction({ id: 7, name: 'Peak trading', status: 'ACTIVE' })])
      const native = vi.spyOn(window, 'confirm')
      renderRoute(<RestrictionsPage />, { path: '/restrictions' })

      await screen.findByRole('table')
      await userEvent.click(screen.getByRole('button', { name: 'Cancel Peak trading' }))

      expect(native).not.toHaveBeenCalled()
      expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    })

    test('posts to the same endpoint the detail page uses', async () => {
      // The audit record must not be able to tell the two routes apart, which is only true
      // while both post here.
      const spy = stubFetch([restriction({ id: 7, name: 'Peak trading', status: 'ACTIVE' })])
      renderRoute(<RestrictionsPage />, { path: '/restrictions' })

      await screen.findByRole('table')
      await userEvent.click(screen.getByRole('button', { name: 'Cancel Peak trading' }))
      await userEvent.click(screen.getByRole('button', { name: 'Cancel freeze' }))

      await waitFor(() => {
        const posted = spy.mock.calls.find(
          ([, init]) => (init as RequestInit | undefined)?.method === 'POST',
        )
        expect(posted).toBeDefined()
        expect(String(posted![0])).toContain('/api/restrictions/7/cancel')
      })
    })

    test('reports a refusal rather than pretending it worked', async () => {
      // 409 is the real case: it finished or was cancelled while this list was open.
      vi.stubGlobal(
        'fetch',
        vi.fn((_input: RequestInfo | URL, init?: RequestInit) =>
          Promise.resolve(
            init?.method === 'POST'
              ? new Response(JSON.stringify({ detail: 'Already completed.' }), {
                  status: 409,
                  headers: { 'Content-Type': 'application/json' },
                })
              : new Response(
                  JSON.stringify([restriction({ id: 7, name: 'Peak trading', status: 'ACTIVE' })]),
                  { status: 200, headers: { 'Content-Type': 'application/json' } },
                ),
          ),
        ),
      )
      renderRoute(<RestrictionsPage />, { path: '/restrictions' })

      await screen.findByRole('table')
      await userEvent.click(screen.getByRole('button', { name: 'Cancel Peak trading' }))
      await userEvent.click(screen.getByRole('button', { name: 'Cancel freeze' }))

      // Reported on the page, with the dialog closed so the message can be read.
      expect(await screen.findByRole('alert')).toHaveTextContent('Already completed.')
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    })
  })

  describe('what a member sees (FZ-190)', () => {
    test('is offered no way to create or cancel', async () => {
      stubFetch([
        restriction({ id: 1, name: 'Peak trading', status: 'ACTIVE' }),
        restriction({ id: 2, name: 'Year-end close', status: 'SCHEDULED' }),
      ])
      renderRoute(<RestrictionsPage />, { path: '/restrictions', role: 'MEMBER' })

      await screen.findByRole('table')
      expect(screen.queryByRole('link', { name: 'New restriction' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /^Cancel / })).not.toBeInTheDocument()
    })

    test('can still read every restriction', async () => {
      // The half that matters. An engineer's whole use of FreezeHub is finding out whether
      // they may deploy; hiding the actions must not hide the answer.
      stubFetch([restriction({ id: 1, name: 'Peak trading', status: 'ACTIVE' })])
      renderRoute(<RestrictionsPage />, { path: '/restrictions', role: 'MEMBER' })

      const table = await screen.findByRole('table')
      expect(within(table).getByRole('link', { name: 'Peak trading' })).toBeInTheDocument()
      expect(within(table).getByText('Revenue-critical period')).toBeInTheDocument()
    })
  })
})
