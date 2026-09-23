import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiKeysSection } from './ApiKeysSection'
import { renderRoute } from '../../test/renderRoute'
import type { ApiKey } from '../../types/api'

const RAW_KEY = 'fzh_exampleKeyOnly-doNotUse-0000000000000000000'

const apiKey = (overrides: Partial<ApiKey> = {}): ApiKey => ({
  id: 1,
  name: 'gitlab-ci',
  keyPrefix: 'fzh_exampl',
  createdBy: 1,
  lastUsedOn: null,
  createdAt: '2026-01-01T00:00:00Z',
  revokedAt: null,
  revoked: false,
  ...overrides,
})

function stubApi(list: ApiKey[], writeResponse?: () => Response) {
  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    void input
    const json = (body: unknown, status = 200) =>
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      })

    if (init?.method && init.method !== 'GET') {
      return Promise.resolve(
        writeResponse?.() ??
          json({ id: 9, name: 'gitlab-ci', keyPrefix: 'fzh_exampl', key: RAW_KEY, createdBy: 1, createdAt: '2026-01-01T00:00:00Z' }, 201),
      )
    }
    return Promise.resolve(json(list))
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

describe('ApiKeysSection', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  test('lists keys by name and prefix, never by key', async () => {
    stubApi([apiKey()])
    renderRoute(<ApiKeysSection />, { path: '/settings' })

    const list = await screen.findByRole('list', { name: 'API keys' })
    expect(within(list).getByText('gitlab-ci')).toBeInTheDocument()
    expect(within(list).getByText(/fzh_exampl…/)).toBeInTheDocument()
  })

  test('shows a newly issued key once, with a warning that it will not be shown again', async () => {
    // The only moment the raw key exists outside the customer's own storage. If it is
    // missed here, the only remedy is issuing another one.
    const user = userEvent.setup()
    stubApi([])
    renderRoute(<ApiKeysSection />, { path: '/settings' })

    await user.type(await screen.findByLabelText('Issue a key'), 'gitlab-ci')
    await user.click(screen.getByRole('button', { name: /issue key/i }))

    expect(await screen.findByText(RAW_KEY)).toBeInTheDocument()
    expect(screen.getByText(/not shown again/i)).toBeInTheDocument()
  })

  test('keeps the issued key on screen until it is dismissed', async () => {
    // Not a toast: a message that disappears on its own is exactly the wrong shape for
    // something unrecoverable.
    const user = userEvent.setup()
    stubApi([])
    renderRoute(<ApiKeysSection />, { path: '/settings' })

    await user.type(await screen.findByLabelText('Issue a key'), 'gitlab-ci')
    await user.click(screen.getByRole('button', { name: /issue key/i }))
    expect(await screen.findByText(RAW_KEY)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /done/i }))

    await waitFor(() => expect(screen.queryByText(RAW_KEY)).not.toBeInTheDocument())
  })

  test('asks before revoking, because there is no way back', async () => {
    const user = userEvent.setup()
    const native = vi.spyOn(window, 'confirm')
    const spy = stubApi([apiKey()])
    renderRoute(<ApiKeysSection />, { path: '/settings' })

    await user.click(await screen.findByRole('button', { name: 'Revoke gitlab-ci' }))

    // In the product, and naming the key — the part FZ-188's copy of this pattern lost.
    expect(screen.getByRole('alertdialog', { name: 'Revoke “gitlab-ci”?' })).toBeInTheDocument()
    expect(native).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Keep it' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(spy.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })

  test('revokes once confirmed', async () => {
    const user = userEvent.setup()
    const spy = stubApi([apiKey()], () => new Response(JSON.stringify(apiKey({ revoked: true })), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    renderRoute(<ApiKeysSection />, { path: '/settings' })

    await user.click(await screen.findByRole('button', { name: 'Revoke gitlab-ci' }))
    await user.click(screen.getByRole('button', { name: 'Revoke key' }))

    await waitFor(() =>
      expect(
        spy.mock.calls.some(
          ([input, init]) => String(input).endsWith('/api/api-keys/1/revoke') && init?.method === 'POST',
        ),
      ).toBe(true),
    )
  })

  test('offers no revoke button for a key already revoked', async () => {
    stubApi([apiKey({ revoked: true, revokedAt: '2026-02-01T00:00:00Z' })])
    renderRoute(<ApiKeysSection />, { path: '/settings' })

    expect(await screen.findByText(/revoked/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Revoke/ })).not.toBeInTheDocument()
  })

  test('explains a 403 rather than showing an empty list', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ status: 403, detail: 'This action requires an administrator.' }), {
            status: 403,
            headers: { 'Content-Type': 'application/problem+json' },
          }),
        ),
      ),
    )
    renderRoute(<ApiKeysSection />, { path: '/settings' })

    expect(await screen.findByText(/only an administrator can manage api keys/i)).toBeInTheDocument()
  })

  test('surfaces the reason a key could not be issued', async () => {
    const user = userEvent.setup()
    stubApi([], () =>
      new Response(JSON.stringify({ status: 400, detail: 'name must not be blank' }), {
        status: 400,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    )
    renderRoute(<ApiKeysSection />, { path: '/settings' })

    await user.type(await screen.findByLabelText('Issue a key'), 'x')
    await user.click(screen.getByRole('button', { name: /issue key/i }))

    expect(await screen.findByText('name must not be blank')).toBeInTheDocument()
  })

  test('says outright when a key has never been used', async () => {
    // The answer that makes a key safe to revoke, so it is stated rather than left blank
    // (FZ-117).
    stubApi([apiKey({ lastUsedOn: null })])
    renderRoute(<ApiKeysSection />, { path: '/settings' })

    expect(await screen.findByText(/never used/)).toBeInTheDocument()
  })

  test('says how recently a key was used', async () => {
    const twoDaysAgo = new Date(Date.now() - 2 * 86_400_000).toISOString().slice(0, 10)
    stubApi([apiKey({ lastUsedOn: twoDaysAgo })])
    renderRoute(<ApiKeysSection />, { path: '/settings' })

    expect(await screen.findByText(/used 2 days ago/)).toBeInTheDocument()
  })

  test('does not describe a revoked key as merely unused', async () => {
    // It stopped being able to authenticate, which is a different fact from nothing
    // having wanted to.
    stubApi([apiKey({ lastUsedOn: null, revoked: true, revokedAt: '2026-01-01T00:00:00Z' })])
    renderRoute(<ApiKeysSection />, { path: '/settings' })

    expect(await screen.findByText(/never used before it was revoked/)).toBeInTheDocument()
  })
})
