import { NavLink, Outlet } from 'react-router'
import { useAuth } from '../features/auth/authContext'
import { TrialBanner } from '../features/billing/TrialBanner'
import { Wordmark } from '../components/Wordmark'
import styles from './AppLayout.module.css'

export function AppLayout() {
  const { signOut } = useAuth()

  return (
    <div className={styles.shell}>
      {/* Above the header, and above every page: the whole point is that a member sees
          it without going looking for it (FZ-085). */}
      <TrialBanner />
      <header className={styles.header}>
        <Wordmark rules="nav" />
        <nav className={styles.nav}>
          <NavLink
            to="/dashboard"
            className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
          >
            Dashboard
          </NavLink>
          <NavLink
            to="/restrictions"
            className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
          >
            Restrictions
          </NavLink>
          <NavLink
            to="/deployment-checks"
            className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
          >
            Checks
          </NavLink>
          <NavLink
            to="/catalog"
            className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
          >
            Catalog
          </NavLink>
          <NavLink
            to="/notifications"
            className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
          >
            Notifications
          </NavLink>
          <NavLink
            to="/audit"
            className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
          >
            Audit
          </NavLink>
          <NavLink
            to="/settings"
            className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
          >
            Settings
          </NavLink>
        </nav>
        <button className={`btn btn-secondary ${styles.signOut}`} type="button" onClick={signOut}>
          Sign out
        </button>
      </header>

      <Outlet />
    </div>
  )
}
