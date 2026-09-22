import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { setupUser } from '../../test/user'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { CreateRestrictionPage } from './CreateRestrictionPage'
import { renderRoute } from '../../test/renderRoute'

const entry = (id: number, name: string) => ({
  id,
  name,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
})

/** Catalog reads succeed; the restriction POST is whatever the test wants it to be. */
function stubApi(postResponse: () => Response) {
  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown) =>
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })

    if (url.includes('/api/restrictions') && init?.method === 'POST') {
      return Promise.resolve(postResponse())
    }
    if (url.includes('/api/teams')) return Promise.resolve(json([entry(1, 'Payments')]))
    if (url.includes('/api/applications')) {
      return Promise.resolve(json([{ ...entry(2, 'payments-api'), teamIds: [] }]))
    }
    if (url.includes('/api/environments')) return Promise.resolve(json([entry(3, 'production')]))
    return Promise.resolve(json([]))
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

const created = () =>
  new Response(JSON.stringify({ id: 99 }), {
    status: 201,
    headers: { 'Content-Type': 'application/json' },
  })

function postedBody(spy: ReturnType<typeof stubApi>) {
  const call = spy.mock.calls.find(([, init]) => init?.method === 'POST')
  return call ? JSON.parse(String(call[1]?.body)) : undefined
}

/** Fills everything required so each test only varies what it is actually about. */
async function fillValidForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText('Name'), 'Black Friday Freeze')
  await user.type(screen.getByLabelText('Reason'), 'Revenue-critical period')
  await user.type(screen.getByLabelText('Starts'), '2026-11-27T09:00')
  await user.type(screen.getByLabelText('Ends'), '2026-12-02T09:00')
  await user.click(await screen.findByRole('button', { name: /^production/ }))
}

describe('CreateRestrictionPage', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  test('converts the local datetime inputs to UTC before submitting', async () => {
    // The headline risk of this story. The suite runs at UTC-5, so an unconverted value
    // would post "2026-11-27T09:00" (or a 09:00Z instant) instead of 14:00Z.
    const user = setupUser()
    const spy = stubApi(created)
    renderRoute(<CreateRestrictionPage />, { path: '/restrictions/new' })

    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: /create restriction/i }))

    await waitFor(() => expect(postedBody(spy)).toBeDefined())
    expect(postedBody(spy).startsAt).toBe('2026-11-27T14:00:00.000Z')
    expect(postedBody(spy).endsAt).toBe('2026-12-02T14:00:00.000Z')
  })

  test('goes to the new restriction once it is created', async () => {
    // Untested until now, and not by oversight: the navigation was throwing into an
    // unhandled rejection that the suite reported and then passed anyway (OI-10). The
    // server decides the id, so this is also what proves the response is read rather
    // than the form state reused.
    const user = setupUser()
    stubApi(created)
    const { router } = renderRoute(<CreateRestrictionPage />, { path: '/restrictions/new' })

    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: /create restriction/i }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/restrictions/99'))
  })

  test('stays put when creation fails, so the filled-in form is not lost', async () => {
    const user = setupUser()
    stubApi(() => new Response(JSON.stringify({ message: 'Name already used' }), {
      status: 409,
      headers: { 'Content-Type': 'application/json' },
    }))
    const { router } = renderRoute(<CreateRestrictionPage />, { path: '/restrictions/new' })

    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: /create restriction/i }))

    expect(await screen.findByText(/name already used/i)).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/restrictions/new')
  })

  test('submits the whole restriction, with scope', async () => {
    const user = setupUser()
    const spy = stubApi(created)
    renderRoute(<CreateRestrictionPage />, { path: '/restrictions/new' })

    await fillValidForm(user)
    await user.click(await screen.findByRole('button', { name: /^Payments/ }))
    await user.click(screen.getByRole('button', { name: /create restriction/i }))

    await waitFor(() => expect(postedBody(spy)).toBeDefined())
    expect(postedBody(spy)).toMatchObject({
      name: 'Black Friday Freeze',
      reason: 'Revenue-critical period',
      level: 'HARD_FREEZE',
      scope: { teamIds: [1], applicationIds: [], environmentIds: [3] },
    })
  })

  test('sends no type or status — both are the server’s to decide', async () => {
    const user = setupUser()
    const spy = stubApi(created)
    renderRoute(<CreateRestrictionPage />, { path: '/restrictions/new' })

    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: /create restriction/i }))

    await waitFor(() => expect(postedBody(spy)).toBeDefined())
    expect(postedBody(spy)).not.toHaveProperty('type')
    expect(postedBody(spy)).not.toHaveProperty('status')
  })

  test('requires a reason', async () => {
    const user = setupUser()
    const spy = stubApi(created)
    renderRoute(<CreateRestrictionPage />, { path: '/restrictions/new' })

    await user.type(await screen.findByLabelText('Name'), 'No reason given')
    await user.type(screen.getByLabelText('Starts'), '2026-11-27T09:00')
    await user.type(screen.getByLabelText('Ends'), '2026-12-02T09:00')
    await user.click(await screen.findByRole('button', { name: /^production/ }))
    await user.click(screen.getByRole('button', { name: /create restriction/i }))

    expect(await screen.findByText(/reason is required/i)).toBeInTheDocument()
    expect(postedBody(spy)).toBeUndefined()
  })

  test('rejects an end that is not after the start', async () => {
    const user = setupUser()
    const spy = stubApi(created)
    renderRoute(<CreateRestrictionPage />, { path: '/restrictions/new' })

    await user.type(await screen.findByLabelText('Name'), 'Backwards')
    await user.type(screen.getByLabelText('Reason'), 'Reason')
    await user.type(screen.getByLabelText('Starts'), '2026-12-02T09:00')
    await user.type(screen.getByLabelText('Ends'), '2026-11-27T09:00')
    await user.click(await screen.findByRole('button', { name: /^production/ }))
    await user.click(screen.getByRole('button', { name: /create restriction/i }))

    expect(await screen.findByText(/end must be after the start/i)).toBeInTheDocument()
    expect(postedBody(spy)).toBeUndefined()
  })

  test('rejects a window entirely in the past', async () => {
    const user = setupUser()
    const spy = stubApi(created)
    renderRoute(<CreateRestrictionPage />, { path: '/restrictions/new' })

    await user.type(await screen.findByLabelText('Name'), 'Historic')
    await user.type(screen.getByLabelText('Reason'), 'Reason')
    await user.type(screen.getByLabelText('Starts'), '2020-01-01T09:00')
    await user.type(screen.getByLabelText('Ends'), '2020-01-02T09:00')
    await user.click(await screen.findByRole('button', { name: /^production/ }))
    await user.click(screen.getByRole('button', { name: /create restriction/i }))

    expect(await screen.findByText(/entirely in the past/i)).toBeInTheDocument()
    expect(postedBody(spy)).toBeUndefined()
  })

  test('requires at least one scope target', async () => {
    const user = setupUser()
    const spy = stubApi(created)
    renderRoute(<CreateRestrictionPage />, { path: '/restrictions/new' })

    await user.type(await screen.findByLabelText('Name'), 'Unscoped')
    await user.type(screen.getByLabelText('Reason'), 'Reason')
    await user.type(screen.getByLabelText('Starts'), '2026-11-27T09:00')
    await user.type(screen.getByLabelText('Ends'), '2026-12-02T09:00')
    await user.click(screen.getByRole('button', { name: /create restriction/i }))

    expect(await screen.findByText(/at least one team, application or environment/i))
      .toBeInTheDocument()
    expect(postedBody(spy)).toBeUndefined()
  })

  test('surfaces a backend rejection rather than swallowing it', async () => {
    // Client-side checks are a convenience; the backend is what actually decides.
    const user = setupUser()
    stubApi(
      () =>
        new Response(JSON.stringify({ message: 'Restriction cannot be entirely in the past' }), {
          status: 400,
          headers: { 'Content-Type': 'application/json' },
        }),
    )
    renderRoute(<CreateRestrictionPage />, { path: '/restrictions/new' })

    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: /create restriction/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/entirely in the past/i)
  })

  test('points at the catalog when there is nothing to scope to', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }),
        ),
      ),
    )
    renderRoute(<CreateRestrictionPage />, { path: '/restrictions/new' })

    expect(await screen.findByRole('status')).toHaveTextContent(/nothing to scope a restriction to/i)
  })

  test('shows the common options as chips and the rest behind "n more…"', async () => {
    // The native multi-select hid what was selected once a catalog outgrew its box
    // (FZ-108). Five applications is already past the four kept inline.
    const user = setupUser()
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input)
        const json = (body: unknown) =>
          new Response(JSON.stringify(body), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        if (url.includes('/api/applications')) {
          return Promise.resolve(
            json(
              ['payments-api', 'ledger-service', 'checkout-web', 'docs-site', 'status-page'].map(
                (name, index) => ({ ...entry(10 + index, name), teamIds: [] }),
              ),
            ),
          )
        }
        return Promise.resolve(json([]))
      }),
    )

    renderRoute(<CreateRestrictionPage />)

    expect(await screen.findByRole('button', { name: /^payments-api/ })).toBeInTheDocument()
    const more = await screen.findByRole('button', { name: /1 more…/ })

    await user.click(more)

    // The dialog lists every value, including the one that was hidden.
    const dialog = await screen.findByRole('dialog', { name: /choose applications/i })
    expect(dialog).toBeInTheDocument()
    expect(await screen.findByRole('checkbox', { name: 'status-page' })).toBeInTheDocument()
  })

  test('a chip carries its selected state where a screen reader can reach it', async () => {
    // The × and + are decorative and aria-hidden, so pressed state is what conveys
    // selection — a chip whose only signal is a glyph is a chip only sighted users can read.
    const user = setupUser()
    stubApi(created)
    renderRoute(<CreateRestrictionPage />)

    const chip = await screen.findByRole('button', { name: 'Payments' })
    expect(chip).toHaveAttribute('aria-pressed', 'false')

    await user.click(chip)

    expect(await screen.findByRole('button', { name: 'Payments' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  test('warns about an overlapping restriction without refusing it', async () => {
    // Overlaps are deliberately allowed (FZ-020), so this must never block submission.
    const user = setupUser()
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        const json = (body: unknown) =>
          new Response(JSON.stringify(body), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })

        if (url.includes('/api/restrictions') && init?.method === 'POST') {
          return Promise.resolve(created())
        }
        if (url.match(/\/api\/restrictions\/\d+/)) {
          return Promise.resolve(
            json({
              id: 7,
              name: 'Year-end close',
              level: 'HARD_FREEZE',
              status: 'SCHEDULED',
              startsAt: '2026-11-28T00:00:00Z',
              endsAt: '2026-12-05T00:00:00Z',
              scope: { teamIds: [], applicationIds: [], environmentIds: [3] },
            }),
          )
        }
        if (url.includes('/api/restrictions')) {
          return Promise.resolve(
            json([
              {
                id: 7,
                name: 'Year-end close',
                level: 'HARD_FREEZE',
                status: 'SCHEDULED',
                startsAt: '2026-11-28T00:00:00Z',
                endsAt: '2026-12-05T00:00:00Z',
              },
            ]),
          )
        }
        if (url.includes('/api/environments')) return Promise.resolve(json([entry(3, 'production')]))
        return Promise.resolve(json([]))
      }),
    )

    renderRoute(<CreateRestrictionPage />)
    await fillValidForm(user)

    expect(await screen.findByText(/Year-end close/)).toBeInTheDocument()
    expect(screen.getByText(/could match the same deployments/i)).toBeInTheDocument()
    expect(screen.getByText(/warning, not a refusal/i)).toBeInTheDocument()

    // And it is still submittable.
    expect(screen.getByRole('button', { name: /create restriction/i })).toBeEnabled()
  })

  test('says plainly when nothing overlaps', async () => {
    const user = setupUser()
    stubApi(created)
    renderRoute(<CreateRestrictionPage />)
    await fillValidForm(user)

    expect(
      await screen.findByText('No overlap with an existing restriction.'),
    ).toBeInTheDocument()
  })

  test('counts the applications the scope will actually match', async () => {
    const user = setupUser()
    stubApi(created)
    renderRoute(<CreateRestrictionPage />)

    await user.click(await screen.findByRole('button', { name: /^production/ }))

    // One application in the catalog, unconstrained by the environment choice.
    expect(await screen.findByText('1 of 1')).toBeInTheDocument()
  })
})
