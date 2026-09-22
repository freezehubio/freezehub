import { useEffect, useState, type FormEvent } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import { apiRequest, ApiError } from '../../api/client'
import type { DevSignInResponse } from '../../types/api'
import { useAuth } from './authContext'
import { beginSignIn, completeSignIn, isCognitoConfigured } from './cognito'
import { Wordmark } from '../../components/Wordmark'
import styles from './SignInPage.module.css'

/**
 * Sign-in, by whichever route this build has (FZ-179).
 *
 * <b>Deployed:</b> a redirect to the Cognito Hosted UI, returning here with `?code=` to be
 * exchanged for an access token. <b>Local:</b> the development form, posting an email to
 * `/api/dev/token`, which exists only under the backend's `local` profile.
 *
 * Which one is decided by configuration rather than a mode flag — see
 * {@link isCognitoConfigured}. `FZ-035` promised this replacement "at FZ-063"; FZ-063 built
 * the user pool and never came back, so for a while the product could create identities and
 * offer nobody any way to obtain one.
 */
export function SignInPage() {
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const { signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()

  const returnTo = (location.state as { from?: string } | null)?.from ?? '/dashboard'
  const cognito = isCognitoConfigured()
  const returningFromCognito = searchParams.has('code') || searchParams.has('error')

  // Derived rather than set in the effect below: arriving with a `?code=` IS the
  // exchanging state, so storing it separately gives two sources of truth for one fact
  // and an extra render to keep them agreeing.
  const exchanging = cognito && returningFromCognito && error === null

  // The callback leg, on arrival back from the Hosted UI.
  useEffect(() => {
    if (!cognito || !returningFromCognito) return

    let cancelled = false

    completeSignIn(searchParams)
      .then(({ accessToken, returnTo: target }) => {
        if (cancelled) return
        signIn(accessToken)
        navigate(target, { replace: true })
      })
      .catch((caught: unknown) => {
        if (cancelled) return
        setError(caught instanceof Error ? caught.message : 'Sign-in failed.')
      })

    return () => {
      cancelled = true
    }
  }, [cognito, returningFromCognito, searchParams, signIn, navigate])

  async function handleCognito() {
    setError(null)
    setSubmitting(true)
    try {
      await beginSignIn(returnTo)
    } catch {
      setError('Could not start sign-in.')
      setSubmitting(false)
    }
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)

    try {
      const response = await apiRequest<DevSignInResponse>('/api/dev/token', {
        method: 'POST',
        body: { email },
      })
      signIn(response.token)
      navigate(returnTo, { replace: true })
    } catch (caught) {
      if (caught instanceof ApiError && caught.isNotFound) {
        setError('No user with that email. Create one before signing in.')
      } else if (caught instanceof ApiError) {
        setError(caught.message)
      } else {
        setError('Could not reach the API. Is the backend running?')
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (cognito) {
    return (
      <main className={styles.page}>
        <div className={styles.card}>
          <h1 className={styles.title}><Wordmark /></h1>

          {exchanging ? (
            <p className={styles.hint}>Completing sign-in…</p>
          ) : (
            <p className={styles.hint}>Sign in to manage deployment freezes.</p>
          )}

          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}

          <button
            className={`btn btn-primary btn-block ${styles.button}`}
            type="button"
            onClick={handleCognito}
            disabled={submitting || exchanging}
          >
            {submitting || exchanging ? 'Signing in…' : 'Continue'}
          </button>

          <p className={styles.note}>
            You will be taken to a secure sign-in page. A first-time user is asked to change
            the temporary password they were emailed.
          </p>
        </div>
      </main>
    )
  }

  return (
    <main className={styles.page}>
      <form className={styles.card} onSubmit={handleSubmit}>
        <h1 className={styles.title}><Wordmark /></h1>
        <p className={styles.hint}>
          Development sign-in. A deployed build redirects to Cognito instead.
        </p>

        <label className={styles.label} htmlFor="email">
          Email
        </label>
        <input
          id="email"
          className={styles.input}
          type="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          placeholder="dev@acme.test"
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
          disabled={submitting || !email}
        >
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>

        <p className={styles.note} id="signin-note">
          The development endpoint exists only under the backend&apos;s{' '}
          <span className="mono">local</span> profile, so this screen cannot sign anyone in
          to a deployed environment.
        </p>
      </form>
    </main>
  )
}
