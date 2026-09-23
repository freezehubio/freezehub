import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { RestrictionDetailPage } from './RestrictionDetailPage'
import { renderRoute } from '../../test/renderRoute'
import type { RestrictionDetail, RestrictionImpact, RestrictionStatus } from '../../types/api'

const entry = (id: number, name: string) => ({
  id,
  name,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
})

function detail(overrides: Partial<RestrictionDetail> = {}): RestrictionDetail {
  return {
    id: 7,
    name: 'Black Friday Freeze',
    description: 'No production deploys during peak trading.',
    reason: 'Revenue-critical period',
    type: 'DEPLOYMENT_FREEZE',
    level: 'HARD_FREEZE',
    status: 'SCHEDULED',
    startsAt: '2026-11-27T14:00:00.000Z',
    endsAt: '2026-12-02T14:00:00.000Z',
    createdBy: 1,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    scope: { teamIds: [], applicationIds: [], environmentIds: [] },
    ...overrides,
  }
}

const noImpact: RestrictionImpact = {
  checksRefused: 0,
  pipelinesAffected: 0,
  notificationsSent: 0,
  notificationsFailed: 0,
}

function stubApi(
  restriction: RestrictionDetail | { status: number },
  cancelStatus = 200,
  impact: RestrictionImpact = noImpact,
) {
  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown, status = 200) =>
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      })

    if (url.includes('/cancel')) {
      return Promise.resolve(
        cancelStatus === 200
          ? json(restriction)
          : json({ message: 'Only a SCHEDULED or ACTIVE restriction can be cancelled' }, cancelStatus),
      )
    }
    // Checked before the restriction itself: `/api/restrictions/7/impact` matches the
    // same prefix, and answering it with a restriction is how the figures silently
    // rendered as dashes.
    if (url.includes('/impact')) {
      return Promise.resolve(json(impact))
    }
    if (url.includes('/api/restrictions/')) {
      return 'status' in restriction && !('name' in restriction)
        ? Promise.resolve(json({ message: 'Restriction not found' }, restriction.status))
        : Promise.resolve(json(restriction))
    }
    if (url.includes('/api/teams')) return Promise.resolve(json([entry(1, 'Payments')]))
    if (url.includes('/api/applications')) {
      return Promise.resolve(json([{ ...entry(2, 'payments-api'), teamIds: [] }]))
    }
    if (url.includes('/api/environments')) return Promise.resolve(json([entry(3, 'production')]))
    void init
    return Promise.resolve(json([]))
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

function render() {
  return renderRoute(<RestrictionDetailPage />, { path: '/restrictions/7', route: '/restrictions/:restrictionId' })
}

describe('RestrictionDetailPage', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  test('shows the restriction with status, level, window and reason', async () => {
    const restriction = detail()
    stubApi(restriction)
    render()

    expect(await screen.findByRole('heading', { name: 'Black Friday Freeze' })).toBeInTheDocument()
    expect(screen.getByText('Revenue-critical period')).toBeInTheDocument()
    expect(screen.getByText('No production deploys during peak trading.')).toBeInTheDocument()
    expect(screen.getByText('scheduled')).toBeInTheDocument()
    expect(screen.getByText('Blocks deploys')).toBeInTheDocument()
  })

  test('resolves scope ids to names rather than showing raw numbers', async () => {
    const restriction = detail({
      scope: { teamIds: [1], applicationIds: [2], environmentIds: [3] },
    })
    stubApi(restriction)
    render()

    await waitFor(() => {
      expect(screen.getByText('Payments')).toBeInTheDocument()
      expect(screen.getByText('payments-api')).toBeInTheDocument()
      expect(screen.getByText('production')).toBeInTheDocument()
    })
  })

  test('shows an unconstrained dimension as "Any"', async () => {
    // The wildcard rule matters: an empty dimension means "any", not "nothing".
    const restriction = detail({
      scope: { teamIds: [], applicationIds: [], environmentIds: [3] },
    })
    stubApi(restriction)
    render()

    await waitFor(() => expect(screen.getAllByText('Any')).toHaveLength(2))
  })

  test.each<[RestrictionStatus, boolean, boolean]>([
    ['SCHEDULED', true, true],
    ['ACTIVE', false, true],
    ['COMPLETED', false, false],
    ['CANCELLED', false, false],
  ])('offers the right actions when %s', async (status, canEdit, canCancel) => {
    // Affordances mirror the backend rules: edit only while SCHEDULED (FZ-023), cancel
    // only while SCHEDULED or ACTIVE (FZ-024). The control stays on the page when the
    // rule forbids it — disabled, with the reason beside it (FZ-107, `1d`) — so what is
    // asserted here is whether it can be used, not whether it exists.
    const restriction = detail({ status })
    stubApi(restriction)
    render()

    await screen.findByRole('heading', { name: 'Black Friday Freeze' })

    if (canEdit) {
      expect(screen.getByRole('link', { name: 'Edit' })).toBeInTheDocument()
    } else {
      expect(screen.getByRole('button', { name: 'Edit' })).toBeDisabled()
    }

    const cancelButton = screen.getByRole('button', { name: /cancel restriction/i })
    if (canCancel) {
      expect(cancelButton).toBeEnabled()
    } else {
      expect(cancelButton).toBeDisabled()
    }
  })

  test('says why an action is closed rather than leaving a dead control', async () => {
    // A disabled button with no reason beside it is a dead end. The sentence is what
    // makes showing the control better than hiding it.
    stubApi(detail({ status: 'COMPLETED' }))
    render()

    await screen.findByRole('heading', { name: 'Black Friday Freeze' })

    expect(screen.getByRole('button', { name: 'Edit' })).toHaveAccessibleDescription(
      /completed and can no longer be changed/i,
    )
  })

  test('cancels through the API', async () => {
    const user = userEvent.setup()
    const restriction = detail({ status: 'ACTIVE' })
    const spy = stubApi(restriction)
    render()

    await user.click(await screen.findByRole('button', { name: /cancel restriction/i }))

    await waitFor(() => {
      const call = spy.mock.calls.find(([url]) => String(url).includes('/cancel'))
      expect(call).toBeDefined()
      expect(call?.[1]?.method).toBe('POST')
    })
  })

  test('surfaces a conflict when the restriction moved on before cancelling', async () => {
    // The realistic race: it completed while this page was open.
    const user = userEvent.setup()
    const restriction = detail({ status: 'ACTIVE' })
    stubApi(restriction, 409)
    render()

    await user.click(await screen.findByRole('button', { name: /cancel restriction/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/can be cancelled/i)
  })

  test('says the restriction does not exist on 404', async () => {
    // 404 also covers another tenant's restriction; the wording must not imply it exists.
    stubApi({ status: 404 })
    renderRoute(<RestrictionDetailPage />, { path: '/restrictions/7', route: '/restrictions/:restrictionId' })

    expect(await screen.findByRole('alert')).toHaveTextContent(/does not exist/i)
  })

  test.each<[RestrictionStatus, 'HARD_FREEZE' | 'ADVISORY', boolean]>([
    ['ACTIVE', 'HARD_FREEZE', true],
    ['ACTIVE', 'ADVISORY', false],
    ['SCHEDULED', 'HARD_FREEZE', false],
    ['COMPLETED', 'HARD_FREEZE', false],
  ])('colours scope magenta only while %s/%s is actually stopping deploys', async (
    status,
    level,
    expectMagenta,
  ) => {
    // The design's one rule for colour: magenta claims a deployment is being stopped
    // here. The mockup draws an ACTIVE hard freeze, so its scope is magenta — copying
    // that onto every restriction would make the colour mean "this is a scope", which
    // is what the rule exists to prevent.
    stubApi(detail({ status, level, scope: { teamIds: [], applicationIds: [], environmentIds: [3] } }))
    render()

    const tag = await screen.findByText('production')
    expect(tag.className).toContain(expectMagenta ? 'tag-accent-2' : 'tag-neutral')
  })

  test('says what the restriction actually did', async () => {
    // The three figures that make a completed freeze more than a row in a list (FZ-112).
    stubApi(detail({ status: 'COMPLETED' }), 200, {
      checksRefused: 14,
      pipelinesAffected: 6,
      notificationsSent: 3,
      notificationsFailed: 0,
    })
    render()

    const block = await screen.findByRole('region', { name: /what it has done/i })
    expect(within(block).getByText('14')).toBeInTheDocument()
    expect(within(block).getByText('6')).toBeInTheDocument()
    expect(within(block).getByText('3')).toBeInTheDocument()
  })

  test('refusals and pipelines are different figures', async () => {
    // Three refusals across two pipelines is the normal case, and reading one as the
    // other overstates how far a freeze reached.
    stubApi(detail({ status: 'COMPLETED' }), 200, {
      checksRefused: 3,
      pipelinesAffected: 2,
      notificationsSent: 1,
      notificationsFailed: 0,
    })
    render()

    const block = await screen.findByRole('region', { name: /what it has done/i })
    expect(within(block).getByText('3')).toBeInTheDocument()
    expect(within(block).getByText('2')).toBeInTheDocument()
  })

  test('a scheduled restriction is not reported as having done nothing', async () => {
    // Three noughts under "what it has done" read as a failure rather than as a freeze
    // that has not started.
    stubApi(detail({ status: 'SCHEDULED' }))
    render()

    await screen.findByRole('heading', { name: 'Black Friday Freeze' })
    expect(screen.queryByRole('region', { name: /what it has done/i })).not.toBeInTheDocument()
  })

  test('an advisory says outright that it refused nothing', async () => {
    // Zero under "checks refused" is the correct answer for an advisory, and without the
    // caption it reads as a freeze that failed to catch anything.
    stubApi(detail({ status: 'COMPLETED', level: 'ADVISORY' }))
    render()

    const block = await screen.findByRole('region', { name: /what it has done/i })
    expect(within(block).getByText(/an advisory refuses nothing/i)).toBeInTheDocument()
  })

  test('links a failed announcement to the notifications it came from', async () => {
    stubApi(detail({ status: 'ACTIVE' }), 200, {
      checksRefused: 0,
      pipelinesAffected: 0,
      notificationsSent: 3,
      notificationsFailed: 1,
    })
    render()

    expect(await screen.findByRole('link', { name: /1 did not arrive/i })).toHaveAttribute(
      'href',
      '/notifications?show=failed',
    )
  })


  test('tells a member why the actions are closed, rather than just greying them (FZ-190)', async () => {
    // The page's own convention is to keep a control and explain it, because it has room for
    // a sentence where a table row has not. A member gets the same treatment a completed
    // restriction gets: disabled, with the reason beside it.
    stubApi(detail({ status: 'SCHEDULED' }))
    renderRoute(<RestrictionDetailPage />, {
      path: '/restrictions/7',
      route: '/restrictions/:restrictionId',
      role: 'MEMBER',
    })

    expect(await screen.findByRole('button', { name: 'Cancel restriction' })).toBeDisabled()
    expect(screen.getByText(/Only an administrator can change a restriction/)).toBeInTheDocument()
  })
})
