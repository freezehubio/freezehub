import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { createApiKey, listApiKeys, revokeApiKey } from '../../api/apiKeys'
import { useAuth } from '../auth/authContext'
import type { ApiKey, IssuedApiKey } from '../../types/api'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import styles from './SettingsPage.module.css'

/**
 * Machine credentials for CI/CD (`FZ-052`), which until now existed only in the API.
 *
 * Two things this screen has to get right, because the API cannot:
 *
 * - **The key is shown once.** It is displayed on its own, with a warning, and stays
 *   until dismissed rather than vanishing on the next render — losing it means issuing
 *   another one, and nobody reads a toast that has already gone.
 * - **Revoking is permanent.** It asks first, because there is no un-revoke.
 */
export function ApiKeysSection() {
  const { token } = useAuth()
  const queryClient = useQueryClient()

  const [name, setName] = useState('')
  const [issued, setIssued] = useState<IssuedApiKey | null>(null)
  const [error, setError] = useState<string | null>(null)
  /** The key whose Revoke was pressed, while its confirmation is open (`FZ-202`). */
  const [confirming, setConfirming] = useState<ApiKey | null>(null)

  const keys = useQuery<ApiKey[]>({
    queryKey: ['api-keys'],
    queryFn: ({ signal }) => listApiKeys(token, signal),
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['api-keys'] })
  }

  const create = useMutation({ mutationFn: () => createApiKey(token, name), onSuccess: invalidate })
  const revoke = useMutation({ mutationFn: (id: number) => revokeApiKey(token, id), onSuccess: invalidate })

  function report(caught: unknown) {
    setError(caught instanceof ApiError ? caught.message : 'Something went wrong. Please try again.')
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      const key = await create.mutateAsync()
      setIssued(key)
      setName('')
    } catch (caught) {
      report(caught)
    }
  }

  const forbidden = keys.error instanceof ApiError && keys.error.status === 403

  // Permanent, and a pipeline stops working the moment it happens — so it asks first.
  async function confirmRevoke() {
    if (!confirming) return
    try {
      await revoke.mutateAsync(confirming.id)
    } catch (caught) {
      report(caught)
    } finally {
      setConfirming(null)
    }
  }

  return (
    <section className={styles.section} aria-labelledby="api-keys-heading">
      <h2 className={styles.sectionHeading} id="api-keys-heading">
        API keys
      </h2>
      <p className={styles.sectionDescription}>
        How a pipeline asks whether it may deploy. A key reaches the policy API and nothing
        else — it cannot read your restrictions, change your catalog, or issue another key.
      </p>

      {forbidden && (
        <p className={styles.state} role="status">
          Only an administrator can manage API keys.
        </p>
      )}

      {!forbidden && keys.isPending && (
        <p className={styles.state} role="status">
          Loading API keys…
        </p>
      )}

      {!forbidden && keys.isError && (
        <p className={styles.actionError} role="alert">
          Could not load API keys. {keys.error.message}
        </p>
      )}

      {error && (
        <p className={styles.actionError} role="alert">
          {error}
        </p>
      )}

      {issued && (
        <div className={styles.revealedKey} role="alert">
          <p className={styles.revealedKeyWarning}>
            Copy this now — it is not shown again, and cannot be recovered.
          </p>
          <code className={styles.revealedKeyValue}>{issued.key}</code>
          <button className={styles.secondary} type="button" onClick={() => setIssued(null)}>
            Done
          </button>
        </div>
      )}

      {!forbidden && keys.data && keys.data.length === 0 && (
        <p className={styles.state}>No API keys yet. A pipeline needs one to ask about a deployment.</p>
      )}

      {!forbidden && keys.data && keys.data.length > 0 && (
        <ul className={styles.list} aria-label="API keys">
          {keys.data.map((key) => (
            <li key={key.id} className={styles.row}>
              <div className={styles.rowMain}>
                <span className={styles.itemName}>{key.name}</span>
                <span className={styles.summary}>
                  {key.keyPrefix}… · {key.revoked ? 'revoked' : 'active'} · {lastUse(key)}
                </span>
              </div>
              <div className={styles.rowActions}>
                {!key.revoked && (
                  <button
                    className={styles.danger}
                    type="button"
                    aria-label={`Revoke ${key.name}`}
                    onClick={() => {
                      setError(null)
                      setConfirming(key)
                    }}
                  >
                    Revoke
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {!forbidden && (
        <form className={styles.createForm} onSubmit={submit}>
          <label className={styles.label} htmlFor="api-key-name">
            Issue a key
          </label>
          <input
            id="api-key-name"
            className={styles.input}
            value={name}
            placeholder="gitlab-ci"
            onChange={(event) => setName(event.target.value)}
          />
          <p className={styles.hint}>
            A name you will recognise later, when deciding which key to revoke.
          </p>
          <button className={styles.primary} type="submit" disabled={!name.trim() || create.isPending}>
            {create.isPending ? 'Issuing…' : 'Issue key'}
          </button>
        </form>
      )}

      <ConfirmDialog
        open={confirming !== null}
        title={`Revoke “${confirming?.name ?? ''}”?`}
        confirmLabel="Revoke key"
        busyLabel="Revoking…"
        dismissLabel="Keep it"
        busy={revoke.isPending}
        onConfirm={() => void confirmRevoke()}
        onDismiss={() => setConfirming(null)}
      >
        <p>Any pipeline using it stops working immediately. This cannot be undone.</p>
      </ConfirmDialog>
    </section>
  )
}

/**
 * When a key was last used, in the words somebody deciding whether to revoke it needs
 * (`FZ-117`).
 *
 * **"never used" is the answer that matters.** It is the one that makes a key safe to
 * revoke, so it is said outright rather than left as a blank. A revoked key is not
 * described as unused: it stopped being able to authenticate, which is a different fact.
 */
function lastUse(key: ApiKey): string {
  if (key.lastUsedOn === null) {
    return key.revoked ? 'never used before it was revoked' : 'never used'
  }

  /*
   * Date to date, not elapsed milliseconds. Measuring the gap from "now" to midnight on
   * the stored day makes the answer depend on the hour: a key used yesterday evening
   * reads "2 days ago" once the clock passes midday. Both ends are pinned to UTC
   * midnight so a day is a day.
   */
  const today = new Date().toISOString().slice(0, 10)
  const days = Math.round(
    (Date.parse(`${today}T00:00:00Z`) - Date.parse(`${key.lastUsedOn}T00:00:00Z`)) / 86_400_000,
  )
  if (days <= 0) return 'used today'
  if (days === 1) return 'used yesterday'
  if (days < 30) return `used ${days} days ago`
  return `last used ${key.lastUsedOn}`
}
