import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, test } from 'vitest'
import { LandingPage } from './LandingPage'
import { renderRoute } from '../../test/renderRoute'

describe('LandingPage', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  test('is readable without a token', () => {
    // The point of the route. Before FZ-111, `/` redirected into the authenticated tree
    // and a signed-out visitor was sent to sign-in, so there was nowhere to send anyone
    // who had not already bought.
    renderRoute(<LandingPage />, { token: null })

    expect(
      screen.getByRole('heading', { name: /is the freeze on, and does it apply to me\?/i }),
    ).toBeInTheDocument()
  })

  test('every sign-in affordance points at sign-in', () => {
    // Two of them, deliberately: the nav button and the footer link. A visitor who has
    // read to the bottom should not have to scroll back up.
    renderRoute(<LandingPage />, { token: null })

    const links = screen.getAllByRole('link', { name: 'Sign in' })
    expect(links).toHaveLength(2)
    links.forEach((link) => expect(link).toHaveAttribute('href', '/signin'))
  })

  test('sends a signed-in reader to the dashboard rather than back through sign-in', () => {
    renderRoute(<LandingPage />, { token: 'a-token' })

    expect(screen.getByRole('link', { name: 'Open dashboard' })).toHaveAttribute(
      'href',
      '/dashboard',
    )
  })

  test('the pipeline sample names the published image, not a plugin', () => {
    // `1j` drew `uses: freezehub/freeze-check@v1`, which does not exist. A landing page's
    // code sample is the first thing a visitor copies, so this asserts the real one.
    renderRoute(<LandingPage />, { token: null })

    expect(screen.getByText(/ghcr\.io\/freezehubio\/freeze-check:v1/)).toBeInTheDocument()
  })

  test('offers the trial, and still leads with the demo', () => {
    // FZ-185 built /signup, so "start free trial" is now true and the claim is allowed.
    // Which of the two leads is a commercial decision, not a layout one: at this stage
    // the conversations are worth more than the volume, so the demo keeps the primary
    // button and the trial takes the secondary. Asserted on the classes because that IS
    // the decision -- swapping them is a deliberate act, not a tidy-up.
    renderRoute(<LandingPage />, { token: null })

    expect(screen.getByRole('link', { name: 'Book a demo' })).toHaveClass('btn-primary')

    const trial = screen.getByRole('link', { name: 'Start free trial' })
    expect(trial).toHaveAttribute('href', '/signup')
    expect(trial).toHaveClass('btn-secondary')
  })
})
