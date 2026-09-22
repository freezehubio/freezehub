import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { signUp } from '../../api/signup'
import styles from './SignUpPage.module.css'

/**
 * Start a free trial — the form for `POST /api/signup` (`FZ-185`, endpoint `FZ-082`).
 *
 * <b>Both doors stay open.</b> The landing page still leads with "Book a demo"; this is
 * the secondary path. At this stage of the product the conversations are worth more than
 * the volume, so self-serve exists without being promoted over talking to us. Promoting it
 * later is a one-line change to the hero.
 *
 * <b>The acknowledgement is the same for every outcome, and that is the feature.</b> An
 * address already in use produces this screen too. It must: telling the visitor "that
 * email is already registered" would turn the page into a customer-enumeration oracle —
 * try a company's domain, read the difference, learn whether they use FreezeHub. The copy
 * is therefore written to be true either way, and says nothing that depends on which
 * happened. The "already have an account" link is how somebody in the duplicate case gets
 * where they were going, without being told that is where they are.
 *
 * <b>No password field.</b> Cognito emails a temporary one and requires a change at first
 * sign-in, so there is nothing here to collect, store or get wrong.
 */
export function SignUpPage() {
  const [company, setCompany] = useState('')
  const [email, setEmail] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)

    try {
      await signUp(company.trim(), email.trim())
      setSubmitted(true)
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 429) {
        // The endpoint is unauthenticated and rate limited per source address (FZ-087).
        // Worth its own message: "try again" is actionable, a generic failure is not.
        setError('Too many attempts from this network. Wait a minute and try again.')
      } else if (caught instanceof ApiError && caught.fieldErrors.length > 0) {
        setError(caught.fieldErrors.map((field) => field.message).join(' '))
      } else if (caught instanceof ApiError) {
        setError(caught.message)
      } else {
        setError('Could not reach FreezeHub. Check your connection and try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (submitted) {
    return (
      <main className={styles.page}>
        <div className={styles.card}>
          <h1 className={styles.title}>Check your email</h1>
          <p className={styles.hint}>
            We have sent a temporary password to <span className="mono">{email.trim()}</span>.
            Sign in with it and you will be asked to choose your own.
          </p>
          <Link className="btn btn-primary btn-block" to="/signin">
            Go to sign in
          </Link>
          <p className={styles.note}>
            Nothing arrived? Check spam first. If this address already has a FreezeHub
            account, sign in with that instead — we will not have sent a new password.
          </p>
        </div>
      </main>
    )
  }

  return (
    <main className={styles.page}>
      <form className={styles.card} onSubmit={handleSubmit}>
        <h1 className={styles.title}>Start a free trial</h1>
        <p className={styles.hint}>
          Fourteen days, every feature, no card. We will email you a temporary password.
        </p>

        <label className={styles.label} htmlFor="company">
          Company
        </label>
        <input
          id="company"
          className={styles.input}
          type="text"
          value={company}
          onChange={(event) => setCompany(event.target.value)}
          placeholder="Northwind"
          maxLength={255}
          required
        />

        <label className={styles.label} htmlFor="email">
          Work email
        </label>
        <input
          id="email"
          className={styles.input}
          type="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          placeholder="you@northwind.com"
          maxLength={320}
          required
        />

        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}

        <button
          className={`btn btn-primary btn-block ${styles.button}`}
          type="submit"
          disabled={submitting || !company.trim() || !email.trim()}
        >
          {submitting ? 'Creating your organization…' : 'Start free trial'}
        </button>

        <p className={styles.note}>
          You will be the administrator, and can invite your team once you are in. Already
          have an account? <Link to="/signin">Sign in</Link>. Would rather talk to us first?{' '}
          <Link to="/#demo">Book a demo</Link>.
        </p>
      </form>
    </main>
  )
}
