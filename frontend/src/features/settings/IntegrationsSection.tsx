import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import {
  createIntegration,
  deleteIntegration,
  listIntegrations,
  setIntegrationEnabled,
} from '../../api/integrations'
import { useAuth } from '../auth/authContext'
import type { Integration, IntegrationType } from '../../types/api'
import styles from './SettingsPage.module.css'

/**
 * What each channel asks for, in its own words (`FZ-189`).
 *
 * Replaces a raw JSON textarea whose placeholder was the only guidance. The stored shape is
 * unchanged — `integration.config` is still opaque JSON that the owning channel interprets
 * (`03-data-model.md`) — but composing it is the form's job now, not the operator's. A
 * misplaced brace used to fail at the backend with a parse error, three layers away from the
 * field that caused it.
 */
const CHANNELS: Record<
  IntegrationType,
  {
    label: string
    /** `url` collects one address; `recipients` collects a list. */
    kind: 'url' | 'recipients'
    fieldLabel: string
    placeholder: string
    hint: string
    /** The JSON key this channel's backend validator reads. */
    configKey: string
  }
> = {
  SLACK: {
    label: 'Slack',
    kind: 'url',
    fieldLabel: 'Incoming webhook URL',
    placeholder: 'https://hooks.slack.com/services/T000/B000/xxxxxxxx',
    hint: 'Create one in Slack under your app’s Incoming Webhooks. The whole URL is a credential — it is stored, never shown again.',
    configKey: 'webhookUrl',
  },
  EMAIL: {
    label: 'Email',
    kind: 'recipients',
    fieldLabel: 'Recipient addresses',
    placeholder: 'releases@acme.test, platform@acme.test',
    hint: 'One or more addresses, separated by commas or new lines.',
    configKey: 'recipients',
  },
  WEBHOOK: {
    label: 'Webhook',
    kind: 'url',
    fieldLabel: 'Endpoint URL',
    placeholder: 'https://acme.test/hooks/freezehub',
    hint: 'An HTTPS endpoint that receives machine-readable lifecycle events, signed with X-FreezeHub-Signature.',
    configKey: 'url',
  },
}

/** Split on commas or new lines, dropping the empties a trailing separator leaves behind. */
function splitRecipients(value: string): string[] {
  return value
    .split(/[,\n]/)
    .map((address) => address.trim())
    .filter((address) => address.length > 0)
}

/**
 * Turns what was typed into the JSON the backend already expects.
 *
 * One place, so the field layout and the stored shape cannot drift apart — and so adding a
 * channel is a row in `CHANNELS` plus a case here, rather than a new textarea convention.
 */
function composeConfig(type: IntegrationType, value: string): string {
  const channel = CHANNELS[type]
  if (channel.kind === 'recipients') {
    return JSON.stringify({ [channel.configKey]: splitRecipients(value) })
  }
  return JSON.stringify({ [channel.configKey]: value.trim() })
}

/**
 * What is wrong with this value, in words, or null when nothing is.
 *
 * **Not a security check, and it matters that nobody later reads it as one.** `OI-23`
 * settled that in `FZ-125`: the egress boundary is at connect time in the backend, because
 * a webhook URL is attacker-chosen by design and a DNS name can be moved between validating
 * and connecting. `OutboundAddressPolicy` is that boundary. This catches typing mistakes
 * while the person is still looking at the field.
 */
function describeProblem(type: IntegrationType, value: string): string | null {
  const channel = CHANNELS[type]
  const trimmed = value.trim()
  if (trimmed === '') return null

  if (channel.kind === 'recipients') {
    const bad = splitRecipients(trimmed).find(
      (address) => !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(address),
    )
    return bad ? `“${bad}” is not an email address.` : null
  }

  let url: URL
  try {
    url = new URL(trimmed)
  } catch {
    return 'That is not a URL. It should start with https://.'
  }
  if (url.protocol !== 'https:') return 'The URL must use https.'
  if (url.username || url.password) {
    return 'Remove the username before the @ — the server that receives this ignores it.'
  }
  return null
}

/**
 * Where restriction announcements are sent (`FZ-045`).
 *
 * Extracted from SettingsPage when API keys and organization settings joined it
 * (`FZ-038`), following the CatalogSection pattern — three sections inline would have
 * made one component nobody wants to read.
 */
export function IntegrationsSection() {
  const { token } = useAuth()
  const queryClient = useQueryClient()

  const [type, setType] = useState<IntegrationType>('SLACK')
  /** What was typed, not what is stored. `composeConfig` turns one into the other. */
  const [value, setValue] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)

  const channel = CHANNELS[type]
  const problem = describeProblem(type, value)

  const integrations = useQuery<Integration[]>({
    queryKey: ['integrations'],
    queryFn: ({ signal }) => listIntegrations(token, signal),
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['integrations'] })
  }

  const create = useMutation({
    mutationFn: () => createIntegration(token, type, composeConfig(type, value)),
    onSuccess: invalidate,
  })
  const toggle = useMutation({
    mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) =>
      setIntegrationEnabled(token, id, enabled),
    onSuccess: invalidate,
  })
  const remove = useMutation({
    mutationFn: (id: number) => deleteIntegration(token, id),
    onSuccess: invalidate,
  })

  function report(caught: unknown) {
    // The backend validates each channel's config; its message says what is wrong with it.
    setActionError(
      caught instanceof ApiError ? caught.message : 'Something went wrong. Please try again.',
    )
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setActionError(null)
    try {
      await create.mutateAsync()
      setValue('')
    } catch (caught) {
      report(caught)
    }
  }

  /**
   * Changing channel clears the field.
   *
   * A Slack webhook URL left behind in a recipients field is not a plausible thing to
   * submit, and carrying it over invites exactly that.
   */
  function changeType(next: IntegrationType) {
    setType(next)
    setValue('')
    setActionError(null)
  }

  const forbidden = integrations.error instanceof ApiError && integrations.error.status === 403

  return (
    <section className={styles.section} aria-labelledby="integrations-heading">
        <h2 className={styles.sectionHeading} id="integrations-heading">
          Notification destinations
        </h2>
        <p className={styles.sectionDescription}>
          Where restriction announcements are sent. A restriction that is scheduled,
          activated, completed or cancelled is announced to every enabled destination.
        </p>

        {forbidden && (
          <p className={styles.state} role="status">
            Only an administrator can manage notification destinations.
          </p>
        )}

        {!forbidden && integrations.isPending && (
          <p className={styles.state} role="status">
            Loading destinations…
          </p>
        )}

        {!forbidden && integrations.isError && (
          <p className={styles.actionError} role="alert">
            Could not load destinations. {integrations.error.message}
          </p>
        )}

        {actionError && (
          <p className={styles.actionError} role="alert">
            {actionError}
          </p>
        )}

        {!forbidden && integrations.data && integrations.data.length === 0 && (
          <p className={styles.state}>
            No destinations yet. Nothing will be announced until one is added.
          </p>
        )}

        {!forbidden && integrations.data && integrations.data.length > 0 && (
          <ul className={styles.list} aria-label="Notification destinations">
            {integrations.data.map((integration) => (
              <li key={integration.id} className={styles.row}>
                <div className={styles.rowMain}>
                  <span className={styles.itemName}>{CHANNELS[integration.type].label}</span>
                  <span className={styles.summary}>{integration.summary}</span>
                  {/*
                    * A channel that is enabled and failing looks identical to one that is
                    * working, unless it says so here — and settings is where somebody
                    * comes to fix it (FZ-117).
                    */}
                  {integration.failedDeliveries > 0 && (
                    <Link className="tag tag-accent-2" to="/notifications?show=failed">
                      {integration.failedDeliveries} not delivered
                    </Link>
                  )}
                </div>
                <div className={styles.rowActions}>
                  <label className={styles.toggle}>
                    <input
                      type="checkbox"
                      checked={integration.enabled}
                      aria-label={`${CHANNELS[integration.type].label} enabled`}
                      onChange={(event) => {
                        setActionError(null)
                        toggle.mutate({ id: integration.id, enabled: event.target.checked })
                      }}
                    />
                    Enabled
                  </label>
                  <button
                    className={styles.danger}
                    type="button"
                    aria-label={`Delete ${CHANNELS[integration.type].label} destination`}
                    onClick={async () => {
                      setActionError(null)
                      try {
                        await remove.mutateAsync(integration.id)
                      } catch (caught) {
                        report(caught)
                      }
                    }}
                  >
                    Delete
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}

        {!forbidden && (
          <form className={styles.createForm} onSubmit={submit}>
            <label className={styles.label} htmlFor="integration-type">
              Add a destination
            </label>
            <select
              id="integration-type"
              className={styles.input}
              value={type}
              onChange={(event) => changeType(event.target.value as IntegrationType)}
            >
              {(Object.keys(CHANNELS) as IntegrationType[]).map((option) => (
                <option key={option} value={option}>
                  {CHANNELS[option].label}
                </option>
              ))}
            </select>

            <label className={styles.label} htmlFor="integration-config">
              {channel.fieldLabel}
            </label>
            {/*
              A textarea only where a list is genuinely multi-line. A single URL in a
              three-row box invites a second line that the backend will refuse.
            */}
            {channel.kind === 'recipients' ? (
              <textarea
                id="integration-config"
                className={styles.textarea}
                rows={3}
                placeholder={channel.placeholder}
                value={value}
                aria-invalid={problem !== null}
                aria-describedby="integration-config-hint"
                onChange={(event) => setValue(event.target.value)}
              />
            ) : (
              <input
                id="integration-config"
                className={styles.input}
                type="url"
                inputMode="url"
                placeholder={channel.placeholder}
                value={value}
                aria-invalid={problem !== null}
                aria-describedby="integration-config-hint"
                onChange={(event) => setValue(event.target.value)}
              />
            )}

            {problem ? (
              <p className={styles.actionError} role="alert">
                {problem}
              </p>
            ) : (
              <p className={styles.hint} id="integration-config-hint">
                {channel.hint}
              </p>
            )}

            <button
              className={styles.primary}
              type="submit"
              disabled={!value.trim() || problem !== null || create.isPending}
            >
              {create.isPending ? 'Adding…' : 'Add destination'}
            </button>
          </form>
        )}
    </section>
  )
}
