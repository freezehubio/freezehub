import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError } from '../../api/client'
import { createCheckoutSession, createPortalSession, getSubscription } from '../../api/billing'
import { useAuth } from '../auth/authContext'
import type { PlanUsage, Subscription } from '../../types/api'
import styles from './SettingsPage.module.css'

/** What each countable resource is called in the product, rather than in the schema. */
const RESOURCE_LABELS: Record<string, string> = {
  applications: 'Applications',
  apiKeys: 'API keys',
  notificationDestinations: 'Notification destinations',
}

const STATUS_LABELS: Record<Subscription['status'], string> = {
  TRIALING: 'Trial',
  ACTIVE: 'Active',
  PAST_DUE: 'Payment failed',
  SUSPENDED: 'Suspended',
  CANCELLED: 'Cancelled',
}

function describeUsage(usage: PlanUsage): string {
  const label = RESOURCE_LABELS[usage.resource] ?? usage.resource
  if (usage.limit === null) return `${label}: ${usage.current} of unlimited`
  return `${label}: ${usage.current} of ${usage.limit}`
}

/**
 * The plan, what it allows, and how much is left (`FZ-085`).
 *
 * Every number comes from the backend. A limit the frontend worked out for itself would
 * be a second source of truth for entitlement, and the first thing to disagree with the
 * code that actually refuses.
 *
 * Visible to any member, unlike the other sections here — a member who cannot see why a
 * creation was refused files a bug instead of asking their administrator to upgrade.
 */
export function BillingSection() {
  const { token } = useAuth()
  const [error, setError] = useState<string | null>(null)

  const subscription = useQuery<Subscription>({
    queryKey: ['subscription'],
    queryFn: ({ signal }) => getSubscription(token, signal),
  })

  // Stripe hosts both pages, so the only thing this does is follow the URL it is given.
  // No card detail ever reaches FreezeHub, which is what keeps it out of PCI scope.
  const goToStripe = useMutation({
    mutationFn: (destination: 'checkout' | 'portal') =>
      destination === 'checkout'
        ? createCheckoutSession(token, 'GROWTH', 'MONTHLY')
        : createPortalSession(token),
    onSuccess: ({ url }) => {
      window.location.assign(url)
    },
    onError: (caught: unknown) => {
      setError(
        caught instanceof ApiError
          ? caught.message
          : 'Could not reach the payment provider. Please try again.',
      )
    },
  })

  if (subscription.isPending) {
    return (
      <section className={styles.section}>
        <h2 className={styles.sectionHeading}>Billing</h2>
        <p className={styles.state}>Loading…</p>
      </section>
    )
  }

  if (subscription.error) {
    return (
      <section className={styles.section}>
        <h2 className={styles.sectionHeading}>Billing</h2>
        <p className={styles.actionError} role="alert">
          {subscription.error instanceof ApiError
            ? subscription.error.message
            : 'Could not load your plan.'}
        </p>
      </section>
    )
  }

  const plan = subscription.data

  return (
    <section className={styles.section}>
      <h2 className={styles.sectionHeading}>Billing</h2>

      <p className={styles.state}>
        On the <strong>{plan.plan}</strong> plan · {STATUS_LABELS[plan.status]}
        {plan.trialDaysRemaining !== null && (
          <> · {plan.trialDaysRemaining} {plan.trialDaysRemaining === 1 ? 'day' : 'days'} left</>
        )}
      </p>

      {!plan.blocksDeployments && (
        <p className={styles.hint}>
          Freezes on this plan are <strong>advisory</strong>: a pipeline is told one is in
          force and deploys anyway. Blocking freezes come with any paid plan.
        </p>
      )}

      {plan.status === 'PAST_DUE' && (
        <p className={styles.actionError} role="alert">
          A payment did not go through. Your restrictions are still enforced and your team
          can still use FreezeHub — update your card to avoid interruption.
        </p>
      )}

      {plan.status === 'SUSPENDED' && (
        <p className={styles.actionError} role="alert">
          This organization is suspended. Existing restrictions are still enforced, but
          nothing can be changed until billing is sorted out.
        </p>
      )}

      {/* Defensive on purpose: this section sits on a page with three others, and a
          malformed body should not take all of them down with it. */}
      <ul className={styles.list} aria-label="Plan usage">
        {(plan.usage ?? []).map((usage) => (
          <li key={usage.resource} className={styles.row}>
            <span className={styles.itemName}>{describeUsage(usage)}</span>
            {usage.percentUsed !== null && (
              <span
                className={usage.atLimit ? styles.meterFull : styles.meter}
                role="img"
                aria-label={`${usage.percentUsed}% used`}
              >
                <span style={{ width: `${usage.percentUsed}%` }} />
              </span>
            )}
            {usage.atLimit && <span className={styles.hint}>At limit</span>}
          </li>
        ))}
      </ul>

      {error && (
        <p className={styles.actionError} role="alert">
          {error}
        </p>
      )}

      <div className={styles.rowActions}>
        {plan.canUpgradeSelfServe && (
          <button
            type="button"
            className={styles.primary}
            onClick={() => goToStripe.mutate('checkout')}
            disabled={goToStripe.isPending}
          >
            {plan.status === 'TRIALING' ? 'Choose a plan' : 'Change plan'}
          </button>
        )}
        {plan.hasBillingAccount && (
          <button
            type="button"
            className={styles.secondary}
            onClick={() => goToStripe.mutate('portal')}
            disabled={goToStripe.isPending}
          >
            Manage billing
          </button>
        )}
      </div>

      {!plan.canUpgradeSelfServe && (
        <p className={styles.state}>
          Enterprise plans are arranged directly. Talk to us to change yours.
        </p>
      )}
    </section>
  )
}
