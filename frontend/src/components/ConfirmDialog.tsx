import { useEffect, useRef, type ReactNode } from 'react'
import styles from './ConfirmDialog.module.css'

/**
 * Asks before something that cannot be undone (`FZ-202`).
 *
 * Replaces `window.confirm`, which drew the browser's own box — a different typeface, a
 * different voice, and a title bar reading "localhost:5173 says" — at the one moment the
 * product most needs to be trusted: just before somebody lifts a freeze or cuts off a
 * pipeline. It also cannot be tested for what it says, only for whether it was called,
 * which is how `FZ-188` shipped `Cancel ""?` with every check green.
 *
 * <b>A native `<dialog>` opened with `showModal()`.</b> That is what gives the page
 * behind it `inert`, traps focus, closes on Escape and draws the backdrop — all without a
 * dependency and all correctly, which a hand-rolled overlay usually is not. Where
 * `showModal` does not exist (jsdom, in tests) the element is opened with the `open`
 * attribute instead, so the same markup is asserted.
 *
 * <b>Focus starts on the safe choice.</b> Enter on a freshly opened destructive dialog
 * must not destroy anything.
 *
 * <b>The two buttons never share a word with the action.</b> A dialog asking "Cancel this
 * freeze?" with a button labelled "Cancel" is a coin toss; the dismiss button says what it
 * keeps.
 */
export function ConfirmDialog({
  open,
  title,
  children,
  confirmLabel,
  busyLabel,
  dismissLabel = 'Keep it',
  busy = false,
  onConfirm,
  onDismiss,
}: {
  open: boolean
  /** Names the thing. A confirmation that does not say what it is about is not one. */
  title: string
  /** The consequence, in one or two sentences. */
  children: ReactNode
  /** Says the action in full — "Cancel freeze", never just "OK" or "Yes". */
  confirmLabel: string
  /** Shown on the confirm button while the request is in flight. */
  busyLabel?: string
  dismissLabel?: string
  busy?: boolean
  onConfirm: () => void
  onDismiss: () => void
}) {
  if (!open) return null
  return (
    <OpenDialog
      title={title}
      confirmLabel={confirmLabel}
      busyLabel={busyLabel}
      dismissLabel={dismissLabel}
      busy={busy}
      onConfirm={onConfirm}
      onDismiss={onDismiss}
    >
      {children}
    </OpenDialog>
  )
}

/**
 * Mounted only while open, so it opens once on mount and is simply removed on close.
 *
 * Keeping a hidden `<dialog>` in the page and toggling it is how stale content gets shown:
 * the element outlives the thing it was about. Mounting per question means the title is
 * always read from the row that was clicked.
 */
function OpenDialog({
  title,
  children,
  confirmLabel,
  busyLabel,
  dismissLabel,
  busy,
  onConfirm,
  onDismiss,
}: {
  title: string
  children: ReactNode
  confirmLabel: string
  busyLabel?: string
  dismissLabel: string
  busy: boolean
  onConfirm: () => void
  onDismiss: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const keep = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (typeof element.showModal === 'function') {
      element.showModal()
    } else {
      element.setAttribute('open', '')
    }
    keep.current?.focus()
  }, [])

  return (
    <dialog
      ref={dialog}
      className={styles.dialog}
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="confirm-dialog-title"
      aria-describedby="confirm-dialog-body"
      // Escape. The browser would close the element itself; routing it through onDismiss
      // keeps React's `open` and the element's in agreement.
      onCancel={(event) => {
        event.preventDefault()
        if (!busy) onDismiss()
      }}
      // A click on the backdrop lands on the <dialog> element itself, never on its content.
      onClick={(event) => {
        if (event.target === dialog.current && !busy) onDismiss()
      }}
    >
      <div className={styles.panel}>
        <h2 className={styles.title} id="confirm-dialog-title">
          {title}
        </h2>
        <div className={styles.body} id="confirm-dialog-body">
          {children}
        </div>
        <div className={styles.actions}>
          <button
            ref={keep}
            type="button"
            className="btn btn-secondary"
            onClick={onDismiss}
            disabled={busy}
          >
            {dismissLabel}
          </button>
          <button
            type="button"
            className={`btn ${styles.destructive}`}
            onClick={onConfirm}
            disabled={busy}
          >
            {busy && busyLabel ? busyLabel : confirmLabel}
          </button>
        </div>
      </div>
    </dialog>
  )
}
