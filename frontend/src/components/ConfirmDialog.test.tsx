import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { ConfirmDialog } from './ConfirmDialog'

function renderDialog(overrides: Partial<Parameters<typeof ConfirmDialog>[0]> = {}) {
  const onConfirm = vi.fn()
  const onDismiss = vi.fn()
  render(
    <ConfirmDialog
      open
      title="Cancel “Peak trading”?"
      confirmLabel="Cancel freeze"
      busyLabel="Cancelling…"
      onConfirm={onConfirm}
      onDismiss={onDismiss}
      {...overrides}
    >
      <p>It ends now and every channel is notified.</p>
    </ConfirmDialog>,
  )
  return { onConfirm, onDismiss }
}

describe('ConfirmDialog', () => {
  test('is an accessible dialog named by what it asks', () => {
    renderDialog()

    const dialog = screen.getByRole('alertdialog', { name: 'Cancel “Peak trading”?' })
    expect(dialog).toHaveAccessibleDescription(/every channel is notified/)
  })

  test('renders nothing while closed', () => {
    renderDialog({ open: false })

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  /** Enter on a freshly opened destructive dialog must not destroy anything. */
  test('starts with focus on the safe choice', () => {
    renderDialog()

    expect(screen.getByRole('button', { name: 'Keep it' })).toHaveFocus()
  })

  test('confirms with the button that names the action', async () => {
    const { onConfirm, onDismiss } = renderDialog()

    await userEvent.click(screen.getByRole('button', { name: 'Cancel freeze' }))

    expect(onConfirm).toHaveBeenCalledOnce()
    expect(onDismiss).not.toHaveBeenCalled()
  })

  test('dismisses without acting', async () => {
    const { onConfirm, onDismiss } = renderDialog()

    await userEvent.click(screen.getByRole('button', { name: 'Keep it' }))

    expect(onDismiss).toHaveBeenCalledOnce()
    expect(onConfirm).not.toHaveBeenCalled()
  })

  test('Escape dismisses', () => {
    const { onConfirm, onDismiss } = renderDialog()

    // What the browser fires on Escape for a modal <dialog>; jsdom does not synthesise it.
    fireEvent(screen.getByRole('alertdialog'), new Event('cancel', { cancelable: true }))

    expect(onDismiss).toHaveBeenCalledOnce()
    expect(onConfirm).not.toHaveBeenCalled()
  })

  /** A second click while the request is in flight would send it twice. */
  test('locks both buttons while the action is running, and says so', () => {
    const { onDismiss } = renderDialog({ busy: true })

    expect(screen.getByRole('button', { name: 'Cancelling…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Keep it' })).toBeDisabled()

    fireEvent(screen.getByRole('alertdialog'), new Event('cancel', { cancelable: true }))
    expect(onDismiss).not.toHaveBeenCalled()
  })

  /**
   * The ambiguity this component exists to avoid: a dialog asking "Cancel this freeze?"
   * whose dismiss button is also labelled "Cancel".
   */
  test('the dismiss button never repeats the action word', () => {
    renderDialog()

    const labels = screen.getAllByRole('button').map((button) => button.textContent)
    expect(labels).toEqual(['Keep it', 'Cancel freeze'])
    expect(labels.filter((label) => label === 'Cancel')).toHaveLength(0)
  })
})
