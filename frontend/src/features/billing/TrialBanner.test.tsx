import { screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { TrialBanner } from './TrialBanner'
import { renderRoute } from '../../test/renderRoute'
import type { Subscription } from '../../types/api'

function stubSubscription(overrides: Partial<Subscription>, status = 200) {
  const subscription: Subscription = {
    plan: 'TRIAL',
    status: 'TRIALING',
    canUpgradeSelfServe: true,
    blocksDeployments: true,
    hasBillingAccount: false,
    trialEndsAt: '2026-09-20T00:00:00Z',
    trialDaysRemaining: 14,
    currentPeriodEndsAt: null,
    usage: [],
    ...overrides,
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve(
        new Response(JSON.stringify(subscription), {
          status,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    ),
  )
}

describe('TrialBanner', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('says nothing while a trial has plenty of time left', async () => {
    // A banner that is always there is one nobody reads.
    stubSubscription({ trialDaysRemaining: 12 })
    renderRoute(<TrialBanner />)

    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
  })

  test('warns once a trial is nearly out', async () => {
    stubSubscription({ trialDaysRemaining: 3 })
    renderRoute(<TrialBanner />)

    expect(await screen.findByRole('status')).toHaveTextContent('Your trial ends in 3 days')
    expect(screen.getByRole('link', { name: 'Choose a plan' })).toBeInTheDocument()
  })

  test('reads naturally on the last day', async () => {
    stubSubscription({ trialDaysRemaining: 0 })
    renderRoute(<TrialBanner />)

    expect(await screen.findByRole('status')).toHaveTextContent('Your trial ends today')
  })

  test('says a suspended organization is still enforcing its freezes', async () => {
    // The reassurance matters as much as the warning: a customer who thinks suspension
    // lifted their freezes will go and deploy (D-21).
    stubSubscription({ status: 'SUSPENDED', trialDaysRemaining: null })
    renderRoute(<TrialBanner />)

    const banner = await screen.findByRole('status')
    expect(banner).toHaveTextContent('suspended')
    expect(banner).toHaveTextContent('still enforced')
  })

  test('says a failed payment has changed nothing yet', async () => {
    stubSubscription({ status: 'PAST_DUE', trialDaysRemaining: null })
    renderRoute(<TrialBanner />)

    expect(await screen.findByRole('status')).toHaveTextContent('Nothing has changed yet')
  })

  test('stays out of the way when the plan cannot be loaded', async () => {
    // Without a banner the product works. With a broken one it looks broken.
    stubSubscription({}, 500)
    renderRoute(<TrialBanner />)

    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
  })
})
