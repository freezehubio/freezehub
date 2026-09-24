import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import {
  changeMemberRole,
  deactivateMember,
  inviteMember,
  listMembers,
  reactivateMember,
} from '../../api/members'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { useAuth } from '../auth/authContext'
import { useCurrentUser } from '../auth/useCurrentUser'
import type { Member, MemberPage } from '../../types/api'
import { formatInstant } from '../../utils/datetime'
import settings from './SettingsPage.module.css'
import styles from './MembersSection.module.css'

type Role = Member['role']

const ROLE_LABEL: Record<Role, string> = {
  ADMINISTRATOR: 'Administrator',
  MEMBER: 'Member',
}

/**
 * Who can sign in to the organization, and what they may do (`FZ-212`).
 *
 * <b>Two roles, by decision.</b> `FZ-190` settled on Administrator and Member: an administrator
 * manages freezes, the catalog and settings; a member sees everything and changes nothing.
 * The description below says so in those words, because "Member" alone does not.
 *
 * <b>Remove means deactivate.</b> The operator's decision: the person can no longer sign in,
 * but stays on the record for what they did, and can be reinstated. The confirmation says so
 * rather than leaving the reader to assume a deletion.
 *
 * <b>Invite first, then the list, a page at a time.</b> Customers are companies, and a roster
 * of hundreds does not fit one screen or one response. The list is newest first, so whoever
 * was just invited appears right under the form; an invitation returns to page one to show it.
 *
 * <b>The rule about the last administrator is the backend's, not this screen's.</b> Nothing
 * here disables a control to anticipate it — `CLAUDE.md` §5 — the server refuses with a
 * message that says what to do instead, and that message is shown.
 */
export function MembersSection() {
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const me = useCurrentUser()

  const [email, setEmail] = useState('')
  const [role, setRole] = useState<Role>('MEMBER')
  const [notice, setNotice] = useState<string | null>(null)
  /**
   * Two error slots, because the form and the list are apart on the page: a refusal to remove
   * someone shown under "Send invitation" reads as the invitation having failed.
   */
  const [inviteError, setInviteError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [removing, setRemoving] = useState<Member | null>(null)
  /** Set only when an administrator is about to demote themselves. */
  const [demotingSelf, setDemotingSelf] = useState<Member | null>(null)
  const [page, setPage] = useState(0)
  const listHeading = useRef<HTMLHeadingElement>(null)

  /**
   * The pager sits under the list, so without this the next page opens at its end and the
   * reader scrolls back up for every page.
   */
  function goToPage(next: number) {
    setPage(next)
    listHeading.current?.scrollIntoView?.({ block: 'start' })
  }

  const members = useQuery<MemberPage>({
    queryKey: ['members', page],
    queryFn: ({ signal }) => listMembers(token, page, signal),
    // Keeps the current page on screen while the next one loads, rather than flashing
    // "Loading…" between pages.
    placeholderData: keepPreviousData,
  })

  /**
   * After any change. `me` is refreshed too, because a change to one's own role changes what
   * every other screen offers — and until it is refetched, they would keep offering it.
   */
  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['members'] })
    void queryClient.invalidateQueries({ queryKey: ['me'] })
  }

  function messageOf(caught: unknown) {
    return caught instanceof ApiError ? caught.message : 'Something went wrong. Please try again.'
  }

  function report(caught: unknown) {
    setActionError(messageOf(caught))
  }

  const invite = useMutation({
    mutationFn: () => inviteMember(token, email.trim(), role),
    onSuccess: refresh,
  })
  const changeRole = useMutation({
    mutationFn: ({ id, next }: { id: number; next: Role }) => changeMemberRole(token, id, next),
    onSuccess: refresh,
  })
  const deactivate = useMutation({
    mutationFn: (id: number) => deactivateMember(token, id),
    onSuccess: refresh,
  })
  const reactivate = useMutation({
    mutationFn: (id: number) => reactivateMember(token, id),
    onSuccess: refresh,
  })

  async function submit(event: FormEvent) {
    event.preventDefault()
    setInviteError(null)
    setNotice(null)
    const invited = email.trim()
    try {
      await invite.mutateAsync()
      setEmail('')
      setRole('MEMBER')
      setPage(0)
      setNotice(`${invited} will receive an email with a temporary password.`)
    } catch (caught) {
      setInviteError(messageOf(caught))
    }
  }

  async function applyRole(member: Member, next: Role) {
    setActionError(null)
    setNotice(null)
    try {
      await changeRole.mutateAsync({ id: member.id, next })
    } catch (caught) {
      report(caught)
    }
  }

  function requestRole(member: Member, next: Role) {
    if (member.id === me.data?.userId && next === 'MEMBER') {
      setDemotingSelf(member)
      return
    }
    void applyRole(member, next)
  }

  async function confirmRemove() {
    if (!removing) return
    setActionError(null)
    setNotice(null)
    try {
      await deactivate.mutateAsync(removing.id)
    } catch (caught) {
      report(caught)
    } finally {
      setRemoving(null)
    }
  }

  async function reinstate(member: Member) {
    setActionError(null)
    setNotice(null)
    try {
      await reactivate.mutateAsync(member.id)
    } catch (caught) {
      report(caught)
    }
  }

  const forbidden = members.error instanceof ApiError && members.error.status === 403
  const busy = changeRole.isPending || deactivate.isPending || reactivate.isPending

  return (
    <section className={settings.section} aria-labelledby="members-heading">
      <h2 className={settings.sectionHeading} id="members-heading">
        Members
      </h2>
      <p className={settings.sectionDescription}>
        Who can sign in to your organization. <strong>Administrators</strong> manage freezes, the
        catalog and these settings. <strong>Members</strong> see everything and change nothing.
        Pipelines use API keys, so none of this affects them.
      </p>

      {forbidden && (
        <p className={settings.state} role="status">
          Only an administrator can see and manage members.
        </p>
      )}

      {!forbidden && (
        <form className={settings.createForm} onSubmit={submit}>
          <label className={settings.label} htmlFor="invite-email">
            Invite someone
          </label>
          <div className={styles.inviteRow}>
            <input
              id="invite-email"
              className={settings.input}
              type="email"
              placeholder="colleague@yourcompany.com"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
            />
            <label className={styles.srOnly} htmlFor="invite-role">
              Role for the invitation
            </label>
            <select
              id="invite-role"
              className={styles.roleSelect}
              value={role}
              onChange={(event) => setRole(event.target.value as Role)}
            >
              <option value="MEMBER">Member</option>
              <option value="ADMINISTRATOR">Administrator</option>
            </select>
          </div>
          <p className={settings.hint}>
            They receive a temporary password by email and choose their own at first sign-in. An
            address can belong to one organization.
          </p>
          <button
            className={settings.primary}
            type="submit"
            disabled={!email.trim() || invite.isPending}
          >
            {invite.isPending ? 'Inviting…' : 'Send invitation'}
          </button>
          {inviteError && (
            <p className={settings.actionError} role="alert">
              {inviteError}
            </p>
          )}
          {notice && (
            <p className={settings.state} role="status">
              {notice}
            </p>
          )}
        </form>
      )}

      {!forbidden && members.isPending && (
        <p className={settings.state} role="status">
          Loading members…
        </p>
      )}

      {!forbidden && members.isError && (
        <p className={settings.actionError} role="alert">
          Could not load members. {members.error.message}
        </p>
      )}

      {members.data && (
        <h3 className={`${settings.label} ${styles.count}`} id="members-list-heading" ref={listHeading}>
          Current members · {members.data.totalItems}
        </h3>
      )}

      {actionError && (
        <p className={settings.actionError} role="alert">
          {actionError}
        </p>
      )}

      {members.data && (
        <ul className={settings.list} aria-labelledby="members-list-heading">
          {members.data.items.map((member) => {
            const isMe = member.id === me.data?.userId
            return (
              <li key={member.id} className={`${settings.row} ${member.active ? '' : styles.removed}`}>
                <div className={`${settings.rowMain} ${styles.memberMain}`}>
                  <span className={settings.itemName} data-name>
                    {member.email}
                    {isMe && <span className={styles.you}> (you)</span>}
                  </span>
                  <span className={settings.summary}>
                    {member.active
                      ? `${ROLE_LABEL[member.role]} · since ${formatInstant(member.createdAt)}`
                      : `Removed ${member.deactivatedAt ? formatInstant(member.deactivatedAt) : ''} · was ${ROLE_LABEL[member.role].toLowerCase()}`}
                  </span>
                </div>
                <div className={settings.rowActions}>
                  {member.active ? (
                    <>
                      <label className={styles.srOnly} htmlFor={`role-${member.id}`}>
                        Role for {member.email}
                      </label>
                      <select
                        id={`role-${member.id}`}
                        className={styles.roleSelect}
                        value={member.role}
                        disabled={busy}
                        onChange={(event) => requestRole(member, event.target.value as Role)}
                      >
                        <option value="ADMINISTRATOR">Administrator</option>
                        <option value="MEMBER">Member</option>
                      </select>
                      <button
                        type="button"
                        className={settings.danger}
                        aria-label={`Remove ${member.email}`}
                        disabled={busy}
                        onClick={() => {
                          setActionError(null)
                          setRemoving(member)
                        }}
                      >
                        Remove
                      </button>
                    </>
                  ) : (
                    <button
                      type="button"
                      className={settings.secondary}
                      aria-label={`Reinstate ${member.email}`}
                      disabled={busy}
                      onClick={() => void reinstate(member)}
                    >
                      Reinstate
                    </button>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      )}

      {members.data && members.data.totalPages > 1 && (
        <nav className={styles.pager} aria-label="Members pages">
          <button
            type="button"
            className={settings.secondary}
            disabled={page === 0 || members.isPlaceholderData}
            onClick={() => goToPage(Math.max(page - 1, 0))}
          >
            Previous
          </button>
          <span className={styles.pageStatus} aria-live="polite">
            Page {page + 1} of {members.data.totalPages}
          </span>
          <button
            type="button"
            className={settings.secondary}
            disabled={page + 1 >= members.data.totalPages || members.isPlaceholderData}
            onClick={() => goToPage(page + 1)}
          >
            Next
          </button>
        </nav>
      )}


      <ConfirmDialog
        open={removing !== null}
        title={`Remove “${removing?.email ?? ''}”?`}
        confirmLabel="Remove member"
        busyLabel="Removing…"
        dismissLabel="Keep them"
        busy={deactivate.isPending}
        onConfirm={() => void confirmRemove()}
        onDismiss={() => setRemoving(null)}
      >
        <p>
          They can no longer sign in, starting with their next click. Everything they did stays on
          the record, and you can reinstate them at any time.
        </p>
      </ConfirmDialog>

      <ConfirmDialog
        open={demotingSelf !== null}
        title="Make yourself a member?"
        confirmLabel="Step down"
        busyLabel="Stepping down…"
        dismissLabel="Stay an administrator"
        busy={changeRole.isPending}
        onConfirm={() => {
          const member = demotingSelf
          setDemotingSelf(null)
          if (member) void applyRole(member, 'MEMBER')
        }}
        onDismiss={() => setDemotingSelf(null)}
      >
        <p>
          You will no longer be able to manage freezes, members or settings. Another administrator
          would have to make you an administrator again.
        </p>
      </ConfirmDialog>
    </section>
  )
}
