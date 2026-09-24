import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { MembersSection } from './MembersSection'
import { renderRoute } from '../../test/renderRoute'
import type { Member } from '../../types/api'

const member = (overrides: Partial<Member> = {}): Member => ({
  id: 2,
  email: 'dev@acme.test',
  role: 'MEMBER',
  active: true,
  deactivatedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
  ...overrides,
})

// userId 1 is who renderRoute signs in.
const me = member({ id: 1, email: 'test@acme.test', role: 'ADMINISTRATOR' })

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** Answers the list, `/api/me`, and any write with `write` (a 200 echo by default). */
function stubApi(list: Member[], write?: (url: string, init: RequestInit) => Response) {
  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (init?.method && init.method !== 'GET') {
      return Promise.resolve(write?.(url, init) ?? json(member()))
    }
    if (url.endsWith('/api/me')) {
      return Promise.resolve(json({ userId: 1, organizationId: 1, email: 'test@acme.test', role: 'ADMINISTRATOR' }))
    }
    return Promise.resolve(json(list))
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

function writes(spy: ReturnType<typeof stubApi>) {
  return spy.mock.calls
    .filter(([, init]) => init?.method && init.method !== 'GET')
    .map(([input, init]) => ({ url: String(input), method: init!.method, body: init!.body ? JSON.parse(String(init!.body)) : undefined }))
}

describe('MembersSection', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  test('lists everyone with their role, marks who you are, and shows removed members as removed', async () => {
    stubApi([me, member(), member({ id: 3, email: 'gone@acme.test', active: false, deactivatedAt: '2026-02-01T00:00:00Z' })])
    renderRoute(<MembersSection />, { path: '/settings' })

    const list = await screen.findByRole('list', { name: 'Members' })
    const rows = within(list).getAllByRole('listitem')
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByText('(you)')).toBeInTheDocument()
    expect(within(rows[1]).getByLabelText('Role for dev@acme.test')).toHaveValue('MEMBER')
    expect(within(rows[2]).getByText(/Removed .* · was member/)).toBeInTheDocument()
    expect(within(rows[2]).getByRole('button', { name: 'Reinstate gone@acme.test' })).toBeInTheDocument()
    expect(within(rows[2]).queryByLabelText('Role for gone@acme.test')).not.toBeInTheDocument()
  })

  test('invites with the chosen role and says what the person will receive', async () => {
    const user = userEvent.setup()
    const spy = stubApi([me], () => json({ userId: 5, email: 'new@acme.test', role: 'ADMINISTRATOR' }, 201))
    renderRoute(<MembersSection />, { path: '/settings' })

    await user.type(await screen.findByLabelText('Invite someone'), '  new@acme.test ')
    await user.selectOptions(screen.getByLabelText('Role for the invitation'), 'ADMINISTRATOR')
    await user.click(screen.getByRole('button', { name: 'Send invitation' }))

    expect(await screen.findByText(/new@acme.test will receive an email/)).toBeInTheDocument()
    expect(writes(spy)).toEqual([
      { url: expect.stringMatching(/\/api\/invites$/), method: 'POST', body: { email: 'new@acme.test', role: 'ADMINISTRATOR' } },
    ])
    expect(screen.getByLabelText('Invite someone')).toHaveValue('')
  })

  test("shows the server's refusal of an invitation in its own words", async () => {
    const user = userEvent.setup()
    const message = 'new@acme.test already has a FreezeHub account in another organization. A person can belong to one organization.'
    stubApi([me], () => json({ status: 409, detail: message }, 409))
    renderRoute(<MembersSection />, { path: '/settings' })

    await user.type(await screen.findByLabelText('Invite someone'), 'new@acme.test')
    await user.click(screen.getByRole('button', { name: 'Send invitation' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('another organization')
  })

  test("changes another member's role straight away", async () => {
    const user = userEvent.setup()
    const spy = stubApi([me, member()])
    renderRoute(<MembersSection />, { path: '/settings' })

    await user.selectOptions(await screen.findByLabelText('Role for dev@acme.test'), 'ADMINISTRATOR')

    await waitFor(() =>
      expect(writes(spy)).toEqual([
        { url: expect.stringMatching(/\/api\/members\/2$/), method: 'PATCH', body: { role: 'ADMINISTRATOR' } },
      ]),
    )
  })

  test('asks before an administrator makes themselves a member, and does nothing if they stay', async () => {
    const user = userEvent.setup()
    const spy = stubApi([me, member({ id: 2, role: 'ADMINISTRATOR' })])
    renderRoute(<MembersSection />, { path: '/settings' })

    await user.selectOptions(await screen.findByLabelText('Role for test@acme.test'), 'MEMBER')
    const dialog = await screen.findByRole('alertdialog', { name: 'Make yourself a member?' })
    await user.click(within(dialog).getByRole('button', { name: 'Stay an administrator' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(writes(spy)).toEqual([])

    await user.selectOptions(screen.getByLabelText('Role for test@acme.test'), 'MEMBER')
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Step down' }))

    await waitFor(() =>
      expect(writes(spy)).toEqual([
        { url: expect.stringMatching(/\/api\/members\/1$/), method: 'PATCH', body: { role: 'MEMBER' } },
      ]),
    )
  })

  test('removes only after confirming, and says the removal can be undone', async () => {
    const user = userEvent.setup()
    const spy = stubApi([me, member()])
    renderRoute(<MembersSection />, { path: '/settings' })

    await user.click(await screen.findByRole('button', { name: 'Remove dev@acme.test' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Remove “dev@acme.test”?' })
    expect(dialog).toHaveTextContent(/reinstate them at any time/)
    expect(writes(spy)).toEqual([])

    await user.click(within(dialog).getByRole('button', { name: 'Remove member' }))

    await waitFor(() =>
      expect(writes(spy)).toEqual([{ url: expect.stringMatching(/\/api\/members\/2\/deactivate$/), method: 'POST', body: undefined }]),
    )
  })

  test("shows the server's refusal to remove the last administrator", async () => {
    // The rule is the backend's (CLAUDE.md §5): the screen does not anticipate it, it reports it.
    const user = userEvent.setup()
    stubApi([me], () =>
      json({ status: 409, detail: "This is the organization's only active administrator. Make someone else an administrator first, or nobody will be able to manage the organization." }, 409),
    )
    renderRoute(<MembersSection />, { path: '/settings' })

    await user.click(await screen.findByRole('button', { name: 'Remove test@acme.test' }))
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Remove member' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('only active administrator')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  test('reinstates a removed member', async () => {
    const user = userEvent.setup()
    const spy = stubApi([me, member({ active: false, deactivatedAt: '2026-02-01T00:00:00Z' })])
    renderRoute(<MembersSection />, { path: '/settings' })

    await user.click(await screen.findByRole('button', { name: 'Reinstate dev@acme.test' }))

    await waitFor(() =>
      expect(writes(spy)).toEqual([{ url: expect.stringMatching(/\/api\/members\/2\/reactivate$/), method: 'POST', body: undefined }]),
    )
  })

  test('tells a member who reaches it that only an administrator can manage members', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(json({ status: 403, detail: 'Forbidden' }, 403))))
    renderRoute(<MembersSection />, { path: '/settings', role: 'MEMBER' })

    expect(await screen.findByText(/Only an administrator can see and manage members/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Invite someone')).not.toBeInTheDocument()
  })
})
