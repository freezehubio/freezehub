import { screen, waitFor, within } from '@testing-library/react'
import { setupUser } from '../../test/user'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { DeploymentChecksPage } from './DeploymentChecksPage'
import { renderRoute } from '../../test/renderRoute'
import type { DeploymentCheck, DeploymentCheckSummary } from '../../types/api'

const check = (overrides: Partial<DeploymentCheck> = {}): DeploymentCheck => ({
  id: 1,
  application: 'payments-api',
  environment: 'production',
  decision: 'ALLOW',
  blockedReason: null,
  matchedRestrictions: null,
  actor: 'alice@acme.test',
  reference: 'a1b2c3d4e5f6789',
  source: 'https://gitlab.acme.test/pipelines/9182',
  checkedBy: 'gitlab-ci',
  checkedAt: '2026-08-31T06:48:53Z',
  ...overrides,
})

/** A fortnight of nothing, unless a test says otherwise. */
const emptySummary: DeploymentCheckSummary = {
  today: { total: 0, allowed: 0, refused: 0 },
  applications: { seen: 0, total: 0 },
  daily: Array.from({ length: 14 }, (_, index) => ({
    date: `2026-08-${String(18 + index).padStart(2, '0')}`,
    allowed: 0,
    refused: 0,
  })),
  refusalsByRestriction: [],
  unregistered: 0,
}

/**
 * Pages of checks, plus the summary the chart and figures read.
 *
 * The two are routed apart by URL rather than by call order: the summary request is not
 * the paging request, and answering it with a page of checks is how this stub used to
 * crash the page.
 */
function stubApi(pages: DeploymentCheck[][], summary: DeploymentCheckSummary = emptySummary) {
  let call = 0
  const spy = vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    const json = (body: unknown) =>
      Promise.resolve(
        new Response(JSON.stringify(body), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

    if (url.includes('/api/deployment-checks/summary')) return json(summary)
    // The catalog behind the check panel's suggestions. Routed explicitly for the same
    // reason the summary is: falling through would hand it a page of checks *and* advance
    // the paging cursor, so a test about "load more" would be reading someone else's call.
    if (url.includes('/api/applications') || url.includes('/api/environments')) return json([])

    const page = pages[Math.min(call, pages.length - 1)]
    call += 1
    return json(page)
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

describe('DeploymentChecksPage', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  test('shows who tried to deploy what, and where', async () => {
    stubApi([[check()]])
    renderRoute(<DeploymentChecksPage />, { path: '/deployment-checks' })

    const list = await screen.findByRole('table', { name: 'Deployment checks' })
    expect(within(list).getByText('payments-api → production')).toBeInTheDocument()
    expect(within(list).getByText('alice@acme.test')).toBeInTheDocument()
    // Truncated: a full SHA is noise in a list, and the first characters identify it.
    expect(within(list).getByText('a1b2c3d4e5f6')).toBeInTheDocument()
    // The reference is the link to the run — one column doing both jobs (1f).
    expect(within(list).getByRole('link', { name: 'a1b2c3d4e5f6' })).toHaveAttribute(
      'href',
      'https://gitlab.acme.test/pipelines/9182',
    )
  })

  test('names the freeze that refused a deployment', async () => {
    // The view worth having: not just that it was refused, but by what.
    stubApi([
      [
        check({
          decision: 'BLOCK',
          blockedReason: 'RESTRICTION',
          matchedRestrictions: [
            { id: 7, name: 'Black Friday Freeze', level: 'HARD_FREEZE' },
            { id: 8, name: 'Release week caution', level: 'ADVISORY' },
          ],
        }),
      ],
    ])
    renderRoute(<DeploymentChecksPage />, { path: '/deployment-checks' })

    expect(await screen.findByText(/Blocked by Black Friday Freeze/)).toBeInTheDocument()
    // The advisory did not refuse anything, so naming it here would be wrong.
    expect(screen.queryByText(/Release week caution/)).not.toBeInTheDocument()
  })

  test('distinguishes an unregistered name from a real freeze', async () => {
    // They mean different things: one is the product working, the other is a pipeline
    // misconfigured — or someone trying a misspelling to get through.
    stubApi([[check({ decision: 'BLOCK', blockedReason: 'UNREGISTERED', environment: 'prod' })]])
    renderRoute(<DeploymentChecksPage />, { path: '/deployment-checks' })

    expect(await screen.findByText(/is not registered, so nothing could be evaluated/)).toBeInTheDocument()
  })

  test('falls back to the credential when the pipeline did not say who', async () => {
    stubApi([[check({ actor: null, reference: null, source: null })]])
    renderRoute(<DeploymentChecksPage />, { path: '/deployment-checks' })

    const list = await screen.findByRole('table', { name: 'Deployment checks' })
    expect(within(list).getByText('gitlab-ci')).toBeInTheDocument()
    expect(within(list).queryByRole('link')).not.toBeInTheDocument()
  })

  test('filters to what was refused, and puts it in the URL', async () => {
    // So "everything we refused during the freeze" is a link someone can send.
    const user = setupUser()
    const spy = stubApi([[check({ decision: 'BLOCK', blockedReason: 'RESTRICTION' })]])
    const { router } = renderRoute(<DeploymentChecksPage />, { path: '/deployment-checks' })

    await user.click(await screen.findByRole('radio', { name: 'Refused' }))

    await waitFor(() => expect(router.state.location.search).toContain('decision=BLOCK'))
    await waitFor(() =>
      expect(spy.mock.calls.some(([input]) => String(input).includes('decision=BLOCK'))).toBe(true),
    )
  })

  test('starts filtered when the URL says so', async () => {
    const spy = stubApi([[check({ decision: 'BLOCK' })]])
    renderRoute(<DeploymentChecksPage />, {
      path: '/deployment-checks?decision=BLOCK',
      route: '/deployment-checks',
    })

    await waitFor(() =>
      expect(spy.mock.calls.some(([input]) => String(input).includes('decision=BLOCK'))).toBe(true),
    )
    expect(screen.getByRole('radio', { name: 'Refused' })).toBeChecked()
  })

  test('pages by cursor rather than by offset', async () => {
    // A full page means there may be more; the next request starts before the last id
    // seen, so a deployment happening mid-read cannot shift the window.
    const user = setupUser()
    const fullPage = Array.from({ length: 50 }, (_, index) => check({ id: 100 - index }))
    const spy = stubApi([fullPage, [check({ id: 40 })]])
    renderRoute(<DeploymentChecksPage />, { path: '/deployment-checks' })

    await user.click(await screen.findByRole('button', { name: /load older/i }))

    await waitFor(() =>
      expect(spy.mock.calls.some(([input]) => String(input).includes('beforeId=51'))).toBe(true),
    )
  })

  test('offers no "load older" when the page was not full', async () => {
    // Asking again would return nothing and offer a button that does nothing.
    stubApi([[check()]])
    renderRoute(<DeploymentChecksPage />, { path: '/deployment-checks' })

    expect(await screen.findByText('payments-api → production')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /load older/i })).not.toBeInTheDocument()
  })

  test('says plainly that these are questions, not deployments', async () => {
    // The naming decision (D-19) has to reach the person reading the screen, not just
    // the code. Believing these are deployments would mislead in exactly the audit this
    // screen exists to serve.
    stubApi([[check()]])
    renderRoute(<DeploymentChecksPage />, { path: '/deployment-checks' })

    expect(
      await screen.findByText(/questions, not the outcomes/i),
    ).toBeInTheDocument()
  })

  test('explains an empty refused view rather than looking broken', async () => {
    stubApi([[]])
    renderRoute(<DeploymentChecksPage />, {
      path: '/deployment-checks?decision=BLOCK',
      route: '/deployment-checks',
    })

    expect(await screen.findByText(/no pipeline has been stopped by a freeze/i)).toBeInTheDocument()
  })

  test('charts the fortnight and states it in words for a reader who cannot see it', async () => {
    stubApi([[check()]], {
      ...emptySummary,
      applications: { seen: 11, total: 14 },
      unregistered: 4,
      daily: [
        ...Array.from({ length: 12 }, (_, index) => ({
          date: `2026-08-${String(18 + index).padStart(2, '0')}`,
          allowed: 0,
          refused: 0,
        })),
        { date: '2026-08-30', allowed: 60, refused: 6 },
        { date: '2026-08-31', allowed: 17, refused: 3 },
      ],
    })
    renderRoute(<DeploymentChecksPage />, { path: '/deployment-checks' })

    // A bar chart is a shape; without this a screen reader gets fourteen empty divs.
    expect(
      await screen.findByRole('img', { name: /86 checks over 14 days, 9 of them refused/i }),
    ).toBeInTheDocument()
  })

  test('the figures describe the fortnight, not the page of rows below them', async () => {
    stubApi([[check()]], {
      ...emptySummary,
      applications: { seen: 11, total: 14 },
      unregistered: 4,
      daily: [
        ...Array.from({ length: 13 }, (_, index) => ({
          date: `2026-08-${String(18 + index).padStart(2, '0')}`,
          allowed: 0,
          refused: 0,
        })),
        { date: '2026-08-31', allowed: 679, refused: 63 },
      ],
    })
    renderRoute(<DeploymentChecksPage />, { path: '/deployment-checks' })

    expect(await screen.findByText('742')).toBeInTheDocument()
    expect(screen.getByText('63')).toBeInTheDocument()
    expect(screen.getByText('refused (8%)')).toBeInTheDocument()
    expect(screen.getByText('11')).toBeInTheDocument()
    expect(screen.getByText('refused as unregistered')).toBeInTheDocument()
  })

  test('an empty fortnight draws no bars rather than dividing by zero', async () => {
    // Every day is 0, so the busiest day is 0. A percentage of nothing is undefined, and
    // the naive scale would draw every column full.
    stubApi([[]], emptySummary)
    renderRoute(<DeploymentChecksPage />, { path: '/deployment-checks' })

    const chart = await screen.findByRole('img', { name: /no checks in the last 14 days/i })
    const bars = chart.querySelectorAll('div[style*="height"]')
    bars.forEach((bar) => expect((bar as HTMLElement).style.height).toBe('0%'))
  })
})
