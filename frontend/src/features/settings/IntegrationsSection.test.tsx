import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { IntegrationsSection } from './IntegrationsSection'
import { renderRoute } from '../../test/renderRoute'

/** No destinations, and a spy that records what a create would have sent. */
function stubApi() {
  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    void input
    const json = (body: unknown, status = 200) =>
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      })
    if (init?.method === 'POST') {
      return Promise.resolve(json({ id: 1, type: 'SLACK', enabled: true, summary: 'hooks.slack.com' }, 201))
    }
    return Promise.resolve(json([]))
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

function postedConfig(spy: ReturnType<typeof stubApi>): unknown {
  const post = spy.mock.calls.find(([, init]) => init?.method === 'POST')
  if (!post) throw new Error('nothing was posted')
  return JSON.parse(JSON.parse(post[1]?.body as string).config)
}

describe('IntegrationsSection', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /** The whole point of FZ-189: the operator supplies a URL, not a JSON document. */
  test('asks for a URL and composes the JSON itself', async () => {
    const spy = stubApi()
    const user = userEvent.setup()
    renderRoute(<IntegrationsSection />)

    const field = await screen.findByLabelText(/incoming webhook url/i)
    await user.type(field, 'https://hooks.slack.com/services/T0/B0/xyz')
    await user.click(screen.getByRole('button', { name: /add destination/i }))

    await waitFor(() => expect(spy.mock.calls.some(([, i]) => i?.method === 'POST')).toBe(true))
    expect(postedConfig(spy)).toEqual({ webhookUrl: 'https://hooks.slack.com/services/T0/B0/xyz' })
  })

  test('nobody has to type a brace, in any channel', async () => {
    stubApi()
    const user = userEvent.setup()
    renderRoute(<IntegrationsSection />)

    await screen.findByLabelText(/incoming webhook url/i)
    for (const [channel, label] of [
      ['Email', /recipient addresses/i],
      ['Webhook', /endpoint url/i],
    ] as const) {
      await user.selectOptions(screen.getByLabelText(/add a destination/i), channel)
      expect(screen.getByLabelText(label)).toBeInTheDocument()
    }

    // No placeholder anywhere still shows JSON.
    document.querySelectorAll('input, textarea').forEach((element) => {
      expect(element.getAttribute('placeholder') ?? '').not.toContain('{')
    })
  })

  test('a list of recipients becomes an array, however it was separated', async () => {
    const spy = stubApi()
    const user = userEvent.setup()
    renderRoute(<IntegrationsSection />)

    await screen.findByLabelText(/incoming webhook url/i)
    await user.selectOptions(screen.getByLabelText(/add a destination/i), 'Email')
    await user.type(screen.getByLabelText(/recipient addresses/i), 'a@acme.test, b@acme.test,')
    await user.click(screen.getByRole('button', { name: /add destination/i }))

    await waitFor(() => expect(spy.mock.calls.some(([, i]) => i?.method === 'POST')).toBe(true))
    // The trailing comma does not become an empty recipient.
    expect(postedConfig(spy)).toEqual({ recipients: ['a@acme.test', 'b@acme.test'] })
  })

  test('says what is wrong at the field, and will not submit it', async () => {
    stubApi()
    const user = userEvent.setup()
    renderRoute(<IntegrationsSection />)

    await user.type(await screen.findByLabelText(/incoming webhook url/i), 'http://hooks.slack.com/x')

    expect(await screen.findByRole('alert')).toHaveTextContent(/must use https/i)
    expect(screen.getByRole('button', { name: /add destination/i })).toBeDisabled()
  })

  /**
   * `OI-23` names this as the trick that defeats a `startsWith("https://")` check. The
   * boundary is at connect time in the backend — this only saves the operator from
   * finding out via a failed delivery.
   */
  test('refuses a userinfo authority, in words that explain it', async () => {
    stubApi()
    const user = userEvent.setup()
    renderRoute(<IntegrationsSection />)

    await user.type(
      await screen.findByLabelText(/incoming webhook url/i),
      'https://hooks.slack.com@10.0.0.5/x',
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(/before the @/i)
  })

  test('changing channel does not carry the previous value across', async () => {
    stubApi()
    const user = userEvent.setup()
    renderRoute(<IntegrationsSection />)

    await user.type(await screen.findByLabelText(/incoming webhook url/i), 'https://hooks.slack.com/x')
    await user.selectOptions(screen.getByLabelText(/add a destination/i), 'Email')

    expect(screen.getByLabelText(/recipient addresses/i)).toHaveValue('')
  })
})
