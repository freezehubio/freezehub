import { screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { DashboardPage } from './DashboardPage'
import { renderRoute } from '../../test/renderRoute'
import { setupUser } from '../../test/user'
import type {
  DeploymentCheckSummary,
  RestrictionDetail,
  RestrictionSummary,
} from '../../types/api'

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

const emptySummary: DeploymentCheckSummary = {
  today: { total: 0, allowed: 0, refused: 0 },
  applications: { seen: 0, total: 0 },
  daily: [],
  refusalsByRestriction: [],
  unregistered: 0,
}

interface World {
  live?: RestrictionSummary[]
  completed?: RestrictionSummary[]
  details?: Record<number, RestrictionDetail>
  environments?: { id: number; name: string }[]
  summary?: DeploymentCheckSummary
  /** Force a status on the restriction list requests. */
  status?: number
}

/**
 * Network is stubbed at the fetch boundary, so no backend is required. Routed by URL
 * rather than by call order, because the page now issues five different requests and
 * some of them depend on the answer to an earlier one.
 */
function stubWorld(world: World = {}) {
  const spy = vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    const json = (body: unknown, status = 200) =>
      Promise.resolve(
        new Response(JSON.stringify(body), {
          status,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

    if (url.includes('/api/deployment-checks/summary')) {
      return json(world.summary ?? emptySummary)
    }
    if (url.includes('/api/environments')) {
      return json(
        (world.environments ?? [{ id: 1, name: 'production' }, { id: 2, name: 'staging' }]).map(
          (entry) => ({ ...entry, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }),
        ),
      )
    }
    // A single restriction by id — the scope lookup behind the status line.
    const byId = url.match(/\/api\/restrictions\/(\d+)/)
    if (byId) {
      const detail = world.details?.[Number(byId[1])]
      return detail ? json(detail) : json({ message: 'Not found' }, 404)
    }
    if (url.includes('/api/restrictions')) {
      if (world.status && world.status !== 200) return json({ message: 'Boom' }, world.status)
      return json(url.includes('status=ACTIVE') ? (world.live ?? []) : (world.completed ?? []))
    }
    return json([])
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

function activeFreeze(over: Partial<RestrictionSummary> = {}) {
  return restriction({
    id: 1,
    name: 'Black Friday Freeze',
    status: 'ACTIVE',
    level: 'HARD_FREEZE',
    startsAt: '2020-01-01T00:00:00Z',
    endsAt: '2099-12-02T09:00:00Z',
    ...over,
  })
}

function detailFor(summary: RestrictionSummary, environmentIds: number[]): RestrictionDetail {
  return {
    ...summary,
    description: null,
    scope: { teamIds: [], applicationIds: [], environmentIds },
  }
}

describe('DashboardPage', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  test('shows a loading state while restrictions are being fetched', () => {
    stubWorld()
    renderRoute(<DashboardPage />)
    expect(screen.getByRole('status')).toHaveTextContent(/loading/i)
  })

  test('surfaces an error with a way to retry', async () => {
    stubWorld({ status: 500 })
    renderRoute(<DashboardPage />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/could not load restrictions/i)
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
  })

  test('names the environments a freeze in force is blocking', async () => {
    // The status line is the one sentence the page asserts rather than displays, and it
    // has to name what it claims — which means resolving scope ids to catalog names.
    const active = activeFreeze()
    stubWorld({ live: [active], details: { 1: detailFor(active, [1, 2]) } })

    renderRoute(<DashboardPage />)

    expect(
      await screen.findByText('Deploys are blocked in production and staging'),
    ).toBeInTheDocument()
    expect(screen.getByText(/^In force now$/i)).toBeInTheDocument()
  })

  test('an empty environment scope is reported as every environment, not as none', async () => {
    // FZ-020's wildcard rule, in the sentence people read first.
    const active = activeFreeze()
    stubWorld({ live: [active], details: { 1: detailFor(active, []) } })

    renderRoute(<DashboardPage />)

    expect(await screen.findByText('Deploys are blocked in every environment')).toBeInTheDocument()
  })

  test('an advisory in force does not claim deploys are blocked', async () => {
    // An advisory is reported, not enforced. Saying otherwise on the headline would be
    // the most consequential thing this page could get wrong.
    const advisory = activeFreeze({ level: 'ADVISORY' })
    stubWorld({ live: [advisory] })

    renderRoute(<DashboardPage />)

    expect(await screen.findByText('No restriction is blocking deploys.')).toBeInTheDocument()
    expect(screen.queryByText(/deploys are blocked in/i)).not.toBeInTheDocument()
  })

  test('a scheduled restriction is in the forward list, not repeated as a card', async () => {
    // Upcoming showed every scheduled freeze as a card beside its own start and
    // completion lines — the same freeze three times on one page (FZ-113).
    stubWorld({
      live: [
        restriction({ id: 1, name: 'Running now', status: 'ACTIVE', level: 'ADVISORY' }),
        restriction({ id: 2, name: 'Starts later', status: 'SCHEDULED' }),
      ],
    })

    renderRoute(<DashboardPage />)

    const active = await screen.findByRole('region', { name: /active now/i })
    expect(within(active).getByText('Running now')).toBeInTheDocument()
    expect(within(active).queryByText('Starts later')).not.toBeInTheDocument()

    expect(screen.queryByRole('region', { name: /^upcoming$/i })).not.toBeInTheDocument()

    const forward = screen.getByRole('region', { name: /then what/i })
    expect(within(forward).getAllByRole('link', { name: 'Starts later' }).length).toBeGreaterThan(0)
  })

  test('links an active restriction to its detail route', async () => {
    const active = activeFreeze({ id: 42, name: 'Linked' })
    stubWorld({ live: [active], details: { 42: detailFor(active, [1]) } })
    renderRoute(<DashboardPage />)

    const cards = await screen.findByRole('region', { name: /active now/i })
    expect(within(cards).getByRole('link', { name: 'Linked' })).toHaveAttribute(
      'href',
      '/restrictions/42',
    )
  })

  test('the forward list links the restriction it names', async () => {
    // Dropping the Upcoming cards removed the only route from here to a scheduled
    // restriction, so the name in each line carries it instead.
    stubWorld({ live: [restriction({ id: 9, name: 'Year-end close', status: 'SCHEDULED' })] })
    renderRoute(<DashboardPage />)

    const forward = await screen.findByRole('region', { name: /then what/i })
    expect(within(forward).getAllByRole('link', { name: 'Year-end close' })[0]).toHaveAttribute(
      'href',
      '/restrictions/9',
    )
  })

  test('draws three metrics over the fortnight, not four over today', async () => {
    stubWorld({
      live: [restriction({ id: 2, name: 'Later', status: 'SCHEDULED' })],
      summary: {
        today: { total: 12, allowed: 10, refused: 2 },
        applications: { seen: 11, total: 14 },
        daily: [
          { date: '2026-09-01', allowed: 60, refused: 6 },
          { date: '2026-09-02', allowed: 0, refused: 0 },
          { date: '2026-09-03', allowed: 17, refused: 3 },
        ],
        refusalsByRestriction: [],
        unregistered: 0,
      },
    })

    renderRoute(<DashboardPage />)

    const metrics = await screen.findByRole('region', { name: /at a glance/i })
    // 60+6+17+3 asked, 9 of them refused.
    expect(within(metrics).getByText('86')).toBeInTheDocument()
    expect(within(metrics).getByText('12 today')).toBeInTheDocument()
    expect(within(metrics).getByText('9')).toBeInTheDocument()
    expect(within(metrics).getByText('10% of checks')).toBeInTheDocument()
    expect(within(metrics).getByText('11')).toBeInTheDocument()
    expect(within(metrics).getByText('of 14 · 3 never asked')).toBeInTheDocument()

    // The two that only restated the rail and the forward list are gone.
    expect(within(metrics).queryByText('Active')).not.toBeInTheDocument()
    expect(within(metrics).queryByText('Scheduled')).not.toBeInTheDocument()
  })

  test('the completed table shows what each restriction refused', async () => {
    const done = restriction({ id: 7, name: 'Peak trading rehearsal', status: 'COMPLETED' })
    stubWorld({
      completed: [done],
      summary: { ...emptySummary, refusalsByRestriction: [{ restrictionId: 7, refused: 14 }] },
    })

    renderRoute(<DashboardPage />)

    const group = await screen.findByRole('region', { name: /recently completed/i })
    const row = within(group).getByRole('row', { name: /peak trading rehearsal/i })
    expect(within(row).getByText('14')).toBeInTheDocument()
  })

  test('a completed restriction that refused nothing shows zero, not blank', async () => {
    const done = restriction({ id: 7, name: 'Payments incident', status: 'COMPLETED' })
    stubWorld({ completed: [done], summary: emptySummary })

    renderRoute(<DashboardPage />)

    const group = await screen.findByRole('region', { name: /recently completed/i })
    const row = within(group).getByRole('row', { name: /payments incident/i })
    expect(within(row).getByText('0')).toBeInTheDocument()
  })

  test('caps recently completed restrictions and shows the newest first', async () => {
    // The backend orders soonest-first and applies no recency window, so the cap and the
    // reversal are this page's job.
    const completed = Array.from({ length: 8 }, (_, index) =>
      restriction({ id: 100 + index, name: `Done ${index}`, status: 'COMPLETED' }),
    )
    stubWorld({ completed })

    renderRoute(<DashboardPage />)

    const group = await screen.findByRole('region', { name: /recently completed/i })
    await waitFor(() => {
      // One row per restriction, plus the header row.
      expect(within(group).getAllByRole('row')).toHaveLength(6)
    })
    expect(within(group).getByText('Done 7')).toBeInTheDocument()
    expect(within(group).queryByText('Done 2')).not.toBeInTheDocument()
  })

  test('says what happens next, ending with "Clear from here."', async () => {
    const active = activeFreeze()
    stubWorld({ live: [active], details: { 1: detailFor(active, [1]) } })

    renderRoute(<DashboardPage />)

    const forward = await screen.findByRole('region', { name: /then what/i })
    // The name is a link now, so the line is split across elements — read the whole row.
    const lines = within(forward)
      .getAllByRole('listitem')
      .map((line) => line.textContent ?? '')

    expect(
      lines.some((line) => line.includes('Black Friday Freeze completes. Deploys reopen.')),
    ).toBe(true)
    expect(lines.at(-1)).toContain('Clear from here.')
  })

  test('says so plainly when there is nothing ahead', async () => {
    stubWorld()
    renderRoute(<DashboardPage />)

    const forward = await screen.findByRole('region', { name: /then what/i })
    expect(within(forward).getByText(/clear from here/i)).toBeInTheDocument()
  })

  test('sends the bearer token with its requests', async () => {
    stubWorld()
    renderRoute(<DashboardPage />, { token: 'a-real-token' })

    await waitFor(() => expect(fetch).toHaveBeenCalled())
    const [, init] = vi.mocked(fetch).mock.calls[0]
    const headers = (init as RequestInit).headers as Record<string, string>
    expect(headers.Authorization).toBe('Bearer a-real-token')
  })
})

/**
 * The view control (`FZ-191`). What matters is that it *swaps* — the two readings of the
 * same restrictions must not both be on the page, or the page answers its own question
 * twice and the control means nothing.
 */
describe('the schedule view', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  test('starts on now-and-next, because that is what the page is for', async () => {
    stubWorld({ live: [activeFreeze()], details: { 1: detailFor(activeFreeze(), [1]) } })
    renderRoute(<DashboardPage />)

    expect(await screen.findByRole('heading', { name: /Active now/ })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /Next 14 days/ })).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Now and next' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  test('swaps the two readings rather than showing both', async () => {
    const user = setupUser()
    stubWorld({ live: [activeFreeze()], details: { 1: detailFor(activeFreeze(), [1]) } })
    renderRoute(<DashboardPage />)

    await user.click(await screen.findByRole('tab', { name: 'Schedule' }))

    expect(screen.getByRole('heading', { name: /Next 14 days/ })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /Active now/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /Then what/ })).not.toBeInTheDocument()
  })

  test('and swaps back', async () => {
    const user = setupUser()
    stubWorld({ live: [activeFreeze()], details: { 1: detailFor(activeFreeze(), [1]) } })
    renderRoute(<DashboardPage />)

    await user.click(await screen.findByRole('tab', { name: 'Schedule' }))
    await user.click(screen.getByRole('tab', { name: 'Now and next' }))

    expect(screen.getByRole('heading', { name: /Active now/ })).toBeInTheDocument()
  })

  test('draws the scheduled restrictions as well as the active ones', async () => {
    // The timeline's axis is what separates present from future, so splitting them before
    // it would undo the reason for drawing them together.
    const user = setupUser()
    const active = activeFreeze()
    const upcoming = restriction({
      id: 2,
      name: 'Year-end close',
      status: 'SCHEDULED',
      startsAt: new Date(Date.now() + 2 * 86_400_000).toISOString(),
      endsAt: new Date(Date.now() + 5 * 86_400_000).toISOString(),
    })
    stubWorld({ live: [active, upcoming], details: { 1: detailFor(active, [1]) } })
    renderRoute(<DashboardPage />)

    await user.click(await screen.findByRole('tab', { name: 'Schedule' }))

    expect(screen.getByRole('link', { name: 'Year-end close' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Black Friday Freeze' })).toBeInTheDocument()
  })

  test('leaves the metrics and the status line alone — they answer a different question', async () => {
    const user = setupUser()
    stubWorld({ live: [activeFreeze()], details: { 1: detailFor(activeFreeze(), [1]) } })
    renderRoute(<DashboardPage />)

    await user.click(await screen.findByRole('tab', { name: 'Schedule' }))

    expect(screen.getByRole('heading', { name: /At a glance/ })).toBeInTheDocument()
    expect(screen.getByText(/in force now/i)).toBeInTheDocument()
  })
})
