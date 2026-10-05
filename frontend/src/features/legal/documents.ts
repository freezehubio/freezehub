/*
 * The legal documents that are published, and only those (`FZ-224`).
 *
 * **This is an allowlist, and it must never become a glob.** `legal/` also holds
 * `data-processing-agreement.md`, which carries the operator's NIT — and for a persona
 * natural the NIT is their cédula (`FZ-223`). A `import.meta.glob('../../../../legal/*.md')`
 * would publish a national identity number to the open internet, and would look like a
 * tidier version of this file while doing it.
 *
 * The markdown is imported, not copied. `legal/*.md` is the source of truth that counsel
 * reviews; a hand-written page would drift from it, and a privacy policy that disagrees with
 * the reviewed one is worse than no page at all.
 */

import politica from '../../../../legal/politica-de-tratamiento.md?raw'
import aviso from '../../../../legal/aviso-de-privacidad.md?raw'

export type LegalDocument = {
  /** The path segment under `/legal/`. Changing one breaks a published URL — see below. */
  readonly slug: string
  readonly title: string
  readonly markdown: string
}

/**
 * `politica` is reachable at `/legal/politica`, and that URL is **load-bearing**.
 *
 * It is written into every outreach email's Art. 12 footer and into the Aviso itself, as the
 * address where the full Política can be read. Those emails cannot be edited once sent, so
 * this slug is effectively permanent: changing it turns a statutory reference into a 404.
 */
export const LEGAL_DOCUMENTS: readonly LegalDocument[] = [
  { slug: 'politica', title: 'Política de Tratamiento de Datos Personales', markdown: politica },
  { slug: 'aviso', title: 'Aviso de Privacidad', markdown: aviso },
]

export function findLegalDocument(slug: string | undefined): LegalDocument | undefined {
  return LEGAL_DOCUMENTS.find((d) => d.slug === slug)
}
