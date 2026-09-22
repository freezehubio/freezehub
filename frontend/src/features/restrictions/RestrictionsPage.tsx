import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { LevelBadge, StatusBadge } from '../../components/Badges'
import { formatInstant } from '../../utils/datetime'
import { ALL_STATUSES, parseStatuses, useRestrictionList } from './useRestrictionList'
import type { RestrictionStatus, RestrictionSummary } from '../../types/api'
import { cancelRestriction } from '../../api/restrictions'
import { ApiError } from '../../api/client'
import { useAuth } from '../auth/authContext'
import styles from './RestrictionsPage.module.css'

/**
 * Cancellable exactly where the detail page says so (`FZ-024`): scheduled, or active.
 * One expression rather than a rule spread over two files — two screens disagreeing about
 * whether a freeze can be lifted is worse than either answer.
 */
function isCancellable(restriction: RestrictionSummary): boolean {
  return restriction.status === 'SCHEDULED' || restriction.status === 'ACTIVE'
}

/**
 * Browsing and filtering restrictions.
 *
 * Filter state lives in the URL, not component state, so a filtered view can be linked
 * to, bookmarked and survives a reload (05-frontend.md).
 *
 * <b>Cancelling is here as well as on the detail page (`FZ-188`).</b> It is the action with
 * the most time pressure in the product — every other thing a list offers can wait, and a
 * freeze that should have been lifted is blocking deployments across the organization for as
 * long as it takes to find the screen. It posts to the same endpoint the detail page does,
 * so the audit record cannot tell the two routes apart.
 */
export function RestrictionsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const selected = parseStatuses(searchParams.getAll('status'))
  const { data, isPending, isError, error, refetch } = useRestrictionList(selected)
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const [actionError, setActionError] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState<number | null>(null)

  const cancel = useMutation({
    mutationFn: (id: number) => cancelRestriction(token, id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['restrictions'] })
    },
  })

  async function requestCancel(restriction: RestrictionSummary) {
    setActionError(null)

    // The same confirmation shape as revoking an API key. Cancelling from a list is one
    // misclick away from every neighbouring row, which the detail page is not.
    if (
      !window.confirm(
        `Cancel ""? It ends now and every channel is notified. This cannot be undone.`,
      )
    ) {
      return
    }

    setCancelling(restriction.id)
    try {
      await cancel.mutateAsync(restriction.id)
    } catch (caught) {
      // 409 means it finished or was cancelled already — likely while this list was open.
      setActionError(
        caught instanceof ApiError ? caught.message : 'Could not cancel the restriction.',
      )
    } finally {
      setCancelling(null)
    }
  }

  function toggleStatus(status: RestrictionStatus) {
    const next = selected.includes(status)
      ? selected.filter((value) => value !== status)
      : [...selected, status]

    const params = new URLSearchParams()
    next.forEach((value) => params.append('status', value))
    setSearchParams(params, { replace: true })
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Restrictions</h1>
        <Link className={styles.newButton} to="/restrictions/new">
          New restriction
        </Link>
      </div>

      {/*
        * A group rather than a fieldset (FZ-134). The box a fieldset draws is Industry's,
        * and the accessible name a legend gives is the only thing that was load-bearing —
        * `role="group"` with `aria-labelledby` keeps that and nothing else.
        */}
      <div className={styles.filters} role="group" aria-labelledby="filter-by-status">
        <span className={styles.filterLabel} id="filter-by-status">
          Filter by status
        </span>
        {ALL_STATUSES.map((status) => {
          const on = selected.includes(status)
          return (
            <label
              key={status}
              className={`tag ${on ? 'tag-accent' : 'tag-outline'} ${styles.chip}`}
            >
              <input type="checkbox" checked={on} onChange={() => toggleStatus(status)} />
              {status.toLowerCase()}
            </label>
          )
        })}
        {selected.length > 0 && (
          <button
            className={styles.clear}
            type="button"
            onClick={() => setSearchParams(new URLSearchParams(), { replace: true })}
          >
            Clear
          </button>
        )}
      </div>

      {actionError && (
        <p className={styles.actionError} role="alert">
          {actionError}
        </p>
      )}

      {isPending && (
        <p className={styles.state} role="status">
          Loading restrictions…
        </p>
      )}

      {isError && (
        <div className={styles.state} role="alert">
          <p className={styles.errorText}>Could not load restrictions. {error.message}</p>
          <button className={styles.retry} type="button" onClick={() => refetch()}>
            Try again
          </button>
        </div>
      )}

      {!isPending && !isError && data.length === 0 && (
        <p className={styles.state}>
          {selected.length > 0
            ? 'No restrictions match this filter.'
            : 'No restrictions yet.'}
        </p>
      )}

      {!isPending && !isError && data.length > 0 && (
        <div className={styles.tableWrap}>
          {/* The system's own table (FZ-134), as the checks console has drawn one since
              `FZ-071`. It was a local copy that boxed itself. */}
          <table className="table">
            <caption className={styles.caption}>
              {data.length} restriction{data.length === 1 ? '' : 's'}, soonest start first
            </caption>
            <thead className={styles.head}>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Status</th>
                <th scope="col">Level</th>
                <th scope="col">Starts</th>
                <th scope="col">Ends</th>
                <th scope="col">
                  <span className={styles.srOnly}>Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {data.map((restriction) => (
                <tr key={restriction.id}>
                  <td>
                    <Link className={styles.name} to={`/restrictions/${restriction.id}`}>
                      {restriction.name}
                    </Link>
                    <span className={styles.reason}>{restriction.reason}</span>
                  </td>
                  <td>
                    <StatusBadge status={restriction.status} />
                  </td>
                  <td>
                    <LevelBadge level={restriction.level} />
                  </td>
                  <td className={styles.when}>{formatInstant(restriction.startsAt)}</td>
                  <td className={styles.when}>{formatInstant(restriction.endsAt)}</td>
                  <td className={styles.actions}>
                    {/* Absent rather than disabled where the status forbids it: a row is
                        one glance, and a greyed control still reads as "there is something
                        here for me". The detail page has room to explain why; this has not. */}
                    {isCancellable(restriction) && (
                      <button
                        className={styles.cancel}
                        type="button"
                        onClick={() => void requestCancel(restriction)}
                        disabled={cancelling !== null}
                        aria-label={`Cancel ${restriction.name}`}
                      >
                        {cancelling === restriction.id ? 'Cancelling…' : 'Cancel'}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  )
}
