import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { renderRoute } from '../../test/renderRoute'
import { LegalPage } from './LegalPage'
import { LEGAL_DOCUMENTS } from './documents'

const at = (path: string) => renderRoute(<LegalPage />, { path, route: '/legal/:slug' })

describe('legal pages', () => {
  it('serves the Política at the URL every outreach email points at', async () => {
    at('/legal/politica')

    expect(
      await screen.findByRole('heading', { name: /Política de Tratamiento de Datos Personales/i }),
    ).toBeInTheDocument()
  })

  it('serves the Aviso de Privacidad', async () => {
    at('/legal/aviso')

    expect(await screen.findByRole('heading', { name: /Aviso de Privacidad/i })).toBeInTheDocument()
  })

  it('renders the markdown rather than showing its source', async () => {
    at('/legal/politica')
    await screen.findByRole('heading', { level: 1 })

    // The Política is largely tables; if marked did not run, this is a wall of pipes.
    expect(screen.getAllByRole('table').length).toBeGreaterThan(0)
    expect(document.body.textContent).not.toContain('|---|')
    expect(document.body.textContent).not.toContain('**Responsable')
  })

  it('reaches a reader who is not signed in', async () => {
    renderRoute(<LegalPage />, { path: '/legal/politica', route: '/legal/:slug', token: null })

    // Decreto 1377 Art. 14 points the Titular here; a sign-in wall would defeat it.
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('shows a 404 for an unknown document instead of an empty page', async () => {
    at('/legal/no-such-document')

    expect(await screen.findByRole('heading', { name: /page not found/i })).toBeInTheDocument()
  })

  /*
   * The two that are about what must NOT be published.
   *
   * `legal/` also holds the DPA, which carries the operator's NIT — and for a persona natural
   * the NIT is their cédula (`FZ-223`). The allowlist in `documents.ts` is the only thing
   * keeping it off the internet, and an allowlist is one careless `import.meta.glob` away
   * from being a glob.
   */
  it('publishes exactly two documents, and neither is the DPA', () => {
    expect(LEGAL_DOCUMENTS.map((d) => d.slug).sort()).toEqual(['aviso', 'politica'])

    for (const doc of LEGAL_DOCUMENTS) {
      expect(doc.markdown).not.toContain('Data Processing Agreement')
      expect(doc.markdown).not.toContain('the **Processor**')
    }
  })

  it('exposes no identity document number in anything it publishes', () => {
    // Not the literal number: a test naming it would reintroduce exactly what it forbids.
    // A cédula is 8–10 digits, and the published documents legitimately contain no such run.
    for (const doc of LEGAL_DOCUMENTS) {
      expect(doc.markdown.match(/\b\d{8,10}\b/g) ?? []).toEqual([])
    }
  })
})
