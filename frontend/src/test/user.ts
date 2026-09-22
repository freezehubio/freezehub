import userEvent from '@testing-library/user-event'

/**
 * `userEvent`, without the wait between keystrokes (`FZ-193`).
 *
 * By default `userEvent.setup()` yields to the event loop after every key. Filling one
 * restriction form types about seventy characters, so that is seventy real scheduler
 * round-trips and seventy React renders — cheap on an idle machine and the dominant cost
 * on a busy one, which is how these tests came to fail only on developer machines
 * (`OI-47`).
 *
 * `delay: null` dispatches the keystrokes synchronously. Nothing here asserts on typing
 * *timing* — the assertions are about what was submitted — so the delay was buying
 * nothing. Use plain `userEvent.setup()` for a test that genuinely cares about debounce or
 * typeahead.
 */
export function setupUser() {
  return userEvent.setup({ delay: null })
}
