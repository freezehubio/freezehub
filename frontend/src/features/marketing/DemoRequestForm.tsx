import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { requestDemo } from '../../api/demoRequests'
import styles from './DemoRequestForm.module.css'

/**
 * The form behind "Book a demo" (`FZ-213`).
 *
 * **It did not exist.** `FZ-083` built `POST /api/demo-requests`, the `demo_request` table
 * and the Slack notification, and nothing ever called them — so the landing page's primary
 * call to action scrolled to three paragraphs of prose whose only link was *start a free
 * trial*. A prospect who wanted to talk to somebody had no way to say so.
 *
 * **Capture, then offer a time** — rather than sending people straight to a scheduler.
 * The row is the only record of a prospect who never signs up, `converted_organization_id`
 * is what later shows a demo became a customer, and the answers here are the ones
 * `gtm/03-discovery.md` wants to have before the call. A bare calendar link gives a name
 * and a time and loses all three.
 */
export function DemoRequestForm() {
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [company, setCompany] = useState('')
  const [teamSize, setTeamSize] = useState('')
  const [message, setMessage] = useState('')

  const [acknowledgement, setAcknowledgement] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  /*
   * Read at build time, like every other VITE_ value: a browser cannot be told an address
   * after the bundle is built. Absent is the normal state until somebody sets up a
   * scheduler, and absent must not produce a dead button — see below.
   */
  const bookingUrl = (import.meta.env.VITE_DEMO_BOOKING_URL ?? '').trim()

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      const { message: acknowledged } = await requestDemo({
        name: name.trim(),
        email: email.trim(),
        company: company.trim(),
        teamSize: teamSize.trim() || undefined,
        message: message.trim() || undefined,
      })
      setAcknowledgement(acknowledged)
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 429) {
        setError('That is a lot of requests. Give it a minute and try again.')
      } else if (caught instanceof ApiError && caught.fieldErrors.length > 0) {
        setError(caught.fieldErrors.map((field) => field.message).join(' '))
      } else if (caught instanceof ApiError) {
        setError(caught.message)
      } else {
        setError('Could not send that. Try again, or email us.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (acknowledgement !== null) {
    return (
      <div className={styles.done} role="status">
        <h3 className={styles.doneTitle}>Request received</h3>
        <p className={styles.doneText}>{acknowledgement}</p>

        {/*
          * Only when a scheduler has been configured. A "Pick a time" button that goes
          * nowhere is worse than no button: it reads as an offer, and the person who
          * clicks it has already decided to meet.
          */}
        {bookingUrl !== '' && (
          <>
            <a
              className={`btn btn-primary ${styles.book}`}
              href={bookingUrl}
              target="_blank"
              rel="noopener noreferrer"
            >
              Pick a time now
            </a>
            <p className={styles.doneNote}>
              Opens our calendar. You do not have to — we will email you either way.
            </p>
          </>
        )}
      </div>
    )
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit} aria-labelledby="demo-form-heading">
      <h3 className={styles.heading} id="demo-form-heading">
        Ask for a demo
      </h3>

      <div className={styles.row}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="demo-name">
            Name
          </label>
          <input
            className={styles.input}
            id="demo-name"
            maxLength={255}
            name="name"
            onChange={(event) => setName(event.target.value)}
            required
            value={name}
          />
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="demo-email">
            Work email
          </label>
          <input
            className={styles.input}
            id="demo-email"
            maxLength={320}
            name="email"
            onChange={(event) => setEmail(event.target.value)}
            required
            type="email"
            value={email}
          />
        </div>
      </div>

      <div className={styles.row}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="demo-company">
            Company
          </label>
          <input
            className={styles.input}
            id="demo-company"
            maxLength={255}
            name="company"
            onChange={(event) => setCompany(event.target.value)}
            required
            value={company}
          />
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="demo-team-size">
            Engineers who deploy <span className={styles.optional}>optional</span>
          </label>
          <input
            className={styles.input}
            id="demo-team-size"
            maxLength={32}
            name="teamSize"
            onChange={(event) => setTeamSize(event.target.value)}
            value={teamSize}
          />
        </div>
      </div>

      <label className={styles.label} htmlFor="demo-message">
        What are you trying to fix? <span className={styles.optional}>optional</span>
      </label>
      <textarea
        className={styles.textarea}
        id="demo-message"
        maxLength={2000}
        name="message"
        onChange={(event) => setMessage(event.target.value)}
        rows={3}
        value={message}
      />

      {error !== null && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}

      <button className={`btn btn-primary ${styles.submit}`} disabled={submitting} type="submit">
        {submitting ? 'Sending…' : 'Ask for a demo'}
      </button>
    </form>
  )
}
