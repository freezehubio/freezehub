/*
 * The legal documents that are published, and only those (`FZ-224`, bilingual in `FZ-226`).
 *
 * **This is an allowlist, and it must never become a glob.** `legal/` also holds
 * `data-processing-agreement.md`, which carries the operator's NIT — and for a persona
 * natural the NIT is their cédula (`FZ-223`). An `import.meta.glob('../../../../legal/*.md')`
 * would publish a national identity number to the open internet, and would look like a
 * tidier version of this file while doing it.
 *
 * The markdown is imported, not copied. `legal/*.md` is the source of truth that counsel
 * reviews; a hand-written page would drift from it, and a privacy policy that disagrees with
 * the reviewed one is worse than no page at all.
 */

import politicaEs from '../../../../legal/politica-de-tratamiento.md?raw'
import politicaEn from '../../../../legal/politica-de-tratamiento.en.md?raw'
import avisoEs from '../../../../legal/aviso-de-privacidad.md?raw'
import avisoEn from '../../../../legal/aviso-de-privacidad.en.md?raw'

/** Spanish is first because it is the authoritative text, not because it sorts that way. */
export const LANGUAGES = ['es', 'en'] as const
export type Language = (typeof LANGUAGES)[number]

export const LANGUAGE_NAMES: Record<Language, string> = { es: 'Español', en: 'English' }

export type LegalDocument = {
  /** The path segment under `/legal/`. English, like every other path in the product. */
  readonly slug: string
  readonly titles: Record<Language, string>
  readonly markdown: Record<Language, string>
}

/**
 * `/legal/privacy-policy` is **load-bearing**.
 *
 * It is printed in the Art. 12 footer of every outreach email, and named inside the Aviso
 * itself as the address where the full Política can be read. Mail cannot be edited once sent,
 * so once the first batch goes out this slug is permanent: changing it would turn a statutory
 * reference into a 404. It was renamed from `politica` in `FZ-226` precisely because nothing
 * had been sent yet — that window is closed the moment it is.
 */
export const LEGAL_DOCUMENTS: readonly LegalDocument[] = [
  {
    slug: 'privacy-policy',
    titles: {
      es: 'Política de Tratamiento de Datos Personales',
      en: 'Personal Data Processing Policy',
    },
    markdown: { es: politicaEs, en: politicaEn },
  },
  {
    slug: 'privacy-notice',
    titles: { es: 'Aviso de Privacidad', en: 'Privacy Notice' },
    markdown: { es: avisoEs, en: avisoEn },
  },
]

export function findLegalDocument(slug: string | undefined): LegalDocument | undefined {
  return LEGAL_DOCUMENTS.find((d) => d.slug === slug)
}

/**
 * Spanish unless English is asked for explicitly.
 *
 * Deliberately **not** inferred from the browser's `Accept-Language`. The authoritative text
 * is the Spanish one, so a reader should land on it unless something — a link in an email
 * written in English, or their own click — says otherwise. Guessing would mean a Colombian
 * data subject with an English-configured laptop is shown the non-binding version by default.
 */
export function languageFrom(value: string | null): Language {
  return value === 'en' ? 'en' : 'es'
}
