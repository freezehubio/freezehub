import { createBrowserRouter } from 'react-router'
import { AppLayout } from './AppLayout'
import { NotFoundPage } from './NotFoundPage'
import { RequireAuth } from '../features/auth/RequireAuth'
import { SignInPage } from '../features/auth/SignInPage'
import { SignUpPage } from '../features/auth/SignUpPage'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { RestrictionsPage } from '../features/restrictions/RestrictionsPage'
import { AuditPage } from '../features/audit/AuditPage'
import { CatalogPage } from '../features/catalog/CatalogPage'
import { DeploymentChecksPage } from '../features/deployments/DeploymentChecksPage'
import { NotificationsPage } from '../features/notifications/NotificationsPage'
import { SettingsPage } from '../features/settings/SettingsPage'
import { CreateRestrictionPage } from '../features/restrictions/CreateRestrictionPage'
import { RestrictionDetailPage } from '../features/restrictions/RestrictionDetailPage'
import { EditRestrictionPage } from '../features/restrictions/EditRestrictionPage'
import { LandingPage } from '../features/marketing/LandingPage'
import { LegalPage } from '../features/legal/LegalPage'

/**
 * MVP routes per 05-frontend.md. Routes arrive with the story that builds their page:
 * /restrictions is FZ-032, /restrictions/new FZ-033, /restrictions/:id FZ-034.
 *
 * `/`, `/signin` and `/signup` are public — the last of those is the free-trial form
 * (`FZ-185`), which has to be reachable by someone with no account by definition.
 *
 * `/` is public and serves the landing page (`FZ-111`). The authenticated tree below it is
 * a **pathless layout route** rather than a route on `/`, so every product path stays
 * exactly where it was — `/dashboard` is still `/dashboard`, and no in-app link, redirect
 * or Cognito callback moves.
 */
export const routes = [
  { path: '/', element: <LandingPage /> },

  /*
   * Public, and it has to be: Decreto 1377 Art. 14 requires the Aviso to say where the
   * Política can be read, and a page behind a sign-in cannot be read by the person the
   * statute protects (`FZ-224`).
   *
   * `/legal/privacy-policy` is written into every outreach email's Art. 12 footer, with
   * `?lang=en` for the English recipients. Sent email cannot be edited, so once the first
   * batch goes out this path is permanent — changing it turns a statutory reference into a
   * 404. It was renamed from the Spanish slug in `FZ-226` while nothing had been sent.
   */
  { path: '/legal/:slug', element: <LegalPage /> },
  { path: '/signin', element: <SignInPage /> },
  { path: '/signup', element: <SignUpPage /> },
  {
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { path: 'dashboard', element: <DashboardPage /> },
      { path: 'restrictions', element: <RestrictionsPage /> },
      { path: 'restrictions/new', element: <CreateRestrictionPage /> },
      { path: 'restrictions/:restrictionId', element: <RestrictionDetailPage /> },
      { path: 'restrictions/:restrictionId/edit', element: <EditRestrictionPage /> },
      { path: 'deployment-checks', element: <DeploymentChecksPage /> },
      { path: 'catalog', element: <CatalogPage /> },
      { path: 'notifications', element: <NotificationsPage /> },
      { path: 'audit', element: <AuditPage /> },
      { path: 'settings', element: <SettingsPage /> },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]

export const router = createBrowserRouter(routes)
