import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { SettingsPage } from './SettingsPage'
import { renderRoute } from '../../test/renderRoute'
import type { Integration } from '../../types/api'

const integration = (overrides: Partial<Integration> = {}): Integration => ({
  id: 1,
  type: 'SLACK',
  enabled: true,
  summary: 'hooks.slack.com',
  failedDeliveries: 0,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  ...overrides,
})

function stubApi(list: Integration[], writeResponse?: () => Response) {
  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (init?.method && init.method !== 'GET') {
      return Promise.resolve(writeResponse?.() ?? new Response(null, { status: 204 }))
    }
    // The page loads several sections, each with its own endpoint. Answering every GET
    // with the integration list gave BillingSection a body it could not read.
    const body = url.includes('/api/billing/subscription')
      ? {
          plan: 'GROWTH',
          status: 'ACTIVE',
          canUpgradeSelfServe: true,
          hasBillingAccount: true,
          trialEndsAt: null,
          trialDaysRemaining: null,
          currentPeriodEndsAt: null,
          usage: [{ resource: 'applications', current: 4, limit: 50, percentUsed: 8, atLimit: false }],
        }
      : list
    return Promise.resolve(
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

describe('SettingsPage', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  test('lists destinations by channel and summary', async () => {
    stubApi([integration(), integration({ id: 2, type: 'EMAIL', summary: '2 recipients' })])
    renderRoute(<SettingsPage />, { path: '/settings?section=integrations', route: '/settings' })

    // Asserted on the summaries, which are unique to the list — "Slack" and "Email" also
    // appear as options in the create form, which renders before the request resolves.
    expect(await screen.findByText('hooks.slack.com')).toBeInTheDocument()
    expect(screen.getByText('2 recipients')).toBeInTheDocument()

    const list = screen.getByRole('list', { name: 'Notification destinations' })
    expect(within(list).getByText('Slack')).toBeInTheDocument()
    expect(within(list).getByText('Email')).toBeInTheDocument()
  })

  test('warns that nothing is announced without a destination', async () => {
    stubApi([])
    renderRoute(<SettingsPage />, { path: '/settings?section=integrations', route: '/settings' })

    expect(await screen.findByText(/nothing will be announced/i)).toBeInTheDocument()
  })

  test('adds a destination', async () => {
    const user = userEvent.setup()
    const spy = stubApi([])
    renderRoute(<SettingsPage />, { path: '/settings?section=integrations', route: '/settings' })

    // A URL, not a JSON document (`FZ-189`) — the form composes the config now.
    await user.type(
      await screen.findByLabelText(/incoming webhook url/i),
      'https://hooks.slack.com/services/x',
    )
    await user.click(screen.getByRole('button', { name: /add destination/i }))

    await waitFor(() => {
      const posted = spy.mock.calls.find(([, init]) => init?.method === 'POST')
      expect(posted).toBeDefined()
      expect(JSON.parse(String(posted?.[1]?.body)).type).toBe('SLACK')
    })
  })

  test('explains a config the backend rejects', async () => {
    // The backend owns what each channel's config must contain; its message is the useful
    // one, so it is surfaced rather than replaced with something generic.
    //
    // The input has to be one the *field* accepts, or this never reaches the backend at
    // all (`FZ-189`). That is the point of the field check and also its limit: it catches
    // shape, and the backend still owns everything else — so this asserts the path that
    // matters, a value the form cannot fault and the server can.
    const user = userEvent.setup()
    stubApi(
      [],
      () =>
        new Response(JSON.stringify({ message: 'That webhook has already been added' }), {
          status: 400,
          headers: { 'Content-Type': 'application/json' },
        }),
    )
    renderRoute(<SettingsPage />, { path: '/settings?section=integrations', route: '/settings' })

    await user.type(
      await screen.findByLabelText(/incoming webhook url/i),
      'https://hooks.slack.com/services/already-there',
    )
    await user.click(screen.getByRole('button', { name: /add destination/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/already been added/i)
  })

  test('disables a destination without deleting it', async () => {
    const user = userEvent.setup()
    const spy = stubApi([integration()])
    renderRoute(<SettingsPage />, { path: '/settings?section=integrations', route: '/settings' })

    await user.click(await screen.findByLabelText('Slack enabled'))

    await waitFor(() => {
      const patched = spy.mock.calls.find(([, init]) => init?.method === 'PATCH')
      expect(JSON.parse(String(patched?.[1]?.body))).toEqual({ enabled: false })
    })
  })

  test('deletes a destination', async () => {
    const user = userEvent.setup()
    const spy = stubApi([integration()])
    renderRoute(<SettingsPage />, { path: '/settings?section=integrations', route: '/settings' })

    await user.click(await screen.findByRole('button', { name: /delete slack destination/i }))

    await waitFor(() => {
      expect(spy.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(true)
    })
  })

  test('tells a non-administrator why the page is empty', async () => {
    // The endpoint is ADMINISTRATOR-only; a bare error would look like a bug.
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ message: 'Forbidden' }), {
            status: 403,
            headers: { 'Content-Type': 'application/json' },
          }),
        ),
      ),
    )
    renderRoute(<SettingsPage />, { path: '/settings?section=integrations', route: '/settings' })

    // Awaited on the message itself: a loading status renders first, so findByRole('status')
    // would resolve against that instead.
    expect(await screen.findByText(/only an administrator/i)).toBeInTheDocument()
  })

  test('never renders a stored credential', async () => {
    // Belt and braces with the backend test: the API omits config, and the UI has no
    // field that would display one.
    stubApi([integration({ summary: 'hooks.slack.com' })])
    const { container } = renderRoute(<SettingsPage />, { path: '/settings?section=integrations', route: '/settings' })

    await screen.findByText('Slack')
    expect(container.textContent).not.toContain('webhookUrl')
    expect(within(container).queryByDisplayValue(/hooks\.slack\.com\/services/)).toBeNull()
  })

  test('opens on the first section, with the others not on the page', async () => {
    // The rail switches rather than scrolls (FZ-118), so the sections it does not name
    // are absent — not merely below the fold.
    stubApi([integration()])
    renderRoute(<SettingsPage />, { path: '/settings' })

    expect(await screen.findByRole('heading', { name: /advance warning/i })).toBeInTheDocument()
    expect(screen.queryByText('hooks.slack.com')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /^api keys$/i })).not.toBeInTheDocument()
  })

  test('clicking a rail entry swaps the section', async () => {
    const user = userEvent.setup()
    stubApi([integration()])
    renderRoute(<SettingsPage />, { path: '/settings' })

    await user.click(screen.getByRole('button', { name: 'Integrations' }))

    expect(await screen.findByText('hooks.slack.com')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /advance warning/i })).not.toBeInTheDocument()
  })

  test('puts the chosen section in the URL, so it can be sent to someone', async () => {
    // The same reasoning as the checks console's filter: a view worth reaching is a view
    // worth linking.
    const user = userEvent.setup()
    stubApi([integration()])
    const { router } = renderRoute(<SettingsPage />, { path: '/settings' })

    await user.click(screen.getByRole('button', { name: 'API keys' }))

    await waitFor(() => expect(router.state.location.search).toContain('section=api-keys'))
  })

  test('a section named in the URL is the one that opens', async () => {
    stubApi([integration()])
    renderRoute(<SettingsPage />, { path: '/settings?section=billing', route: '/settings' })

    expect(await screen.findByRole('heading', { name: /^billing$/i })).toBeInTheDocument()
  })

  test('an unknown section falls back to the first rather than to nothing', async () => {
    // A mistyped or stale link should still show a usable page.
    stubApi([integration()])
    renderRoute(<SettingsPage />, { path: '/settings?section=nonsense', route: '/settings' })

    expect(await screen.findByRole('heading', { name: /advance warning/i })).toBeInTheDocument()
  })

  test('asks only for the data of the section on screen', async () => {
    // Four sections mounted meant four requests to open a page showing one of them.
    const spy = stubApi([integration()])
    renderRoute(<SettingsPage />, { path: '/settings' })

    await screen.findByRole('heading', { name: /advance warning/i })

    await waitFor(() => expect(spy).toHaveBeenCalled())
    expect(spy.mock.calls.some(([url]) => String(url).includes('/api/integrations'))).toBe(false)
    expect(spy.mock.calls.some(([url]) => String(url).includes('/api/api-keys'))).toBe(false)
  })
})
