import { screen, waitFor } from '@testing-library/react'
import { setupUser } from '../../test/user'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { DeployCheck } from './DeployCheck'
import { renderRoute } from '../../test/renderRoute'
import type { DeploymentCheckPreview } from '../../types/api'

function answer(overrides: Partial<DeploymentCheckPreview> = {}): DeploymentCheckPreview {
  return {
    decision: 'ALLOW',
    application: 'payments-api',
    environment: 'production',
    evaluatedAt: '2026-09-08T14:32:07Z',
    message: 'Allowed: no restriction is in force for this deployment.',
    unregistered: [],
    restrictions: [],
    ...overrides,
  }
}

function stubApi(preview: DeploymentCheckPreview | { status: number }) {
  const spy = vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    const json = (body: unknown, status = 200) =>
      Promise.resolve(
        new Response(JSON.stringify(body), {
          status,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

    if (url.includes('/api/deployment-checks/preview')) {
      return 'status' in preview
        ? json({ detail: 'Boom' }, preview.status)
        : json(preview)
    }
    if (url.includes('/api/applications')) {
      return json([
        { id: 1, name: 'payments-api', teamIds: [], createdAt: '', updatedAt: '' },
      ])
    }
    if (url.includes('/api/environments')) {
      return json([{ id: 2, name: 'production', createdAt: '', updatedAt: '' }])
    }
    return json([])
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

async function ask(application = 'payments-api', environment = 'production') {
  const user = setupUser()
  await user.type(screen.getByLabelText('Application'), application)
  await user.type(screen.getByLabelText('Environment'), environment)
  await user.click(screen.getByRole('button', { name: 'Evaluate' }))
}

describe('DeployCheck', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  test('asks nothing until somebody asks', async () => {
    // The check is a question a person puts, not something the dashboard fetches because
    // it opened. It is also what keeps the answer honest: an evaluation held from page
    // load would be stale by the time it is read.
    const spy = stubApi(answer())
    renderRoute(<DeployCheck />)

    await waitFor(() => expect(spy).toHaveBeenCalled())
    expect(spy.mock.calls.some(([url]) => String(url).includes('/preview'))).toBe(false)
  })

  test('will not evaluate with a field empty', async () => {
    const user = setupUser()
    stubApi(answer())
    renderRoute(<DeployCheck />)

    expect(screen.getByRole('button', { name: 'Evaluate' })).toBeDisabled()

    await user.type(screen.getByLabelText('Application'), 'payments-api')
    expect(screen.getByRole('button', { name: 'Evaluate' })).toBeDisabled()

    await user.type(screen.getByLabelText('Environment'), 'production')
    expect(screen.getByRole('button', { name: 'Evaluate' })).toBeEnabled()
  })

  test('asks about what was typed', async () => {
    const spy = stubApi(answer())
    renderRoute(<DeployCheck />)

    await ask()

    await waitFor(() => {
      const call = spy.mock.calls.find(([url]) => String(url).includes('/preview'))
      expect(String(call?.[0])).toContain('application=payments-api')
      expect(String(call?.[0])).toContain('environment=production')
    })
  })

  test('shows the answer in the backend’s own words', async () => {
    // The sentence is not rebuilt here. The product must not be able to describe a
    // decision differently from the gate that made it.
    stubApi(answer())
    renderRoute(<DeployCheck />)

    await ask()

    expect(await screen.findByText('ALLOW')).toBeInTheDocument()
    expect(
      screen.getByText('Allowed: no restriction is in force for this deployment.'),
    ).toBeInTheDocument()
  })

  test('a refusal names the freeze and links to it', async () => {
    stubApi(
      answer({
        decision: 'BLOCK',
        message: 'Blocked by a change restriction in force: Black Friday Freeze.',
        restrictions: [
          {
            id: 7,
            name: 'Black Friday Freeze',
            reason: 'Revenue-critical period',
            level: 'HARD_FREEZE',
            startsAt: '2026-11-27T14:00:00Z',
            endsAt: '2026-12-02T14:00:00Z',
          },
        ],
      }),
    )
    renderRoute(<DeployCheck />)

    await ask()

    expect(await screen.findByText('BLOCK')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Black Friday Freeze' })).toHaveAttribute(
      'href',
      '/restrictions/7',
    )
  })

  test('colours the verdict magenta only when a deployment is being stopped', async () => {
    // The design's one rule for colour. An ALLOW stops nothing, so it does not get to
    // make the claim.
    stubApi(answer())
    const { unmount } = renderRoute(<DeployCheck />)
    await ask()
    expect((await screen.findByText('ALLOW')).className).toContain('verdictAllow')
    unmount()

    vi.unstubAllGlobals()
    stubApi(answer({ decision: 'BLOCK', message: 'Blocked by a change restriction in force: x.' }))
    renderRoute(<DeployCheck />)
    await ask()
    expect((await screen.findByText('BLOCK')).className).toContain('verdictBlock')
  })

  test('reports an advisory that matched without calling it a refusal', async () => {
    stubApi(
      answer({
        decision: 'ALLOW',
        message: 'Allowed, but 1 advisory restriction is in force for this deployment.',
        restrictions: [
          {
            id: 9,
            name: 'Year-end change window',
            reason: 'Reduced on-call cover',
            level: 'ADVISORY',
            startsAt: '2026-12-20T00:00:00Z',
            endsAt: '2027-01-02T00:00:00Z',
          },
        ],
      }),
    )
    renderRoute(<DeployCheck />)

    await ask()

    expect(await screen.findByText('ALLOW')).toBeInTheDocument()
    expect(screen.getByText('Advisory')).toBeInTheDocument()
  })

  test('says so when the check itself fails', async () => {
    // Distinct from a BLOCK: a failed request is not an answer, and rendering it as one
    // would tell somebody they are frozen when nobody said that.
    stubApi({ status: 500 })
    renderRoute(<DeployCheck />)

    await ask()

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not evaluate/i)
    expect(screen.queryByText('BLOCK')).not.toBeInTheDocument()
  })
})
