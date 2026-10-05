import { useMemo } from 'react'
import { Link, useParams } from 'react-router'
import { marked } from 'marked'
import { findLegalDocument } from './documents'
import { NotFoundPage } from '../../app/NotFoundPage'
import styles from './LegalPage.module.css'

/**
 * Renders a published legal document (`FZ-224`).
 *
 * <p>Reachable without signing in, deliberately: Decreto 1377 Art. 14 requires the Aviso to
 * say where the Política can be read, and a page behind authentication cannot be read by the
 * person the statute is protecting.
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
  const document = findLegalDocument(slug)

  // Parsing is synchronous and cheap, but it is pure work over a constant, so it should not
  // run again on every render of a page people will leave open while they read it.
  const html = useMemo(
    () => (document ? marked.parse(document.markdown, { async: false }) : ''),
    [document],
  )

  // An unknown slug is a 404 like any other, rather than an empty document shell that looks
  // like the policy is missing.
  if (!document) return <NotFoundPage />

  return (
    <main className={styles.page}>
      <Link to="/" className={styles.back}>
        FreezeHub
      </Link>
      <article className={styles.prose} dangerouslySetInnerHTML={{ __html: html }} />
    </main>
  )
}
