import { describe, expect, test, vi } from 'vitest'
import { ApiError, apiRequest } from './client'

/** How a failed response becomes something a person can act on (FZ-061). */
describe('apiRequest error handling', () => {
  function respondWith(status: number, body: unknown, contentType = 'application/problem+json') {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(typeof body === 'string' ? body : JSON.stringify(body), {
            status,
            headers: { 'Content-Type': contentType },
          }),
        ),
      ),
    )
  }

  async function failureFrom(): Promise<ApiError> {
    try {
      await apiRequest('/api/teams')
      throw new Error('expected the request to fail')
    } catch (caught) {
      return caught as ApiError
    }
  }

  test('uses the reason the backend stated', async () => {
    respondWith(409, { status: 409, title: 'Conflict', detail: 'A team with this name already exists' })

    const error = await failureFrom()

    expect(error.status).toBe(409)
    expect(error.isConflict).toBe(true)
    expect(error.message).toBe('A team with this name already exists')
  })

  test('spells out which fields were rejected', async () => {
    // "The request has 2 invalid fields" tells someone staring at a form nothing about
    // which two.
    respondWith(400, {
      status: 400,
      detail: 'The request has 2 invalid fields.',
      errors: [
        { field: 'name', message: 'must not be blank' },
        { field: 'reason', message: 'must not be blank' },
      ],
    })

    const error = await failureFrom()

    expect(error.message).toBe('name must not be blank, reason must not be blank')
    expect(error.fieldErrors).toHaveLength(2)
    expect(error.fieldErrors[0]).toEqual({ field: 'name', message: 'must not be blank' })
  })

  test('falls back to the status when the response has no body', async () => {
    // A 401 from the security chain is exactly this: no body at all.
    respondWith(401, '')

    const error = await failureFrom()

    expect(error.isUnauthorized).toBe(true)
    expect(error.message).toBe('Request failed (401)')
  })

  test('survives a body that is not the shape it should be', async () => {
    // Anything served by a proxy in front of the API is out of the backend's hands.
    respondWith(502, '<html>Bad Gateway</html>', 'text/html')

    const error = await failureFrom()

    expect(error.status).toBe(502)
    expect(error.message).toContain('Bad Gateway')
  })

  test('does not present an empty message when the body carries none', async () => {
    respondWith(500, { status: 500, detail: '   ' })

    const error = await failureFrom()

    expect(error.message).toBe('Request failed (500)')
  })
})

describe('plan limits', () => {
  test('a 402 reads as the limit it hit, not as a generic failure', async () => {
    // The acceptance criterion FZ-085 exists for: "10 of 10 applications used", with the
    // way out, rather than "Request failed (402)".
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              status: 402,
              detail: 'The STARTER plan allows 10 applications; this organization has 10.',
              plan: 'STARTER',
              resource: 'applications',
              limit: 10,
              current: 10,
            }),
            { status: 402, headers: { 'Content-Type': 'application/problem+json' } },
          ),
        ),
      ),
    )

    const caught = await apiRequest('/api/applications', { method: 'POST', token: 't' }).catch(
      (error: unknown) => error,
    )

    expect(caught).toBeInstanceOf(ApiError)
    const error = caught as ApiError
    expect(error.isPlanLimit).toBe(true)
    expect(error.planLimit).toEqual({
      plan: 'STARTER',
      resource: 'applications',
      limit: 10,
      current: 10,
    })
    expect(error.message).toContain('STARTER')
    expect(error.message).toContain('10 applications')
    expect(error.message).toContain('Settings')
  })

  test('a 402 without the extensions still says something useful', async () => {
    // A 402 from anywhere but our own handler has no numbers to render, and a usage
    // figure invented from a missing field would be a confident lie.
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ detail: 'Payment required' }), {
            status: 402,
            headers: { 'Content-Type': 'application/problem+json' },
          }),
        ),
      ),
    )

    const caught = await apiRequest('/api/applications', { method: 'POST', token: 't' }).catch(
      (error: unknown) => error,
    )

    const error = caught as ApiError
    expect(error.planLimit).toBeNull()
    expect(error.message).toBe('Payment required')
  })
})

/**
 * A request that is accepted and never answered (`FZ-124`).
 *
 * Fake timers throughout: the point is what happens after twenty seconds, and a test that
 * actually waits twenty seconds is a test nobody runs.
 */
describe('a server that does not answer', () => {
  /** Accepts the request and never settles — the wedged-task case, not the unreachable one. */
  function neverAnswers() {
    const spy = vi.fn(
      (_input: RequestInfo | URL, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () =>
            // What fetch does when its signal fires, and the only thing the wrapper sees.
            reject(new DOMException('The operation was aborted.', 'AbortError')),
          )
        }),
    )
    vi.stubGlobal('fetch', spy)
    return spy
  }

  test('gives up and says so, rather than waiting for ever', async () => {
    vi.useFakeTimers()
    neverAnswers()
    try {
      const pending = apiRequest('/api/restrictions').catch((error: unknown) => error)

      await vi.advanceTimersByTimeAsync(19_000)
      // Still waiting at nineteen seconds: the backstop must not fire on a merely slow API.
      expect(await Promise.race([pending, Promise.resolve('still pending')])).toBe('still pending')

      await vi.advanceTimersByTimeAsync(2_000)

      const error = (await pending) as ApiError
      expect(error).toBeInstanceOf(ApiError)
      expect(error.isTimeout).toBe(true)
      expect(error.message).toMatch(/did not answer within 20 seconds/i)
    } finally {
      vi.useRealTimers()
      vi.unstubAllGlobals()
    }
  })

  test('a cancelled request stays a cancellation, not a failure', async () => {
    /*
     * The distinction the story turned on. TanStack Query aborts on unmount and on
     * refetch; both arrive as the same AbortError as a timeout. Reporting those as errors
     * would flash a failure banner every time somebody navigates away, so the caller's
     * abort has to come back untouched.
     */
    vi.useFakeTimers()
    neverAnswers()
    try {
      const controller = new AbortController()
      const pending = apiRequest('/api/restrictions', { signal: controller.signal }).catch(
        (error: unknown) => error,
      )

      controller.abort()

      const error = await pending
      expect(error).not.toBeInstanceOf(ApiError)
      expect((error as DOMException).name).toBe('AbortError')
    } finally {
      vi.useRealTimers()
      vi.unstubAllGlobals()
    }
  })

  test('honours a longer deadline when a caller asks for one', async () => {
    vi.useFakeTimers()
    neverAnswers()
    try {
      const pending = apiRequest('/api/restrictions', { timeoutMs: 60_000 }).catch(
        (error: unknown) => error,
      )

      await vi.advanceTimersByTimeAsync(21_000)
      expect(await Promise.race([pending, Promise.resolve('still pending')])).toBe('still pending')

      await vi.advanceTimersByTimeAsync(40_000)
      expect(((await pending) as ApiError).isTimeout).toBe(true)
      expect(((await pending) as ApiError).message).toMatch(/within 60 seconds/i)
    } finally {
      vi.useRealTimers()
      vi.unstubAllGlobals()
    }
  })

  test('an answer that arrives in time is not touched', async () => {
    // The timer must not leave the request hanging, or every successful call would sit
    // waiting for a timeout that has already been cleared.
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify([{ id: 1, name: 'Payments' }]), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        ),
      ),
    )
    try {
      await expect(apiRequest('/api/teams')).resolves.toEqual([{ id: 1, name: 'Payments' }])
    } finally {
      vi.unstubAllGlobals()
    }
  })
})

describe('plan capabilities', () => {
  test('a capability refusal carries the upgrade path, like a limit does', async () => {
    // FZ-143 sends `plan` and `feature` and deliberately no numbers -- a capability is not
    // a count. Before FZ-146 that fell past planLimitFrom, which requires `limit`, so the
    // one 402 a free organization actually meets was the only one with no way out on it.
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              status: 402,
              detail: 'The FREE plan does not include blocking freezes.',
              plan: 'FREE',
              feature: 'blocking freezes',
            }),
            { status: 402, headers: { 'Content-Type': 'application/problem+json' } },
          ),
        ),
      ),
    )

    const caught = await apiRequest('/api/restrictions', { method: 'POST', token: 't' }).catch(
      (error: unknown) => error,
    )

    const error = caught as ApiError
    expect(error.isPlanLimit).toBe(true)
    expect(error.message).toContain('FREE')
    expect(error.message).toContain('blocking freezes')
    expect(error.message).toContain('Settings')
  })

  test('a capability refusal is not reported as a usage figure', async () => {
    // There is no count, so nothing may render a usage bar for it. Inventing a zero would
    // read as "0 of 0 used", which is true and tells the reader nothing.
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              status: 402,
              detail: 'The FREE plan does not include blocking freezes.',
              plan: 'FREE',
              feature: 'blocking freezes',
            }),
            { status: 402, headers: { 'Content-Type': 'application/problem+json' } },
          ),
        ),
      ),
    )

    const caught = await apiRequest('/api/restrictions', { method: 'POST', token: 't' }).catch(
      (error: unknown) => error,
    )

    expect((caught as ApiError).planLimit).toBeNull()
  })
})
