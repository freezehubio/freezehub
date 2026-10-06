import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderRoute } from '../../test/renderRoute'
import { LegalPage } from './LegalPage'
import { LEGAL_DOCUMENTS, LANGUAGES } from './documents'

const at = (path: string) => renderRoute(<LegalPage />, { path, route: '/legal/:slug' })

describe('legal pages', () => {
  it('serves the Política in Spanish at the URL every outreach email points at', async () => {
    at('/legal/privacy-policy')

    expect(
      await screen.findByRole('heading', { name: /Política de Tratamiento de Datos Personales/i }),
    ).toBeInTheDocument()
  })

  it('serves the English text when the link says so, which eleven of nineteen will', async () => {
    at('/legal/privacy-policy?lang=en')

    expect(
      await screen.findByRole('heading', { name: /Personal Data Processing Policy/i }),
    ).toBeInTheDocument()
  })

  it('defaults to Spanish rather than guessing from the browser', async () => {
    // The Spanish text is the authoritative one; a reader should land on it unless a link
    // or a click says otherwise.
    at('/legal/privacy-policy?lang=fr')

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(/Política/i)
  })

  it('switches language and puts the choice in the URL so it can be linked and reloaded', async () => {
    const { router } = at('/legal/privacy-policy')
    await screen.findByRole('heading', { level: 1 })

    await userEvent.click(screen.getByRole('button', { name: 'English' }))

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(/Personal Data/i)
    expect(router.state.location.search).toBe('?lang=en')

    await userEvent.click(screen.getByRole('button', { name: 'Español' }))
    expect(router.state.location.search).toBe('')
  })

  it('says which language is being read, for a screen reader too', async () => {
    at('/legal/privacy-policy?lang=en')
    await screen.findByRole('heading', { level: 1 })

    expect(screen.getByRole('button', { name: 'English' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Español' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('serves the Aviso de Privacidad', async () => {
    at('/legal/privacy-notice')

    expect(await screen.findByRole('heading', { name: /Aviso de Privacidad/i })).toBeInTheDocument()
  })

  it('renders the markdown rather than showing its source', async () => {
    at('/legal/privacy-policy')
    await screen.findByRole('heading', { level: 1 })

    expect(screen.getAllByRole('table').length).toBeGreaterThan(0)
    expect(document.body.textContent).not.toContain('|---|')
    expect(document.body.textContent).not.toContain('**Responsable')
  })

  it('reaches a reader who is not signed in', async () => {
    renderRoute(<LegalPage />, { path: '/legal/privacy-policy', route: '/legal/:slug', token: null })

    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('shows a 404 for an unknown document instead of an empty page', async () => {
    at('/legal/no-such-document')

    expect(await screen.findByRole('heading', { name: /page not found/i })).toBeInTheDocument()
  })

  /*
   * The ones about what must NOT be published.
   *
   * `legal/` also holds the DPA, which carries the operator's NIT — and for a persona natural
   * the NIT is their cédula (`FZ-223`). The allowlist in `documents.ts` is the only thing
   * keeping it off the internet, and an allowlist is one careless `import.meta.glob` away
   * from being a glob.
   */
  it('publishes exactly two documents, and neither is the DPA', () => {
    expect(LEGAL_DOCUMENTS.map((d) => d.slug).sort()).toEqual(['privacy-notice', 'privacy-policy'])

    for (const doc of LEGAL_DOCUMENTS) {
      for (const lang of LANGUAGES) {
        expect(doc.markdown[lang]).not.toContain('Data Processing Agreement')
        expect(doc.markdown[lang]).not.toContain('the **Processor**')
      }
    }
  })

  it('exposes no identity document number in anything it publishes', () => {
    // Not the literal number: a test naming it would reintroduce exactly what it forbids.
    for (const doc of LEGAL_DOCUMENTS) {
      for (const lang of LANGUAGES) {
        expect(doc.markdown[lang].match(/\b\d{8,10}\b/g) ?? []).toEqual([])
      }
    }
  })

  it('carries no internal note into a published page', () => {
    // The Aviso once shipped its own editorial preamble and a section headed "Note for
    // whoever wires this into the product", citing an open issue, live on a public page.
    for (const doc of LEGAL_DOCUMENTS) {
      for (const lang of LANGUAGES) {
        expect(doc.markdown[lang]).not.toMatch(/OI-\d+|FZ-\d+/)
        expect(doc.markdown[lang]).not.toMatch(/Note for whoever/i)
      }
    }
  })

  /*
   * The guard against the two languages drifting apart. A section added to one and forgotten
   * in the other is how a document goes out half-translated, and nothing else would catch it.
   */
  it('keeps both languages structurally in step', () => {
    const sections = (md: string) => (md.match(/^#{2,3} \d+(\.\d+)?/gm) ?? []).map((h) => h.trim())
    // The Aviso has no numbered sections — Art. 14's four required elements are four bold
    // lead-ins — so headings alone would compare two empty lists and prove nothing.
    const leadIns = (md: string) => (md.match(/^\*\*[^*]+\*\*/gm) ?? []).length

    for (const doc of LEGAL_DOCUMENTS) {
      expect(sections(doc.markdown.en)).toEqual(sections(doc.markdown.es))
      expect(leadIns(doc.markdown.en)).toBe(leadIns(doc.markdown.es))

      // And the signature must be non-trivial, or this test passes by comparing nothing.
      expect(sections(doc.markdown.es).length + leadIns(doc.markdown.es)).toBeGreaterThan(3)
    }
  })

  it('says in English that the Spanish prevails', () => {
    // Without this the binding text is ambiguous, and a translation slip becomes a
    // compliance defect rather than a typo.
    for (const doc of LEGAL_DOCUMENTS) {
      expect(doc.markdown.en).toMatch(/courtesy translation/i)
      expect(doc.markdown.en).toMatch(/prevails/i)
    }
  })
})
