import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'

/**
 * FZ-179. The interesting cases are the refusals: a code accepted without checking `state`
 * is a code somebody else's authorization can deliver, and an ID token stored in place of
 * an access token is a 401 that looks like a broken sign-in rather than a wrong token.
 *
 * Configuration is read at call time, so stubbing the environment is enough — no
 * vi.resetModules(). An earlier version of this file reset the module registry, which is
 * worker-wide and made three tests fail in a file it had nothing to do with.
 */
async function load(env: Record<string, string>) {
  vi.stubEnv('VITE_COGNITO_DOMAIN', env.domain ?? '')
  vi.stubEnv('VITE_COGNITO_CLIENT_ID', env.clientId ?? '')
  return import('./cognito')
}

const CONFIGURED = { domain: 'pool.auth.us-east-2.amazoncognito.com', clientId: 'abc123' }

describe('isCognitoConfigured', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    sessionStorage.clear()
  })

  it('is false with no configuration, so local development keeps the dev form', async () => {
    const { isCognitoConfigured } = await load({})
    expect(isCognitoConfigured()).toBe(false)
  })

  it('needs both the domain and the client id, not either', async () => {
    const { isCognitoConfigured } = await load({ domain: CONFIGURED.domain })
    expect(isCognitoConfigured()).toBe(false)
  })

  it('is true once both are present', async () => {
    const { isCognitoConfigured } = await load(CONFIGURED)
    expect(isCognitoConfigured()).toBe(true)
  })
})

describe('completeSignIn', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  /** What beginSignIn would have left behind. */
  function primeSession(state = 'the-state') {
    sessionStorage.setItem('freezehub.pkce.verifier', 'the-verifier')
    sessionStorage.setItem('freezehub.pkce.state', state)
    sessionStorage.setItem('freezehub.pkce.returnTo', '/restrictions')
  }

  it('refuses a code whose state does not match the one it issued', async () => {
    const { completeSignIn } = await load(CONFIGURED)
    primeSession('the-state')

    await expect(
      completeSignIn(new URLSearchParams({ code: 'c', state: 'somebody-elses' })),
    ).rejects.toThrow(/could not be verified/i)
  })

  it('refuses a code when no sign-in was started in this session', async () => {
    const { completeSignIn } = await load(CONFIGURED)

    await expect(
      completeSignIn(new URLSearchParams({ code: 'c', state: 'anything' })),
    ).rejects.toThrow(/could not be verified/i)
  })

  it('surfaces an error the Hosted UI sent back instead of a code', async () => {
    const { completeSignIn } = await load(CONFIGURED)
    primeSession()

    await expect(
      completeSignIn(
        new URLSearchParams({ error: 'access_denied', error_description: 'User cancelled' }),
      ),
    ).rejects.toThrow('User cancelled')
  })

  it('keeps the access token, not the id token', async () => {
    const { completeSignIn } = await load(CONFIGURED)
    primeSession()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ access_token: 'the-access-token', id_token: 'the-id-token' }),
      }),
    )

    const result = await completeSignIn(new URLSearchParams({ code: 'c', state: 'the-state' }))

    expect(result.accessToken).toBe('the-access-token')
    expect(result.returnTo).toBe('/restrictions')
  })

  it('sends the verifier, so the exchange is bound to this browser', async () => {
    const { completeSignIn } = await load(CONFIGURED)
    primeSession()
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ access_token: 't' }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await completeSignIn(new URLSearchParams({ code: 'the-code', state: 'the-state' }))

    const body = String(fetchMock.mock.calls[0][1].body)
    expect(body).toContain('code_verifier=the-verifier')
    expect(body).toContain('grant_type=authorization_code')
  })

  it('clears the verifier so a code cannot be replayed', async () => {
    const { completeSignIn } = await load(CONFIGURED)
    primeSession()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: async () => ({ access_token: 't' }) }),
    )

    await completeSignIn(new URLSearchParams({ code: 'c', state: 'the-state' }))

    expect(sessionStorage.getItem('freezehub.pkce.verifier')).toBeNull()
    expect(sessionStorage.getItem('freezehub.pkce.state')).toBeNull()
  })

  it('fails loudly when Cognito returns no access token', async () => {
    const { completeSignIn } = await load(CONFIGURED)
    primeSession()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id_token: 'only-an-id' }) }),
    )

    await expect(
      completeSignIn(new URLSearchParams({ code: 'c', state: 'the-state' })),
    ).rejects.toThrow(/no access token/i)
  })

  it('reports a refused exchange rather than storing nothing silently', async () => {
    const { completeSignIn } = await load(CONFIGURED)
    primeSession()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400 }))

    await expect(
      completeSignIn(new URLSearchParams({ code: 'c', state: 'the-state' })),
    ).rejects.toThrow(/400/)
  })
})
