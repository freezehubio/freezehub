import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { SignUpPage } from './SignUpPage'
import { renderRoute } from '../../test/renderRoute'

function stubSignup(status = 202, body: unknown = { message: 'Check your email.' }) {
  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    void input
    void init
    return Promise.resolve(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

async function fillAndSubmit(company = 'Northwind', email = 'founder@northwind.test') {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText(/company/i), company)
  await user.type(screen.getByLabelText(/work email/i), email)
  await user.click(screen.getByRole('button', { name: /start free trial/i }))
}

describe('SignUpPage', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('is reachable without a token', () => {
    // The whole point: the person filling it in has no account yet.
    renderRoute(<SignUpPage />, { path: '/signup', token: null })

    expect(screen.getByRole('heading', { name: /start a free trial/i })).toBeInTheDocument()
  })

  test('posts the company and email, and sends no Authorization header', async () => {
    const spy = stubSignup()
    renderRoute(<SignUpPage />, { path: '/signup', token: null })

    await fillAndSubmit()

    await waitFor(() => expect(spy).toHaveBeenCalled())
    const [url, init] = spy.mock.calls[0]
    expect(String(url)).toContain('/api/signup')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(init?.body as string)).toEqual({
      company: 'Northwind',
      email: 'founder@northwind.test',
    })
    // Defaulted, not optional-chained (`FZ-193`). `init?.headers?.Authorization` is also
    // undefined when there were no headers at all, so it would pass for the wrong reason.
    // An empty object asserts that the header is absent, not that the request was.
    const headers = (init?.headers ?? {}) as Record<string, string>
    expect(headers.Authorization).toBeUndefined()
  })

  test('acknowledges without claiming an organization was created', async () => {
    stubSignup()
    renderRoute(<SignUpPage />, { path: '/signup', token: null })

    await fillAndSubmit()

    expect(await screen.findByRole('heading', { name: /check your email/i })).toBeInTheDocument()
  })

  /**
   * The security property of this page, asserted rather than assumed.
   *
   * The backend answers an address already in use with exactly the 202 it answers a new
   * one with, so the screen cannot distinguish them — and must not appear to. If this ever
   * renders something different for a duplicate, the page has become a
   * customer-enumeration oracle: try a company's domain, read the difference.
   */
  test('a duplicate address is indistinguishable from a new one', async () => {
    stubSignup()
    renderRoute(<SignUpPage />, { path: '/signup', token: null })
    await fillAndSubmit('Somebody Else Ltd', 'taken@northwind.test')
    const duplicate = (await screen.findByRole('heading', { name: /check your email/i }))
      .parentElement?.textContent

    vi.unstubAllGlobals()
    sessionStorage.clear()
    stubSignup()
    renderRoute(<SignUpPage />, { path: '/signup', token: null })
    await fillAndSubmit('Somebody Else Ltd', 'taken@northwind.test')
    const fresh = (await screen.findAllByRole('heading', { name: /check your email/i }))
      .at(-1)?.parentElement?.textContent

    expect(fresh).toEqual(duplicate)
  })

  test('says what to do when the network refuses the attempt', async () => {
    // Unauthenticated and rate limited per source address (FZ-087). "Try again in a
    // minute" is actionable; a generic failure is not.
    stubSignup(429, { detail: 'Too many requests' })
    renderRoute(<SignUpPage />, { path: '/signup', token: null })

    await fillAndSubmit()

    expect(await screen.findByRole('alert')).toHaveTextContent(/wait a minute/i)
  })

  test('shows the field the backend rejected', async () => {
    stubSignup(400, {
      detail: 'Validation failed',
      errors: [{ field: 'email', message: 'must be a well-formed email address' }],
    })
    renderRoute(<SignUpPage />, { path: '/signup', token: null })

    await fillAndSubmit()

    expect(await screen.findByRole('alert')).toHaveTextContent(/well-formed email/i)
  })

  test('offers sign-in to somebody who already has an account', async () => {
    // How the duplicate case gets where it was going, without being told that is where
    // it is.
    renderRoute(<SignUpPage />, { path: '/signup', token: null })

    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/signin')
  })
})
