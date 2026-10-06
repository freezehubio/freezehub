import { useMemo } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { marked } from 'marked'
import { findLegalDocument, languageFrom, LANGUAGES, LANGUAGE_NAMES } from './documents'
import type { Language } from './documents'
import { NotFoundPage } from '../../app/NotFoundPage'
import styles from './LegalPage.module.css'

/**
 * Renders a published legal document in one of its two languages (`FZ-224`, `FZ-226`).
 *
 * <p>Reachable without signing in, deliberately: Decreto 1377 Art. 14 requires the Aviso to
 * say where the Política can be read, and a page behind authentication cannot be read by the
 * person the statute is protecting.
 *
 * <p>**The language lives in the URL, not in component state.** `contacts.csv` records a
 * language per recipient, so an English prospect's email footer links straight to
 * `?lang=en` and a Spanish one to the bare URL. A toggle that only held state in the page
 * could not be linked to, would not survive a reload, and would leave eleven of nineteen
 * recipients landing on a document they cannot read.
 *
 * <p>`dangerouslySetInnerHTML` is doing what its name warns about, so the reason it is safe
 * here is worth stating rather than assuming. The input is not user content and never can be:
 * it is a markdown file imported from this repository at build time, through the allowlist in
 * `documents.ts`. There is no path by which a visitor supplies any of it. If that ever stops
 * being true — a document fetched at runtime, a document a customer can edit — this needs a
 * sanitiser before the change lands, not after.
 */
export function LegalPage() {
  const { slug } = useParams()
  const [params, setParams] = useSearchParams()
  const document = findLegalDocument(slug)
  const language = languageFrom(params.get('lang'))

  // Parsing is synchronous and cheap, but it is pure work over a constant, so it should not
  // run again on every render of a page people will leave open while they read it.
  const html = useMemo(
    () => (document ? marked.parse(document.markdown[language], { async: false }) : ''),
    [document, language],
  )

  // An unknown slug is a 404 like any other, rather than an empty document shell that looks
  // like the policy is missing.
  if (!document) return <NotFoundPage />

  const select = (next: Language) => {
    // `replace`, not push: switching language is not a step a reader wants to walk back
    // through, and leaving it in history makes the back button feel broken.
    setParams(next === 'es' ? {} : { lang: next }, { replace: true })
  }

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <Link to="/" className={styles.back}>
          FreezeHub
        </Link>

        <nav className={styles.languages} aria-label={language === 'es' ? 'Idioma' : 'Language'}>
          {LANGUAGES.map((code) => (
            <button
              key={code}
              type="button"
              onClick={() => select(code)}
              className={styles.language}
              // Pressed rather than disabled: a disabled control is skipped by screen
              // readers, which hides which language you are already reading.
              aria-pressed={code === language}
            >
              {LANGUAGE_NAMES[code]}
            </button>
          ))}
        </nav>
      </header>

      <article className={styles.prose} dangerouslySetInnerHTML={{ __html: html }} />
    </main>
  )
}
