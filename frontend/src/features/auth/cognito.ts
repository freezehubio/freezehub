/**
 * The Cognito Hosted UI sign-in the product actually uses (FZ-179).
 *
 * `FZ-035` built a development-only sign-in and said it would be "replaced by a redirect
 * to the Cognito Hosted UI at FZ-063". FZ-063 created the user pool and never touched the
 * frontend, so the replacement was never written — a deployed FreezeHub could create
 * identities, validate tokens, and offer nobody any way to obtain one. This is it.
 *
 * <b>Authorization code with PKCE</b>, because the app client is public
 * (`generate_secret = false`): there is no secret a browser could keep, so the code is
 * bound to a verifier this browser generated instead.
 *
 * <b>The access token is what is kept.</b> Cognito returns an ID token alongside it and
 * they are interchangeable to anything that only checks a signature — which is exactly
 * what `FZ-128` refuses. `token_use` must be `access`, so storing the ID token would
 * produce a 401 that looks like a broken sign-in rather than a wrong token.
 */

/**
 * Read at call time rather than at import time. Both are fixed for the life of a build,
 * so a constant would be equivalent in production — but a module-level constant can only
 * be re-read by resetting the module registry, and doing that in one test file disturbs
 * every other file sharing the worker. Cheap here, and it keeps the tests to stubbing
 * environment variables.
 *
 * Empty in local development, where LocalJwtConfig and /api/dev/token stand in.
 */
function domain(): string {
  return import.meta.env.VITE_COGNITO_DOMAIN ?? ''
}

function clientId(): string {
  return import.meta.env.VITE_COGNITO_CLIENT_ID ?? ''
}

const VERIFIER_KEY = 'freezehub.pkce.verifier'
const STATE_KEY = 'freezehub.pkce.state'
const RETURN_KEY = 'freezehub.pkce.returnTo'

/**
 * Whether this build talks to Cognito at all.
 *
 * The local profile has no pool, so the development form stays. Deciding by configuration
 * rather than by a mode flag means there is no way to run the development sign-in against
 * a deployed backend by mistake — the endpoint it posts to would not exist anyway.
 */
export function isCognitoConfigured(): boolean {
  return domain() !== '' && clientId() !== ''
}

/**
 * Derived, never configured. It has to match the app client's callback URL exactly, and a
 * value that can disagree with the browser's own origin is a value that will.
 */
function redirectUri(): string {
  return `${window.location.origin}/signin`
}

function randomUrlSafe(bytes: number): string {
  const raw = new Uint8Array(bytes)
  crypto.getRandomValues(raw)
  return base64Url(raw)
}

function base64Url(bytes: Uint8Array): string {
  let binary = ''
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte)
  })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

async function challengeFor(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return base64Url(new Uint8Array(digest))
}

/**
 * Sends the browser to the Hosted UI. Never returns — the page navigates away.
 *
 * `state` is a CSRF guard, not decoration: without it a code from somebody else's
 * authorization could be delivered to this callback and exchanged.
 */
export async function beginSignIn(returnTo: string): Promise<void> {
  const verifier = randomUrlSafe(32)
  const state = randomUrlSafe(16)

  sessionStorage.setItem(VERIFIER_KEY, verifier)
  sessionStorage.setItem(STATE_KEY, state)
  sessionStorage.setItem(RETURN_KEY, returnTo)

  const query = new URLSearchParams({
    client_id: clientId(),
    response_type: 'code',
    scope: 'openid email profile',
    redirect_uri: redirectUri(),
    state,
    code_challenge: await challengeFor(verifier),
    code_challenge_method: 'S256',
  })

  window.location.assign(`https://${domain()}/oauth2/authorize?${query}`)
}

export interface CompletedSignIn {
  accessToken: string
  returnTo: string
}

/**
 * Exchanges the `?code=` the Hosted UI came back with. Throws with a message a person can
 * act on, because every failure here looks identical from the user's side — a sign-in that
 * did not work.
 */
export async function completeSignIn(params: URLSearchParams): Promise<CompletedSignIn> {
  const error = params.get('error')
  if (error !== null) {
    throw new Error(params.get('error_description') ?? error)
  }

  const code = params.get('code')
  if (code === null) {
    throw new Error('The sign-in did not return an authorization code.')
  }

  const expectedState = sessionStorage.getItem(STATE_KEY)
  if (expectedState === null || params.get('state') !== expectedState) {
    throw new Error('The sign-in could not be verified. Start again from the sign-in page.')
  }

  const verifier = sessionStorage.getItem(VERIFIER_KEY)
  if (verifier === null) {
    throw new Error('This sign-in was started in another tab or session. Start again.')
  }

  const response = await fetch(`https://${domain()}/oauth2/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: clientId(),
      code,
      redirect_uri: redirectUri(),
      code_verifier: verifier,
    }),
  })

  const returnTo = sessionStorage.getItem(RETURN_KEY) ?? '/dashboard'
  sessionStorage.removeItem(VERIFIER_KEY)
  sessionStorage.removeItem(STATE_KEY)
  sessionStorage.removeItem(RETURN_KEY)

  if (!response.ok) {
    throw new Error(`Cognito refused the authorization code (${response.status}).`)
  }

  const body: unknown = await response.json()
  const accessToken =
    typeof body === 'object' && body !== null && 'access_token' in body
      ? String((body as { access_token: unknown }).access_token)
      : ''

  if (accessToken === '') {
    throw new Error('Cognito returned no access token.')
  }

  return { accessToken, returnTo }
}

/**
 * Ends the Cognito session as well as this one. Without it, "sign out" clears the local
 * token and the next sign-in is silently accepted by a session the user thinks they ended
 * — which is worse than not offering sign-out.
 */
export function signOutUrl(): string {
  const query = new URLSearchParams({
    client_id: clientId(),
    logout_uri: redirectUri(),
  })
  return `https://${domain()}/logout?${query}`
}
