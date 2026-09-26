import { screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { DemoRequestForm } from './DemoRequestForm'
import { renderRoute } from '../../test/renderRoute'
import { setupUser } from '../../test/user'

function stubApi(status = 202, body: unknown = { message: "Thanks — we'll be in touch shortly." }) {
  const spy = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
    Promise.resolve(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  )
  vi.stubGlobal('fetch', spy)
  return spy
}

async function fillAndSubmit(user: ReturnType<typeof setupUser>) {
  await user.type(screen.getByLabelText('Name'), 'Dana Okafor')
  await user.type(screen.getByLabelText('Work email'), 'dana@northwind.test')
  await user.type(screen.getByLabelText('Company'), 'Northwind')
  await user.click(screen.getByRole('button', { name: 'Ask for a demo' }))
}

describe('DemoRequestForm', () => {
  beforeEach(() => vi.unstubAllEnvs())
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
  })

  test('sends the request to the endpoint FZ-083 built and nothing called', async () => {
    const user = setupUser()
    const fetchSpy = stubApi()
    renderRoute(<DemoRequestForm />)

    await fillAndSubmit(user)

    expect(fetchSpy).toHaveBeenCalledTimes(1)
    const [url, init] = fetchSpy.mock.calls[0]
    expect(String(url)).toContain('/api/demo-requests')
    expect(init?.method).toBe('POST')

    const sent = JSON.parse(String(init?.body))
    expect(sent).toMatchObject({
      name: 'Dana Okafor',
      email: 'dana@northwind.test',
      company: 'Northwind',
      // Set by the module, not by the form: a value the form could set is a value a
      // visitor could set.
      source: 'landing',
    })
  })

  test('omits the optional fields rather than sending empty strings', async () => {
    // The backend caps their length and trims them to null; sending "" would store a row
    // whose teamSize is present and meaningless.
    const user = setupUser()
    const fetchSpy = stubApi()
    renderRoute(<DemoRequestForm />)

    await fillAndSubmit(user)

    const sent = JSON.parse(String(fetchSpy.mock.calls[0][1]?.body))
    expect(sent.teamSize).toBeUndefined()
    expect(sent.message).toBeUndefined()
  })

  test('shows what the backend said, rather than a message of its own', async () => {
    const user = setupUser()
    stubApi(202, { message: 'Thanks — we will be in touch shortly.' })
    renderRoute(<DemoRequestForm />)

    await fillAndSubmit(user)

    expect(await screen.findByText(/we will be in touch shortly/i)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Request received' })).toBeInTheDocument()
  })

  test('offers a time when a scheduler is configured', async () => {
    vi.stubEnv('VITE_DEMO_BOOKING_URL', 'https://calendar.example.com/freezehub')
    const user = setupUser()
    stubApi()
    renderRoute(<DemoRequestForm />)

    await fillAndSubmit(user)

    const link = await screen.findByRole('link', { name: /pick a time/i })
    expect(link).toHaveAttribute('href', 'https://calendar.example.com/freezehub')
    // Off-site, so the opener must not be reachable from it.
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))
  })

  test('offers NO time when no scheduler is configured', async () => {
    // The whole reason the variable is read rather than hard-coded. A "Pick a time" button
    // that goes nowhere is worse than none: it reads as an offer, and whoever clicks it has
    // already decided to meet.
    vi.stubEnv('VITE_DEMO_BOOKING_URL', '')
    const user = setupUser()
    stubApi()
    renderRoute(<DemoRequestForm />)

    await fillAndSubmit(user)

    expect(await screen.findByRole('heading', { name: 'Request received' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /pick a time/i })).not.toBeInTheDocument()
  })

  test('treats a whitespace-only booking url as unset', async () => {
    vi.stubEnv('VITE_DEMO_BOOKING_URL', '   ')
    const user = setupUser()
    stubApi()
    renderRoute(<DemoRequestForm />)

    await fillAndSubmit(user)

    expect(await screen.findByRole('heading', { name: 'Request received' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /pick a time/i })).not.toBeInTheDocument()
  })

  test('surfaces a rate limit as something the visitor can act on', async () => {
    const user = setupUser()
    stubApi(429, { message: 'Too many requests' })
    renderRoute(<DemoRequestForm />)

    await fillAndSubmit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent(/give it a minute/i)
  })

  test('keeps what was typed when the request fails', async () => {
    // Re-typing a message after a failed submit is how a lead is lost.
    const user = setupUser()
    stubApi(500, { message: 'Boom' })
    renderRoute(<DemoRequestForm />)

    await fillAndSubmit(user)

    await screen.findByRole('alert')
    expect(screen.getByLabelText('Company')).toHaveValue('Northwind')
    expect(screen.getByRole('button', { name: 'Ask for a demo' })).toBeEnabled()
  })
})
