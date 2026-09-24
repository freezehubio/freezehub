import { useSearchParams } from 'react-router'
import { ApiKeysSection } from './ApiKeysSection'
import { BillingSection } from './BillingSection'
import { IntegrationsSection } from './IntegrationsSection'
import { MembersSection } from './MembersSection'
import { OrganizationSection } from './OrganizationSection'
import styles from './SettingsPage.module.css'

/**
 * Everything an administrator configures for their organization (`1h`, FZ-116), one
 * section at a time (FZ-118).
 *
 * The rail switches rather than scrolls. Ordered roughly by how often each is touched,
 * and Settings opens on the first.
 *
 * **The chosen section lives in the URL**, as the checks console's filter does: Settings →
 * API keys is then a link somebody can send, and browser back steps between sections
 * instead of leaving the page. An unknown or missing value falls back to the first rather
 * than rendering nothing, because a mistyped URL should still show a usable page.
 */
const SECTIONS = [
  { id: 'organization', label: 'Organization', render: () => <OrganizationSection /> },
  { id: 'members', label: 'Members', render: () => <MembersSection /> },
  { id: 'integrations', label: 'Integrations', render: () => <IntegrationsSection /> },
  { id: 'api-keys', label: 'API keys', render: () => <ApiKeysSection /> },
  { id: 'billing', label: 'Billing', render: () => <BillingSection /> },
] as const

export function SettingsPage() {
  const [searchParams, setSearchParams] = useSearchParams()

  const requested = searchParams.get('section')
  const current = SECTIONS.find((section) => section.id === requested) ?? SECTIONS[0]

  function show(id: string) {
    const params = new URLSearchParams(searchParams)
    params.set('section', id)
    setSearchParams(params)
  }

  return (
    <main className={styles.page}>
      {/*
        * Buttons, not links: this swaps a panel on the page rather than navigating to a
        * document, and a link that does not go anywhere is a link that middle-click and
        * "open in new tab" quietly break.
        */}
      <nav className={styles.rail} aria-label="Settings sections">
        {SECTIONS.map((section) => (
          <button
            key={section.id}
            type="button"
            className={section.id === current.id ? styles.railLinkActive : styles.railLink}
            aria-current={section.id === current.id ? 'true' : undefined}
            onClick={() => show(section.id)}
          >
            {section.label}
          </button>
        ))}
      </nav>

      <div className={styles.content}>
        <h1 className={styles.title}>Settings</h1>
        {/*
          * Only the chosen section is mounted, so only its data is fetched — opening
          * Settings asks one question rather than four. Each still loads and fails on its
          * own terms.
          */}
        {current.render()}
      </div>
    </main>
  )
}
